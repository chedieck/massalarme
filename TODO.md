# TODO — massalarme

Roughly in order; each step is independently shippable.

## Done before the first public push

- History rewritten with `git filter-repo`. `weights.db`, `alarms.yaml`,
  `local.properties`, `nvim.log`, `.sisyphus/`, the tracked `.pyc` files, the raw
  `btmon` captures and two large `.webm` recordings are gone from every commit,
  not just from `HEAD`.
- The daemon's hardcoded phone MAC and `min_weight_kg: 68` default are gone. The
  MAC now defaults to empty, which skips LAN discovery instead of matching the
  first neighbour on the network.
- All bundled audio removed. Alarms ring the system alarm sound unless an
  optional `custom-alarm.mp3` is dropped into the app's assets directory.
- `LICENSE` (AGPL-3.0) and `CONTRIBUTING.md` added.

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
- [ ] Decide whether the daemon stays at all. It is the reference implementation
      the Kotlin ports are tested against, which is a real argument for keeping
      it. It is also a second implementation of things the phone now does alone.

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

- [ ] Pin down what `PUT /api/v1/plugin` actually wants. The hosted ontoplano
      rejects `Ontoplano.manifest()` with `each attribute key must be an
      object`, so it expects a map of attribute descriptors where we send
      `metaKeys` as a list of names. The call is best-effort now and no longer
      fails the connection test, so this is cosmetic — the stream renders from
      the server's defaults until it is fixed.
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
- [ ] Support a second scale. `CONTRIBUTING.md` describes the work; the blocker
      is that nobody here owns one.
