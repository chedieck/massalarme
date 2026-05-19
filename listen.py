import asyncio
import sys
from datetime import datetime
from pathlib import Path

import yaml
from bleak import BleakScanner


def load_config() -> dict:
    config_dir = Path(
        __import__("os").environ.get("XDG_CONFIG_HOME", Path.home() / ".config")
    ) / "massalarme"
    with open(config_dir / "config.yaml") as f:
        return yaml.safe_load(f)


def main() -> None:
    cfg = load_config()
    scale_name = cfg["scale_name"]
    uuid_prefix = cfg["scale_uuid_prefix"]
    stop_flag = cfg.get("syncing_weight_flag")

    def on_advertisement(device, adv_data):
        if device.name != scale_name:
            return
        for uuid, data in adv_data.service_data.items():
            if not uuid.lower().startswith(uuid_prefix):
                continue
            flag = data[1]
            raw_weight = data[-2] | (data[-1] << 8)
            weight_kg = raw_weight / 200.0
            ts = datetime.now().strftime("%H:%M:%S")
            marker = " <-- stop flag" if flag == stop_flag else ""
            print(f"{ts} flag 0x{flag:02x}, {weight_kg:.1f}kg{marker}")

    async def run():
        scanner = BleakScanner(detection_callback=on_advertisement)
        print(
            f"Listening for '{scale_name}' (uuid prefix {uuid_prefix})... "
            f"stop flag = 0x{stop_flag:02x}"
        )
        await scanner.start()
        try:
            while True:
                await asyncio.sleep(1)
        except asyncio.CancelledError:
            pass
        finally:
            await scanner.stop()

    try:
        asyncio.run(run())
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
