# Capture Recipe (U5 / U6)

Per-row capture loop driving both platforms into each ledger state, attaching paired evidence, then invoking the U4 blinded review.

> **Capture-execution status (updated 2026-05-15):** BOTH HALVES UNBLOCKED + PROVEN. The paired capture loop can now run headlessly end-to-end.
>
> **iOS:** `xcode-select` → `/Applications/Xcode.app`; iPhone 17 sim (`DA1A3D70-71D5-427C-9A8A-451166E94047`, iOS 26.3) boots; `Pilgrim.app` (Debug, fcd2255) installs; `xcrun simctl io <udid> screenshot` scriptable. **iOS-sim UI automation RESOLVED via idb** — `idb-companion` (Homebrew) + `fb-idb 1.1.7` (`~/.local/bin`) installed; `idb ui tap` drives the sim like Android's `uiautomator`. Seed imported via: `simctl openurl file://<seed>` → Files Save dialog → `idb ui tap` Save → fresh Pilgrim launch → tab pill → Settings → Data → Import Data → doc picker → tap saved file. **iOS seed import VERIFIED:** alert "16 walks added, 53 walks archived" + Settings shows "74 walks · 116 mi / 149 walks · 226 mi / 59 days, the path unbroken".
>
> **Android:** device reconnected; shared seed `fixtures/parity-seed.pilgrim` staged; import VERIFIED (23→39 walks, v1.6.0 tended/archived path exercised on real data).
>
> **idb operational gotchas (learned 2026-05-15):**
> - **Duplicate companions silently break taps.** Two `idb_companion` procs on one udid → `idb ui tap` returns success but injects nothing; `idb ui describe-all` returns only the app shell. Fix: `pkill -f idb_companion; rm /tmp/idb/*.sock`; start exactly one; `idb connect <udid>`.
> - **Native alerts (`UIAlertController`) are a separate `UIWindow`** — `idb ui tap` cannot reach the OK button and `describe-all` won't list it. Dismiss by `simctl terminate` + relaunch; persisted writes (Core Data import) survive the relaunch.
> - **Coords are LOGICAL points** (iPhone 17 = 402×874; screenshot 1206×2622 = ÷3). The bottom tab pill is inset: calibrated Settings tab = `(292, 835)`, Journal ≈ `(190, 835)`, Path ≈ `(110, 835)`.
> - idb env: `export PATH="$HOME/.local/bin:/opt/homebrew/bin:$PATH"`. Companion: `(idb_companion --udid $D > /tmp/idbc2.log 2>&1 &)`.
>
> `seed req: none` rows (Welcome, Permissions, Path-idle, Settings tree, Appearance, About, sound/bell, options-idle, intention sheet, prompts, constellation overlay) reach state from fresh/default on both platforms — pair them first; seed-gated rows now also fully capturable.

## iOS capture commands (proven)

```
sudo xcode-select -s /Applications/Xcode.app/Contents/Developer   # one-time, needs a real TTY
xcrun simctl boot <udid>
xcodebuild -workspace Pilgrim.xcworkspace -scheme Pilgrim -configuration Debug \
  -destination 'platform=iOS Simulator,id=<udid>' -derivedDataPath /tmp/pilgrim-ios-dd build
xcrun simctl install <udid> /tmp/pilgrim-ios-dd/Build/Products/Debug-iphonesimulator/Pilgrim.app
xcrun simctl launch <udid> org.walktalkmeditate.pilgrim --demo-mode   # seeded; drop arg for fresh/onboarding rows
xcrun simctl io <udid> screenshot evidence/<row-id>__<mode>__ios.png
xcrun simctl ui <udid> appearance dark|light   # appearance cross-cut (constellation = in-app Settings→Appearance)
```

## Prerequisites

1. `parity-seed.pilgrim` built + round-trip-equivalence-checked on both platforms (`docs/parity/fixtures/README-seed.md`).
2. Android: debug build installed on a device/emulator. Device-coordinate caveat (learned 2026-05-15): `adb screencap` images are downscaled vs real input coords — drive taps from `uiautomator dump` bounds (real px), not screenshot pixels.
3. iOS: a booted simulator (`xcrun simctl boot <udid>`) OR a connected iOS device with the v1.6.0 build (`fcd2255`).
4. iOS pinned: `git -C ../pilgrim-ios rev-parse --short HEAD` == `fcd2255`.

## Per-row loop (origin F1)

For each ledger row, for each appearance mode in its `appearance` column:

1. **Seed/state** — import `parity-seed.pilgrim` on both, OR run the row's manual recipe (`README-seed.md` "cannot encode" table) for runtime states.
2. **Appearance** — set the mode (Settings→Appearance) on both before capture. Reduce-motion rows: enable OS reduce-motion first.
3. **Navigate** — drive both apps to the exact screen+state.
   - Android: `adb shell uiautomator dump` → parse target `bounds` → `adb shell input tap <cx> <cy>` (real px). Screenshot: `adb shell screencap -p /sdcard/s.png && adb pull`.
   - iOS sim: `xcrun simctl ui <udid>` to set appearance/motion; navigate via accessibility or scripted XCUITest; `xcrun simctl io <udid> screenshot <out>.png`. iOS device: comparable via `idevicescreenshot` / Xcode.
4. **Attach evidence** — save to `docs/parity/evidence/<row-id>__<mode>__{ios,android}.png`.
5. **Behavior pair** — pull the iOS Swift slice for the screen + the Android implementation reference; record both verbatim in the review packet (U4 allowed input).
6. **Animated rows (U6)** — also record motion:
   - Android: `adb shell screenrecord --time-limit 8 /sdcard/m.mp4 && adb pull` → trim to the animation window.
   - iOS: `xcrun simctl io <udid> recordVideo <out>.mov` (Ctrl-C to stop) → trim.
   - Save to `docs/parity/evidence/motion/<row-id>__<mode>__{ios,android}.mp4`.
   - U6 defines the cadence-eval method (frame-sample diff vs side-by-side playback — settled on first animated row); **U5's loop invokes it; U6 does not run a parallel review.**
7. **Blinded review (U4)** — assemble the review prompt with ONLY the allowed inputs (screenshots + motion + behavior pair + label); exclude every forbidden input. Fresh agent. Get verdict + `observed-diff`.
8. **Write back** — set the row's per-mode verdict + evidence links + `observed-diff` in `2026-05-15-parity-ledger.md`. Row effective verdict = worst across modes.
9. A row is NOT done until every listed appearance mode (+ motion for `anim`) is captured + verdicted. Missing evidence ⇒ stays `unverified`, never defaults to `match`.

## Animated row set (U6 targets)

`anim`-flagged ledger rows: `setup.launch.loading`, `setup.welcome.entrance`, `setup.breath.transition`, `path.wander.idle`, `path.wander.recovery-banner`, `journal.inkscroll.populated`, `journal.dot.standard/newest`, `journal.expandcard.open`, `journal.fab.hidden`, `walk.active.tracking`, `walk.active.stats-expanded`, `walk.active.peek-hint`, `walk.active.greeting`, `walk.active.sparkline`, `walk.turning.card`, `walk.meditation.timer`, `sealreveal.*`, `summary.reveal`, `summary.reliquary.carousel`, `settings.about`, `settings.practiceheader`, `overlay.constellation`, `overlay.proximity`, `overlay.pilgrimlogo`, `overlay.streakflame`. Reduce-motion variant captured as a still with a note.

## Ordering

Process in ledger order. The capturer owns the loop; the reviewer (U4) owns the verdict; A5 (user/triage) owns the disposition (U7). The capturer never assigns `match` itself.
