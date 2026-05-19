"""
Massalarme – Scale Alarm Manager

PC daemon that monitors a Xiaomi BLE scale and controls an alarm
on an Android phone via LAN HTTP. Reads configuration from
XDG-compliant paths.
"""

import argparse
import asyncio
import logging
import logging.handlers
import os
import secrets
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
}


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
        _show_secret_qr(cfg["shared_secret"])

    return cfg


def _show_secret_qr(secret: str) -> None:
    """Print a QR code of the shared secret to the terminal."""
    try:
        import qrcode  # type: ignore[import-untyped]

        qr = qrcode.QRCode(border=1)
        qr.add_data(secret)
        qr.make(fit=True)
        qr.print_ascii(tty=sys.stdout.isatty())
        logger.info(
            "Scan the QR code above with the Massalarme app to set the shared secret."
        )
    except ImportError:
        logger.warning(
            "qrcode package not installed \u2013 cannot display QR code. "
            "Install with: pip install qrcode[pil]"
        )
        logger.info("Shared secret (copy manually): %s", secret)


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


def init_db() -> None:
    conn = sqlite3.connect(DB_FILE)
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS weights (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            timestamp TEXT NOT NULL,
            weight_kg REAL NOT NULL,
            impedance REAL NOT NULL,
            alarm_name TEXT,
            raw_value TEXT
        )
        """
    )
    conn.commit()
    conn.close()
    logger.info("Database ready: %s", DB_FILE)


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
    conn = sqlite3.connect(DB_FILE)
    conn.execute(
        "INSERT INTO weights (timestamp, weight_kg, impedance, alarm_name, raw_value) "
        "VALUES (?, ?, ?, ?, ?)",
        (datetime.now().isoformat(), weight_kg, impedance, alarm_name, raw_value),
    )
    conn.commit()
    conn.close()
    logger.info(
        "Logged: %.2fkg (raw=%s) | Alarm: %s",
        weight_kg,
        raw_value,
        alarm_name or "manual",
    )


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


async def main_loop() -> None:
    global _current_alarms, _current_cfg, _alarm_active, _phone_ip

    cfg = load_config()
    _current_cfg = cfg

    logger.info("Massalarme daemon started.")
    init_db()

    alarms_config, last_mtime = load_alarms()
    alarms_config = _prune_old_tombstones(alarms_config)
    _save_alarms(alarms_config)
    last_mtime = os.path.getmtime(ALARMS_FILE)
    _current_alarms = alarms_config

    await _start_pc_server(cfg)

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

            cached_next_alarm = None
            await asyncio.sleep(1)

        except Exception:
            logger.exception("Unexpected error in main loop")
            await asyncio.sleep(5)


# =====================================================================
# CLI
# =====================================================================


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
    args = parser.parse_args()

    _setup_logging()

    if args.show_secret:
        cfg = load_config()
        secret = cfg.get("shared_secret", "")
        if not secret:
            logger.error("No shared secret found in config.")
            sys.exit(1)
        _show_secret_qr(secret)
        sys.exit(0)

    if args.alarms:
        load_config()
        alarms_config, _ = load_alarms()
        print_alarms(alarms_config)
        sys.exit(0)

    try:
        asyncio.run(main_loop())
    except KeyboardInterrupt:
        logger.info("Alarm manager stopped by user.")


if __name__ == "__main__":
    main()
