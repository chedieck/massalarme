# FILE: alarm_manager.py
"""
LAN Alarm Manager with Xiaomi Scale Integration & Weight Logging

Features:
- Reads alarms from alarms.yaml with support for weekly, date-specific, and 'next:' alarms
- Auto-discovers phone IP using nmap + known MAC
- Disconnects BT 1min before alarm → triggers scale advertisement
- HTTP alarm to phone → waits for BLE weight → stops alarm
- **NEW: Logs all weights to SQLite database with timestamp & alarm context**
"""

import asyncio
import subprocess
import json
import yaml
import requests
from datetime import datetime, timedelta
from pathlib import Path
from typing import List, Dict, Optional
import sqlite3

from bleak import BleakScanner

# ====================== CONFIGURATION ======================
PHONE_MAC = ""          # ← CHANGE TO YOUR PHONE'S MAC ADDRESS
LAN_NETWORK = "192.168.1.0/24"           # Your local network
PORT = 8080
YAML_FILE = Path("alarms.yaml")
DB_FILE = Path("weights.db")
TARGET_SCALE_NAME = "MIBFS"
TARGET_UUID_PREFIX = "0000181b"           # Body Composition service

# HTTP endpoints
ALARM_URL_TEMPLATE = "http://{phone_ip}:{port}/alarm"
STOP_URL_TEMPLATE  = "http://{phone_ip}:{port}/stop"
# =========================================================

def init_db():
    """Initialize SQLite database with weights table."""
    conn = sqlite3.connect(DB_FILE)
    cursor = conn.cursor()
    cursor.execute("""
        CREATE TABLE IF NOT EXISTS weights (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            timestamp TEXT NOT NULL,
            weight_kg REAL NOT NULL,
            alarm_name TEXT,
            raw_value INTEGER
        )
    """)
    conn.commit()
    conn.close()
    print(f"Database ready: {DB_FILE}")

def log_weight(weight_kg: float, raw_value: int, alarm_name: str = None):
    """Log weight reading to SQLite database."""
    conn = sqlite3.connect(DB_FILE)
    cursor = conn.cursor()
    cursor.execute(
        "INSERT INTO weights (timestamp, weight_kg, alarm_name, raw_value) VALUES (?, ?, ?, ?)",
        (datetime.now().isoformat(), weight_kg, alarm_name, raw_value)
    )
    conn.commit()
    conn.close()
    print(f"✅ Logged: {weight_kg:.2f}kg (raw={raw_value}) | Alarm: {alarm_name or 'manual'}")

def discover_phone_ip() -> Optional[str]:
    """Scan LAN with nmap and find device with matching MAC address."""
    try:
        print(f"🔍 Scanning {LAN_NETWORK} for phone (MAC: {PHONE_MAC})...")
        result = subprocess.check_output(["nmap", "-sn", LAN_NETWORK], text=True)
        lines = result.splitlines()
        for i, line in enumerate(lines):
            if PHONE_MAC.lower() in line.lower():
                if i - 2 < len(lines):
                    ip_line = lines[i + -2]
                    if "Nmap scan report for" in ip_line:
                        ip = ip_line.split()[-1]
                        if ip.endswith(")"):
                            ip = ip[:-1].split("(")[-1]
                        print(f"📱 Phone found: {ip}")
                        return ip
    except FileNotFoundError:
        print("❌ nmap not installed")
    except Exception as e:
        print(f"❌ IP discovery error: {e}")
    return None

def disconnect_all_bluetooth():
    """Disconnect all connected Bluetooth devices."""
    try:
        print("🔌 Disconnecting all Bluetooth devices...")
        devices_output = subprocess.check_output(["bluetoothctl", "devices"], text=True)
        disconnected = 0
        for line in devices_output.splitlines():
            if line.startswith("Device "):
                mac = line.split()[1]
                info = subprocess.check_output(["bluetoothctl", "info", mac], text=True)
                if "Connected: yes" in info:
                    print(f"  ↳ {mac}")
                    subprocess.call(["bluetoothctl", "disconnect", mac])
                    disconnected += 1
        print(f"✅ {disconnected} devices disconnected")
    except Exception as e:
        print(f"❌ Bluetooth disconnect error: {e}")

def trigger_alarm(phone_ip: str):
    url = ALARM_URL_TEMPLATE.format(phone_ip=phone_ip, port=PORT)
    try:
        resp = requests.get(url, timeout=5)
        print(f"🚨 Alarm → {resp.status_code}: {resp.text.strip()}")
    except Exception as e:
        print(f"❌ Alarm failed: {e}")

