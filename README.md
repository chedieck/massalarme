# Massalarmee

Force yourself out of bed by requiring a weigh-in on a Xiaomi BLE scale to silence
your alarm. A Python daemon on your PC watches for the scale's Bluetooth
advertisement and talks to a foreground service on your Android phone over LAN HTTP.

## How it works

```
┌─────────┐  BLE advert   ┌──────────────┐  HTTP /alarm  ┌──────────────┐
│  Scale   │──────────────▶│  PC daemon   │──────────────▶│  Android app │
│  (MIBFS) │               │  (Python)    │◀──────────────│  (Kotlin)    │
└─────────┘               └──────────────┘  HTTP /stop   └──────────────┘
```

1. Daemon loads the alarm schedule from `~/.config/massalarme/alarms.json`.
2. One minute before an alarm, it starts scanning for the phone on the LAN.
3. At alarm time it sends `GET /alarm?key=<secret>` to the phone.
4. Phone plays `trombetas.mp3` at max volume, locks volume, opens a full-screen
   dismiss activity over the lock screen.
5. Alarm can **only** be stopped by:
   - Stepping on the scale (daemon detects stable weight → sends `/stop`), or
   - Typing the passphrase exactly:
     `The Industrial Revolution and its consequences have been a disaster for the human race.`
6. Wrong input clears the field, shakes it, plays `bell.mp3`, shows **TRY AGAIN!**
   in red.

## Requirements

- **PC**: Linux with Bluetooth, Python 3.10+, [uv](https://docs.astral.sh/uv/)
- **Phone**: Android 8+ (API 26), on the same LAN as the PC
- **Scale**: Xiaomi Mi Body Composition Scale (MIBFS) or compatible BLE scale

## Quick start

```bash
# Clone and install (creates venv via uv, sets up systemd service)
make install

# Show the shared secret QR code — scan it from the phone app
make secret

# View live logs
make logs
```

Then build and install the Android app on your phone (see [Android setup](#android-setup)).

## PC setup

### Configuration

After `make install`, config files live in XDG paths:

| File | Path | Purpose |
|------|------|---------|
| `config.yaml` | `~/.config/massalarme/config.yaml` | Phone MAC, network, port, scale params, shared secret |
| `alarms.json` | `~/.config/massalarme/alarms.json` | Alarm schedule |
| `weights.db` | `~/.local/share/massalarme/weights.db` | Weight log (SQLite) |

Edit `config.yaml` with your phone's Bluetooth MAC address and LAN subnet.

### Alarm schedule format

```json
{
  "monday": [
    {"name": "Wake up", "time": "07:30"}
  ],
  "saturday": [
    {"name": "Lazy day", "time": "09:00"}
  ],
  "date": [
    {"name": "Dentist", "time": "08:00", "date": "15-04-2026"}
  ],
  "next": [
    {"name": "One-shot", "time": "06:00"}
  ]
}
```

Times can be `HH:MM` or `HH:MM:SS`.

### Service management

```bash
make start          # Start the daemon
make stop           # Stop the daemon
make restart        # Restart
make status         # systemctl status
make logs           # journalctl -f
make enable         # Enable on login
make disable        # Disable on login
```

### Makefile targets

| Target | Description |
|--------|-------------|
| `make install` | Full setup: uv venv, XDG dirs, configs, systemd service |
| `make run` | Run daemon directly (foreground, for debugging) |
| `make secret` | Show shared secret QR code in terminal |
| `make start/stop/restart/status` | systemd service control |
| `make logs` | Follow journal logs |
| `make enable/disable` | Autostart on login |
| `make apk` | Build Android debug APK |
| `make apk-install` | Build and install APK via adb |
| `make clean` | Remove venv |
| `make uninstall` | Remove service, XDG files, venv |

## Android setup

```bash
# Build the debug APK
make apk

# Or build and install directly (phone must be connected via adb)
make apk-install
```

On the phone:

1. Open the **Massalarmee** app.
2. Tap **Scan QR Secret** and scan the QR code from `make secret`.
3. Enable **Start on boot** so the service survives reboots.
4. Grant notification and DND override permissions when prompted.

The service runs in the background — you don't need to keep the app open.

### Assets

The app ships with `trombetas.mp3` as the alarm sound. To add the wrong-input
bell sound, place a `bell.mp3` file in:

```
lanalarm/app/src/main/assets/bell.mp3
```

## Project structure

```
massalarme/
├── alarm_manager.py        # PC daemon
├── Makefile                # Common tasks
├── requirements.txt        # Python dependencies
├── massalarme.service      # systemd unit template
├── install.sh              # Setup script
├── config.yaml.example     # Config template
├── alarms.json.example     # Schedule template
├── dep/                    # Dev reference (BLE protocol notes)
└── lanalarm/               # Android app
    └── app/src/main/
        ├── assets/
        │   ├── trombetas.mp3
        │   └── bell.mp3        # ← you provide this
        ├── kotlin/org/example/lanalarm/
        │   ├── AlarmService.kt          # HTTP server + MediaPlayer
        │   ├── AlarmDismissActivity.kt  # Full-screen passphrase input
        │   ├── BootReceiver.kt          # Auto-start on boot
        │   ├── MainActivity.kt          # Settings UI
        │   └── App.kt                   # Notification channels
        ├── res/layout/
        │   ├── activity_main.xml
        │   └── activity_alarm_dismiss.xml
        └── AndroidManifest.xml
```

## Security

The PC and phone share a 64-character hex secret generated on first run. All HTTP
requests include `?key=<secret>`. The phone rejects requests without a valid key
(403). The secret is exchanged via QR code — it never leaves the LAN.
