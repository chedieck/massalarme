# TODO — publishing massalarme

A short plan to get this from "works on my two machines" to a public GitHub repo
that a stranger can install, and that plugs into ontoplano if they happen to run
it. Roughly in order; each step is independently shippable.

## 0. Scrub the repo before the first public push

Nothing else on this list matters if the first push leaks personal data. All of
these are **currently tracked**, so deleting them at HEAD is not enough — they
stay in the history and have to be rewritten out (`git filter-repo`) or the repo
has to be published from a fresh, squashed initial commit.

- [ ] `weights.db` — real weigh-ins. Remove from history; ship
      `weights.db.example` or nothing at all and let the daemon create it.
- [ ] `alarms.yaml` — the live config, not the `.example`. Untrack it.
- [ ] `lanalarm/local.properties` — hardcodes an SDK path from one machine.
      Untrack, add to `.gitignore` (Android Studio regenerates it).
- [ ] `__pycache__/*.pyc` — tracked despite `.gitignore`. `git rm --cached`.
- [ ] `nvim.log` and `.sisyphus/plans/overhaul.md` — scratch work. Drop, or
      move anything worth keeping into `docs/`. (`xiaomi-exploration/` is
      untracked already; it holds raw `btmon` captures and a MAC address, so
      keep it that way.)
- [ ] The two `.webm` music files under `lanalarm/` (deleted at HEAD, still in
      history) — tens of MB of someone else's recordings. Must be rewritten out.
- [ ] Audio licensing: `trombetas.mp3`, `bell.mp3`, `Audio/*.ogg`. Either
      confirm they are freely redistributable and record the source in
      `Audio/CREDITS.md`, or replace them with a CC0 set. A repo that ships
      unclearable audio cannot be published at all.
- [ ] Check the git history for the shared secret and any ontoplano token
      (`git log -p -S onto_`). If one was ever committed, rotate it.

## 1. Decide the repo shape

One repo, two components, is the honest description of what this is:

```
massalarme/
  daemon/      the Python PC peer (alarm_manager.py, sync.py, store.py, …)
  android/     the phone app (currently lanalarm/ — rename it, the app is
               called Massalarme everywhere else)
  docs/
```

- [ ] Move the loose `.py` files into `daemon/`, rename `lanalarm/` → `android/`.
- [ ] Rename the Android package `org.example.lanalarm` → something real
      (`com.chedieck.massalarme`). `org.example` is a placeholder namespace and
      will read as unfinished on any store page.
- [ ] Add `LICENSE` (MIT or AGPL — pick one) and a `.gitignore` that actually
      covers `build/`, `.gradle/`, `local.properties`, `*.db`, `venv`.

## 2. Make "standalone" true, not just claimed

The README already says the phone works with the PC off — that is the strongest
selling point, so make it the default path rather than the fallback.

- [ ] A first-run flow that never mentions a PC: grant permissions, set a home
      wifi, add an alarm, done. Pairing becomes an optional settings screen.
- [ ] Ship a release APK in GitHub Releases so people can install without
      Android Studio. `keystore` in repo secrets, signed in CI.
- [ ] Replace the hardcoded dismissal passphrase with a user-set one — a public
      repo publishes the current one to everybody.
- [ ] `make install` assumes Linux + systemd + uv. Say so, or add a plain
      `pip install -e .` path for everyone else.

## 3. Ontoplano as an optional plugin

The client in `ontoplano.py` already covers most of `docs/PLUGINS.md`: derived
`external_id`, duplicates treated as success, 422s dropped, 429 with
`Retry-After`, base URL configurable, `whoami`, schedule read. What is missing:

- [ ] Declare the manifest at startup — `PUT /api/v1/plugin` with `source:
      "massalarme"` and the `metaKeys` this app reads (`hard_alarm`, `alarm`,
      `remind_min`). Needs the `plugin:declare` scope.
- [ ] Move the token out of the config file into the OS keychain
      (`secret-tool` / `keyring`) as the plugin checklist asks. The file path
      stays as a documented fallback for headless boxes.
- [ ] Surface sync status in the phone UI: last success, pending count, last
      error. The Settings tab shows PC link state but not ontoplano's.
- [ ] Document the exact scopes requested (`streams:write` + `schedule:read` +
      `plugin:declare`) and why each is needed, in the README.
- [ ] A one-command smoke test against a local ontoplano
      (`make ontoplano-check BASE=… TOKEN=…`) so a self-hoster can prove the
      link before trusting it with a morning.

## 4. Make it reviewable

- [ ] CI: GitHub Actions running `make test` (pytest + the Robolectric suite)
      and `./gradlew assembleDebug`. The Android side needs a JDK 17 setup step
      and `android-actions/setup-android`.
- [ ] `AGENTS.md` / `CONTRIBUTING.md`: how to run the daemon against fake data,
      how to regenerate fixtures, where the BLE decode lives.
- [ ] README: cut the ontoplano framing from the opening paragraph. The first
      thing a stranger reads should be "alarm you cannot dismiss without
      weighing yourself"; ontoplano is a section further down.
- [ ] Note the hardware this is actually tested against (Xiaomi MIBFS) and be
      explicit that other BLE scales are untested.
