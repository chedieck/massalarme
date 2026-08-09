"""Outbound sync worker: massalarme weigh-ins -> ontoplano.

Runs beside the daemon. Wakes on a new reading, on a periodic timer, and once at
startup. Never blocks anything else: if ontoplano is unreachable for a week the
readings simply stay queued, and massalarme keeps working exactly as it does with
sync switched off.

The queue lives in the `weigh_ins` table (`synced_at IS NULL` means pending), so
it survives restarts without a separate queue table.
"""

from __future__ import annotations

import asyncio
import logging
import random
from datetime import datetime, timezone
from typing import Optional

from ontoplano import (
    AuthError,
    OntoplanoClient,
    OntoplanoError,
    PlanLimitError,
    Point,
    RateLimitedError,
    ServerError,
    StreamMissingError,
)
from store import WeighInStore, utc_iso

logger = logging.getLogger("massalarme.sync")

INITIAL_BACKOFF_SECONDS = 30
MAX_BACKOFF_SECONDS = 3600
IDLE_POLL_SECONDS = 300


class SyncWorker:
    def __init__(
        self,
        store: WeighInStore,
        client: OntoplanoClient,
        *,
        batch_size: int = 500,
        idle_poll_seconds: int = IDLE_POLL_SECONDS,
        enabled: bool = True,
    ):
        self.store = store
        self.client = client
        self.batch_size = max(1, batch_size)
        self.idle_poll_seconds = idle_poll_seconds
        self.enabled = enabled

        self._wakeup = asyncio.Event()
        self._backoff = 0.0
        self._stream_declared = False
        # Set when the server told us to stop trying (401/402). Cleared only by
        # a restart or a config change, because retrying cannot help.
        self._halted_reason: Optional[str] = None

    # ── Public surface ───────────────────────────────────────────────

    def notify(self) -> None:
        """Called when a new reading lands. Cheap and safe from any task."""
        self._wakeup.set()

    @property
    def halted(self) -> bool:
        return self._halted_reason is not None

    async def run_forever(self) -> None:
        if not self.enabled:
            logger.info("ontoplano sync disabled -- worker not started")
            return

        logger.info("ontoplano sync worker started")
        while True:
            try:
                await self.drain()
            except asyncio.CancelledError:
                raise
            except Exception:
                logger.exception("Unexpected error in sync worker")
                self._bump_backoff()

            delay = self._backoff if self._backoff else self.idle_poll_seconds
            if self.halted:
                # Nothing to retry, but stay alive so the status surface keeps
                # reporting and a restart is the only thing needed to recover.
                delay = self.idle_poll_seconds

            self._wakeup.clear()
            try:
                await asyncio.wait_for(self._wakeup.wait(), timeout=delay)
            except asyncio.TimeoutError:
                pass

    async def drain(self) -> int:
        """Push every pending weigh-in. Returns how many were delivered."""
        if not self.enabled or self.halted:
            return 0

        delivered = 0
        while True:
            pending = self.store.pending(limit=self.batch_size)
            if not pending:
                if delivered:
                    logger.info("ontoplano sync: %d weigh-in(s) delivered", delivered)
                self._backoff = 0.0
                return delivered

            outcome = await self._push_batch(pending)
            if outcome is None:
                return delivered  # error already recorded; back off and retry later

            batch_delivered, batch_resolved = outcome
            delivered += batch_delivered

            if batch_resolved == 0:
                # Nothing moved out of the pending set, so the next pass would
                # fetch the same rows forever. Bail and let the backoff handle it.
                logger.warning(
                    "ontoplano push resolved none of %d pending weigh-in(s) -- pausing",
                    len(pending),
                )
                self._bump_backoff()
                return delivered

    # ── Internals ────────────────────────────────────────────────────

    async def _push_batch(self, pending: list) -> Optional[tuple[int, int]]:
        """Push one batch. Returns `(delivered, resolved)`, or None if the whole
        batch failed and should be retried later. `resolved` counts rows that
        left the pending set, delivered or dropped."""
        points = [
            Point(
                external_id=weigh_in.external_id,
                at=utc_iso(weigh_in.captured_at),
                value=round(weigh_in.weight_kg, 3),
                meta=self._build_meta(weigh_in),
            )
            for weigh_in in pending
        ]
        external_ids = [weigh_in.external_id for weigh_in in pending]

        self.store.set_state("last_attempt", utc_iso(datetime.now(timezone.utc)))

        try:
            await self._ensure_stream()
            result = await self.client.push_points(points)
        except StreamMissingError as exc:
            # Declare once more, then let the next pass retry the push.
            logger.warning("Stream missing (%s) -- re-declaring", exc)
            self._stream_declared = False
            try:
                await self._ensure_stream()
            except OntoplanoError as declare_exc:
                self._record_failure(external_ids, declare_exc)
                return None
            self._bump_backoff()
            return None
        except RateLimitedError as exc:
            wait = exc.retry_after if exc.retry_after else self._next_backoff()
            self._backoff = min(float(wait), MAX_BACKOFF_SECONDS)
            self._record_failure(
                external_ids, exc, note=f"rate limited, retrying in {self._backoff:.0f}s"
            )
            return None
        except (AuthError, PlanLimitError) as exc:
            self._halted_reason = str(exc)
            self._record_failure(external_ids, exc)
            logger.error(
                "ontoplano sync halted (%s): %s. "
                "Readings stay queued locally; reconnect ontoplano to resume.",
                type(exc).__name__,
                exc,
            )
            return None
        except OntoplanoError as exc:
            if exc.retryable:
                self._bump_backoff()
            else:
                self._halted_reason = str(exc)
            self._record_failure(external_ids, exc)
            return None

        if result.delivered:
            self.store.mark_synced(result.delivered)
        for external_id, reason in result.rejected:
            # 422-class rejects will never succeed. Drop from the queue, keep the
            # row -- massalarme owns this data.
            self.store.mark_dropped(external_id, reason)

        if result.delivered:
            self.store.set_state("last_success", utc_iso(datetime.now(timezone.utc)))
            self.store.set_state("last_error", None)
            self.store.set_state("last_error_at", None)
            self._backoff = 0.0

        logger.info(
            "ontoplano push: %d accepted, %d duplicate, %d rejected",
            len(result.accepted),
            len(result.duplicates),
            len(result.rejected),
        )
        return (len(result.delivered), len(result.delivered) + len(result.rejected))

    @staticmethod
    def _build_meta(weigh_in) -> dict:
        meta: dict = {"source": weigh_in.source}
        if weigh_in.impedance:
            meta["impedance_ohm"] = weigh_in.impedance
            meta["confidence"] = "high"
        else:
            meta["confidence"] = "weight_only"
        if weigh_in.alarm_name:
            meta["alarm"] = weigh_in.alarm_name
        return meta

    async def _ensure_stream(self) -> None:
        if self._stream_declared:
            return
        await self.client.declare_stream()
        self._stream_declared = True

    def _next_backoff(self) -> float:
        base = self._backoff or INITIAL_BACKOFF_SECONDS
        grown = min(base * 2, MAX_BACKOFF_SECONDS)
        # Jitter so a fleet of retries after an outage does not arrive in lockstep.
        return grown * random.uniform(0.7, 1.3)

    def _bump_backoff(self) -> None:
        self._backoff = min(self._next_backoff(), MAX_BACKOFF_SECONDS)

    def _record_failure(self, external_ids, exc: Exception, note: str = "") -> None:
        message = f"{type(exc).__name__}: {exc}"
        if note:
            message = f"{message} ({note})"
        self.store.record_attempt(external_ids, message)
        self.store.set_state("last_error", message)
        self.store.set_state("last_error_at", utc_iso(datetime.now(timezone.utc)))
        logger.warning("ontoplano sync failed: %s", message)
