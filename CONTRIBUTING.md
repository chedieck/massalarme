# Contributing

Massalarme is tested against exactly one piece of hardware: a Xiaomi Mi Body
Composition Scale (`MIBFS`). Everything about how it reads a scale — the
advertisement it listens for, the byte offsets it decodes, the flag bits that
decide an alarm has been satisfied — is that one model's. If you own a different
scale, you are the only person who can make it work, and adding one is the most
useful thing you can do here.

## If you own a scale

The whole job is: prove your scale broadcasts something readable, work out what
the bytes mean, and add a decoder.

### 1. Find out what your scale says

Plug the scale in, step on it, and watch the air:

```bash
make daemon-setup     # venv and dependencies, one-off
make listen           # prints every BLE advertisement it can see
```

You are looking for a device that appears when you step on and goes quiet a few
seconds after you step off. Note its advertised **name** and the **service UUID**
its payload arrives under. If nothing shows up, your scale probably needs a GATT
connection rather than broadcasting openly, which is a larger job than this
document covers — open an issue and say so.

`make listen` prints the raw payload as hex. Capture a full trip to the scale:
step on, stand still until it settles, step off. Keep the output.

### 2. Work out the bytes

Take three or four captures at weights you know — yourself, yourself holding
something heavy, a suitcase. Then find the bytes that track it. On the Xiaomi the
weight is the last two bytes, little-endian, in units of 200ths of a kilogram;
your scale will differ, but the shape of the problem is the same.

Two other things matter as much as the weight, and they are the parts people miss:

- **"The reading has settled."** Scales broadcast continuously while you are
  stepping on, and those intermediate numbers are real but still climbing. There
  is almost always a bit or a byte that flips when the scale stops deciding.
  Without it the app records the number it saw a quarter of a second in.
- **"You have stepped off."** Usually a separate bit. On the Xiaomi it is `0x80`,
  and confusing it with *settled* is a bug this app actually shipped: a hard alarm
  held out for a flag byte that had the stepped-off bit set, so it kept ringing
  while its owner stood on the scale and only stopped once they gave up and got
  off. Treat these as independent bits and test them independently.

Impedance — the body-fat measurement, which needs bare feet — is optional. Say so
in your decoder if your scale does not report it.

### 3. Add the decoder

`ScaleCodec` is deliberately free of every Android import: it is a pure function
from a byte array to a reading, which is what lets it be unit-tested on a plain
JVM.

```
lanalarm/app/src/main/kotlin/org/example/lanalarm/ScaleCodec.kt   the decode
lanalarm/app/src/main/kotlin/org/example/lanalarm/ScaleScanner.kt what it scans for
lanalarm/app/src/test/kotlin/org/example/ScaleTest.kt             the tests
```

Add your model alongside the Xiaomi path rather than replacing it, keyed on the
advertised name and service UUID. Bring tests: paste your real captured payloads
in as hex literals and assert the weight, the settled bit and the stepped-off bit
each come out right. Those captures are the entire evidence that your decoder is
correct, and nobody reviewing the pull request owns your scale.

Open an issue before a large one, so nobody writes the same decoder twice.

## Running the thing

```bash
make test        # both suites
make test-py     # pytest
make test-apk    # Kotlin, on the JVM under Robolectric
make apk         # build the debug APK
make apk-install # build and install on a phone connected over adb
```

`make apk` finds your SDK from `$ANDROID_HOME`, `$ANDROID_SDK_ROOT`,
`~/Android/Sdk` or `/opt/android-sdk`, and writes `lanalarm/local.properties`
itself. That file is machine-specific and untracked — never commit it.

The Kotlin suite runs under Robolectric on the JVM, so it needs no emulator and
no phone. It drives the real Activity, the real dialogs and the real
`AlarmManager`, because that layer is where every bug that reached a user
actually lived.

**Two things are pinned across both languages** and will fail the build if they
drift apart:

- `external_id`, computed independently in `ScaleCodec` and in
  `store.make_external_id`. Drift means one weigh-in becomes two points on
  somebody's chart.
- The planner-to-alarm mapping, where `OntoplanoScheduleTest` mirrors
  `tests/test_schedule_sync.py` case for case.

Change one side of either and you must change the other.

## Never commit real data

This repository has no fixture of anybody's body. `weights.db`, `alarms.yaml`,
`config.yaml` and `lanalarm/local.properties` are all ignored, and they are
ignored because they are one person's weigh-ins, schedule, home network and file
paths. The `.example` files are the ones meant to be read.

The same goes for raw Bluetooth captures: a `btmon` log contains your scale's MAC
address and, usually, every other device in your house. Strip the addresses
before you attach one to an issue.

The repository also ships **no audio**, deliberately — see
`lanalarm/app/src/main/assets/README.md`. Please do not add any. A sound file
whose licence nobody can name is the one thing that can get the whole project
taken down.

## Pull requests

- One logical change per commit, with a conventional prefix: `feat`, `fix`,
  `docs`, `test`, `chore`, `perf`, `refactor`.
- Write what changed and why, not a restatement of the diff.
- `make test` passes before you open the PR.
- Match the style of the file you are in, including how much it comments. Several
  files here carry a paragraph explaining why something is the way it is — those
  paragraphs are load-bearing, and the reason is usually a bug that shipped.

## Licence

By contributing you agree that your contribution is licensed under the
[GNU AGPL v3](LICENSE), the same terms as the rest of the project.
