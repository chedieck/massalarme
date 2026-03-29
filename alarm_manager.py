"""
MassAlarme – Scale Alarm Manager

PC daemon that monitors a Xiaomi BLE scale and controls an alarm
on an Android phone via LAN HTTP. Reads configuration from
XDG-compliant paths.
"""

import argparse
import asyncio
import logging
import os
import secrets
import sqlite3
import subprocess
import sys
from datetime import datetime, timedelta
from pathlib import Path
from typing import Dict, List, Optional, Tuple

import requests
import yaml
from bleak import BleakScanner

# ---------------------------------------------------------------------------
# XDG paths
# ---------------------------------------------------------------------------
_XDG_CONFIG = Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config"))
_XDG_DATA = Path(os.environ.get("XDG_DATA_HOME", Path.home() / ".local" / "share"))

CONFIG_DIR = _XDG_CONFIG / "massalarme"
DATA_DIR = _XDG_DATA / "massalarme"
CONFIG_FILE = CONFIG_DIR / "config.yaml"
ALARMS_FILE = CONFIG_DIR / "alarms.yaml"
DB_FILE = DATA_DIR / "weights.db"

# ---------------------------------------------------------------------------
# Logging
# ---------------------------------------------------------------------------
logger = logging.getLogger("massalarme")

# ---------------------------------------------------------------------------
# Default configuration values
# ---------------------------------------------------------------------------
_DEFAULT_CONFIG = {
    "phone_mac": "",
    "lan_network": "192.168.1.0/24",
    "port": 8080,
    "scale_name": "MIBFS",
    "scale_uuid_prefix": "0000181b",
    "syncing_weight_flag": 0x26,
    "min_weight_kg": 68,
    "shared_secret": "",
}


# =====================================================================
# Configuration helpers
# =====================================================================


def _ensure_dirs() -> None:
    """Create XDG directories if they don't exist."""
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    DATA_DIR.mkdir(parents=True, exist_ok=True)


def _migrate_legacy_files() -> None:
    """Move files from the old working-directory layout to XDG paths."""
    script_dir = Path(__file__).resolve().parent

    legacy_alarms = script_dir / "alarms.yaml"
    if legacy_alarms.exists() and not ALARMS_FILE.exists():
        logger.info("Migrating %s → %s", legacy_alarms, ALARMS_FILE)
        ALARMS_FILE.write_text(
            legacy_alarms.read_text(encoding="utf-8"), encoding="utf-8"
        )

    legacy_db = script_dir / "weights.db"
    if legacy_db.exists() and not DB_FILE.exists():
        import shutil

        logger.info("Migrating %s → %s", legacy_db, DB_FILE)
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
            "Scan the QR code above with the MassAlarme app to set the shared secret."
        )
    except ImportError:
        logger.warning(
            "qrcode package not installed – cannot display QR code. "
            "Install with: pip install qrcode[pil]"
        )
        logger.info("Shared secret (copy manually): %s", secret)


# =====================================================================
# Alarm schedule
# =====================================================================


def load_alarms() -> Tuple[Dict, float]:
    """Load alarms.yaml and return (config, mtime)."""
    if not ALARMS_FILE.exists():
        logger.warning("Alarms file not found: %s", ALARMS_FILE)
        return {}, 0.0
    try:
        with open(ALARMS_FILE, encoding="utf-8") as fh:
            config = yaml.safe_load(fh) or {}
        mtime = os.path.getmtime(ALARMS_FILE)
        return config, mtime
    except (yaml.YAMLError, OSError) as exc:
        logger.error("Failed to load alarms file: %s", exc)
        return {}, 0.0


