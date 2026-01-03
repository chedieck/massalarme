import asyncio
from bleak import BleakScanner

TARGET_NAME = "MIBFS"          # Xiaomi scale name
TARGET_UUID_PREFIX = "0000181b"           # Body Composition

def callback(device, adv):
    for k in adv.service_data.keys():
        if k.startswith(TARGET_UUID_PREFIX):
            print('oia:')
            data = adv.service_data[k]
            print(device.address, data, type(data))

async def main():
    scanner = BleakScanner(detection_callback=callback)
    await scanner.start()
    while True:
        await asyncio.sleep(1)

asyncio.run(main())

