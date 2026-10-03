# Massalarme

[![Licence: AGPL v3](https://img.shields.io/badge/licence-AGPL--3.0-blue.svg)](LICENSE)

An alarm clock you cannot switch off from bed. It only goes quiet once you have
stood on your Xiaomi BLE scale, and it shows your weight climbing on the lock
screen while you do it.

Everything runs on the phone.

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
> phone does both itself now. The daemon is kept because it is the reference
> implementation the Kotlin ports are tested against, it can migrate an old
> `weights.db` into ontoplano, and it can print the setup QR for the phone
> (`make phone-qr`). Nothing in the app talks to it.

### Hard and soft alarms

| | Hard | Soft |
|---|---|---|
| Silenced by | Standing on the scale | A dismiss button |
| Ringtone | The system alarm sound, or your own `custom-alarm.mp3` | Your own `custom-alarm-soft.mp3`, else the same |
| Escape hatch | The passphrase, if you leave it on | — |
| Snooze | Yes — comes back hard | Yes |

A hard alarm only demands the scale **when the phone is on your home wifi** —
that is where the scale is. Away from home it degrades to a soft alarm and tells
you why. The same happens if the Bluetooth permission is missing.

While it rings, the dismiss screen shows the live reading off the scale: the
number climbing as you step on, then whether it has settled and whether the
body-fat measurement landed. The scale broadcasts continuously; standing on one
at 07:00 with a siren going and seeing nothing happen for four seconds is how you
conclude the app is broken and reach for the passphrase.

**Passphrase.** A hard alarm can also be silenced by typing a phrase. It defaults
to the one this app shipped with:

```
Act as if what you do makes a difference. It does.
```

Set your own under **Settings → Dismissal**, or switch it off entirely and make
the scale the only way out. Off is a real choice with a real cost: a flat scale
battery then means a siren you cannot stop. Wrong input clears the field, shakes
it, and shows **TRY AGAIN!** in red.

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
  untested — the byte offsets in `ScaleCodec` are this hardware's. If you own a
  different one, [CONTRIBUTING.md](CONTRIBUTING.md) explains how to add it.
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

The Python daemon used to be required: it held the schedule, watched the clock,
and drove the phone over the LAN. None of that is true now, and it no longer runs
as a service — `make install` and the systemd unit are gone, along with the
targets that rang or silenced the phone over HTTP, since the phone no longer
listens on a port.

What survives is a handful of one-shot commands, and only two reasons to run any
of them:

**Getting a token onto the phone without typing it.**

```bash
make daemon-setup                       # venv, deps, ~/.config/massalarme/config.yaml
$EDITOR ~/.config/massalarme/config.yaml   # set ontoplano.base_url
make set-token                          # paste the token; stored 0600
make phone-qr                           # scan this from the app
```

The token comes from ontoplano's **Settings → Integrations**, with
`streams:write` and `schedule:read` ticked — see
[ontoplano](#ontoplano) for why. `make check-ontoplano` prints which scopes the
stored token actually carries.

**Moving an existing weight history into ontoplano.** If you ran the old daemon
you have a `weights.db` full of raw scale rows; the phone's store only ever has
what the phone itself measured. (No database ships with this repository — those
are somebody's actual weigh-ins.)

```bash
make backfill      # collapse the raw log into weigh-ins
make sync-now      # push the queue
make sync-status   # pending count, last success, last error
```

`make listen` prints what the scale is broadcasting, which is the fastest way to
check the hardware end of things. `make run` still starts the daemon in the
foreground if you want to poke at it; nothing on the phone will talk to it.

If you installed the old systemd unit, `make remove-service` takes it away.

### Alarm schedule format

The phone stores its schedule in this shape. The daemon reads the same format
from `~/.config/massalarme/alarms.json`.

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

Add `"kind": "soft"` for a dismissible alarm. Omitted or anything else means
`hard`: every alarm that existed before this setting was introduced behaved that
way, and quietly downgrading a wake-up alarm is the wrong direction to fail in.

- **Weekly**: set `days` to a list of weekday names
- **Date-specific**: set `date` to `DD-MM-YYYY` (no `days` field)
- **One-shot**: set `type` to `"next"` (fires once at next occurrence)

Times can be `HH:MM` or `HH:MM:SS`. `updated_at` is epoch ms; a deleted alarm is
kept as `"deleted": true` for thirty days so an ontoplano re-sync propagates the
deletion instead of bringing the alarm back.

### Makefile targets

`make` on its own lists these.

| Target | Description |
|--------|-------------|
| `make apk` | Build the Android debug APK |
| `make apk-install` | Build and install via adb |
| `make test` | Both test suites |
| `make test-py` / `make test-apk` | One of them |
| `make daemon-setup` | One-off: venv, deps, config file |
| `make set-token` | Store the ontoplano token (0600 file; prompts if `TOKEN=` omitted) |
| `make phone-qr` | Show the QR the app scans to reach ontoplano |
| `make check-ontoplano` | Verify the token and show its scopes |
| `make backfill` | Collapse the raw scale log into weigh-ins |
| `make sync-now` / `make sync-status` | Push the queue / inspect it |
| `make listen` | Live BLE scale debug — flags and weight |
| `make alarms` | Upcoming alarms per the daemon's copy |
| `make run` | Legacy daemon in the foreground |
| `make clean` | Remove the venv and Android build output |
| `make remove-service` | Remove the old systemd unit |

`make apk` finds your SDK from `$ANDROID_HOME`, `$ANDROID_SDK_ROOT`,
`~/Android/Sdk` or `/opt/android-sdk`, and writes `lanalarm/local.properties`
itself. That file is untracked and machine-specific; it is also rewritten
whenever it points at a directory that no longer exists, because Gradle prefers
it over `$ANDROID_HOME` and a stale path there fails the build on a machine with
a perfectly good SDK.

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
6. Under **Settings → ontoplano**, paste a token — or tap **Scan QR** and scan
   the code from `make phone-qr` on a machine that already holds one. Create the
   token in ontoplano under **Settings → Integrations**, with the
   `streams:write` and `schedule:read` scopes ticked; see
   [ontoplano](#ontoplano) for what each one buys you.

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
- **Weight** — your weigh-in history as a chart plus a list. This phone's own
  store first, so the chart draws with no network at all, with anything published
  to Ontoplano that this phone never measured folded in on top — a backfilled
  scale log, or a reading from an older phone. That half needs the token to carry
  `streams:read`; without it you simply see less history, and nothing nags about
  it.

### Scale BLE flags

Byte 1 of the advertisement (`data[1]`) is a bitfield, not an opaque tag. Three
bits matter:

| Bit | Meaning |
|-----|---------|
| `0x02` | An impedance value came with this reading |
| `0x20` | The reading has settled — the scale has stopped deciding |
| `0x80` | The weight has been taken off the scale |

Which gives five states, not two:

| Flag | Meaning |
|------|---------|
| `0x04` | Stepping on. Weight real but still climbing — this is what the live readout shows |
| `0x24` | Settled, still stood on it |
| `0xa4` | Settled, stepped off. The scale gave up on body fat |
| `0x26` | Settled with impedance, still stood on it |
| `0xa6` | Settled with impedance, stepped off |

`make listen` prints what your scale is broadcasting, if you want to check.

**Which one stops a hard alarm** is a setting, under **Settings → Scale**:

- **Socks on** — stop as soon as the weight settles. No impedance recorded.
- **Bare feet** — hold out for the impedance measurement, which the scale only
  manages against skin. Stand there in socks and it keeps ringing.

Both conditions are tested against the *bits*, not against a whole flag byte.
That matters: `0xa4` has the stepped-off bit set, so an equality test against it
left a hard alarm ringing while you stood on the scale, and only stopped once you
gave up and got off.

Seasonal in practice. Note that what stops the alarm is *not* what gets recorded:
every settled reading is written down whichever mode you are in. Filtering the
recording by the stop flag is a bug this app had — a morning in socks with the
app in bare-feet mode threw the weigh-in away entirely.

The weight log only ever contains `0xa4` and `0x26` rows, because those were the
only two the old PC daemon accepted — which is how the stepped-off bit went
unnoticed for so long. `0x04` was found by capturing raw `btmon` output partway
up, mid-step. Those captures are not in this repository: a Bluetooth log carries
the MAC address of every device in the house.

### Ringtones

The app ships **no audio**. Out of the box both kinds of alarm ring the phone's
own alarm sound, and you never have to know this section exists.

If you want your own, drop MP3s into `lanalarm/app/src/main/assets/` before you
build:

| File | Played when |
|---|---|
| `custom-alarm.mp3` | A hard alarm rings |
| `custom-alarm-soft.mp3` | A soft alarm rings (falls back to `custom-alarm.mp3`, then the system sound) |
| `custom-bell.mp3` | A wrong passphrase is typed. Purely decorative |

Both alarm files loop, so pick something that survives six minutes at full
volume. The directory ignores audio files, so your siren never ends up in a
commit.

Nothing is bundled on purpose: a sound file whose licence nobody can name is the
one thing that can get a repository taken down, and what a siren should sound
like is a matter of taste anyway.

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
├── config.yaml.example     # Config template
├── alarms.json.example     # Schedule template
├── CONTRIBUTING.md         # How to add support for another scale
├── LICENSE                 # GNU AGPL v3
└── lanalarm/               # Android app
    └── app/src/main/
        ├── assets/              # empty; drop custom-alarm.mp3 here (optional)
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

### Getting a token

In ontoplano, go to **Settings → Integrations** and create an API token. A token
there carries **scopes** — the permissions it is allowed to use — and you have to
tick them yourself; a token with none is accepted by the app and then cannot do
anything. Tick these two:

| Scope | Needed for | Without it |
|-------|------------|------------|
| `streams:write` | Publishing weigh-ins into the `massalarme.weight` stream. | Weigh-ins stay on the phone. The queue holds them and never drains. |
| `schedule:read` | Reading planner occurrences, so a planned task can ring. | No task ever becomes an alarm. Your hand-made alarms are unaffected. |

Two more are worth ticking, and both are safe to skip:

| Scope | Needed for | Without it |
|-------|------------|------------|
| `streams:read` | Showing weigh-ins published before this phone existed — a backfilled scale log, or a reading taken on another device. | The Weight tab shows only what this phone measured. Nothing warns you; there is just less history. |
| `plugin:declare` | Naming the three `massalarme` attribute keys to your account, so Ontoplano shows them as this app's words on a task rather than as anonymous strings. | The keys still work. They are just unlabelled in Ontoplano's own screens. |

Nothing here can read your planner's contents beyond the scheduled occurrences,
or write anything other than weight points.

The token is not your ontoplano password, and the app stores it in the Android
keystore rather than in settings — if the keystore refuses, the settings screen
says so instead of pretending.

### Connecting the phone

**Settings → ontoplano**: the server address is prefilled with `https://app.ontoplano.com`
(change it if you self-host), paste the token under it, then **Save + test**. The
test calls `/api/v1/me`, which needs no scope, so it answers the question worth
asking: whether the token works, and whether it carries the scopes the app needs.
A token that is missing one is named in the reply rather than failing later on a
morning. Once a token is stored the field is replaced by **Token stored**; tap
**Replace** to swap it.

To avoid typing a 40-character token on a phone keyboard, generate the QR on a
machine that already holds one:

```bash
make set-token     # paste the token from Settings → Integrations
make phone-qr      # scan this from Settings → ontoplano → Scan QR
```

The QR carries the base URL and the token and is printed, never written to an
image — a token saved as a PNG is a token in someone's photo roll.

### Which tasks become alarms

Mark the block, in ontoplano, with an **attribute**. Attributes are the
user-defined key/value pairs ontoplano stores on a task and never interprets —
exactly so that a plugin can define its own vocabulary — and massalarme reads
three of them:

| Attribute | What it does |
|-----------|--------------|
| `massalarme = true` | Rings gently. One tap dismisses it. |
| `soft_massalarme = true` | The same thing, spelt out. |
| `hard_massalarme = true` | Rings the siren and keeps ringing until the scale reports a weight. Snooze still works. |

**Hard wins** if a block carries both. `true`, `1`, `yes`, `y` and `on` all count
as yes; anything else — including `false`, a blank, and a typo — is a no, because
an alarm that goes off at 05:00 over a misspelt value is worse than one that
stays quiet and can be looked at over breakfast. A block carrying none of them is
not an alarm, so there is nothing to opt into and nothing to configure on the
phone; the settings screen just lists the three keys.

This used to be a regex matched against the task's title, which meant the alarm
depended on spelling: rename "Acordar" to "Levantar" and it silently stopped
happening, while an unrelated block called "wake up early" started ringing.
Whether a block should wake you is a property of that block, so that is where it
now lives.

Derived alarms carry `origin: ontoplano`, are read-only in the app, and update in
place on each sync, so your hand-made alarms are never touched. An activity
repeated across several days collapses into one weekly alarm rather than becoming
three unrelated single-day ones, and they can be snoozed like anything else.

Publishing is retry-safe by construction: `external_id` is derived from the
reading, so a resend comes back as a duplicate rather than a second point. A `403`
or `422` is treated as permanent so the queue cannot wedge; a `429` or `5xx` holds
the reading, because a server having a bad minute is not a reason to lose a
weigh-in.

## Contributing

**If you own a scale, that is the contribution this project wants.**

Everything here is tested against exactly one piece of hardware — a Xiaomi MIBFS.
The advertisement it listens for, the byte offsets it decodes, and the flag bits
that decide you are actually stood on it are all that one model's.

The job is three steps: run `make listen` and capture what your scale broadcasts,
work out which bytes carry the weight and which bit means *settled*, then add a
decoder to `ScaleCodec` with your captured payloads as test fixtures.
[CONTRIBUTING.md](CONTRIBUTING.md) walks through all three, including the two
flag bits that are easy to confuse and the bug that confusing them causes.

It also covers running the two test suites, what is pinned across the Kotlin and
Python implementations, and what must never be committed — nobody's weigh-ins,
nobody's Bluetooth captures, and no audio.
