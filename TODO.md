# TODO — publishing massalarme

A short plan to get this from "works on my phone" to a public GitHub repo a
stranger can install. Roughly in order; each step is independently shippable.

## 0. Scrub the repo before the first public push

Nothing else on this list matters if the first push leaks personal data.

`weights.db`, `alarms.yaml`, `local.properties`, `nvim.log`, `.sisyphus/` and
the tracked `.pyc` files are **untracked and ignored as of the last commit**, and
the two `.webm` music files are deleted at HEAD. That is not enough on its own:
they are all still in the history.

- [ ] Rewrite the history (`git filter-repo`) or publish from a fresh, squashed
      initial commit. `weights.db` is real weigh-ins and the two `.webm` files
      are tens of MB of someone else's recordings, so neither can ship.
- [ ] Ship `weights.db.example` or nothing at all, and let the daemon create it.
- [ ] Audio licensing: `trombetas.mp3`, `bell.mp3`, `Audio/*.ogg`. Either confirm
      they are freely redistributable and record the source in
      `Audio/CREDITS.md`, or replace them with a CC0 set. A repo that ships
      unclearable audio cannot be published at all.
- [ ] `git log -p -S onto_` for a leaked ontoplano token. Rotate it if one is
      there. (`xiaomi-exploration/` is untracked and holds raw `btmon` captures
      and a MAC address — keep it that way.)

## 1. Decide the repo shape

The phone is the product now and the daemon is a legacy component, so the layout
should say so:

```
massalarme/
  android/     the app (currently lanalarm/)
  daemon/      the legacy Python peer, clearly labelled
  docs/
```

- [ ] Rename `lanalarm/` → `android/`, move the loose `.py` files into `daemon/`.
- [ ] Rename the Android package `org.example.lanalarm` → something real
      (`com.chedieck.massalarme`). `org.example` is a placeholder namespace and
      reads as unfinished on any store page.
- [ ] Add a `LICENSE` (MIT or AGPL — pick one).
- [ ] Decide whether the daemon stays at all. It is the reference implementation
      the Kotlin ports are tested against and it holds the weight history, which
      is a real argument for keeping it. It is also a second implementation of
      things the phone now does alone.

## 2. Ship it

- [ ] A signed release APK in GitHub Releases, so people can install without
      Android Studio. Keystore in repo secrets, signed in CI.
- [ ] A first-run flow rather than a settings screen: grant permissions, set home
      wifi, add an alarm. Three steps, no mention of ontoplano.
- [ ] Migration note for anyone on 2.x: the LAN pairing is gone and the app will
      not find their PC. Their alarms and readings are untouched; the ontoplano
      connection has to be set up on the phone.

## 3. ontoplano polish

The phone client covers the plugin contract: derived `external_id`, duplicates
treated as success, 422s dropped, 429 with `Retry-After`, configurable base URL,
`whoami`, plugin manifest, stream declaration, schedule read. What is left:

- [ ] Import the *whole* declared metaKeys set into the manifest automatically
      rather than by hand — right now `Ontoplano.manifest()` and what
      `ReadingUploader` actually writes are kept in step by eye.
- [ ] Surface the pending-publish count somewhere better than a caption. A
      weigh-in stuck in the queue for a week should be visible without going
      looking.
- [ ] A one-command smoke test against a local ontoplano
      (`make ontoplano-check BASE=… TOKEN=…`) so a self-hoster can prove the link
      before trusting it with a morning.

## 4. Make it reviewable

- [ ] CI: GitHub Actions running `make test` (pytest + the Robolectric suite) and
      `./gradlew assembleDebug`. The Android side needs a JDK 17 setup step and
      `android-actions/setup-android`.
- [ ] `CONTRIBUTING.md`: how to run the daemon against fake data, how to
      regenerate fixtures, where the BLE decode lives.
- [ ] Be explicit that this is tested against one scale (Xiaomi MIBFS) and that
      the byte offsets in `ScaleCodec` are that hardware's.
