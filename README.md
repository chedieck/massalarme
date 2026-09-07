# Massalarme

An alarm clock you cannot switch off from bed. It only goes quiet once you have
stood on your Xiaomi BLE scale, and it shows your weight climbing on the lock
screen while you do it.

Everything runs on the phone. There is no server to keep awake, nothing polling
in the background, and no account required.

## How it works

```
┌──────────┐  BLE advert   ┌──────────────┐   streams API   ┌───────────┐
│  Scale   │──────────────▶│ Android app  │────────────────▶│ ontoplano │
│  (MIBFS) │               │  (Kotlin)    │◀────────────────│ (optional)│
└──────────┘               └──────────────┘  planner tasks  └───────────┘
```

The phone holds the schedule, books exact alarms with `AlarmManager`, listens to
the scale itself, and stores every reading locally. It wakes to ring, to read the
scale, and to publish a weigh-in — and stops again. Nothing else runs.

[ontoplano](https://github.com/) is optional on both sides. Switch it on and the
app publishes your weigh-ins to it and can read your planner back, so putting
"wake up" at 07:00 on Tuesday is what makes the alarm ring. Leave it off and
nothing changes about how the alarm behaves.

> **The Python daemon in this repo is no longer in the path.** Earlier versions
> needed a PC on the same LAN to keep the schedule and republish readings; the
> phone does both itself now. The daemon is kept because it holds the historical
> `weights.db`, is the reference implementation the Kotlin ports are tested
> against, and can print the setup QR for the phone (`make phone-qr`). Nothing in
> the app talks to it.

### Hard and soft alarms

| | Hard | Soft |
|---|---|---|
| Silenced by | Standing on the scale | A dismiss button |
| Ringtone | `trombetas.mp3` | `soft.mp3` (falls back to the siren) |
| Escape hatch | The passphrase, if you leave it on | — |
| Snooze | Yes — comes back hard | Yes |

A hard alarm only demands the scale **when the phone is on your home wifi** —
that is where the scale is. Away from home it degrades to a soft alarm and tells
you why, rather than trapping you with a siren in a hotel room. The same happens
if the Bluetooth permission is missing.

While it rings, the dismiss screen shows the live reading off the scale: the
number climbing as you step on, then whether it has settled and whether the
body-fat measurement landed. The scale broadcasts continuously; standing on one
at 07:00 with a siren going and seeing nothing happen for four seconds is how you
conclude the app is broken and reach for the passphrase.

**Passphrase.** A hard alarm can also be silenced by typing a phrase. It defaults
to the one this app shipped with:

```
The Industrial Revolution and its consequences have been a disaster for the human race.
```

Set your own under **Settings → Dismissal**, or switch it off entirely and make
the scale the only way out. Off is a real choice with a real cost: a flat scale
battery then means a siren you cannot stop. Wrong input clears the field, shakes
it, plays `bell.mp3`, and shows **TRY AGAIN!** in red.

**Snooze.** Off, 5, 9 or 15 minutes, under **Settings → Dismissal**. A snoozed
alarm comes back on the same terms — a hard one is still hard — so snoozing is
asking for a few more minutes, not talking the app out of its job. The snooze and
the next scheduled alarm are booked separately, so snoozing at 07:00 never
discards the 07:30 alarm.

### Where the data lives

The phone is the **source of truth** for your readings. They go into a local
SQLite store the moment the scale reports them, and ontoplano holds a copy for
charting elsewhere. Local data is never deleted because it was published, and
with sync switched off the app behaves exactly as it does in flight mode.

The scale rebroadcasts one measurement dozens of times, and your weight drifts as
you settle onto it — a single trip to the scale produced up to 37 rows in the old
log. Those are collapsed into **one weigh-in**, keyed by an `external_id` derived
purely from the reading. Retries and re-runs can therefore never duplicate a point
on your chart.

## Requirements

- **Phone**: Android 8+ (API 26) with Bluetooth
- **Scale**: Xiaomi Mi Body Composition Scale (MIBFS). Other BLE scales are
  untested — the byte offsets in `ScaleCodec` are this hardware's.
- **ontoplano**: optional
- **PC**: only if you want to run the legacy daemon. Linux with Bluetooth,
  Python 3.10+, [uv](https://docs.astral.sh/uv/).

## Quick start

```bash
make apk-install     # build and install on a phone connected via adb
```

Then open the app, grant permissions, set your home wifi, and add an alarm. That
is the whole of setup. See [Android setup](#android-setup).

## Legacy PC daemon

Everything from here to [Android setup](#android-setup) describes the Python
daemon, which the app no longer talks to. Skip it unless you are running it
deliberately.

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
| `make phone-qr` | Show the QR the phone scans to reach ontoplano |
| `make secret` | Legacy LAN pairing QR. The app no longer reads it. |
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

## Android setup

```bash
make apk           # build the debug APK
make apk-install   # build and install (phone connected via adb)
```

On the phone:

1. Open the **Massalarme** app.
2. Tap **Grant permissions**. Bluetooth scanning and location are what let the
   phone hear the scale and tell whether it is on your home wifi — without them,
   hard alarms fall back to a dismiss button.
3. Tap **Use current wifi as home** while connected to your home network.
4. Add an alarm on the **Alarms** tab.

That is the app working. Everything below is optional.

5. Under **Settings → Dismissal**, set your own passphrase or switch it off, and
   pick a snooze length.
6. Under **Settings → ontoplano**, paste a server address and token — or tap
   **Scan QR** and scan the code from `make phone-qr` on a machine that already
   holds a token.

**Listen for scale** records a weigh-in outside an alarm, and is the way to check
the scale is reachable at all: the reading appears under the button as it
arrives. It stops by itself after three minutes.

### Background behaviour

The app runs nothing between alarms. There is no always-on service, no socket
listening, and nothing polling. It starts when:

- an alarm fires,
- you tap **Listen for scale**,
- a weigh-in needs publishing to ontoplano.

and stops again as soon as that is done. A publish that fails with no network
books a single `JobScheduler` job with a network constraint, which the system
runs alongside whatever else wakes the device; it is cancelled the moment the
queue drains. Leave **Restore alarms after a reboot** on — Android drops every
registered alarm on both reboot and app update.

### Tabs

- **Settings** — next alarm, scale mode, dismissal, home wifi, ontoplano,
  permissions, background. One card per concern.
- **Alarms** — the schedule. Tap an alarm to edit; ontoplano-derived ones carry a
  badge and are read-only.
- **Weight** — your weigh-in history as a chart plus a list, from this phone's
  own store.

### Scale BLE flags

Byte 1 of the advertisement (`data[1]`) is a bitfield, not an opaque tag. Three
bits matter:

| Bit | Meaning |
|-----|---------|
| `0x02` | An impedance value came with this reading |
| `0x20` | The reading has settled — the scale has stopped deciding |
| `0x80` | The weight has been taken off the scale |

Which gives the states you actually see:

| Flag | Meaning |
|------|---------|
| `0x04` | Someone is stepping on. Weight real but still climbing — this is what the live readout shows |
| `0xa4` | Settled, no impedance, stepped off. The scale gave up on body fat |
| `0x26` | Settled with impedance. Full body-composition measurement complete |

`make listen` prints what your scale is broadcasting, if you want to check.

**Which one stops a hard alarm** is a setting, under **Settings → Scale**:

- **Socks on** — `0xa4`. The scale gives up on body fat and reports the weight
  alone, which is what happens when you step off. No impedance recorded.
- **Bare feet** — `0x26`. The alarm keeps going until the impedance measurement
  lands, which the scale only manages against skin. Stand there in socks and it
  keeps ringing.

Seasonal in practice. Note that what stops the alarm is *not* what gets recorded:
every settled reading is written down whichever mode you are in. Filtering the
recording by the stop flag is a bug this app had — a morning in socks with the
app in bare-feet mode threw the weigh-in away entirely.

This scale emits `0x26` for the impedance case, not the `0xa6` some documentation
claims — verified against the weight log, where every `0x26` row carries an
impedance value and every `0xa4` row does not.

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
        │   ├── ScaleScanner.kt          # Owns the BLE radio, nothing else
        │   ├── ScaleSession.kt          # One trip to the scale: show / stop / record
        │   ├── ScaleCodec.kt            # Pure decode, flag bits, external_id
        │   ├── ReadingStore.kt          # Local weigh-in log and outbound queue
        │   ├── ReadingUploader.kt       # Drains the queue into ontoplano
        │   ├── Ontoplano.kt             # HTTP client: plugin, streams, schedule
        │   ├── OntoplanoSchedule.kt     # Planner occurrences → alarms
        │   ├── OntoplanoSync.kt         # One sync pass: declare, publish, read back
        │   ├── SecretStore.kt           # Token, encrypted with an Android Keystore key
        │   ├── SyncRetryJob.kt          # Network-constrained retry, no polling
        │   ├── AlarmSchedule.kt         # Next-occurrence maths, tombstones
        │   ├── AlarmScheduler.kt        # AlarmManager wiring, snooze, AlarmReceiver
        │   ├── HomeNetwork.kt           # "Am I at home?" gate for hard alarms
        │   ├── AppSettings.kt           # Every persisted setting
        │   ├── AlarmService.kt          # Rings, scans, syncs, then stops itself
        │   ├── AlarmDismissActivity.kt  # Full-screen dismissal, live weight, snooze
        │   ├── BootReceiver.kt          # Rebooks alarms after boot or update
        │   ├── MainActivity.kt          # The whole UI
        │   └── App.kt                   # Notification channels
        ├── res/layout/
        └── AndroidManifest.xml
```

## Tests

```bash
make test          # both suites
make test-py       # pytest: store, sync, ingest endpoint, schedule mapping
make test-apk      # Kotlin: scale rules, ontoplano client, alarm flows, snooze
```

Two things are pinned across the two languages, because a silent disagreement
between them corrupts data rather than crashing:

- **`external_id`** — derived from the reading itself, computed independently in
  `ScaleCodec` and `store.make_external_id`. Drift means one weigh-in becomes two
  points on your chart.
- **Planner mapping** — `OntoplanoScheduleTest` mirrors `tests/test_schedule_sync.py`
  case for case. Drift means the same task becomes a different alarm.

The Kotlin suite runs on the JVM under Robolectric: it drives the real Activity,
the real dialogs, the real `AlarmManager`, and a real local HTTP server for the
ontoplano client. That layer is where every bug that reached a user actually
lived; the schedule maths underneath was unit-tested from the start and was never
the thing that was wrong.

## ontoplano

Optional. Switch it on and the app publishes weigh-ins and can read your planner
back to set alarms; leave it off and nothing about the alarm changes.

**On the phone**, under **Settings → ontoplano**: paste the server address and a
token, then **Save + test**. The test calls `/api/v1/me`, which needs no scope, so
it tells you whether the token works and whose account it is — the useful answer,
rather than "saved".

To avoid typing a token on a phone keyboard, generate the QR on a machine that
already holds one:

```bash
make set-token     # paste the token from /settings/integrations
make phone-qr      # scan this from Settings → ontoplano → Scan QR
```

The QR carries the base URL and the token and is printed, never written to an
image — a token saved as a PNG is a token in someone's photo roll.

**Scopes**, and why each is needed:

| Scope | For |
|-------|-----|
| `streams:write` | Publishing weigh-ins. The whole point. |
| `schedule:read` | Reading planner occurrences, so a task can ring. |
| `plugin:declare` | Registering the manifest at `PUT /api/v1/plugin`. |

`streams:read` is deliberately **not** requested: the phone owns its readings and
never needs them back.

**Which tasks become alarms** is decided on the phone, not in ontoplano — it
reports what is scheduled and knows nothing about alarms, scales or ringtones.
Set a title pattern and a kind under **Settings → ontoplano**; an empty pattern
means nothing becomes an alarm. Matching is case-insensitive without needing
`(?i)`.

Derived alarms carry `origin: ontoplano`, are read-only in the app, and update in
place on each sync, so your hand-made alarms are never touched. An activity
repeated across several days collapses into one weekly alarm rather than becoming
three unrelated single-day ones. They default to **soft** and can be snoozed like
anything else.

Publishing is retry-safe by construction: `external_id` is derived from the
reading, so a resend comes back as a duplicate rather than a second point. A `403`
or `422` is treated as permanent so the queue cannot wedge; a `429` or `5xx` holds
the reading, because a server having a bad minute is not a reason to lose a
weigh-in.

## Security

There is no LAN protocol left, so there is no shared secret and nothing listening
on a port. The app makes exactly one kind of outbound request: to the ontoplano
address you typed in.

The ontoplano token is encrypted with a key held in the **Android Keystore** —
hardware-backed on most phones — and stored in the app's private preferences. The
key is deliberately not bound to device unlock: a weigh-in has to be publishable
while the phone is locked on a bedside table, which is exactly when it cannot be
unlocked. If a device's keystore refuses the key, the token is stored as-is and
**the settings screen says so** rather than implying a protection that is not
there.

The token is never rendered back into the settings field, never logged, and never
written to an image.

On the legacy daemon, the token lives in `~/.config/massalarme/ontoplano_token`
with mode `0600` — never in `config.yaml`, never in the repo.