def get_next_alarm_time(alarms_config: Dict) -> Optional[Tuple[datetime, str]]:
    """Return the (datetime, name) of the soonest upcoming alarm, or None."""
    now = datetime.now()
    candidates: List[Tuple[datetime, str]] = []

    # 'next' – one-shot alarms (fires once at next occurrence of HH:MM)
    for alarm in alarms_config.get("next") or []:
        try:
            t = datetime.strptime(alarm["time"], "%H:%M").time()
        except (KeyError, ValueError):
            continue
        dt = datetime.combine(now.date(), t)
        if dt <= now:
            dt += timedelta(days=1)
        candidates.append((dt, alarm.get("name", "Next Alarm")))

    # 'date' – date-specific alarms
    for alarm in alarms_config.get("date") or []:
        try:
            dt = datetime.strptime(f"{alarm['date']} {alarm['time']}", "%d-%m-%Y %H:%M")
        except (KeyError, ValueError):
            continue
        if dt > now:
            candidates.append((dt, alarm.get("name", alarm["date"])))

    # Weekly alarms
    weekdays = [
        "monday",
        "tuesday",
        "wednesday",
        "thursday",
        "friday",
        "saturday",
        "sunday",
    ]
    for i, day in enumerate(weekdays):
        for alarm in alarms_config.get(day) or []:
            try:
                t = datetime.strptime(alarm["time"], "%H:%M").time()
            except (KeyError, ValueError):
                continue
            days_ahead = (i - now.weekday()) % 7
            if days_ahead == 0 and datetime.combine(now.date(), t) <= now:
                days_ahead = 7
            target_date = now.date() + timedelta(days=days_ahead)
            dt = datetime.combine(target_date, t)
            candidates.append((dt, alarm.get("name", day.title())))

    return min(candidates, key=lambda x: x[0]) if candidates else None


def _iter_upcoming_alarm_datetimes(
    alarms_config: Dict, *, horizon_days: int = 14
) -> List[Tuple[datetime, str]]:
    """Return sorted, de-duplicated list of upcoming alarms within *horizon_days*."""
    now = datetime.now()
    start_date = now.date()
    candidates: List[Tuple[datetime, str]] = []

    # 'next'
    for alarm in alarms_config.get("next") or []:
        try:
            t = datetime.strptime(alarm["time"], "%H:%M").time()
            name = alarm.get("name", "Next Alarm")
        except (KeyError, ValueError):
            continue
        for d in range(horizon_days + 1):
            dt = datetime.combine(start_date + timedelta(days=d), t)
            if dt >= now:
                candidates.append((dt, name))
                break

    # 'date'
    for alarm in alarms_config.get("date") or []:
        try:
            dt = datetime.strptime(f"{alarm['date']} {alarm['time']}", "%d-%m-%Y %H:%M")
        except (KeyError, ValueError):
            continue
        if dt >= now:
            candidates.append((dt, alarm.get("name", alarm["date"])))

    # Weekly
    weekdays = [
        "monday",
        "tuesday",
        "wednesday",
        "thursday",
        "friday",
        "saturday",
        "sunday",
    ]
    for i, day in enumerate(weekdays):
        for alarm in alarms_config.get(day) or []:
            try:
                t = datetime.strptime(alarm["time"], "%H:%M").time()
                name = alarm.get("name", day.title())
            except (KeyError, ValueError):
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
            logger.error("'ip' command not found – cannot discover phone IP.")
        except subprocess.SubprocessError as exc:
            logger.warning("IP discovery error: %s", exc)

        if phone_ip is None:
            logger.info("Phone not found. Retrying in 10s...")
            await asyncio.sleep(10)

    return phone_ip


def _build_url(template: str, phone_ip: str, cfg: dict) -> str:
    """Build an HTTP URL with the shared secret as query parameter."""
    base = template.format(phone_ip=phone_ip, port=cfg["port"])
    return f"{base}?key={cfg['shared_secret']}"


def trigger_alarm(phone_ip: str, cfg: dict) -> None:
    url = _build_url("http://{phone_ip}:{port}/alarm", phone_ip, cfg)
    try:
        resp = requests.get(url, timeout=5)
        logger.info("ALARM TRIGGERED -> %d: %s", resp.status_code, resp.text.strip())
    except requests.RequestException as exc:
        logger.error("Alarm trigger failed: %s", exc)


