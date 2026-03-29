"""
LAN Alarm Manager with Xiaomi Scale Integration & Weight Logging
Improvements:
- Loops every ~1s max for frequent config checks
- Caches next alarm to avoid constant recalculations
- TRUE live reloading (instant on edit)
- Logs with [HH:MM:SS] timestamps
- Precise alarm at :00 seconds
"""

import asyncio
import subprocess
import requests
import yaml
from datetime import datetime, timedelta
from pathlib import Path
from typing import Optional, Tuple, Dict
from pprint import pprint
import sqlite3
from bleak import BleakScanner
import os

# ====================== CONFIGURATION ======================
PHONE_MAC = ""      # ← CHANGE TO YOUR PHONE'S MAC
LAN_NETWORK = "192.168.1.0/24"
PORT = 8080
YAML_FILE = Path("alarms.yaml")
DB_FILE = Path("weights.db")
TARGET_SCALE_NAME = "MIBFS"
TARGET_UUID_PREFIX = "0000181b"
SYNCING_WEIGHT_FLAG = 38            # 0x26
ALARM_URL_TEMPLATE = "http://{phone_ip}:{port}/alarm"
STOP_URL_TEMPLATE = "http://{phone_ip}:{port}/stop"
MIN_WEIGHT = 68
# =========================================================

def log(msg: str):
    """Print with timestamp"""
    now = datetime.now().strftime("%H:%M:%S")
    print(f"[{now}] {msg}")

def format_bytes(data: bytes) -> str:
    return ''.join(f'{b:02x}' for b in data)

def get_relevant_data(data: bytes) -> str:
    hex_str = format_bytes(data)
    first_flag = hex_str[2:4]
    value_str = hex_str[-4:]
    value = int(value_str[-2:] + value_str[:2], 16)
    second_flag = hex_str[-8:-4]
    return f'{first_flag}:{second_flag}:{value}'

def init_db():
    conn = sqlite3.connect(DB_FILE)
    cursor = conn.cursor()
    cursor.execute("""
        CREATE TABLE IF NOT EXISTS weights (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            timestamp TEXT NOT NULL,
            weight_kg REAL NOT NULL,
            impedance REAL NOT NULL,
            alarm_name TEXT,
            raw_value TEXT
        )
    """)
    conn.commit()
    conn.close()
    log(f"Database ready: {DB_FILE}")

def log_weight(weight_kg: float, impedance: float, raw_value: str, alarm_name: str = None):
    conn = sqlite3.connect(DB_FILE)
    cursor = conn.cursor()
    ts = datetime.now().isoformat()
    cursor.execute(
        "INSERT INTO weights (timestamp, weight_kg, impedance, alarm_name, raw_value) VALUES (?, ?, ?, ?, ?)",
        (ts, weight_kg, impedance, alarm_name, raw_value)
    )
    conn.commit()
    conn.close()
    log(f"Logged: {weight_kg:.2f}kg (raw={raw_value}) | Alarm: {alarm_name or 'manual'}")

async def discover_phone_ip() -> str:
    phone_ip = None

    while not phone_ip:
        try:
            log(f"Scanning ARP table for phone (MAC: {PHONE_MAC})...")

            # refresh neighbors (ping broadcast range quickly)
            subprocess.run(
                ["ping", "-c", "1", "-b", LAN_NETWORK.split("/")[0]],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )

            result = subprocess.check_output(["ip", "neigh"], text=True)

            for line in result.splitlines():
                if PHONE_MAC.lower() in line.lower():
                    # format: 192.168.1.10 dev wlan0 lladdr aa:bb:cc:dd:ee:ff REACHABLE
                    ip = line.split()[0]
                    log(f"Phone found at: {ip}")
                    phone_ip = ip
                    break

        except FileNotFoundError:
            log("ip command not found")
        except Exception as e:
            log(f"IP discovery error: {e}")

        if phone_ip is None:
            log("Phone not found. Retrying in 10s...")
            await asyncio.sleep(10)

    return phone_ip

def disconnect_all_bluetooth():
    try:
        log("Disconnecting all Bluetooth devices...")
        output = subprocess.check_output(["bluetoothctl", "devices"], text=True)
        disconnected = 0
        for line in output.splitlines():
            if line.startswith("Device "):
                mac = line.split()[1]
                info = subprocess.check_output(["bluetoothctl", "info", mac], text=True)
                if "Connected: yes" in info:
                    log(f"Disconnecting {mac}")
                    subprocess.call(["bluetoothctl", "disconnect", mac], stdout=subprocess.DEVNULL)
                    disconnected += 1
        log(f"{disconnected} devices disconnected")
    except Exception as e:
        log(f"Bluetooth disconnect error: {e}")

def trigger_alarm(phone_ip: str):
    url = ALARM_URL_TEMPLATE.format(phone_ip=phone_ip, port=PORT)
    try:
        resp = requests.get(url, timeout=5)
        log(f"ALARM TRIGGERED → {resp.status_code}: {resp.text.strip()}")
    except Exception as e:
        log(f"Alarm trigger failed: {e}")

