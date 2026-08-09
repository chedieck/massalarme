"""Weigh-in storage for massalarme.

The historical `weights` table is a *raw advertisement log*: the BLE scale
rebroadcasts a completed measurement dozens of times, and a single trip to the
scale usually produces several genuinely distinct measurements as the user
settles their weight. Both end up as separate rows. It is the honest record of
what the scale said, and it is kept untouched.

What the rest of the system wants -- the alarm log, the phone UI, ontoplano --
is one row per *weigh-in*. That is the `weigh_ins` table: deduplicated,
UTC-stamped, and carrying its own outbound sync state.

Sessionisation happens in two steps:

1. Identical `raw_value` means the same measurement rebroadcast. The scale's own
   clock is embedded in the payload and increments, so equal payloads are the
   same reading, never two readings that happen to match.
2. Measurements separated by less than `session_gap_seconds` are one trip to the
   scale. The last measurement wins, because the scale is still settling until
   then.

`external_id` is a pure function of the canonical measurement, so re-running a
backfill or retrying an upload can never create a second point downstream.
"""

from __future__ import annotations

import hashlib
import logging
import sqlite3
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable, Optional, Sequence

logger = logging.getLogger("massalarme.store")

DEFAULT_SESSION_GAP_SECONDS = 90

SOURCE_PC = "pc"
SOURCE_PHONE = "phone"

# Terminal sync states. Anything else is retryable.
SYNC_PENDING = None
SYNC_DROPPED = "dropped"


# =====================================================================
# Identity
# =====================================================================


def utc_iso(moment: datetime) -> str:
    """Render an aware or naive datetime as UTC ISO-8601 with a Z suffix.

    Naive datetimes are assumed to be in the machine's local zone, which is what
    `datetime.now()` produced for every historical row.
    """
    if moment.tzinfo is None:
        moment = moment.astimezone()
    return (
        moment.astimezone(timezone.utc)
        .replace(microsecond=0)
        .strftime("%Y-%m-%dT%H:%M:%SZ")
    )


def parse_timestamp(raw: str) -> datetime:
    """Parse a timestamp from either schema generation.

    Historical rows hold `datetime.now().isoformat()` (naive, local). New rows
    hold UTC with a Z suffix.
    """
    text = raw.strip()
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    return datetime.fromisoformat(text)


def make_external_id(captured_at: datetime, raw_value: Optional[str], weight_kg: float) -> str:
    """Derive a stable id from the reading itself.

    Never call `uuid()` here: the id has to survive retries, reinstalls and
    device changes, otherwise a response lost to a dropped connection turns into
    a duplicate point on the user's chart.

    The scale's raw payload alone is not unique -- it embeds a clock that is
    often unset, and two weigh-ins on different days can produce byte-identical
    payloads (rows 55 and 56 of the shipped database do exactly that). Pairing it
    with the capture second fixes that while staying deterministic.
    """
    fingerprint = raw_value if raw_value else f"{weight_kg:.4f}"
    digest = hashlib.sha1(fingerprint.encode("utf-8")).hexdigest()[:8]
    return f"{utc_iso(captured_at)}-{digest}"


# =====================================================================
# Sessionisation
# =====================================================================


@dataclass
class Measurement:
    """One decoded scale advertisement."""

    captured_at: datetime
    weight_kg: float
    impedance: Optional[float] = None
    raw_value: Optional[str] = None
    alarm_name: Optional[str] = None
    source: str = SOURCE_PC


@dataclass
class WeighIn:
    """One trip to the scale, ready to be stored and uploaded."""

    external_id: str
    captured_at: datetime
    weight_kg: float
    impedance: Optional[float]
    raw_value: Optional[str]
    alarm_name: Optional[str]
    source: str
    measurement_count: int = 1
    session_started_at: Optional[datetime] = None


