# listen_all.py
# Comprehensive BLE advertisement listener using Bleak
# Shows everything in a clean, human-readable format

import asyncio
from collections import defaultdict
from bleak import BleakScanner
import time

# Known company IDs (partial list - common ones)
KNOWN_COMPANIES = {
    0x004C: "Apple, Inc.",
    0x0059: "Nordic Semiconductor",
    0x00E0: "Google",
    0x0006: "Microsoft",
    0x00F3: "Xiaomi Inc.",
    0x0157: "Tile, Inc.",
    0x01B3: "Fitbit",
    # Add more if you want: https://www.bluetooth.com/specifications/assigned-numbers/company-identifiers/
}

def format_bytes(data: bytes) -> str:
    """Convert bytes to clean lowercase hex string with zero-padding"""
    return ''.join(f'{b:02x}' for b in data)

def decode_manufacturer_data(manuf_data: dict) -> str:
    lines = []
    for company_id, data in manuf_data.items():
        company_name = KNOWN_COMPANIES.get(company_id, f"Unknown (0x{company_id:04x})")
        hex_data = format_bytes(data)
        lines.append(f"    └ Manufacturer [{company_name}]: {hex_data}")
    return '\n'.join(lines) or "    └ (none)"

def decode_service_data(service_data: dict) -> str:
    lines = []
    for uuid, data in service_data.items():
        short_uuid = uuid[-8:].upper() if len(uuid) > 8 else uuid.upper()
        hex_data = format_bytes(data)
        lines.append(f"    └ {short_uuid}: {hex_data}")
    return '\n'.join(lines) or "    └ (none)"

def decode_services(services: list) -> str:
    if not services:
        return "    └ (none)"
    return '\n'.join(f"    └ {s[-8:].upper() if len(s) > 8 else s.upper()}" for s in services)

# Keep track of last seen time and data to avoid reprinting unchanged ads too often
seen_devices = {}
PRINT_INTERVAL = 2.0  # Only reprint a device if data changed or >2s passed

def callback(device, advertisement_data):
    now = time.time()
    addr = device.address
    key = (addr, advertisement_data.rssi, str(advertisement_data))

    # Only print if new, changed, or not seen recently
    last_time, last_key = seen_devices.get(addr, (0, None))
    if key != last_key or (now - last_time > PRINT_INTERVAL):
        seen_devices[addr] = (now, key)

        name = advertisement_data.local_name or "(no name)"
        rssi = advertisement_data.rssi
        tx_power = advertisement_data.tx_power

        print("\n" + "="*80)
        print(f"[{time.strftime('%H:%M:%S')}] {device.address} | RSSI: {rssi} dBm | Name: {name}")
        if tx_power is not None:
            print(f"                           Tx Power: {tx_power} dBm")

        print(f"  Platform Data: {dict(advertisement_data.platform_data)}")

        print("  Service UUIDs:")
        print(decode_services(advertisement_data.service_uuids))

        print("  Service Data:")
        print(decode_service_data(advertisement_data.service_data))

        print("  Manufacturer Data:")
        print(decode_manufacturer_data(advertisement_data.manufacturer_data))

async def main():
    print("Starting full BLE advertisement listener... Press Ctrl+C to stop.\n")
    scanner = BleakScanner(detection_callback=callback)
    await scanner.start()
    try:
        while True:
            await asyncio.sleep(1)
    except KeyboardInterrupt:
        print("\n\nStopping scanner...")
    finally:
        await scanner.stop()

if __name__ == "__main__":
    asyncio.run(main())