def stop_alarm(phone_ip: str):
    url = STOP_URL_TEMPLATE.format(phone_ip=phone_ip, port=PORT)
    try:
        resp = requests.get(url, timeout=5)
        log(f"Alarm stopped → {resp.status_code}: {resp.text.strip()}")
    except Exception as e:
        log(f"Stop alarm failed: {e}")

def load_alarms() -> Tuple[Dict, float]:
    """Load alarms.yaml and return config + last modification time"""
    if not YAML_FILE.exists():
        log(f"{YAML_FILE} not found! Skipping load.")
        return {}, 0.0
    try:
        with open(YAML_FILE, "r", encoding="utf-8") as f:
            config = yaml.safe_load(f) or {}
        mtime = os.path.getmtime(YAML_FILE)
        return config, mtime
    except Exception as e:
        log(f"Failed to load alarms.yaml: {e}")
        return {}, 0.0

def get_next_alarm_time(alarms_config: Dict) -> Optional[Tuple[datetime, str]]:
    now = datetime.now()
    candidates = []
    # 'next' alarms
    if "next" in alarms_config:
        for alarm in alarms_config["next"]:
            try:
                t = datetime.strptime(alarm["time"], "%H:%M").time()
                dt = datetime.combine(now.date(), t)
                if dt <= now:
                    dt += timedelta(days=1)
                name = alarm.get("name", "Next Alarm")
                candidates.append((dt, name))
            except:
                continue
    # date-specific
    if "date" in alarms_config:
        for alarm in alarms_config["date"]:
            try:
                dt = datetime.strptime(f"{alarm['date']} {alarm['time']}", "%d-%m-%Y %H:%M")
                if dt > now:
                    name = alarm.get("name", alarm['date'])
                    candidates.append((dt, name))
            except:
                continue
    # weekly
    weekdays = ["monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"]
    for i, day in enumerate(weekdays):
        if day in alarms_config:
            for alarm in alarms_config[day]:
                try:
                    t = datetime.strptime(alarm["time"], "%H:%M").time()
                    days_ahead = (i - now.weekday()) % 7
                    if days_ahead == 0 and datetime.combine(now.date(), t) <= now:
                        days_ahead = 7
                    target_date = now.date() + timedelta(days=days_ahead)
                    dt = datetime.combine(target_date, t)
                    name = alarm.get("name", day.title())
                    candidates.append((dt, name))
                except:
                    continue
    return min(candidates, key=lambda x: x[0]) if candidates else None

async def wait_for_weight(alarm_name: str) -> bool:
    weight_received = asyncio.Event()

    def callback(device, adv_data):
        if device.name != TARGET_SCALE_NAME:
            return
        for uuid, data in adv_data.service_data.items():
            if uuid.lower().startswith(TARGET_UUID_PREFIX):
                first_flag = data[1]
                raw_weight = data[-2] | (data[-1] << 8)
                impedance = data[-4] | (data[-3] << 8)
                weight_kg = raw_weight / 200.0
                raw_hex = format_bytes(data)
                log(get_relevant_data(data))
                if first_flag == SYNCING_WEIGHT_FLAG and weight_kg > MIN_WEIGHT:
                    log(f"STABLE WEIGHT: {weight_kg:.2f}kg & {impedance}Ω → LOGGED!")
                    log_weight(weight_kg, impedance, raw_hex, alarm_name)
                    weight_received.set()
                else:
                    log("Weight not stable yet...")

    log("Listening for scale advertisement...")
    scanner = BleakScanner(detection_callback=callback)
    await scanner.start()
    try:
        await asyncio.wait_for(weight_received.wait(), timeout=300)
        return True
    except asyncio.TimeoutError:
        log("Timeout: No stable weight in 5 minutes")
        return False
    finally:
        await scanner.stop()