def sessionise(
    measurements: Iterable[Measurement],
    *,
    gap_seconds: int = DEFAULT_SESSION_GAP_SECONDS,
) -> list[WeighIn]:
    """Collapse raw advertisements into one weigh-in per trip to the scale.

    Pure and order-independent (input is sorted internally), so running it twice
    over the same rows yields byte-identical ids.
    """
    ordered = sorted(measurements, key=lambda m: (m.captured_at, m.raw_value or ""))
    if not ordered:
        return []

    sessions: list[list[Measurement]] = []
    current: list[Measurement] = [ordered[0]]

    for measurement in ordered[1:]:
        elapsed = (measurement.captured_at - current[-1].captured_at).total_seconds()
        if elapsed <= gap_seconds:
            current.append(measurement)
        else:
            sessions.append(current)
            current = [measurement]
    sessions.append(current)

    weigh_ins: list[WeighIn] = []
    for session in sessions:
        # The scale is still settling until the last distinct payload arrives,
        # so that one is the reading the user actually cares about.
        canonical = session[-1]
        distinct_payloads = len({m.raw_value for m in session})
        weigh_ins.append(
            WeighIn(
                external_id=make_external_id(
                    canonical.captured_at, canonical.raw_value, canonical.weight_kg
                ),
                captured_at=canonical.captured_at,
                weight_kg=canonical.weight_kg,
                impedance=canonical.impedance,
                raw_value=canonical.raw_value,
                alarm_name=next(
                    (m.alarm_name for m in session if m.alarm_name), None
                ),
                source=canonical.source,
                measurement_count=distinct_payloads,
                session_started_at=session[0].captured_at,
            )
        )
    return weigh_ins


# =====================================================================
# Schema
# =====================================================================

_SCHEMA = """
CREATE TABLE IF NOT EXISTS weights (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    timestamp TEXT NOT NULL,
    weight_kg REAL NOT NULL,
    impedance REAL NOT NULL,
    alarm_name TEXT,
    raw_value TEXT
);

CREATE TABLE IF NOT EXISTS weigh_ins (
    external_id   TEXT PRIMARY KEY,
    captured_at   TEXT NOT NULL,
    weight_kg     REAL NOT NULL,
    impedance     REAL,
    alarm_name    TEXT,
    source        TEXT NOT NULL,
    raw_value     TEXT,
    measurements  INTEGER NOT NULL DEFAULT 1,
    created_at    TEXT NOT NULL,
    synced_at     TEXT,
    sync_state    TEXT,
    sync_attempts INTEGER NOT NULL DEFAULT 0,
    last_error    TEXT
);

CREATE INDEX IF NOT EXISTS ix_weigh_ins_captured_at ON weigh_ins(captured_at);
CREATE INDEX IF NOT EXISTS ix_weigh_ins_pending
    ON weigh_ins(captured_at) WHERE synced_at IS NULL AND sync_state IS NULL;

CREATE TABLE IF NOT EXISTS sync_state (
    key   TEXT PRIMARY KEY,
    value TEXT
);
"""


