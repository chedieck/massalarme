import asyncio
from bleak import BleakScanner

TARGET_NAME = "MIBFS"          # Xiaomi scale name
TARGET_UUID_PREFIX = "0000181b"           # Body Composition

def format_bytes(byte_str):
    return ''.join(f'{b:02x}' for b in byte_str)


def callback(device, adv):
    for uuid, data in adv.service_data.items():
        print(f'{uuid}> {format_bytes(data)}')
        if uuid.startswith(TARGET_UUID_PREFIX):
            raw = (data[-2] | (data[-1] << 8) )/ 200
            print('raw', raw)
            #print(f'>{raw:.2f}')


async def main():
    scanner = BleakScanner(detection_callback=callback)
    await scanner.start()
    while True:
        await asyncio.sleep(1)

asyncio.run(main())


eu = '14750' # 73.75
nina = '10520' # 52.60
tipo_15_14_13 = 3050