def stop_alarm(phone_ip: str):
    url = STOP_URL_TEMPLATE.format(phone_ip=phone_ip, port=PORT)
    try:
        resp = requests.get(url, timeout=5)
        print(f"🛑 Alarm stopped → {resp.status_code}: {resp.text.strip()}")
    except Exception as e:
        print(f"❌ Stop failed: {e}")

def load_alarms() -> Dict:
    if not YAML_FILE.exists():
        raise FileNotFoundError(f"{YAML_FILE} not found! Create alarms.yaml")
    with open(YAML_FILE, "r", encoding="utf-8") as f:
        return yaml.safe_load(f) or {}

def get_next_alarm_time(alarms_config: Dict) -> Optional[tuple[datetime, str]]:
    """Find soonest upcoming alarm."""
    now = datetime.now()
    candidates = []

    # 1. 'next:' (highest priority)
    if "next" in alarms_config:
        for alarm in alarms_config["next"]:
            dt = datetime.combine(now.date(), datetime.strptime(alarm["time"], "%H:%M").time())
            if dt <= now: dt += timedelta(days=1)
            candidates.append((dt, alarm.get("name", "Next")))

    # 2. Specific dates
    if "date" in alarms_config:
        for alarm in alarms_config["date"]:
            try:
                dt = datetime.strptime(f"{alarm['date']} {alarm['time']}", "%d-%m-%Y %H:%M")
                if dt > now:
                    candidates.append((dt, alarm.get("name", alarm['date'])))
            except (KeyError, ValueError):
                continue

    # 3. Weekly
    weekdays = ["monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"]
    for i, day in enumerate(weekdays):
        if day in alarms_config:
            for alarm in alarms_config[day]:
                days_ahead = (i - now.weekday()) % 7
                if days_ahead == 0:
                    alarm_dt = datetime.combine(now.date(), datetime.strptime(alarm["time"], "%H:%M").time())
                    if alarm_dt <= now: days_ahead = 7
                alarm_date = now.date() + timedelta(days=days_ahead)
                dt = datetime.combine(alarm_date, datetime.strptime(alarm["time"], "%H:%M").time())
                candidates.append((dt, alarm.get("name", f"{day.title()}")))

    return min(candidates, key=lambda x: x[0]) if candidates else None

async def wait_for_weight(alarm_name: str) -> bool:
    """Wait for scale BLE advertisement and log weight."""
    weight_received = asyncio.Event()
    def callback(device, adv_data):
        if device.name != TARGET_SCALE_NAME: return
        for uuid, data in adv_data.service_data.items():
            if uuid.lower().startswith(TARGET_UUID_PREFIX):
                raw = data[-2] | (data[-1] << 8)
                weight_kg = raw / 200.0
                print(f"⚖️  {weight_kg:.2f}kg (raw={raw}) → Logged!")
                log_weight(weight_kg, raw, alarm_name)
                weight_received.set()

    print("👂 Listening for scale...")
    scanner = BleakScanner(detection_callback=callback)
    await scanner.start()
    try:
        await asyncio.wait_for(weight_received.wait(), timeout=300)
        return True
    except asyncio.TimeoutError:
        print("⏰ Timeout: No weight in 5min")
        return False
    finally:
        await scanner.stop()

async def main_loop():
    print("🎯 LAN Alarm Manager + Weight Logger")
    init_db()
    
    phone_ip = discover_phone_ip()
    if not phone_ip:
        phone_ip = input("📱 Enter phone IP: ").strip()
    
    while True:
        alarms_config = load_alarms()
        next_alarm = get_next_alarm_time(alarms_config)
        
        if not next_alarm:
            print("😴 No alarms. Sleeping 1h...")
            await asyncio.sleep(3600)
            continue
        
        alarm_dt, alarm_name = next_alarm
        prep_time = alarm_dt - timedelta(minutes=1)
        now = datetime.now()
        
        if prep_time > now:
            wait_sec = (prep_time - now).total_seconds()
            print(f"\n⏰ Next: {alarm_name} at {alarm_dt.strftime('%H:%M')} ({wait_sec/60:.0f}min)")
            await asyncio.sleep(wait_sec)
        
        # 1min before: Disconnect BT
        disconnect_all_bluetooth()
        await asyncio.sleep(60)
        
        # Trigger alarm
        print(f"🚨 {alarm_name}")
        trigger_alarm(phone_ip)
        
        # Wait & log weight
        success = await wait_for_weight(alarm_name)
        if success:
            stop_alarm(phone_ip)
        else:
            print("⚠️  Manual intervention needed")
        
        await asyncio.sleep(10)

if __name__ == "__main__":
    try:
        asyncio.run(main_loop())
    except KeyboardInterrupt:
        print("\n👋 Stopped")