class WeighInStore:
    """SQLite-backed weigh-in log. massalarme owns this file outright."""

    def __init__(self, db_path: Path | str, *, gap_seconds: int = DEFAULT_SESSION_GAP_SECONDS):
        self.db_path = Path(db_path)
        self.gap_seconds = gap_seconds

    def _connect(self) -> sqlite3.Connection:
        conn = sqlite3.connect(self.db_path, timeout=10)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA journal_mode=WAL")
        conn.execute("PRAGMA foreign_keys=ON")
        return conn

    def init(self) -> None:
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        with self._connect() as conn:
            conn.executescript(_SCHEMA)
        logger.info("Weigh-in store ready: %s", self.db_path)

    # ── Writing ──────────────────────────────────────────────────────

    def record_raw(self, measurement: Measurement) -> None:
        """Append to the raw advertisement log, preserving historical behaviour."""
        with self._connect() as conn:
            conn.execute(
                "INSERT INTO weights (timestamp, weight_kg, impedance, alarm_name, raw_value) "
                "VALUES (?, ?, ?, ?, ?)",
                (
                    measurement.captured_at.isoformat(),
                    measurement.weight_kg,
                    measurement.impedance if measurement.impedance is not None else -1.0,
                    measurement.alarm_name,
                    measurement.raw_value,
                ),
            )

    def upsert_weigh_ins(self, weigh_ins: Sequence[WeighIn]) -> tuple[int, int]:
        """Store weigh-ins, ignoring ones already present.

        Returns `(inserted, duplicates)`. An existing row is never overwritten:
        its sync state is the reason this table exists, and a re-delivery of the
        same reading carries no new information.
        """
        if not weigh_ins:
            return (0, 0)

        inserted = 0
        now = utc_iso(datetime.now(timezone.utc))
        with self._connect() as conn:
            for weigh_in in weigh_ins:
                cursor = conn.execute(
                    """
                    INSERT OR IGNORE INTO weigh_ins
                        (external_id, captured_at, weight_kg, impedance, alarm_name,
                         source, raw_value, measurements, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        weigh_in.external_id,
                        utc_iso(weigh_in.captured_at),
                        weigh_in.weight_kg,
                        weigh_in.impedance,
                        weigh_in.alarm_name,
                        weigh_in.source,
                        weigh_in.raw_value,
                        weigh_in.measurement_count,
                        now,
                    ),
                )
                inserted += cursor.rowcount
        return (inserted, len(weigh_ins) - inserted)

    # ── Backfill ─────────────────────────────────────────────────────

    def backfill_from_raw_log(self) -> tuple[int, int]:
        """Collapse the whole `weights` table into `weigh_ins`.

        Safe to run any number of times: sessionisation is deterministic and the
        upsert ignores rows that already exist.
        """
        with self._connect() as conn:
            rows = conn.execute(
                "SELECT timestamp, weight_kg, impedance, alarm_name, raw_value "
                "FROM weights ORDER BY timestamp"
            ).fetchall()

        measurements = []
        skipped = 0
        for row in rows:
            try:
                captured_at = parse_timestamp(row["timestamp"])
            except ValueError:
                skipped += 1
                continue
            impedance = row["impedance"]
            measurements.append(
                Measurement(
                    captured_at=captured_at,
                    weight_kg=row["weight_kg"],
                    # 65533 and -1 are the scale's "no impedance measured" sentinels.
                    impedance=None if impedance in (None, -1.0, 65533.0) else impedance,
                    raw_value=row["raw_value"],
                    alarm_name=row["alarm_name"],
                    source=SOURCE_PC,
                )
            )

        if skipped:
            logger.warning("Backfill skipped %d row(s) with unparseable timestamps", skipped)

        weigh_ins = sessionise(measurements, gap_seconds=self.gap_seconds)
        inserted, duplicates = self.upsert_weigh_ins(weigh_ins)
        logger.info(
            "Backfill: %d raw rows -> %d weigh-ins (%d new, %d already present)",
            len(measurements),
            len(weigh_ins),
            inserted,
            duplicates,
        )
        return (inserted, duplicates)

    # ── Reading ──────────────────────────────────────────────────────

    def pending(self, limit: int = 500) -> list[WeighIn]:
        """Weigh-ins not yet accepted by ontoplano, oldest first."""
        with self._connect() as conn:
            rows = conn.execute(
                """
                SELECT * FROM weigh_ins
                WHERE synced_at IS NULL AND sync_state IS NULL
                ORDER BY captured_at
                LIMIT ?
                """,
                (limit,),
            ).fetchall()
        return [
            WeighIn(
                external_id=row["external_id"],
                captured_at=parse_timestamp(row["captured_at"]),
                weight_kg=row["weight_kg"],
                impedance=row["impedance"],
                raw_value=row["raw_value"],
                alarm_name=row["alarm_name"],
                source=row["source"],
                measurement_count=row["measurements"],
            )
            for row in rows
        ]

    def pending_count(self) -> int:
        with self._connect() as conn:
            row = conn.execute(
                "SELECT COUNT(*) AS n FROM weigh_ins "
                "WHERE synced_at IS NULL AND sync_state IS NULL"
            ).fetchone()
        return int(row["n"])

    def latest(self, limit: int = 10) -> list[WeighIn]:
        with self._connect() as conn:
            rows = conn.execute(
                "SELECT * FROM weigh_ins ORDER BY captured_at DESC LIMIT ?", (limit,)
            ).fetchall()
        return [
            WeighIn(
                external_id=row["external_id"],
                captured_at=parse_timestamp(row["captured_at"]),
                weight_kg=row["weight_kg"],
                impedance=row["impedance"],
                raw_value=row["raw_value"],
                alarm_name=row["alarm_name"],
                source=row["source"],
                measurement_count=row["measurements"],
            )
            for row in rows
        ]

    # ── Sync bookkeeping ─────────────────────────────────────────────

    def mark_synced(self, external_ids: Sequence[str]) -> None:
        """Mark as delivered. Called for accepted *and* duplicate points alike --
        a duplicate means the server already has it, which is success."""
        if not external_ids:
            return
        now = utc_iso(datetime.now(timezone.utc))
        with self._connect() as conn:
            conn.executemany(
                "UPDATE weigh_ins SET synced_at = ?, last_error = NULL WHERE external_id = ?",
                [(now, external_id) for external_id in external_ids],
            )

    def mark_dropped(self, external_id: str, reason: str) -> None:
        """Give up on a point the server will never accept (422).

        The local row stays -- massalarme is the source of truth and never
        deletes a reading because an upload failed.
        """
        with self._connect() as conn:
            conn.execute(
                "UPDATE weigh_ins SET sync_state = ?, last_error = ? WHERE external_id = ?",
                (SYNC_DROPPED, reason[:500], external_id),
            )
        logger.warning("Dropped weigh-in %s from sync queue: %s", external_id, reason)

    def record_attempt(self, external_ids: Sequence[str], error: Optional[str]) -> None:
        if not external_ids:
            return
        with self._connect() as conn:
            conn.executemany(
                "UPDATE weigh_ins SET sync_attempts = sync_attempts + 1, last_error = ? "
                "WHERE external_id = ?",
                [(error[:500] if error else None, external_id) for external_id in external_ids],
            )

    # ── Status surface ───────────────────────────────────────────────

    def set_state(self, key: str, value: Optional[str]) -> None:
        with self._connect() as conn:
            conn.execute(
                "INSERT INTO sync_state (key, value) VALUES (?, ?) "
                "ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                (key, value),
            )

    def get_state(self, key: str) -> Optional[str]:
        with self._connect() as conn:
            row = conn.execute(
                "SELECT value FROM sync_state WHERE key = ?", (key,)
            ).fetchone()
        return row["value"] if row else None

    def status(self) -> dict:
        """Everything needed to answer 'is sync actually working?'."""
        with self._connect() as conn:
            totals = conn.execute(
                """
                SELECT
                    COUNT(*)                                             AS total,
                    SUM(CASE WHEN synced_at IS NOT NULL THEN 1 ELSE 0 END) AS synced,
                    SUM(CASE WHEN sync_state = 'dropped' THEN 1 ELSE 0 END) AS dropped
                FROM weigh_ins
                """
            ).fetchone()
            state = {
                row["key"]: row["value"]
                for row in conn.execute("SELECT key, value FROM sync_state").fetchall()
            }

        total = totals["total"] or 0
        synced = totals["synced"] or 0
        dropped = totals["dropped"] or 0
        return {
            "total": total,
            "synced": synced,
            "dropped": dropped,
            "pending": total - synced - dropped,
            "last_success": state.get("last_success"),
            "last_error": state.get("last_error"),
            "last_error_at": state.get("last_error_at"),
            "last_attempt": state.get("last_attempt"),
        }