def stop_alarm(phone_ip: str, cfg: dict) -> None:
    url = _build_url("http://{phone_ip}:{port}/stop", phone_ip, cfg)
    try:
        resp = requests.get(url, timeout=5)
        logger.info("Alarm stopped -> %d: %s", resp.status_code, resp.text.strip())
    except requests.RequestException as exc:
        logger.error("Stop alarm failed: %s", exc)


# =====================================================================
# Scale listener
# =====================================================================


async def wait_for_weight(cfg: dict, alarm_name: str) -> bool:
    """Listen for BLE advertisements until a stable weight is detected."""
    weight_received = asyncio.Event()
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
                weight_received.set()
            else:
                logger.debug("Weight not stable yet...")

    logger.info("Listening for scale advertisement...")
    scanner = BleakScanner(detection_callback=on_advertisement)
    await scanner.start()
    try:
        await asyncio.wait_for(weight_received.wait(), timeout=300)
        return True
    except asyncio.TimeoutError:
        logger.warning("Timeout: No stable weight in 5 minutes.")
        return False
    finally:
        await scanner.stop()


# =====================================================================
# Main loop
# =====================================================================


async def main_loop() -> None:
    cfg = load_config()

    logger.info("MassAlarme daemon started.")
    init_db()

    phone_ip = await discover_phone_ip(cfg)
    logger.info("Using phone IP: %s", phone_ip)

    last_mtime = 0.0
    alarms_config: Dict = {}
    cached_next_alarm: Optional[Tuple[datetime, str]] = None

    while True:
        try:
            # Check for config changes
            new_config, new_mtime = load_alarms()
            if new_mtime != last_mtime:
                logger.info("alarms.yaml updated – reloading configuration.")
                log_upcoming_alarms(new_config, limit=8)
                alarms_config = new_config
                last_mtime = new_mtime
                cached_next_alarm = None

            # Get next alarm
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

            # --- Prep time reached ---
            logger.info("Prep time for '%s' – running now.", alarm_name)
            phone_ip = await discover_phone_ip(cfg)

            now = datetime.now()
            time_to_alarm = (alarm_dt - now).total_seconds()
            if time_to_alarm > 0:
                logger.info(
                    "Waiting %.1fs until alarm time %s",
                    time_to_alarm,
                    alarm_dt.strftime("%H:%M:%S"),
                )
                await asyncio.sleep(time_to_alarm)
            else:
                logger.info("Alarm time already passed – triggering immediately.")

            logger.info(
                "TRIGGERING ALARM: %s @ %s",
                alarm_name,
                datetime.now().strftime("%H:%M:%S"),
            )
            trigger_alarm(phone_ip, cfg)

            # Wait for weight
            success = await wait_for_weight(cfg, alarm_name)
            if success:
                stop_alarm(phone_ip, cfg)
            else:
                logger.warning(
                    "No weight detected – alarm may continue (manual stop needed)."
                )

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
        description="MassAlarme – Xiaomi BLE scale → LAN alarm on Android",
    )
    parser.add_argument(
        "--show-secret",
        action="store_true",
        help="Display the shared secret as a QR code and exit.",
    )
    args = parser.parse_args()

    logging.basicConfig(
        level=logging.DEBUG,
        format="%(asctime)s %(levelname)-8s %(message)s",
        datefmt="%H:%M:%S",
    )

    if args.show_secret:
        cfg = load_config()
        secret = cfg.get("shared_secret", "")
        if not secret:
            logger.error("No shared secret found in config.")
            sys.exit(1)
        _show_secret_qr(secret)
        sys.exit(0)

    try:
        asyncio.run(main_loop())
    except KeyboardInterrupt:
        logger.info("Alarm manager stopped by user.")


if __name__ == "__main__":
    main()
