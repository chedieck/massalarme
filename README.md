# Massalarme

Force yourself out of bed by requiring a weigh-in on a Xiaomi BLE scale to silence
your alarm. The Android app senses and rings on its own; a Python daemon on your
PC keeps the schedule in sync and publishes your weight to
[ontoplano](https://github.com/) alongside the rest of your week.

## How it works

```
┌─────────┐  BLE advert   ┌──────────────┐  POST /readings  ┌────────────┐  streams API  ┌───────────┐
│  Scale   │──────────────▶│  Android app │─────────────────▶│ PC daemon  │──────────────▶│ ontoplano │
│  (MIBFS) │               │  (Kotlin)    │◀─────────────────│  (Python)  │◀──────────────│           │
└─────────┘               └──────────────┘   alarm schedule  └────────────┘  planner      └───────────┘
```

The phone owns the alarm. It holds the schedule, books exact alarms with
`AlarmManager`, listens to the scale itself, and stores every reading locally.
**The alarm rings whether or not the PC is on.**

The PC is the home-network peer: it merges schedule edits, receives weigh-ins,
and republishes them to ontoplano. It can also read your ontoplano planner and
turn tasks into alarms — put "wake up" at 07:00 Tuesday and that is when it rings.

### Hard and soft alarms

| | Hard | Soft |
|---|---|---|
| Silenced by | Standing on the scale | A dismiss button |
| Ringtone | `trombetas.mp3` | `soft.mp3` (falls back to the siren) |
| Escape hatch | The passphrase | — |

A hard alarm only demands the scale **when the phone is on your home wifi** —
that is where the scale is. Away from home it degrades to a soft alarm and tells
you why, rather than trapping you with a siren in a hotel room. The same happens
if the Bluetooth permission is missing.

The passphrase remains the escape hatch for hard alarms:

```
The Industrial Revolution and its consequences have been a disaster for the human race.
```

Wrong input clears the field, shakes it, plays `bell.mp3`, shows **TRY AGAIN!** in red.

### Where the data lives

massalarme is the **source of truth** for your readings; ontoplano holds a copy
for charting. Local data is never deleted because it was uploaded, and with sync
switched off massalarme works exactly as it always did.

The scale rebroadcasts one measurement dozens of times, and your weight drifts as
you settle onto it — a single trip to the scale produced up to 37 rows in the old
log. Those are collapsed into **one weigh-in**, keyed by an `external_id` derived
purely from the reading. Retries and re-runs can therefore never duplicate a point
on your chart.

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
  "version": 2,
  "alarms": [
    {
      "id": "a1b2c3d4",
      "name": "Wake up",
      "time": "07:30",
      "days": ["monday", "tuesday", "wednesday", "thursday", "friday"],
      "enabled": true,
      "updated_at": 0
    },
    {
      "id": "e5f6g7h8",
      "name": "Dentist",
      "time": "08:00",
      "date": "15-04-2026",
      "enabled": true,
      "updated_at": 0
    },
    {
      "id": "i9j0k1l2",
      "name": "One-shot",
      "time": "06:00",
      "type": "next",
      "enabled": true,
      "updated_at": 0
    }
  ]
}
```

Each alarm has a unique `id` and an `updated_at` timestamp (epoch ms) used for
sync conflict resolution between PC and phone — latest edit wins.

Add `"kind": "soft"` for a dismissible alarm. Omitted or anything else means
`hard`: every alarm that existed before this setting was introduced behaved that
way, and quietly downgrading a wake-up alarm is the wrong direction to fail in.

- **Weekly**: set `days` to a list of weekday names
- **Date-specific**: set `date` to `DD-MM-YYYY` (no `days` field)
- **One-shot**: set `type` to `"next"` (fires once at next occurrence)

Times can be `HH:MM` or `HH:MM:SS`. Old v1 format (per-day keys) is auto-migrated.

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
| `make secret` | Show the pairing QR code in terminal |
| `make test` | Run the Python and Kotlin test suites |
| `make set-token` | Store the ontoplano API token (0600 file, prompts if `TOKEN=` omitted) |
| `make check-ontoplano` | Verify the token and show its scopes |
| `make backfill` | Collapse the raw scale log into weigh-ins and queue the history |
| `make sync-status` | Pending count, last success, last error |
| `make sync-now` | Drain the ontoplano queue once |
| `make schedule-now` | Pull the planner once and apply derived alarms |
| `make alarms` | Show upcoming alarms with time remaining |
| `make listen` | Live BLE scale debug — shows flags and weight |
| `make start/stop/restart/status` | systemd service control |
| `make logs` | Follow journal logs |
| `make enable/disable` | Autostart on login |
| `make apk` | Build Android debug APK |
| `make apk-install` | Build and install APK via adb |
| `make clean` | Remove venv |
| `make uninstall` | Remove service, XDG files, venv |

### Scale BLE flags

Use `make listen` to see what the scale broadcasts. The flag byte (`data[1]`)
indicates measurement state:

| Flag | Meaning |
|------|---------|
| `0x84` | Idle — scale broadcasting last known weight, no one on it |
| `0x24` | Measuring — someone stepped on, weight still fluctuating |
| `0xa4` | Weight stabilized — no impedance reading (socks are fine) |
| `0x26` | Impedance done — full body composition measurement complete |

After you step off, the scale keeps spamming `0xa4` or `0x26` for a while
(broadcasting the result to any listening device).

Pick which one stops a hard alarm in the app, under **Settings → Scale**:

- **Weight** — `164` (0xa4), stops on stable weight, socks are fine
- **Body fat** — `38` (0x26), waits for the impedance reading, needs bare feet

It is a seasonal choice in practice. The PC's `syncing_weight_flag` still exists
for `alarm_owner: pc`, and the app's setting is what matters otherwise.

Note this scale emits `0x26` for the impedance case, not the `0xa6` some
documentation claims — verified against the weight log, where every `0x26` row
carries an impedance value and every `0xa4` row does not.

## Android setup

```bash
# Build the debug APK
make apk