async def main_loop():
    log("LAN Alarm Manager + Weight Logger STARTED (Fast Loop + Caching)")
    init_db()

    phone_ip = await discover_phone_ip()
    log(f"Using phone IP: {phone_ip}")

    last_mtime = 0.0
    alarms_config = {}
    cached_next_alarm: Optional[Tuple[datetime, str]] = None

    while True:
        try:
            # === CHECK FOR CONFIG CHANGES (every loop, fast) ===
            new_config, new_mtime = load_alarms()
            if new_mtime != last_mtime:
                print("alarms.yaml UPDATED → Reloading configuration!")
                print_next_alarms(new_config, limit=8)
                pprint(new_config, sort_dicts=False)
                alarms_config = new_config
                last_mtime = new_mtime
                cached_next_alarm = None  # Invalidate cache on change

            # === GET NEXT ALARM (only if cache invalid) ===
            if cached_next_alarm is None:
                cached_next_alarm = get_next_alarm_time(alarms_config)

            if cached_next_alarm is None:
                await asyncio.sleep(1)  # No alarms: check again in 1s
                continue

            alarm_dt, alarm_name = cached_next_alarm
            prep_time = alarm_dt - timedelta(minutes=1)
            prep_time = prep_time.replace(second=0, microsecond=0)
            now = datetime.now()

            time_to_prep = (prep_time - now).total_seconds()

            if time_to_prep > 0:
                # Sleep up to 1s, then loop/check again
                await asyncio.sleep(min(1, time_to_prep))
                continue

            # === PREP TIME REACHED: HANDLE ALARM ===
            log(f"Prep time for '{alarm_name}' → Running now")
            disconnect_all_bluetooth()
            phone_ip = await discover_phone_ip()
            # Calculate exact time until the actual alarm (alarm_dt)
            now = datetime.now()
            time_to_alarm = (alarm_dt - now).total_seconds()

            if time_to_alarm > 0:
                log(f"Waiting {time_to_alarm:.1f} seconds until alarm time {alarm_dt.strftime('%H:%M:%S')}")
                await asyncio.sleep(time_to_alarm)
            else:
                log("Alarm time already passed or very close – triggering immediately")

            # Trigger exactly on the minute
            log(f"TRIGGERING ALARM: {alarm_name} @ {datetime.now().strftime('%H:%M:%S')}")
            trigger_alarm(phone_ip)

            # Wait for weight
            success = await wait_for_weight(alarm_name)
            if success:
                stop_alarm(phone_ip)
            else:
                log("No weight detected → Alarm may continue (manual stop needed)")

            # After handling, invalidate cache to recalc next alarm
            cached_next_alarm = None
            await asyncio.sleep(1)  # Short pause before next cycle
        except Exception as inst:
            print(type(inst))    # the exception type
            print(inst.args)     # arguments stored in .args
            print(inst)


def _iter_upcoming_alarm_datetimes(alarms_config: Dict, *, horizon_days: int = 14):
    now = datetime.now()
    start_date = now.date()
    end_date = start_date + timedelta(days=horizon_days)

    candidates: list[tuple[datetime, str]] = []

    # "next": treated as daily repeating at HH:MM
    for alarm in (alarms_config.get("next") or []):
        try:
            t = datetime.strptime(alarm["time"], "%H:%M").time()
            name = alarm.get("name", "Next Alarm")
        except Exception:
            continue

        for d in range(horizon_days + 1):
            dt = datetime.combine(start_date + timedelta(days=d), t).replace(second=0, microsecond=0)
            if dt >= now:
                candidates.append((dt, name))
                break

    # date-specific: dd-mm-YYYY HH:MM
    for alarm in (alarms_config.get("date") or []):
        try:
            dt = datetime.strptime(f"{alarm['date']} {alarm['time']}", "%d-%m-%Y %H:%M")
            dt = dt.replace(second=0, microsecond=0)
            if dt >= now:
                candidates.append((dt, alarm.get("name", alarm["date"])))
        except Exception:
            continue

    # weekly: generate occurrences within horizon
    weekdays = ["monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"]
    for i, day in enumerate(weekdays):
        for alarm in (alarms_config.get(day) or []):
            try:
                t = datetime.strptime(alarm["time"], "%H:%M").time()
                name = alarm.get("name", day.title())
            except Exception:
                continue

            for d in range(horizon_days + 1):
                cur = start_date + timedelta(days=d)
                if cur.weekday() != i:
                    continue
                dt = datetime.combine(cur, t).replace(second=0, microsecond=0)
                if dt >= now:
                    candidates.append((dt, name))
                    break

    candidates.sort(key=lambda x: x[0])

    # de-dupe exact duplicates
    seen = set()
    out = []
    for dt, name in candidates:
        key = (dt, name)
        if key in seen:
            continue
        seen.add(key)
        out.append((dt, name))
    return out


def print_next_alarms(alarms_config: Dict, *, limit: int = 10, horizon_days: int = 14):
    now = datetime.now()
    items = _iter_upcoming_alarm_datetimes(alarms_config, horizon_days=horizon_days)[:limit]

    if not items:
        log("No upcoming alarms in horizon")
        return

    log(f"Upcoming alarms (next {len(items)}):")
    for dt, name in items:
        dt = dt.replace(second=0, microsecond=0)

        in_seconds = int((dt - now).total_seconds())
        if in_seconds < 0:
            continue

        days, rem = divmod(in_seconds, 86400)
        hours, rem = divmod(rem, 3600)
        mins, secs = divmod(rem, 60)

        # when your code will actually trigger:
        prep_dt = (dt - timedelta(minutes=1)).replace(second=0, microsecond=0)
        will_trigger_at = dt  # you sleep until alarm_dt, then trigger

        # safety: if we are already past prep time, you’ll run prep immediately
        prep_state = "OK" if prep_dt > now else "PREP NOW"

        if days:
            eta = f"{days}d {hours:02}h {mins:02}m {secs:02}s"
        else:
            eta = f"{hours:02}h {mins:02}m {secs:02}s"

        print(
            f"  - {dt.strftime('%Y-%m-%d %H:%M')} | {name}"
            f" | in {eta}"
            f" | prep {prep_dt.strftime('%H:%M')} ({prep_state})"
            f" | rings {will_trigger_at.strftime('%H:%M:%S')}"
        )


if __name__ == "__main__":
    try:
        asyncio.run(main_loop())
    except KeyboardInterrupt:
        log("Alarm manager stopped by user")
