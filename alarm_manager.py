"""
Massalarme – Scale Alarm Manager

PC daemon. Historically it owned everything: it watched the Xiaomi BLE scale,
scheduled the alarms, and drove the Android phone over LAN HTTP.

The phone now owns sensing and ringing (`alarm_owner: phone`, the default). This
daemon's job is to be the home-network half of the system:

  * serve the alarm schedule and merge edits from the phone (HTTP + WebSocket),
  * ingest weigh-ins the phone reports (`POST /readings`),
  * publish them to ontoplano.

The legacy PC-owned path is still here and still works -- set
`alarm_owner: pc` in config.yaml to get the old behaviour back.

Reads configuration from XDG-compliant paths.
"""

import argparse
import asyncio
import logging
import logging.handlers
import os
import re
import secrets
import socket
import sqlite3
import subprocess
import sys
import time
import uuid
from datetime import datetime, time as dt_time, timedelta
from pathlib import Path
from typing import Dict, List, Optional, Tuple

import json
import weakref

import aiohttp as aiohttp_client
import yaml
from aiohttp import web
from bleak import BleakScanner

import ontoplano
import schedule_sync
from store import (
    SOURCE_PC,
    SOURCE_PHONE,
    Measurement,
    WeighIn,
    WeighInStore,
    make_external_id,
    parse_timestamp,
    utc_iso,
)
from sync import SyncWorker

# ---------------------------------------------------------------------------
# XDG paths
# ---------------------------------------------------------------------------
_XDG_CONFIG = Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config"))
_XDG_DATA = Path(os.environ.get("XDG_DATA_HOME", Path.home() / ".local" / "share"))
_XDG_RUNTIME = Path(os.environ.get("XDG_RUNTIME_DIR", f"/run/user/{os.getuid()}"))

CONFIG_DIR = _XDG_CONFIG / "massalarme"
DATA_DIR = _XDG_DATA / "massalarme"
CONFIG_FILE = CONFIG_DIR / "config.yaml"
ALARMS_FILE = CONFIG_DIR / "alarms.json"
_LEGACY_ALARMS_YAML = CONFIG_DIR / "alarms.yaml"
DB_FILE = DATA_DIR / "weights.db"
ALARM_STATE_FILE = _XDG_RUNTIME / "massalarme-alarm.json"

LOG_FILE = DATA_DIR / "massalarme.log"

# ---------------------------------------------------------------------------
# Logging
# ---------------------------------------------------------------------------
logger = logging.getLogger("massalarme")


def _setup_logging() -> None:
    _ensure_dirs()
    fmt = logging.Formatter(
        "%(asctime)s %(levelname)-8s %(message)s", datefmt="%H:%M:%S"
    )

    console = logging.StreamHandler(sys.stdout)
    console.setLevel(logging.INFO)
    console.setFormatter(fmt)

    file_handler = logging.handlers.RotatingFileHandler(
        LOG_FILE,
        maxBytes=2 * 1024 * 1024,
        backupCount=3,
        encoding="utf-8",
    )
    file_handler.setLevel(logging.DEBUG)
    file_handler.setFormatter(
        logging.Formatter(
            "%(asctime)s %(levelname)-8s %(message)s", datefmt="%Y-%m-%d %H:%M:%S"
        )
    )

    logger.setLevel(logging.DEBUG)
    logger.addHandler(console)
    logger.addHandler(file_handler)


# ---------------------------------------------------------------------------
# Default configuration values
# ---------------------------------------------------------------------------
_DEFAULT_CONFIG = {
    "phone_mac": "",
    "lan_network": "192.168.1.0/24",
    "port": 8080,
    "pc_port": 8888,
    "scale_name": "MIBFS",
    "scale_uuid_prefix": "0000181b",
    "syncing_weight_flag": 0x26,
    "min_weight_kg": 68,
    "shared_secret": "",
    "phone_scan_interval": 10,
    "retry_fast_interval": 10,
    "retry_interval": 120,
    "max_trigger_attempts": 5,
    # Who schedules alarms and listens to the scale.
    #   "phone" – the app does both; the PC serves data and publishes (default)
    #   "pc"    – legacy: this daemon scans BLE and drives the phone
    "alarm_owner": "phone",
    # Advertisements closer together than this are one trip to the scale.
    "weigh_in_gap_seconds": 90,
    # Outbound publishing to ontoplano. The token lives in a separate 0600 file
    # (see --set-token), never here.
    "ontoplano": {
        "enabled": False,
        "base_url": "",
        "timeout_seconds": 15,
        "batch_size": 500,
        # Derive alarms from the ontoplano planner. Which occurrences become
        # alarms, and whether they are hard or soft, is decided here — ontoplano
        # knows nothing about alarms and should not.
        "schedule": {
            "enabled": False,
            "days": 7,
            "poll_minutes": 30,
            "rules": [
                {"match": {"title": "(?i)wake up|acordar"}, "kind": "hard"},
            ],
        },
    },
}

ALARM_OWNER_PHONE = "phone"
ALARM_OWNER_PC = "pc"


# =====================================================================
# Configuration helpers
# =====================================================================


def _ensure_dirs() -> None:
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    DATA_DIR.mkdir(parents=True, exist_ok=True)


# ---------------------------------------------------------------------------
# Bluetooth power management
# ---------------------------------------------------------------------------