# Or build and install directly (phone must be connected via adb)
make apk-install
```

On the phone:

1. Open the **Massalarme** app.
2. Tap **Scan QR Secret** and scan the QR code from `make secret`. This pairs with
   the PC and configures the scale in one step. If it will not scan, zoom the
   terminal in (`Ctrl +`) or open the PNG that `make secret` also writes to
   `~/.local/share/massalarme/pairing-qr.png`.
3. Tap **Grant permissions**. Bluetooth scanning and location are what let the
   phone hear the scale and tell whether it is on your home wifi — without them,
   hard alarms fall back to a dismiss button.
4. Tap **Use current wifi as home** while connected to your home network.
5. Enable **Start on boot** so the service survives reboots.

The service runs in the background — you don't need to keep the app open. Use
**Listen for scale** to record a weigh-in outside an alarm, or to check the scale
is reachable at all.

### Tabs

- **Settings** — service, PC pairing, ontoplano account, scale mode, home wifi,
  permissions. One card per concern.
- **Alarms** — the schedule. Tap an alarm to edit; ontoplano-derived ones carry a
  badge and are read-only.
- **Weight** — your weigh-in history as a chart plus a list, read from the PC
  (which has the whole record) and falling back to this phone's own readings.

### Assets

The app ships with `trombetas.mp3` (the hard-alarm siren) and `bell.mp3` (played
on wrong passphrase input) in `lanalarm/app/src/main/assets/`.

Soft alarms look for `soft.mp3` in the same directory. If it is absent they fall
back to the siren — better than failing to ring at all — so drop a gentler tone in
there if you want the distinction:

```
lanalarm/app/src/main/assets/soft.mp3
```

## Project structure

```
massalarme/
├── alarm_manager.py        # PC daemon: schedule server, ingest, orchestration
├── store.py                # Weigh-in store: sessionisation, identity, sync state
├── ontoplano.py            # ontoplano streams client (interface + HTTP + disabled)
├── sync.py                 # Outbound sync worker (batching, backoff, partial success)
├── schedule_sync.py        # Planner occurrences → alarms
├── tests/                  # pytest suite + a mock ontoplano server
├── Makefile                # Common tasks
├── requirements.txt        # Python dependencies
├── massalarme.service      # systemd unit template
├── install.sh              # Setup script
├── config.yaml.example     # Config template
├── alarms.json.example     # Schedule template
└── lanalarm/               # Android app
    └── app/src/main/
        ├── assets/
        │   ├── trombetas.mp3    # hard-alarm siren
        │   ├── soft.mp3         # ← you provide this (optional)
        │   └── bell.mp3
        ├── kotlin/org/example/lanalarm/
        │   ├── ScaleScanner.kt          # BLE scale listening (moved off the PC)
        │   ├── ScaleCodec.kt            # Pure decode + external_id derivation
        │   ├── ReadingStore.kt          # Local weigh-in log and upload queue
        │   ├── ReadingUploader.kt       # Ships readings to the PC
        │   ├── AlarmSchedule.kt         # Next-occurrence maths
        │   ├── AlarmScheduler.kt        # AlarmManager wiring + AlarmReceiver
        │   ├── HomeNetwork.kt           # "Am I at home?" gate for hard alarms
        │   ├── AppSettings.kt           # Prefs + QR provisioning
        │   ├── AlarmService.kt          # Foreground service, ringing, WS client
        │   ├── AlarmDismissActivity.kt  # Full-screen dismissal (hard/soft)
        │   ├── BootReceiver.kt          # Auto-start on boot
        │   ├── MainActivity.kt          # Settings UI
        │   └── App.kt                   # Notification channels
        ├── res/layout/
        └── AndroidManifest.xml
```

## Tests

```bash
make test          # both suites
make test-py       # pytest: store, sync, ingest endpoint, schedule mapping
make test-apk      # Kotlin: identity parity with Python, decode, alarm maths
```

The Python and Kotlin suites both assert the *same* `external_id` values. If the
two implementations ever drift, the tests fail rather than your weight chart
quietly growing duplicates.

## ontoplano

massalarme publishes weigh-ins to ontoplano and can read your planner back to set
alarms. Both are off by default; massalarme is fully standalone without them.

```bash
make set-token                 # paste the token from /settings/integrations
make check-ontoplano           # confirm it works and see its scopes
make backfill && make sync-now # push your existing history
```

Then in `~/.config/massalarme/config.yaml`:

```yaml
ontoplano:
  enabled: true
  base_url: "http://192.168.1.10:1493"   # or https://app.ontoplano.…
  schedule:
    enabled: true
    rules:
      - match: {title: "(?i)wake up"}
        kind: hard
      - match: {category: duty}
        kind: soft
```

Scopes: `streams:write` to push readings, `schedule:read` for planner-driven
alarms. `streams:read` is deliberately **not** needed — massalarme owns its
readings and never reads them back.

ontoplano reports *what is scheduled* and knows nothing about alarms, scales or
ringtones. Which occurrences become alarms, and whether they are hard or soft, is
decided by the rules above. First match wins; anything unmatched gets no alarm.
Derived alarms carry `origin: ontoplano` and update in place on each sync — your
hand-made alarms are never touched.

## Security

The PC and phone share a 64-character hex secret generated on first run. All HTTP
requests include `?key=<secret>`. The phone rejects requests without a valid key
(403). The secret is exchanged via QR code — it never leaves the LAN.

The QR code now also carries the PC's LAN address and the scale parameters, since
the phone has to work without the PC prompting it first. Scanning it is the whole
of setup.

The ontoplano API token is a **separate** credential from the QR secret. It lives
in `~/.config/massalarme/ontoplano_token` with mode `0600` — never in
`config.yaml`, never in the repo, never logged.