async def ensure_bluetooth_on() -> bool:
    """Ensure the BT adapter is powered on via bluetoothctl.
    Returns True if BT is (now) on, False on failure."""
    try:
        proc = await asyncio.create_subprocess_exec(
            "bluetoothctl",
            "show",
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
        stdout, _ = await proc.communicate()
        if b"Powered: yes" in stdout:
            return True

        logger.info("Bluetooth is OFF – powering on via bluetoothctl...")
        proc = await asyncio.create_subprocess_exec(
            "bluetoothctl",
            "power",
            "on",
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
        stdout, _ = await proc.communicate()
        if proc.returncode == 0:
            logger.info("Bluetooth powered on successfully.")
            # Adapter needs a moment to initialise after power-on
            await asyncio.sleep(2)
            return True

        logger.error(
            "Failed to power on Bluetooth (exit %d): %s",
            proc.returncode,
            stdout.decode(),
        )
        return False
    except FileNotFoundError:
        logger.error("bluetoothctl not found – cannot manage Bluetooth power state")
        return False
    except Exception as exc:
        logger.error("Bluetooth power check failed: %s", exc)
        return False


# ---------------------------------------------------------------------------
# Alarm state persistence for the active alarm
# ---------------------------------------------------------------------------


def _write_alarm_state(alarm_name: str, phone_ip: str) -> None:
    try:
        ALARM_STATE_FILE.parent.mkdir(parents=True, exist_ok=True)
        ALARM_STATE_FILE.write_text(
            json.dumps(
                {
                    "alarm_name": alarm_name,
                    "phone_ip": phone_ip,
                    "timestamp": datetime.now().isoformat(),
                }
            ),
            encoding="utf-8",
        )
    except OSError as exc:
        logger.warning("Failed to write alarm state: %s", exc)


def _read_alarm_state() -> Optional[dict]:
    try:
        if ALARM_STATE_FILE.exists():
            data = json.loads(ALARM_STATE_FILE.read_text(encoding="utf-8"))
            return data if isinstance(data, dict) else None
    except (json.JSONDecodeError, OSError):
        pass
    return None


def _clear_alarm_state() -> None:
    try:
        ALARM_STATE_FILE.unlink(missing_ok=True)
    except OSError:
        pass


def _migrate_legacy_files() -> None:
    """Move files from the old working-directory layout to XDG paths."""
    script_dir = Path(__file__).resolve().parent

    # Migrate legacy alarms.yaml from project dir to XDG config
    legacy_alarms = script_dir / "alarms.yaml"
    if legacy_alarms.exists() and not _LEGACY_ALARMS_YAML.exists():
        logger.info("Migrating %s \u2192 %s", legacy_alarms, _LEGACY_ALARMS_YAML)
        _LEGACY_ALARMS_YAML.write_text(
            legacy_alarms.read_text(encoding="utf-8"), encoding="utf-8"
        )

    # Migrate alarms.yaml \u2192 alarms.json
    if _LEGACY_ALARMS_YAML.exists() and not ALARMS_FILE.exists():
        logger.info("Converting %s \u2192 %s", _LEGACY_ALARMS_YAML, ALARMS_FILE)
        try:
            with open(_LEGACY_ALARMS_YAML, encoding="utf-8") as fh:
                data = yaml.safe_load(fh) or {}
            ALARMS_FILE.write_text(
                json.dumps(data, indent=2, ensure_ascii=False), encoding="utf-8"
            )
            logger.info("Migration complete. You can remove %s", _LEGACY_ALARMS_YAML)
        except Exception as exc:
            logger.error("Failed to migrate alarms YAML \u2192 JSON: %s", exc)

    legacy_db = script_dir / "weights.db"
    if legacy_db.exists() and not DB_FILE.exists():
        import shutil

        logger.info("Migrating %s \u2192 %s", legacy_db, DB_FILE)
        shutil.copy2(legacy_db, DB_FILE)


def _generate_secret(cfg: dict) -> str:
    """Generate a random 32-byte hex secret, persist it, and return it."""
    secret = secrets.token_hex(32)
    cfg["shared_secret"] = secret
    _save_config(cfg)
    logger.info("Generated new shared secret.")
    return secret


def _save_config(cfg: dict) -> None:
    """Write config dict back to config.yaml."""
    CONFIG_FILE.write_text(yaml.dump(cfg, default_flow_style=False), encoding="utf-8")


def load_config() -> dict:
    """Load config.yaml, creating it with defaults if absent."""
    _ensure_dirs()
    _migrate_legacy_files()

    if not CONFIG_FILE.exists():
        _save_config(_DEFAULT_CONFIG)
        logger.info("Created default config at %s", CONFIG_FILE)

    with open(CONFIG_FILE, encoding="utf-8") as fh:
        cfg = yaml.safe_load(fh) or {}

    # Fill in any missing keys from defaults
    changed = False
    for key, default in _DEFAULT_CONFIG.items():
        if key not in cfg:
            cfg[key] = default
            changed = True
    if changed:
        _save_config(cfg)

    # Auto-generate secret on first run
    if not cfg.get("shared_secret"):
        _generate_secret(cfg)
        _show_secret_qr(cfg["shared_secret"], cfg)

    return cfg


def _local_ip(cfg: dict) -> str:
    """Best guess at this machine's LAN address.

    Opens a UDP socket towards the configured subnet -- no packet is sent, but
    the kernel picks the interface it would route through, which is the address
    the phone needs to reach us.
    """
    probe_target = cfg.get("lan_network", "192.168.1.0/24").split("/")[0]
    probe_target = probe_target.rsplit(".", 1)[0] + ".1"
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.connect((probe_target, 9))
        return sock.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        sock.close()


def build_provisioning_payload(cfg: dict) -> dict:
    """Everything the phone needs to become autonomous, in one QR code.

    The phone owns the alarm and the scale now, so it needs the scale decoding
    parameters as well as the shared secret -- and it needs to know where the PC
    is, because the PC no longer initiates contact.
    """
    return {
        "v": 2,
        "secret": cfg.get("shared_secret", ""),
        "pc_host": _local_ip(cfg),
        "pc_port": int(cfg.get("pc_port", 8888)),
        "scale": {
            "name": cfg.get("scale_name", "MIBFS"),
            "uuid_prefix": cfg.get("scale_uuid_prefix", "0000181b"),
            "stable_flag": int(cfg.get("syncing_weight_flag", 0xA4)),
            "min_weight_kg": float(cfg.get("min_weight_kg", 68)),
            "session_gap_seconds": int(cfg.get("weigh_in_gap_seconds", 90)),
        },
    }


PROVISIONING_PREFIX = "MA2"


def encode_provisioning(payload: dict) -> str:
    """Render the provisioning payload as a compact, scannable string.

    Deliberately not JSON. The QR has to be read off a terminal by a phone
    camera, and density is everything: the JSON form of this same payload needs a
    63x63-module code, against 39x39 for the string below -- unreadable at the
    same printed width, which is exactly how this broke.

    Two things buy that back. The payload only carries what the app actually
    uses, and every character stays inside QR's *alphanumeric* mode (uppercase
    A-Z, digits, and a few punctuation marks including `:` and `.`), which packs
    ~40% tighter than byte mode. Hence the uppercased secret.

        MA2:<SECRET>:<host>:<port>:<stable_flag>:<min_kg>:<session_gap_s>
    """
    scale = payload["scale"]
    min_weight = scale["min_weight_kg"]
    # Keep it integral when it can be; "68" beats "68.0" and both are legal
    # alphanumeric characters.
    min_weight_text = (
        str(int(min_weight)) if float(min_weight).is_integer() else f"{min_weight:.1f}"
    )

    return ":".join(
        [
            PROVISIONING_PREFIX,
            str(payload["secret"]).upper(),
            str(payload["pc_host"]),
            str(payload["pc_port"]),
            str(scale["stable_flag"]),
            min_weight_text,
            str(scale["session_gap_seconds"]),
        ]
    )


def _write_secret_png(encoded: str) -> Optional[Path]:
    """Also save the QR as an image, as a guaranteed-scannable fallback.

    A terminal at a small font size can defeat any QR; an image the user can
    open and zoom cannot.
    """
    try:
        import qrcode  # type: ignore[import-untyped]

        qr = qrcode.QRCode(border=4, box_size=10)
        qr.add_data(encoded)
        qr.make(fit=True)
        path = DATA_DIR / "pairing-qr.png"
        qr.make_image(fill_color="black", back_color="white").save(str(path))
        return path
    except Exception as exc:  # pillow missing, read-only dir, ...
        logger.debug("Could not write QR image: %s", exc)
        return None


def _show_secret_qr(secret: str, cfg: Optional[dict] = None) -> None:
    """Print the provisioning QR code to the terminal."""
    payload = build_provisioning_payload(cfg or {"shared_secret": secret})
    encoded = encode_provisioning(payload)

    try:
        import qrcode  # type: ignore[import-untyped]

        # A full four-module quiet zone. The old border=1 survived a 64-character
        # payload but gives scanners nothing to lock onto on a denser code.
        qr = qrcode.QRCode(border=4)
        qr.add_data(encoded)
        qr.make(fit=True)
        qr.print_ascii(tty=sys.stdout.isatty())

        logger.info(
            "Scan the QR code above with the Massalarme app. It carries the shared "
            "secret, this PC's address (%s:%s) and the scale parameters.",
            payload["pc_host"],
            payload["pc_port"],
        )
        image_path = _write_secret_png(encoded)
        if image_path:
            logger.info(
                "If the terminal code will not scan, zoom in (Ctrl +) or open %s",
                image_path,
            )
    except ImportError:
        logger.warning(
            "qrcode package not installed \u2013 cannot display QR code. "
            "Install with: pip install qrcode[pil]"
        )
        logger.info("Provisioning payload (copy manually): %s", encoded)


# =====================================================================
# Alarm schedule (v2 format)
# =====================================================================

WEEKDAYS = [
    "monday",
    "tuesday",
    "wednesday",
    "thursday",
    "friday",
    "saturday",
    "sunday",
]
_WEEKDAY_INDEX = {d: i for i, d in enumerate(WEEKDAYS)}


def _gen_alarm_id() -> str:
    """Generate a short unique alarm ID."""
    return uuid.uuid4().hex[:8]


def _now_ms() -> int:
    """Current time as epoch milliseconds."""
    return int(time.time() * 1000)


def _migrate_v1_to_v2(v1: Dict) -> Dict:
    """Convert legacy per-day alarm format to v2 (alarm-centric with days list).

    Alarms with the same name+time across different days are merged into one
    alarm with a combined ``days`` list.
    """
    now_ms = _now_ms()
    # Group weekly alarms by (name, time) to merge days
    weekly_key: Dict[Tuple[str, str], Dict] = {}
    for day in WEEKDAYS:
        for entry in v1.get(day) or []:
            t = entry.get("time", "")
            n = entry.get("name", "")
            key = (n, t)
            if key not in weekly_key:
                weekly_key[key] = {
                    "id": _gen_alarm_id(),
                    "name": n,
                    "time": t,
                    "days": [],
                    "enabled": True,
                    "updated_at": now_ms,
                }
            weekly_key[key]["days"].append(day)

    alarms: List[Dict] = list(weekly_key.values())

    # Date alarms
    for entry in v1.get("date") or []:
        alarms.append(
            {
                "id": _gen_alarm_id(),
                "name": entry.get("name", ""),
                "time": entry.get("time", ""),
                "date": entry.get("date", ""),
                "enabled": True,
                "updated_at": now_ms,
            }
        )

    # Next (one-shot) alarms
    for entry in v1.get("next") or []:
        alarms.append(
            {
                "id": _gen_alarm_id(),
                "name": entry.get("name", ""),
                "time": entry.get("time", ""),
                "type": "next",
                "enabled": True,
                "updated_at": now_ms,
            }
        )

    return {"version": 2, "alarms": alarms}


def _save_alarms(alarms_config: Dict) -> None:
    """Write alarms dict to disk."""
    ALARMS_FILE.write_text(
        json.dumps(alarms_config, indent=2, ensure_ascii=False), encoding="utf-8"
    )


def load_alarms() -> Tuple[Dict, float]:
    """Load alarms.json, migrating v1→v2 if needed. Returns (config, mtime)."""
    if not ALARMS_FILE.exists():
        logger.warning("Alarms file not found: %s", ALARMS_FILE)
        return {"version": 2, "alarms": []}, 0.0
    try:
        with open(ALARMS_FILE, encoding="utf-8") as fh:
            config = json.load(fh)
        if not isinstance(config, dict):
            config = {}

        # Detect v1 format (no "version" key, has weekday keys at top level)
        if config.get("version") != 2:
            logger.info("Migrating alarms.json from v1 → v2 format.")
            config = _migrate_v1_to_v2(config)
            _save_alarms(config)
            logger.info("Migration complete: %d alarm(s).", len(config["alarms"]))

        mtime = os.path.getmtime(ALARMS_FILE)
        return config, mtime
    except (json.JSONDecodeError, OSError) as exc:
        logger.error("Failed to load alarms file: %s", exc)
        return {"version": 2, "alarms": []}, 0.0


def _parse_time(time_str: str) -> dt_time:
    """Parse a time string in HH:MM or HH:MM:SS format."""
    for fmt in ("%H:%M:%S", "%H:%M"):
        try:
            return datetime.strptime(time_str, fmt).time()
        except ValueError:
            continue
    raise ValueError(f"Invalid time format: {time_str!r} (expected HH:MM or HH:MM:SS)")


def _parse_datetime(date_str: str, time_str: str) -> datetime:
    """Parse a date + time pair. Date format: DD-MM-YYYY, time: HH:MM or HH:MM:SS."""
    t = _parse_time(time_str)
    d = datetime.strptime(date_str, "%d-%m-%Y").date()
    return datetime.combine(d, t)


def _alarm_kind(alarm: Dict) -> str:
    """Return 'weekly', 'date', or 'next' depending on alarm fields."""
    if alarm.get("date"):
        return "date"
    if alarm.get("type") == "next":
        return "next"
    return "weekly"


def merge_alarms(local: Dict, remote: Dict) -> Dict:
    """Merge two v2 alarm configs. Per alarm ID, keep the one with the
    latest ``updated_at``. Alarms only on one side are kept (new alarm
    created while offline). Soft-deleted alarms (``deleted: true``) are
    preserved as tombstones so deletions propagate across devices."""
    local_by_id = {a["id"]: a for a in local.get("alarms") or []}
    remote_by_id = {a["id"]: a for a in remote.get("alarms") or []}
    all_ids = set(local_by_id) | set(remote_by_id)

    merged: List[Dict] = []
    for aid in all_ids:
        l = local_by_id.get(aid)
        r = remote_by_id.get(aid)
        if l and r:
            merged.append(l if l.get("updated_at", 0) >= r.get("updated_at", 0) else r)
        elif l:
            merged.append(l)
        else:
            merged.append(r)  # type: ignore[arg-type]

    return {"version": 2, "alarms": merged}


def _prune_old_tombstones(alarms_config: Dict, *, max_age_days: int = 30) -> Dict:
    """Remove soft-deleted alarms older than *max_age_days*."""
    cutoff_ms = _now_ms() - (max_age_days * 86400 * 1000)
    kept: List[Dict] = []
    for alarm in alarms_config.get("alarms") or []:
        if alarm.get("deleted") and alarm.get("updated_at", 0) < cutoff_ms:
            continue
        kept.append(alarm)
    return {"version": 2, "alarms": kept}


def _is_active_alarm(alarm: Dict) -> bool:
    """Return True if alarm is not soft-deleted."""
    return not alarm.get("deleted", False)


def _inject_tombstones_for_removed(old_config: Dict, new_config: Dict) -> Dict:
    """If alarms were physically removed from the file (manual edit), inject
    tombstone entries so the deletion propagates via merge. Returns updated config
    (may modify new_config in place and re-save)."""
    old_ids = {a["id"] for a in old_config.get("alarms") or [] if not a.get("deleted")}
    new_by_id = {a["id"]: a for a in new_config.get("alarms") or []}
    new_ids = set(new_by_id.keys())

    removed_ids = old_ids - new_ids
    if not removed_ids:
        return new_config

    now_ms = _now_ms()
    alarms_list = new_config.get("alarms") or []
    for aid in removed_ids:
        old_alarm = next(
            (a for a in old_config.get("alarms") or [] if a["id"] == aid), None
        )
        if old_alarm is None:
            continue
        tombstone = {
            "id": aid,
            "name": old_alarm.get("name", ""),
            "time": old_alarm.get("time", ""),
            "deleted": True,
            "updated_at": now_ms,
        }
        alarms_list.append(tombstone)

    new_config["alarms"] = alarms_list
    _save_alarms(new_config)
    logger.info(
        "Injected %d tombstone(s) for manually removed alarm(s).", len(removed_ids)
    )
    return new_config


def get_next_alarm_time(alarms_config: Dict) -> Optional[Tuple[datetime, str]]:
    """Return the (datetime, name) of the soonest upcoming alarm, or None."""
    now = datetime.now()
    candidates: List[Tuple[datetime, str]] = []

    for alarm in alarms_config.get("alarms") or []:
        if not _is_active_alarm(alarm):
            continue
        if not alarm.get("enabled", True):
            continue
        try:
            t = _parse_time(alarm["time"])
        except (KeyError, ValueError):
            continue

        kind = _alarm_kind(alarm)
        name = alarm.get("name", "Alarm")

        if kind == "next":
            dt = datetime.combine(now.date(), t)
            if dt <= now:
                dt += timedelta(days=1)
            candidates.append((dt, name))

        elif kind == "date":
            try:
                dt = _parse_datetime(alarm["date"], alarm["time"])
            except (KeyError, ValueError):
                continue
            if dt > now:
                candidates.append((dt, name))

        else:  # weekly
            days = alarm.get("days") or []
            for day_name in days:
                i = _WEEKDAY_INDEX.get(day_name)
                if i is None:
                    continue
                days_ahead = (i - now.weekday()) % 7
                if days_ahead == 0 and datetime.combine(now.date(), t) <= now:
                    days_ahead = 7
                target_date = now.date() + timedelta(days=days_ahead)
                dt = datetime.combine(target_date, t)
                candidates.append((dt, name))

    return min(candidates, key=lambda x: x[0]) if candidates else None


def _iter_upcoming_alarm_datetimes(
    alarms_config: Dict, *, horizon_days: int = 14
) -> List[Tuple[datetime, str]]:
    """Return sorted, de-duplicated list of upcoming alarms within *horizon_days*."""
    now = datetime.now()
    start_date = now.date()
    candidates: List[Tuple[datetime, str]] = []

    for alarm in alarms_config.get("alarms") or []:
        if not _is_active_alarm(alarm):
            continue
        if not alarm.get("enabled", True):
            continue
        try:
            t = _parse_time(alarm["time"])
        except (KeyError, ValueError):
            continue

        kind = _alarm_kind(alarm)
        name = alarm.get("name", "Alarm")

        if kind == "next":
            for d in range(horizon_days + 1):
                dt = datetime.combine(start_date + timedelta(days=d), t)
                if dt >= now:
                    candidates.append((dt, name))
                    break

        elif kind == "date":
            try:
                dt = _parse_datetime(alarm["date"], alarm["time"])
            except (KeyError, ValueError):
                continue
            if dt >= now:
                candidates.append((dt, name))

        else:  # weekly
            days = alarm.get("days") or []
            for day_name in days:
                i = _WEEKDAY_INDEX.get(day_name)
                if i is None:
                    continue
                for d in range(horizon_days + 1):
                    cur = start_date + timedelta(days=d)
                    if cur.weekday() != i:
                        continue
                    dt = datetime.combine(cur, t)
                    if dt >= now:
                        candidates.append((dt, name))
                        break

    candidates.sort(key=lambda x: x[0])
    seen: set[Tuple[datetime, str]] = set()
    out: List[Tuple[datetime, str]] = []
    for item in candidates:
        if item not in seen:
            seen.add(item)
            out.append(item)
    return out


def log_upcoming_alarms(
    alarms_config: Dict, *, limit: int = 10, horizon_days: int = 14
) -> None:
    """Log the next few upcoming alarms."""
    now = datetime.now()
    items = _iter_upcoming_alarm_datetimes(alarms_config, horizon_days=horizon_days)[
        :limit
    ]
    if not items:
        logger.info("No upcoming alarms in horizon.")
        return

    logger.info("Upcoming alarms (next %d):", len(items))
    for dt, name in items:
        delta = int((dt - now).total_seconds())
        if delta < 0:
            continue
        days, rem = divmod(delta, 86400)
        hours, rem = divmod(rem, 3600)
        mins, _ = divmod(rem, 60)
        eta = f"{days}d {hours:02}h {mins:02}m" if days else f"{hours:02}h {mins:02}m"
        prep_dt = (dt - timedelta(minutes=1)).replace(second=0, microsecond=0)
        prep_state = "OK" if prep_dt > now else "PREP NOW"
        logger.info(
            "  %s | %s | in %s | prep %s (%s) | rings %s",
            dt.strftime("%Y-%m-%d %H:%M"),
            name,
            eta,
            prep_dt.strftime("%H:%M"),
            prep_state,
            dt.strftime("%H:%M:%S"),
        )


def print_alarms(alarms_config: Dict) -> None:
    """Print upcoming alarms to stdout in a human-friendly table."""
    now = datetime.now()
    items = _iter_upcoming_alarm_datetimes(alarms_config, horizon_days=14)

    if not items:
        print("No upcoming alarms.")
        return

    print(f"{'When':<18} {'Name':<20} {'ETA':>14}")
    print("-" * 54)

    for dt, name in items:
        delta = int((dt - now).total_seconds())
        if delta < 0:
            continue
        d, rem = divmod(delta, 86400)
        h, rem = divmod(rem, 3600)
        m, _ = divmod(rem, 60)
        if d > 0:
            eta = f"{d}d {h:02}h {m:02}m"
        elif h > 0:
            eta = f"{h}h {m:02}m"
        else:
            eta = f"{m}m"
        when_str = dt.strftime("%a %d/%m %H:%M")
        print(f"{when_str:<18} {name:<20} {'in ' + eta:>14}")


# =====================================================================
# BLE helpers
# =====================================================================


def format_bytes(data: bytes) -> str:
    return "".join(f"{b:02x}" for b in data)


def get_relevant_data(data: bytes) -> str:
    hex_str = format_bytes(data)
    first_flag = hex_str[2:4]
    value_str = hex_str[-4:]
    value = int(value_str[-2:] + value_str[:2], 16)
    second_flag = hex_str[-8:-4]
    return f"{first_flag}:{second_flag}:{value}"


# =====================================================================
# Database
# =====================================================================


_store: Optional[WeighInStore] = None
_sync_worker: Optional[SyncWorker] = None

# Set when the phone changes the ontoplano rule, so the schedule loop can wake
# early instead of waiting out its poll interval.
_schedule_refresh: Optional[asyncio.Event] = None

# Cached /api/v1/me result, so the status surface can name the account the token
# belongs to rather than just saying "connected".
_ontoplano_identity: dict = {}


def get_store(cfg: Optional[dict] = None) -> WeighInStore:
    """The weigh-in store, created on first use."""
    global _store
    if _store is None:
        gap = int((cfg or {}).get("weigh_in_gap_seconds", 90))
        _store = WeighInStore(DB_FILE, gap_seconds=gap)
        _store.init()
    return _store


def init_db(cfg: Optional[dict] = None) -> None:
    store = get_store(cfg)
    logger.info("Database ready: %s", DB_FILE)

    # Collapse the raw log the first time we see one that has never been
    # sessionised. Without this the weigh-in history stays empty until someone
    # happens to run `make backfill`, which is not a step anyone should have to
    # know about — and it silently makes the app's Weight tab look broken.
    try:
        if store.pending_count() == 0 and not store.latest(limit=1):
            inserted, _ = store.backfill_from_raw_log()
            if inserted:
                logger.info("First run: collapsed the raw log into %d weigh-in(s)", inserted)
    except Exception:
        logger.exception("Initial backfill failed; continuing without it")


def _get_last_weight() -> Optional[float]:
    try:
        conn = sqlite3.connect(DB_FILE)
        row = conn.execute(
            "SELECT weight_kg FROM weights ORDER BY id DESC LIMIT 1"
        ).fetchone()
        conn.close()
        return row[0] if row else None
    except Exception:
        return None


def log_weight(
    weight_kg: float, impedance: float, raw_value: str, alarm_name: Optional[str] = None
) -> None:
    """Append one scale advertisement to the raw log.

    Only the raw log: a single trip to the scale produces dozens of these, so
    turning them into a weigh-in is a separate, deduplicating step.
    """
    get_store().record_raw(
        Measurement(
            captured_at=datetime.now(),
            weight_kg=weight_kg,
            impedance=None if impedance in (-1.0, 65533.0) else impedance,
            raw_value=raw_value,
            alarm_name=alarm_name,
            source=SOURCE_PC,
        )
    )
    logger.info(
        "Logged: %.2fkg (raw=%s) | Alarm: %s",
        weight_kg,
        raw_value,
        alarm_name or "manual",
    )


def store_weigh_ins(weigh_ins: List, cfg: Optional[dict] = None) -> Tuple[int, int]:
    """Persist finished weigh-ins and wake the syncer. Returns (new, duplicate).

    Note what this deliberately does *not* do: re-run sessionisation. Whoever
    produced these already decided where the session boundaries are. Re-deriving
    them here would let a redelivered batch merge two weigh-ins that were
    previously stored separately, minting a third id -- exactly the duplicate the
    whole design exists to prevent.
    """
    store = get_store(cfg)
    inserted, duplicates = store.upsert_weigh_ins(weigh_ins)
    if inserted and _sync_worker is not None:
        _sync_worker.notify()
    if duplicates:
        logger.debug("Ignored %d already-known weigh-in(s)", duplicates)
    return (inserted, duplicates)


def collapse_raw_log(cfg: Optional[dict] = None) -> int:
    """Derive weigh-ins from the PC's own raw advertisement log.

    Used by the legacy `alarm_owner: pc` path, where the BLE callback writes raw
    rows and has no notion of when a trip to the scale ended. Deterministic and
    idempotent, so it is safe to call on a timer.
    """
    store = get_store(cfg)
    inserted, _ = store.backfill_from_raw_log()
    if inserted and _sync_worker is not None:
        _sync_worker.notify()
    return inserted


# =====================================================================
# Network
# =====================================================================


async def discover_phone_ip(cfg: dict) -> str:
    """Scan ARP table until phone is found. Blocks with retries."""
    phone_mac = cfg["phone_mac"]
    lan_network = cfg["lan_network"]
    phone_ip: Optional[str] = None

    while not phone_ip:
        try:
            logger.info("Scanning ARP table for phone (MAC: %s)...", phone_mac)
            subprocess.run(
                ["ping", "-c", "1", "-b", lan_network.split("/")[0]],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            result = subprocess.check_output(["ip", "neigh"], text=True)
            for line in result.splitlines():
                if phone_mac.lower() in line.lower():
                    phone_ip = line.split()[0]
                    logger.info("Phone found at: %s", phone_ip)
                    break
        except FileNotFoundError:
            logger.error("'ip' command not found \u2013 cannot discover phone IP.")
        except subprocess.SubprocessError as exc:
            logger.warning("IP discovery error: %s", exc)

        if phone_ip is None:
            interval = cfg.get("phone_scan_interval", 10)
            logger.info("Phone not found. Retrying in %ds...", interval)
            await asyncio.sleep(interval)

    return phone_ip


async def discover_phone_ip_until(cfg: dict, deadline: datetime) -> Optional[str]:
    """Scan ARP table for the phone until *deadline* is reached."""
    phone_mac = cfg["phone_mac"]
    lan_network = cfg["lan_network"]
    interval = cfg.get("phone_scan_interval", 10)

    while True:
        try:
            logger.info("Scanning ARP table for phone (MAC: %s)...", phone_mac)
            subprocess.run(
                ["ping", "-c", "1", "-b", lan_network.split("/")[0]],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            result = subprocess.check_output(["ip", "neigh"], text=True)
            for line in result.splitlines():
                if phone_mac.lower() in line.lower():
                    phone_ip = line.split()[0]
                    logger.info("Phone found at: %s", phone_ip)
                    return phone_ip
        except FileNotFoundError:
            logger.error("'ip' command not found – cannot discover phone IP.")
        except subprocess.SubprocessError as exc:
            logger.warning("IP discovery error: %s", exc)

        remaining_seconds = (deadline - datetime.now()).total_seconds()
        if remaining_seconds <= 0:
            return None

        logger.info(
            "Phone not found. Retrying in %ds (%.1fs remaining before skip)...",
            interval,
            remaining_seconds,
        )
        await asyncio.sleep(min(interval, remaining_seconds))


_SCRIPT_DIR = Path(__file__).resolve().parent
_ICON_PATH = _SCRIPT_DIR / "lanalarm" / "icon.png"

_FAST_RETRIES = 3
_DEFAULT_MAX_TRIGGER_ATTEMPTS = 5
_ALARM_MISFIRE_GRACE_SECONDS = 5
_TRIGGER_NOTIFICATION_ID = 1
_HTTP_TIMEOUT = aiohttp_client.ClientTimeout(total=5)


def _notify_send(
    summary: str,
    body: str,
    urgency: str = "critical",
    timeout_ms: int = 8000,
    replace_id: Optional[int] = None,
) -> None:
    icon = str(_ICON_PATH) if _ICON_PATH.exists() else "alarm-clock"
    cmd = ["notify-send", "-u", urgency, "-i", icon, "-t", str(timeout_ms)]
    if replace_id is not None:
        cmd += [
            "-h",
            f"int:transient:1",
            "-h",
            f"string:x-dunst-stack-tag:massalarme-{replace_id}",
        ]
    cmd += [summary, body]
    try:
        subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except FileNotFoundError:
        logger.debug("notify-send not available")


def _build_url(template: str, phone_ip: str, cfg: dict) -> str:
    base = template.format(phone_ip=phone_ip, port=cfg["port"])
    return f"{base}?key={cfg['shared_secret']}"


async def trigger_alarm(phone_ip: str, cfg: dict, cancel_event: asyncio.Event) -> bool:
    """Try to trigger the alarm on the phone (async, cancellable).
    Returns True on success, False if cancelled before the phone acknowledged."""
    url = _build_url("http://{phone_ip}:{port}/alarm", phone_ip, cfg)
    fast_interval = cfg.get("retry_fast_interval", 10)
    long_interval = cfg.get("retry_interval", 120)
    max_attempts = max(
        1, int(cfg.get("max_trigger_attempts", _DEFAULT_MAX_TRIGGER_ATTEMPTS))
    )
    attempt = 0

    async with aiohttp_client.ClientSession(timeout=_HTTP_TIMEOUT) as session:
        while not cancel_event.is_set() and attempt < max_attempts:
            attempt += 1
            try:
                async with session.get(url) as resp:
                    body = await resp.text()
                    if resp.status < 400:
                        logger.info(
                            "ALARM TRIGGERED -> %d: %s", resp.status, body.strip()
                        )
                        return True
                    logger.warning(
                        "Alarm trigger got %d (attempt %d)", resp.status, attempt
                    )
            except (aiohttp_client.ClientError, asyncio.TimeoutError, OSError) as exc:
                logger.warning("Alarm trigger failed (attempt %d): %s", attempt, exc)

            delay = fast_interval if attempt <= _FAST_RETRIES else long_interval

            _notify_send(
                "Massalarme – trigger failed",
                f"Attempt {attempt}. Retrying in {delay}s...",
                urgency="normal",
                replace_id=_TRIGGER_NOTIFICATION_ID,
            )

            try:
                await asyncio.wait_for(cancel_event.wait(), timeout=delay)
                break
            except asyncio.TimeoutError:
                pass

    if attempt >= max_attempts and not cancel_event.is_set():
        logger.warning(
            "Alarm trigger failed after %d attempts – giving up for this alarm",
            attempt,
        )
        _notify_send(
            "Massalarme – alarm skipped",
            f"Phone service did not respond after {attempt} attempts.",
            urgency="normal",
            replace_id=_TRIGGER_NOTIFICATION_ID,
        )
        return False

    logger.info("Alarm trigger cancelled after %d attempts", attempt)
    return False


def _is_alarm_missed(alarm_dt: datetime, *, now: Optional[datetime] = None) -> bool:
    reference_time = now or datetime.now()
    return (
        reference_time - alarm_dt
    ).total_seconds() > _ALARM_MISFIRE_GRACE_SECONDS


def _skip_alarm(alarm_name: str, reason: str) -> None:
    logger.warning("Skipping alarm '%s': %s", alarm_name, reason)
    _notify_send(
        "Massalarme – alarm skipped",
        f"'{alarm_name}' skipped: {reason}",
        urgency="normal",
        replace_id=_TRIGGER_NOTIFICATION_ID,
    )


async def stop_alarm_on_phone(phone_ip: str, cfg: dict) -> None:
    url = _build_url("http://{phone_ip}:{port}/stop", phone_ip, cfg)
    try:
        async with aiohttp_client.ClientSession(timeout=_HTTP_TIMEOUT) as session:
            async with session.get(url) as resp:
                body = await resp.text()
                logger.info("Alarm stopped -> %d: %s", resp.status, body.strip())
    except (aiohttp_client.ClientError, asyncio.TimeoutError, OSError) as exc:
        logger.error("Stop alarm failed: %s", exc)


async def sync_alarms_to_phone(phone_ip: str, cfg: dict, alarms_config: dict) -> None:
    url = _build_url("http://{phone_ip}:{port}/sync-alarms", phone_ip, cfg)
    try:
        async with aiohttp_client.ClientSession(timeout=_HTTP_TIMEOUT) as session:
            async with session.post(url, json=alarms_config) as resp:
                logger.info("Alarms synced to phone -> %d", resp.status)
    except (aiohttp_client.ClientError, asyncio.TimeoutError, OSError) as exc:
        logger.warning("Failed to sync alarms to phone: %s", exc)


# =====================================================================
# Scale listener
# =====================================================================


async def wait_for_weight(cfg: dict, alarm_name: str) -> Optional[float]:
    """Listen for BLE advertisements until a stable weight is detected,
    or until the alarm is dismissed via passphrase. Returns weight_kg on
    scale success, None on passphrase dismiss or timeout."""
    global _alarm_dismissed

    weight_received = asyncio.Event()
    detected_weight: List[float] = []
    scale_name = cfg["scale_name"]
    uuid_prefix = cfg["scale_uuid_prefix"]
    sync_flag = cfg["syncing_weight_flag"]
    min_weight = cfg["min_weight_kg"]

    def on_advertisement(device, adv_data):  # type: ignore[no-untyped-def]
        if device.name != scale_name:
            return
        for uuid, data in adv_data.service_data.items():
            if not uuid.lower().startswith(uuid_prefix):
                continue
            first_flag = data[1]
            raw_weight = data[-2] | (data[-1] << 8)
            impedance = data[-4] | (data[-3] << 8)
            weight_kg = raw_weight / 200.0
            raw_hex = format_bytes(data)

            logger.debug("%s", get_relevant_data(data))

            if first_flag == sync_flag and weight_kg > min_weight:
                logger.info("STABLE WEIGHT: %.2fkg & %d ohm", weight_kg, impedance)
                log_weight(weight_kg, impedance, raw_hex, alarm_name)
                detected_weight.append(weight_kg)
                weight_received.set()
            else:
                logger.debug("Weight not stable yet...")

    logger.info("Listening for scale advertisement...")
    scanner = BleakScanner(detection_callback=on_advertisement)
    await scanner.start()
    try:
        dismiss_event = _alarm_dismissed
        if dismiss_event is None:
            dismiss_event = asyncio.Event()
            _alarm_dismissed = dismiss_event

        done, _ = await asyncio.wait(
            [
                asyncio.create_task(weight_received.wait()),
                asyncio.create_task(dismiss_event.wait()),
            ],
            timeout=300,
            return_when=asyncio.FIRST_COMPLETED,
        )
        if not done:
            logger.warning("Timeout: No stable weight in 5 minutes.")
            return None
        if dismiss_event.is_set():
            logger.info(
                "Alarm dismissed via passphrase \u2013 stopping scale listener."
            )
            return None
        return detected_weight[0] if detected_weight else None
    finally:
        await scanner.stop()


# =====================================================================
# PC WebSocket + HTTP server
# =====================================================================

_current_alarms: Dict = {"version": 2, "alarms": []}
_current_cfg: dict = {}
_ws_clients: weakref.WeakSet[web.WebSocketResponse] = weakref.WeakSet()
_alarm_dismissed: Optional[asyncio.Event] = None
_alarm_active: bool = False
_phone_ip: Optional[str] = None


async def _handle_alarms(request: web.Request) -> web.Response:
    """GET /alarms?key=<secret> \u2014 HTTP fallback for alarm data."""
    secret = _current_cfg.get("shared_secret", "")
    key = request.query.get("key", "")
    if not secret or key != secret:
        return web.Response(status=403, text="Invalid key")
    return web.json_response(_current_alarms)


async def _handle_stop_alarm(request: web.Request) -> web.Response:
    """GET /stop-alarm?key=<secret> \u2014 failsafe: stop the alarm from the PC."""
    secret = _current_cfg.get("shared_secret", "")
    key = request.query.get("key", "")
    if not secret or key != secret:
        return web.Response(status=403, text="Invalid key")

    global _alarm_active, _alarm_dismissed

    if not _alarm_active:
        return web.Response(text="No alarm is currently active")

    logger.info("FAILSAFE STOP triggered from PC")

    if _alarm_dismissed is not None:
        _alarm_dismissed.set()

    phone_ip = _phone_ip
    if phone_ip:
        try:
            await stop_alarm_on_phone(phone_ip, _current_cfg)
        except Exception as exc:
            logger.error("Failsafe stop_alarm to phone failed: %s", exc)

    _alarm_active = False
    _clear_alarm_state()
    _notify_send(
        "Massalarme \u2013 alarm stopped",
        "Failsafe stop from PC",
        urgency="normal",
        timeout_ms=5000,
    )
    return web.Response(text="Alarm stopped via failsafe")


def _parse_reported_reading(entry: dict) -> WeighIn:
    """Turn one JSON reading from the phone into a WeighIn, or raise ValueError.

    The `external_id` is recomputed from the reading's own content rather than
    trusted. Both sides run the same pure function, so a well-behaved phone
    always agrees; a buggy one cannot poison the id space.
    """
    captured_raw = entry.get("captured_at") or entry.get("at")
    if not captured_raw:
        raise ValueError("missing captured_at")
    captured_at = parse_timestamp(str(captured_raw))

    raw_weight = entry.get("weight_kg", entry.get("value"))
    if raw_weight is None:
        raise ValueError("missing weight_kg")
    weight_kg = float(raw_weight)
    if not (0 < weight_kg < 500):
        raise ValueError(f"implausible weight {weight_kg}")

    impedance_raw = entry.get("impedance")
    impedance = None
    if impedance_raw is not None:
        impedance = float(impedance_raw)
        if impedance in (-1.0, 65533.0):
            impedance = None

    raw_value = entry.get("raw_value") or None
    external_id = make_external_id(captured_at, raw_value, weight_kg)

    claimed = entry.get("external_id")
    if claimed and claimed != external_id:
        logger.warning(
            "Phone reported external_id %s but content derives %s -- using derived",
            claimed,
            external_id,
        )

    return WeighIn(
        external_id=external_id,
        captured_at=captured_at,
        weight_kg=weight_kg,
        impedance=impedance,
        raw_value=raw_value,
        alarm_name=entry.get("alarm_name") or None,
        source=SOURCE_PHONE,
        measurement_count=int(entry.get("measurements", 1) or 1),
    )


async def _handle_readings(request: web.Request) -> web.Response:
    """POST /readings?key=<secret> — the phone reports finished weigh-ins.

    Mirrors the ontoplano contract on purpose: batched, idempotent, partial
    success. The phone's uploader and this daemon's producer then share the same
    retry semantics, and a duplicate is success on both hops.
    """
    secret = _current_cfg.get("shared_secret", "")
    key = request.query.get("key", "")
    if not secret or key != secret:
        return web.Response(status=403, text="Invalid key")

    try:
        body = await request.json()
    except (json.JSONDecodeError, ValueError):
        return web.json_response({"error": "malformed JSON"}, status=400)

    entries = body.get("readings")
    if not isinstance(entries, list):
        return web.json_response({"error": "expected a 'readings' array"}, status=400)
    if len(entries) > 500:
        return web.json_response({"error": "at most 500 readings per request"}, status=400)

    parsed = []
    rejected = []
    for entry in entries:
        if not isinstance(entry, dict):
            rejected.append({"external_id": None, "reason": "not an object"})
            continue
        try:
            parsed.append(_parse_reported_reading(entry))
        except (ValueError, TypeError) as exc:
            # Malformed readings are dropped, never retried -- they will never
            # become valid, and the phone needs a definitive answer to stop
            # resending them.
            rejected.append(
                {"external_id": entry.get("external_id"), "reason": str(exc)}
            )

    inserted, duplicates = store_weigh_ins(parsed, _current_cfg)

    peer = request.remote or "phone"
    logger.info(
        "Readings from %s: %d new, %d duplicate, %d rejected",
        peer,
        inserted,
        duplicates,
        len(rejected),
    )

    if inserted:
        newest = max(parsed, key=lambda w: w.captured_at)
        await _broadcast_weight(newest.weight_kg)
        _notify_send(
            "Massalarme – weigh-in received",
            f"{newest.weight_kg:.1f} kg from the phone",
            urgency="low",
            timeout_ms=5000,
        )

    return web.json_response(
        {"accepted": inserted, "duplicates": duplicates, "rejected": rejected}
    )


async def _handle_sync_status(request: web.Request) -> web.Response:
    """GET /sync-status?key=<secret> — so the phone can show whether the whole
    chain is actually working. Silent sync failure is the default failure mode
    of an integration like this."""
    secret = _current_cfg.get("shared_secret", "")
    if not secret or request.query.get("key", "") != secret:
        return web.Response(status=403, text="Invalid key")

    section = _current_cfg.get("ontoplano") or {}
    status = get_store(_current_cfg).status()
    status["ontoplano_enabled"] = bool(section.get("enabled", False))
    status["ontoplano_base_url"] = section.get("base_url", "")
    status["halted"] = bool(_sync_worker is not None and _sync_worker.halted)

    # Who the token belongs to. "Connected" on its own is not much use when the
    # question is whether it is pointing at the right account.
    if _ontoplano_identity:
        status["ontoplano_user"] = _ontoplano_identity.get("user_id")
        status["ontoplano_timezone"] = _ontoplano_identity.get("timezone")
        status["ontoplano_scopes"] = _ontoplano_identity.get("scopes", [])

    rules = (section.get("schedule") or {}).get("rules") or []
    if rules:
        match = rules[0].get("match") or {}
        status["ontoplano_pattern"] = match.get("title", "")
        status["ontoplano_kind"] = rules[0].get("kind", "hard")

    return web.json_response(status)


async def _handle_weigh_ins(request: web.Request) -> web.Response:
    """GET /weigh-ins?key=<secret>&limit=N — the weight history, for the app.

    The PC holds the full record, including everything captured before the phone
    took over sensing; the phone only has what it measured itself.
    """
    secret = _current_cfg.get("shared_secret", "")
    if not secret or request.query.get("key", "") != secret:
        return web.Response(status=403, text="Invalid key")

    try:
        limit = min(int(request.query.get("limit", 200)), 1000)
    except ValueError:
        limit = 200

    weigh_ins = get_store(_current_cfg).latest(limit=limit)
    return web.json_response(
        {
            "weigh_ins": [
                {
                    "external_id": w.external_id,
                    "captured_at": utc_iso(w.captured_at),
                    "weight_kg": round(w.weight_kg, 2),
                    "impedance": w.impedance,
                    "alarm_name": w.alarm_name,
                    "source": w.source,
                }
                for w in weigh_ins
            ]
        }
    )


async def _handle_ws(request: web.Request) -> web.WebSocketResponse:
    secret = _current_cfg.get("shared_secret", "")
    key = request.query.get("key", "")
    if not secret or key != secret:
        resp = web.Response(status=403, text="Invalid key")
        return resp  # type: ignore[return-value]

    ws = web.WebSocketResponse(heartbeat=15)
    await ws.prepare(request)
    _ws_clients.add(ws)
    peer = request.remote or "unknown"
    logger.info("WS client connected: %s (%d total)", peer, len(_ws_clients))
    _notify_send(
        "Massalarme \u2013 phone connected",
        peer,
        urgency="low",
        timeout_ms=4000,
        replace_id=1,
    )

    try:
        await ws.send_json({"type": "alarms", "data": _current_alarms})

        async for msg in ws:
            if msg.type == web.WSMsgType.TEXT:
                if not msg.data or not msg.data.strip():
                    continue
                logger.debug("WS recv from %s: %s", peer, msg.data[:200])
                try:
                    payload = json.loads(msg.data)
                    msg_type = payload.get("type", "")
                    if msg_type == "update_alarms":
                        phone_alarms = payload.get("data")
                        if not isinstance(phone_alarms, dict):
                            await ws.send_json(
                                {"type": "error", "message": "Invalid alarms data"}
                            )
                            continue
                        merged = merge_alarms(_current_alarms, phone_alarms)
                        _save_alarms(merged)
                        _current_alarms.clear()
                        _current_alarms.update(merged)
                        logger.info("Alarms merged via WS from %s", peer)
                        await broadcast_alarms(_current_alarms, exclude=ws)
                    elif msg_type == "set_ontoplano_rule":
                        # The phone chooses which planner tasks become alarms;
                        # this side owns the token and does the fetching.
                        _apply_ontoplano_rule(
                            payload.get("pattern", ""), payload.get("kind", "hard")
                        )
                        if _schedule_refresh is not None:
                            _schedule_refresh.set()
                    elif msg_type == "alarm_dismissed":
                        logger.info("Alarm dismissed via passphrase (from %s)", peer)
                        if _alarm_dismissed is not None:
                            _alarm_dismissed.set()
                    else:
                        logger.debug("WS unknown msg type from %s: %s", peer, msg_type)
                except (json.JSONDecodeError, Exception) as exc:
                    logger.warning("WS bad message from %s: %s", peer, exc)
            elif msg.type in (web.WSMsgType.ERROR, web.WSMsgType.CLOSE):
                break
    finally:
        _ws_clients.discard(ws)
        logger.info("WS client disconnected: %s (%d remain)", peer, len(_ws_clients))
        if not _ws_clients:
            _notify_send(
                "Massalarme \u2013 phone disconnected",
                f"WebSocket lost ({peer}). Waiting for reconnection...",
                urgency="normal",
                timeout_ms=10000,
                replace_id=1,
            )

    return ws


async def broadcast_alarms(
    alarms_config: Dict,
    *,
    exclude: Optional[web.WebSocketResponse] = None,
) -> None:
    payload = json.dumps({"type": "alarms", "data": alarms_config})
    stale: list[web.WebSocketResponse] = []
    for ws in set(_ws_clients):
        if ws is exclude:
            continue
        try:
            await ws.send_str(payload)
        except (ConnectionResetError, ConnectionError, Exception):
            stale.append(ws)
    for ws in stale:
        _ws_clients.discard(ws)
    if _ws_clients:
        logger.info("Broadcast alarms to %d client(s)", len(_ws_clients))


async def _broadcast_weight(weight_kg: float) -> None:
    payload = json.dumps({"type": "weight_update", "weight_kg": weight_kg})
    for ws in set(_ws_clients):
        try:
            await ws.send_str(payload)
        except (ConnectionResetError, ConnectionError, Exception):
            pass
    logger.debug("Broadcast weight %.1fkg to WS clients", weight_kg)


async def _start_pc_server(cfg: dict) -> None:
    pc_port = cfg.get("pc_port", 8888)
    app = web.Application()
    app.router.add_get("/alarms", _handle_alarms)
    app.router.add_get("/stop-alarm", _handle_stop_alarm)
    app.router.add_get("/sync-status", _handle_sync_status)
    app.router.add_get("/weigh-ins", _handle_weigh_ins)
    app.router.add_post("/readings", _handle_readings)
    app.router.add_get("/ws", _handle_ws)

    runner = web.AppRunner(app, access_log=None)
    await runner.setup()
    site = web.TCPSite(runner, "0.0.0.0", pc_port)
    await site.start()
    logger.info("PC server listening on 0.0.0.0:%d (HTTP + WS)", pc_port)


# =====================================================================
# Alarm cycle
# =====================================================================


async def _run_alarm_cycle(phone_ip: str, cfg: dict, alarm_name: str) -> None:
    global _alarm_active, _alarm_dismissed

    _alarm_active = True
    _write_alarm_state(alarm_name, phone_ip)

    try:
        _alarm_dismissed = asyncio.Event()

        await ensure_bluetooth_on()

        triggered = await trigger_alarm(phone_ip, cfg, _alarm_dismissed)

        if not triggered:
            logger.info("Alarm trigger cancelled before phone acknowledged.")
            return

        weight_kg = await wait_for_weight(cfg, alarm_name)
        if weight_kg is not None:
            await stop_alarm_on_phone(phone_ip, cfg)
            await _broadcast_weight(weight_kg)
        else:
            logger.info("Scale listener ended without weight (passphrase or timeout).")
    finally:
        _alarm_active = False
        _alarm_dismissed = None
        _clear_alarm_state()


# =====================================================================
# Main loop
# =====================================================================


async def _pc_owned_alarm_loop(cfg: dict, alarms_config: Dict, last_mtime: float) -> None:
    """Legacy path: this daemon schedules alarms, scans BLE and drives the phone.

    Kept intact for `alarm_owner: pc`. The phone-owned path below is the default.
    """
    global _current_alarms, _alarm_active, _phone_ip

    await ensure_bluetooth_on()

    phone_ip = await discover_phone_ip(cfg)
    _phone_ip = phone_ip
    logger.info("Using phone IP: %s", phone_ip)
    await sync_alarms_to_phone(phone_ip, cfg, alarms_config)

    stale_state = _read_alarm_state()
    if stale_state:
        stale_name = stale_state.get("alarm_name", "unknown")
        logger.warning(
            "Alarm '%s' was active before daemon restart \u2013 skipping stale alarm state",
            stale_name,
        )
        _notify_send(
            "Massalarme \u2013 stale alarm cleared",
            f"'{stale_name}' was active before restart and will not be resumed.",
            urgency="normal",
            replace_id=_TRIGGER_NOTIFICATION_ID,
        )
        _clear_alarm_state()

    cached_next_alarm: Optional[Tuple[datetime, str]] = None

    while True:
        try:
            new_config, new_mtime = load_alarms()
            if new_mtime != last_mtime:
                logger.info("alarms.json updated – reloading configuration.")
                new_config = _inject_tombstones_for_removed(alarms_config, new_config)
                new_config = _prune_old_tombstones(new_config)
                _save_alarms(new_config)
                log_upcoming_alarms(new_config, limit=8)
                alarms_config = new_config
                _current_alarms = alarms_config
                last_mtime = os.path.getmtime(ALARMS_FILE)
                cached_next_alarm = None
                await broadcast_alarms(alarms_config)
                await sync_alarms_to_phone(phone_ip, cfg, alarms_config)

            if cached_next_alarm is None:
                cached_next_alarm = get_next_alarm_time(alarms_config)

            if cached_next_alarm is None:
                await asyncio.sleep(1)
                continue

            alarm_dt, alarm_name = cached_next_alarm
            prep_time = (alarm_dt - timedelta(minutes=1)).replace(
                second=0, microsecond=0
            )
            now = datetime.now()
            time_to_prep = (prep_time - now).total_seconds()

            if time_to_prep > 0:
                await asyncio.sleep(min(1, time_to_prep))
                continue

            logger.info("Prep time for '%s' \u2013 running now.", alarm_name)
            await ensure_bluetooth_on()
            prep_phone_ip = await discover_phone_ip_until(cfg, alarm_dt)
            if prep_phone_ip is None:
                _skip_alarm(alarm_name, "phone was unavailable before alarm time")
                cached_next_alarm = None
                await asyncio.sleep(1)
                continue
            phone_ip = prep_phone_ip
            _phone_ip = phone_ip
            await broadcast_alarms(alarms_config)
            await sync_alarms_to_phone(phone_ip, cfg, alarms_config)

            now = datetime.now()
            time_to_alarm = (alarm_dt - now).total_seconds()
            if time_to_alarm > 0:
                logger.info(
                    "Waiting %.1fs until alarm time %s",
                    time_to_alarm,
                    alarm_dt.strftime("%H:%M:%S"),
                )
                await asyncio.sleep(time_to_alarm)
            elif _is_alarm_missed(alarm_dt, now=now):
                _skip_alarm(alarm_name, "alarm time already passed")
                cached_next_alarm = None
                await asyncio.sleep(1)
                continue
            else:
                logger.info("Alarm time already passed slightly \u2013 triggering immediately.")

            if _alarm_active:
                logger.warning(
                    "Alarm already active \u2013 skipping trigger for '%s'", alarm_name
                )
                cached_next_alarm = None
                await asyncio.sleep(1)
                continue

            logger.info(
                "TRIGGERING ALARM: %s @ %s",
                alarm_name,
                datetime.now().strftime("%H:%M:%S"),
            )
            await _run_alarm_cycle(phone_ip, cfg, alarm_name)

            # The BLE callback only appends raw advertisements; this is where a
            # finished trip to the scale becomes one uploadable weigh-in.
            collapse_raw_log(cfg)

            cached_next_alarm = None
            await asyncio.sleep(1)

        except Exception:
            logger.exception("Unexpected error in main loop")
            await asyncio.sleep(5)


# =====================================================================
# Phone-owned mode (default)
# =====================================================================


async def _phone_owned_serve_loop(cfg: dict, alarms_config: Dict, last_mtime: float) -> None:
    """Default path: the phone schedules alarms and listens to the scale.

    This daemon keeps the schedule file in sync with the app and publishes what
    the phone reports. No Bluetooth, no alarm triggering -- if the PC is asleep
    the alarm still rings.
    """
    global _current_alarms

    logger.info(
        "Alarm ownership: phone. This daemon serves the schedule and publishes "
        "weigh-ins; it will not scan BLE or trigger alarms."
    )
    log_upcoming_alarms(alarms_config, limit=8)

    while True:
        try:
            new_config, new_mtime = load_alarms()
            if new_mtime != last_mtime:
                logger.info("alarms.json updated – reloading and broadcasting.")
                new_config = _inject_tombstones_for_removed(alarms_config, new_config)
                new_config = _prune_old_tombstones(new_config)
                _save_alarms(new_config)
                log_upcoming_alarms(new_config, limit=8)
                alarms_config = new_config
                _current_alarms = alarms_config
                last_mtime = os.path.getmtime(ALARMS_FILE)
                await broadcast_alarms(alarms_config)

            await asyncio.sleep(2)
        except Exception:
            logger.exception("Unexpected error in serve loop")
            await asyncio.sleep(5)


async def main_loop() -> None:
    global _current_alarms, _current_cfg, _sync_worker

    cfg = load_config()
    _current_cfg = cfg

    logger.info("Massalarme daemon started.")
    init_db(cfg)

    alarms_config, last_mtime = load_alarms()
    alarms_config = _prune_old_tombstones(alarms_config)
    _save_alarms(alarms_config)
    last_mtime = os.path.getmtime(ALARMS_FILE)
    _current_alarms = alarms_config

    await _start_pc_server(cfg)

    sync_task = await _start_sync_worker(cfg)
    schedule_task = await _start_schedule_sync(cfg)

    stale_state = _read_alarm_state()
    if stale_state:
        stale_name = stale_state.get("alarm_name", "unknown")
        logger.warning(
            "Alarm '%s' was active before daemon restart – skipping stale alarm state",
            stale_name,
        )
        _clear_alarm_state()

    owner = str(cfg.get("alarm_owner", ALARM_OWNER_PHONE)).lower()
    try:
        if owner == ALARM_OWNER_PC:
            await _pc_owned_alarm_loop(cfg, alarms_config, last_mtime)
        else:
            if owner != ALARM_OWNER_PHONE:
                logger.warning(
                    "Unknown alarm_owner %r – defaulting to %r", owner, ALARM_OWNER_PHONE
                )
            await _phone_owned_serve_loop(cfg, alarms_config, last_mtime)
    finally:
        for task in (sync_task, schedule_task):
            if task is not None:
                task.cancel()




def _apply_ontoplano_rule(pattern: str, kind: str) -> None:
    """Persist the phone's task-matching rule into config.yaml.

    Stored as the single rule under `ontoplano.schedule.rules`, replacing
    whatever was there: the phone's screen is the source of truth for it, and
    silently keeping a stale rule alongside would be worse than surprising.
    """
    pattern = (pattern or "").strip()
    kind = kind if kind in (schedule_sync.KIND_HARD, schedule_sync.KIND_SOFT) else "hard"

    if pattern:
        try:
            re.compile(pattern)
        except re.error as exc:
            logger.warning("Phone sent an invalid ontoplano pattern %r: %s", pattern, exc)
            return

    section = _current_cfg.setdefault("ontoplano", {}).setdefault("schedule", {})
    section["rules"] = [{"match": {"title": pattern}, "kind": kind}] if pattern else []
    section["enabled"] = bool(pattern)
    _save_config(_current_cfg)

    logger.info(
        "ontoplano rule from phone: %s",
        f"title ~ {pattern!r} -> {kind}" if pattern else "cleared",
    )


async def _schedule_sync_loop(cfg: dict) -> None:
    """Poll the ontoplano planner and turn occurrences into alarms.

    Failures here are never fatal: a planner that is unreachable just means the
    schedule stops updating, and every alarm already on the phone still rings.

    The matching rule is re-read every pass rather than captured once, because
    the phone can change it at any moment over the WebSocket.
    """
    global _current_alarms, _schedule_refresh

    op_config = ontoplano.config_from_dict(cfg, CONFIG_DIR)
    if not op_config.usable:
        logger.info("ontoplano schedule sync skipped: sync is not configured")
        return

    _schedule_refresh = asyncio.Event()
    client = ontoplano.build_client(op_config)
    logger.info("ontoplano schedule sync started")

    try:
        while True:
            section = (_current_cfg.get("ontoplano") or {}).get("schedule") or {}
            rules = schedule_sync.parse_rules(section.get("rules") or [])
            days = int(section.get("days", 7))
            poll_seconds = max(60, int(section.get("poll_minutes", 30)) * 60)

            if rules:
                try:
                    schedule = await client.fetch_schedule(days=days)
                    now_ms = _now_ms()
                    derived = schedule_sync.build_alarms(schedule, rules, now_ms)

                    current, _ = load_alarms()
                    merged = schedule_sync.merge_into_schedule(
                        current, derived, now_ms=now_ms, horizon_days=days
                    )

                    if merged != current:
                        _save_alarms(merged)
                        _current_alarms = merged
                        logger.info(
                            "ontoplano schedule applied: %d alarm(s) derived", len(derived)
                        )
                        await broadcast_alarms(merged)
                    else:
                        logger.debug("ontoplano schedule unchanged")

                except ontoplano.OntoplanoError as exc:
                    logger.warning("ontoplano schedule fetch failed: %s", exc)
                except Exception:
                    logger.exception("Unexpected error in schedule sync")
            else:
                # No rule set yet. Retire anything previously derived, so
                # clearing the pattern on the phone actually clears the alarms.
                current, _ = load_alarms()
                merged = schedule_sync.merge_into_schedule(
                    current, [], now_ms=_now_ms(), horizon_days=days
                )
                if merged != current:
                    _save_alarms(merged)
                    _current_alarms = merged
                    logger.info("ontoplano rule cleared – derived alarms retired")
                    await broadcast_alarms(merged)

            # Wake early when the phone changes the rule.
            _schedule_refresh.clear()
            try:
                await asyncio.wait_for(_schedule_refresh.wait(), timeout=poll_seconds)
                logger.info("ontoplano rule changed – refreshing now")
            except asyncio.TimeoutError:
                pass
    finally:
        await client.close()


async def _start_schedule_sync(cfg: dict) -> Optional[asyncio.Task]:
    op_config = ontoplano.config_from_dict(cfg, CONFIG_DIR)
    if not op_config.usable:
        return None
    # Started even with no rule configured: the phone can set one at any time,
    # and the loop handles the empty case by retiring derived alarms.
    return asyncio.create_task(_schedule_sync_loop(cfg))


async def _start_sync_worker(cfg: dict) -> Optional[asyncio.Task]:
    """Start the ontoplano producer, if configured. Never fatal: massalarme must
    work completely standalone with sync disabled."""
    global _sync_worker

    op_config = ontoplano.config_from_dict(cfg, CONFIG_DIR)
    client = ontoplano.build_client(op_config)
    _sync_worker = SyncWorker(
        get_store(cfg),
        client,
        batch_size=op_config.batch_size,
        enabled=op_config.usable,
    )
    if not op_config.usable:
        return None

    # Anything captured while sync was off or the daemon was down is still
    # queued; the worker drains it on its first pass.
    global _ontoplano_identity
    try:
        _ontoplano_identity = await client.whoami()
        logger.info(
            "ontoplano account %s (%s), scopes: %s",
            _ontoplano_identity.get("user_id"),
            _ontoplano_identity.get("timezone"),
            ", ".join(_ontoplano_identity.get("scopes", [])) or "none",
        )
    except ontoplano.OntoplanoError as exc:
        # Not fatal: the queue still drains, and the status surface just has
        # less to say about who we are talking to.
        logger.warning("Could not identify the ontoplano account: %s", exc)

    pending = get_store(cfg).pending_count()
    if pending:
        logger.info("%d weigh-in(s) queued for ontoplano", pending)

    return asyncio.create_task(_sync_worker.run_forever())


# =====================================================================
# CLI
# =====================================================================


def _print_sync_status(cfg: dict) -> None:
    op_config = ontoplano.config_from_dict(cfg, CONFIG_DIR)
    status = get_store(cfg).status()

    if not op_config.enabled:
        state = "disabled"
    elif not op_config.base_url:
        state = "enabled but no base_url configured"
    elif not op_config.token:
        state = f"enabled but no token (run --set-token, or create {CONFIG_DIR / ontoplano.TOKEN_FILENAME})"
    else:
        state = f"enabled -> {op_config.base_url}"

    print(f"ontoplano sync : {state}")
    print(f"weigh-ins      : {status['total']} total")
    print(f"  synced       : {status['synced']}")
    print(f"  pending      : {status['pending']}")
    print(f"  dropped      : {status['dropped']}")
    print(f"last success   : {status['last_success'] or 'never'}")
    if status["last_error"]:
        print(f"last error     : {status['last_error']}")
        print(f"  at           : {status['last_error_at']}")

    latest = get_store(cfg).latest(limit=5)
    if latest:
        print("\nrecent weigh-ins:")
        for weigh_in in latest:
            print(
                f"  {utc_iso(weigh_in.captured_at)}  {weigh_in.weight_kg:6.2f} kg  "
                f"[{weigh_in.source}]  {weigh_in.alarm_name or '-'}"
            )


async def _run_sync_once(cfg: dict) -> int:
    op_config = ontoplano.config_from_dict(cfg, CONFIG_DIR)
    if not op_config.usable:
        print("ontoplano sync is not configured. See --sync-status.")
        return 1

    client = ontoplano.build_client(op_config)
    worker = SyncWorker(get_store(cfg), client, batch_size=op_config.batch_size)
    try:
        delivered = await worker.drain()
    finally:
        await client.close()

    pending = get_store(cfg).pending_count()
    print(f"Delivered {delivered} weigh-in(s). {pending} still pending.")
    return 0 if pending == 0 else 2


async def _check_ontoplano(cfg: dict) -> int:
    """Confirm the token works and report what it can do."""
    op_config = ontoplano.config_from_dict(cfg, CONFIG_DIR)
    if not op_config.base_url:
        print("No ontoplano base_url configured in config.yaml.")
        return 1
    if not op_config.token:
        print(f"No token found. Run: massalarme --set-token <TOKEN>")
        return 1

    # Force the client on even when sync is disabled -- this is a setup check.
    client = ontoplano.HttpOntoplanoClient(op_config)
    try:
        identity = await client.whoami()
        scopes = identity.get("scopes", [])
        print(f"Connected to {op_config.base_url}")
        print(f"  user     : {identity.get('user_id')}")
        print(f"  timezone : {identity.get('timezone')}")
        print(f"  scopes   : {', '.join(scopes) or 'none'}")

        missing = [s for s in ("streams:write",) if s not in scopes]
        if missing:
            print(f"\nMissing scope(s): {', '.join(missing)} — pushing readings will fail.")
            return 2

        schedule_enabled = ((cfg.get("ontoplano") or {}).get("schedule") or {}).get(
            "enabled", False
        )
        if schedule_enabled and "schedule:read" not in scopes:
            print("\nSchedule sync is enabled but the token lacks 'schedule:read'.")
            return 2

        print("\nToken looks good.")
        return 0
    except ontoplano.OntoplanoError as exc:
        print(f"ontoplano check failed: {type(exc).__name__}: {exc}")
        return 1
    finally:
        await client.close()


async def _run_schedule_once(cfg: dict) -> int:
    section = (cfg.get("ontoplano") or {}).get("schedule") or {}
    op_config = ontoplano.config_from_dict(cfg, CONFIG_DIR)
    if not op_config.base_url or not op_config.token:
        print("ontoplano is not configured. See --check-ontoplano.")
        return 1

    rules = schedule_sync.parse_rules(section.get("rules") or [])
    if not rules:
        print("No schedule rules configured under ontoplano.schedule.rules.")
        return 1

    days = int(section.get("days", 7))
    client = ontoplano.HttpOntoplanoClient(op_config)
    try:
        schedule = await client.fetch_schedule(days=days)
    except ontoplano.OntoplanoError as exc:
        print(f"Schedule fetch failed: {type(exc).__name__}: {exc}")
        return 1
    finally:
        await client.close()

    now_ms = _now_ms()
    derived = schedule_sync.build_alarms(schedule, rules, now_ms)
    current, _ = load_alarms()
    merged = schedule_sync.merge_into_schedule(
        current, derived, now_ms=now_ms, horizon_days=days
    )
    _save_alarms(merged)

    print(f"Timezone   : {schedule.get('timezone')}")
    print(f"Occurrences: {len(schedule.get('occurrences', []))}")
    print(f"Alarms     : {len(derived)} derived")
    for alarm in derived:
        print(f"  {alarm['date']} {alarm['time']}  [{alarm['kind']}]  {alarm['name']}")
    return 0


def main() -> None:
    parser = argparse.ArgumentParser(
        prog="massalarme",
        description="Massalarme \u2013 Xiaomi BLE scale \u2192 LAN alarm on Android",
    )
    parser.add_argument(
        "--show-secret",
        action="store_true",
        help="Display the shared secret as a QR code and exit.",
    )
    parser.add_argument(
        "--alarms",
        action="store_true",
        help="Show upcoming alarms and time until each, then exit.",
    )
    parser.add_argument(
        "--backfill",
        action="store_true",
        help="Collapse the raw weight log into weigh-ins and queue the full "
        "history for ontoplano. Safe to run repeatedly.",
    )
    parser.add_argument(
        "--sync-status",
        action="store_true",
        help="Show ontoplano sync state: pending count, last success, last error.",
    )
    parser.add_argument(
        "--set-token",
        metavar="TOKEN",
        help="Store the ontoplano API token in a 0600 file and exit. "
        "Use '-' to read it from stdin.",
    )
    parser.add_argument(
        "--sync-now",
        action="store_true",
        help="Drain the ontoplano queue once and exit.",
    )
    parser.add_argument(
        "--check-ontoplano",
        action="store_true",
        help="Verify the ontoplano token and show which scopes it carries.",
    )
    parser.add_argument(
        "--schedule-now",
        action="store_true",
        help="Pull the ontoplano planner once, apply derived alarms, and exit.",
    )
    args = parser.parse_args()

    _setup_logging()

    if args.show_secret:
        cfg = load_config()
        secret = cfg.get("shared_secret", "")
        if not secret:
            logger.error("No shared secret found in config.")
            sys.exit(1)
        _show_secret_qr(secret, cfg)
        sys.exit(0)

    if args.alarms:
        load_config()
        alarms_config, _ = load_alarms()
        print_alarms(alarms_config)
        sys.exit(0)

    if args.set_token is not None:
        token = sys.stdin.read().strip() if args.set_token == "-" else args.set_token
        if not token:
            logger.error("Empty token.")
            sys.exit(1)
        path = ontoplano.write_token(CONFIG_DIR, token)
        # Never log the token itself.
        print(f"Token stored at {path} (mode 0600).")
        print("Enable sync with 'ontoplano: {enabled: true, base_url: ...}' in config.yaml.")
        sys.exit(0)

    if args.backfill:
        cfg = load_config()
        store = get_store(cfg)
        inserted, duplicates = store.backfill_from_raw_log()
        print(f"Backfill complete: {inserted} new weigh-in(s), {duplicates} already known.")
        print(f"{store.pending_count()} weigh-in(s) queued for ontoplano.")
        sys.exit(0)

    if args.sync_status:
        cfg = load_config()
        _print_sync_status(cfg)
        sys.exit(0)

    if args.sync_now:
        cfg = load_config()
        sys.exit(asyncio.run(_run_sync_once(cfg)))

    if args.check_ontoplano:
        cfg = load_config()
        sys.exit(asyncio.run(_check_ontoplano(cfg)))

    if args.schedule_now:
        cfg = load_config()
        sys.exit(asyncio.run(_run_schedule_once(cfg)))

    try:
        asyncio.run(main_loop())
    except KeyboardInterrupt:
        logger.info("Alarm manager stopped by user.")


if __name__ == "__main__":
    main()
