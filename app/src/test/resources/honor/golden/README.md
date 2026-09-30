# Honor golden engine traces (Phase 21, U16)

Nine synthetic Ways and GPS traces, run through iOS's own Honor engine at the parity pin. `HonorGoldenTraceTest` runs Android's `HonorEngine` over the same inputs and checks it against iOS input for input: the same events, in the same order, at the same fix, with the same moment ids; the same engine state after every input; and the same `CLLocation.distance` calls, in the same order, to the same places. All nine match with no allowance.

This directory also pins the distance behind iOS's `CLLocation.distance(from:)` call sites (parity spec B §16.1, D1–D4: moment radii of 42 and 60 m, voice drops at 300 m, arrival at 30 m), which Apple does not document. See [What `CLLocation.distance` computes](#what-cllocationdistance-computes).

## Contents

- `capture/capture.sh` fetches six files verbatim from pilgrim-ios at the pin with `git show`: `Pilgrim/Models/Honor/{Way,WayGeometry,HonorTuning,HonorMomentTracker,HonorEngine}.swift` and `Pilgrim/Models/Walk/Seek/ArrivalDebounce.swift`. It compiles them with the three files here and rewrites `corpus/` and `expected/`. No iOS source is copied into this repository, and nothing is written to pilgrim-ios.
- `capture/Stubs.swift` holds the two `SeekEngineTuning` constants that `HonorTuning.swift` reads, with `SeekEngine.swift:27-28@7c200bf`'s values.
- `capture/Corpus.swift` defines the corpus: every Way and every trace, drawn in local metres around a round-number origin, with seeded integer-only noise.
- `capture/main.swift` is the harness. It writes the corpus, drives the engine, writes the event streams and the distance pairs, and checks the distance model.
- `corpus/<trace>/way.json` is the Way, encoded as `WayStore` encodes one (`.iso8601`, `[.sortedKeys]`: U13's format). `corpus/<trace>/trace.json` holds the inputs, one per line.
- `expected/<trace>.jsonl` holds what iOS did, one record per input. `expected/cl-distance-pairs.txt` holds `GeoDistanceTest`'s literals.

## The capture

| | |
|---|---|
| Pin | `pilgrim-ios` `v2.0.0` = `7c200bf` |
| Captured | 2026-09-29, `TZ=UTC` |
| macOS | 26.7.1 (25G241) |
| Swift | 6.3.3 (swiftlang-6.3.3.1.3), `swiftc -O`, Swift 5 language mode |
| Cross-check | iOS 26.5 simulator (23F77, iPhone 17 Pro). The same harness, built for `arm64-apple-ios26.0-simulator` and run with `xcrun simctl spawn`, loads the runtime's own `CoreLocation.framework` and writes byte-identical `corpus/` and `expected/` |

To reproduce, run `capture/capture.sh`. It needs macOS with `swiftc` and a pilgrim-ios checkout, by default this repository's sibling (set `IOS_REPO` otherwise). It rewrites every file under `corpus/` and `expected/`. A clean `git status` afterwards means the capture reproduced byte for byte. The run takes about 10 s.

To cross-check on a simulator, build the same six fetched files plus `capture/*.swift` with `swiftc -O -target arm64-apple-ios26.0-simulator -sdk "$(xcrun --sdk iphonesimulator --show-sdk-path)"`, boot a device, and run `xcrun simctl spawn <device> <binary> corpus|capture|pairs|cache` with the same arguments. Then diff the output.

Recapture whenever the parity pin moves. A changed `expected/` means iOS changed its engine. A changed `corpus/` means the generator changed. Only synthetic data may go into the corpus. A real walk would commit someone's home, and their route to it, into git history permanently.

### How the harness drives the engine

The harness follows `ActiveWalkViewModel+Honor.swift@7c200bf` (`startHonorEngineIfNeeded`, `handleHonorEvent`, `startVoice`, `onFinished`, `skipVoice`):

- **Through the real `bind`.** The engine gets `PassthroughSubject`s for the fixes, the engine clock, and the four gates, and its events are sunk after `bind`, as in the view model. After every input the harness drains the main queue, so each of the engine's `receive(on: DispatchQueue.main)` hops runs in delivery order.
- **Begin.** `bind` subscribes to `@Published` values that replay on subscribe, in this order: `$currentLocation` (the last pre-Begin fix), `$activeDurationSeconds` (0), and the gates, where `paused` still reads `status != .recording`. That is true, because the status reaches the view model through a main-queue hop. So every trace starts with four inputs: the fix, a tick of 0, the gates with `paused: true`, and then `paused: false` when the status hop lands. A tick of 0.02 follows, because stamping the start date makes the clock's `combineLatest` emit.
- **The engine clock.** The view model's 1 Hz main-run-loop timer ticks here at a fixed phase, 0.4 s past each second. Its value is walk time minus pauses, and the value holds through a pause (Android's pause, owner decision 1; iOS has no reachable pause). A finished recording changes the `voiceRecordings` list, which ticks the clock once more; `stationary-voice-35n` has that extra tick.
- **`now()`** is injected, as `HonorEngineTests` does. For every input it is the input's own time, `t0 + t`, where `t0` is 2026-05-01T08:00:00Z. Fixes land on whole even seconds, so every wall-clock interval is exact on both platforms: `Date` holds whole seconds exactly, and Android's clock is milliseconds.
- **`voiceDidFinish()`** is called directly, as the view model calls it. A `finish` input is the player ending or a skip, and the harness fails if nothing is playing then. A voice listed in `missingMedia` has no file, so the event sink hands the turn straight back from inside `.voiceStart`, as `startVoice` does.
- **Fixes** become `CLLocation(coordinate:altitude:horizontalAccuracy:verticalAccuracy:course:speed:timestamp:)`, as `honorLocationFixes` builds them. A missing accuracy or speed is iOS's `-1`. Android sees `null` for these, and `-1` where the trace says `-1`. The trace feeds the engine directly, as parity spec B resolution 3 asks, so the route filter's 10/20 m differences never enter. Before each fix, the harness also runs `LocationManagement`'s route-distance call (`LocationManagement.swift:291@7c200bf`, the new fix measured from the previous one), so CoreLocation's cache follows the walker as it does in the app.
- **Private state** (`softTapArmed`/`softTapSince`, the debounce count, `offWaySince`, `lastReacquireAttempt`, the tracker's `queue`) is read with `Mirror` on iOS and with reflection in the Android test. Nothing in either engine was changed to expose it.
- **Distance calls.** `-[CLLocation distanceFromLocation:]` is swizzled so the harness can see each call the engine makes. The value still comes from Apple's implementation. Each trace runs twice: once with CoreLocation's cache as the run leaves it (`cl`), and once with a far-away measurement before every call, which makes CoreLocation recompute its radii for the pair (`fresh`, the value with no history). The two runs must produce identical events and states, or the harness stops: a difference would mean CoreLocation's cache decided something.

### Record format

A `trace.json` input has a `kind` (`fix`, `tick`, `gates`, `gate`, or `finish`) and a time `t` in seconds since `t0`. A fix adds `lat`, `lon`, `accuracy`, and `speed`, where absent means none. A tick adds `activeSeconds`. `gates` carries all four gates. A `gate` input carries one `gate` and its `value`.

An `expected/*.jsonl` record has an input index `i` and a `kind`. A fix also has `fix`, its ordinal among the fixes, dropped ones included. A tick has only `companionFrac`. Every other record has `events` (with `id`, `offWayMeters`, `theirSeconds`, and `yourSeconds` where they apply) and `state`:

- the published values: `progressFrac`, `distanceRemainingMeters`, `offWayMeters`, `isOnWay`, `companionFrac`, `phase`, `startFrac`, `companionT0`, `distanceWalkedMeters`, and `isAnchoredOnWay`;
- `softTap`: `armed`, `timing`, or `disarmed`;
- `arrivalCount`;
- `offWaySince` and `lastReacquireAttempt`, in seconds since `t0`;
- the tracker's `playing`, `voicePaused`, and `queue`.

A fix record also has `calls`. Each call has a `target` (`end`, or a moment id), the place measured to (`to`), and the `cl` and `fresh` values.

## The corpus

| Trace | Where | What it proves (fix indices from the capture) |
|---|---|---|
| `straight-42n` | 42.88°N, 8.54°W; 822 m with a 4 min rest plateau | Every moment kind. The replayed pre-Begin fix starts the trailhead voice (fix 0) on the engine's default open gates; bind's `paused: true` pauses it and the status hop resumes it. The waypoint (fix 48) and photo (fix 100) are 20 and 35 m off the line. `voice-2` has no `at`, so it is placed by its frac, and starts at fix 142. The rest (fix 182) and sitting (fix 229) follow, then `voice-3` at fix 266. Arrival comes at fix 313: their 926.0 s, your 625.4 s. |
| `loop-long-0n` | on the equator, 32.61°E; a 1,072 m loop whose ends coincide, crossing 0° | AE2. Nine fixes standing at the trailhead never count toward arrival. Walking the loop arrives exactly once (fix 398). With voices off, the voice moments are reached silently; the photo (fix 65) and waypoint (fix 248) are not silent. |
| `loop-short-60n` | 60.17°N; a 280 m square loop | pilgrim-ios #100, as shipped. The walker stands 3 m north of the start on the closing side. The anchor lands on the first side (frac 0.002), and the same fix tracks onto the closing leg (0.988). Arrival comes at fix 2, three fixes after Begin: their 232.5 s, your 3.4 s. Afterwards tracking and moments go on, with the waypoint at fix 37 and the photo at fix 68. |
| `out-and-back-33s` | 33.87°S, 151.21°E; 693 m out and back on shared pavement, 2 m apart | Begin from a car park 220 m away takes the fallback anchor, so the companion waits. The soft tap stays silent for 130 s beyond 200 m. Re-acquire fails at 120 s and every 10 s after. The join re-anchors, and the approach through the end zone at progress 0 does not arrive. The return leg's voice passes the outbound leg (frac gate) and starts on the return (fix 318). Arrival comes at the start (fix 387). |
| `detour-reacquire-47n` | 47.6°N, 122.33°W; 1,007 m, the original walker at 0.8 m/s | A voice waits behind a whisper (`externalAudio`) and is dropped 300 m away (fix 230). The soft tap's timer starts, is cleared by a dip under 200 m, and restarts. It fires once, at 293.8 m (fix 290), and re-arms on the rejoin. Re-acquire is first tried exactly 120 s after the walker left the Way, then every 10 s. A bus ride rejoins 461 m ahead, outside the window; the re-acquire credits only 424 m of it, the Way's own pace over the 530 s spent off it. Arrival at fix 472. |
| `blackout-42n` | 42.87°N, 8.50°W; 563 m | Fixes over 50 m, at 50.5 m, at −1, and missing never reach the engine, so a photo passed during the blackout is never reached. Fixes at exactly 50 m do reach it (`<=`). In the last 30 m the arrival count runs 1, 2, then a 36 m multipath jump resets it to 0, then 1; two bad fixes neither advance nor reset it; then 2, 3, arrived (fix 228). |
| `stationary-voice-35n` | 35.0°N, 135.77°E; 799 m | Two voices queue while the walker records a reply. The walker then stands at the second while the fixes drift. Four stationary fixes (speed under 0.4 m/s) up to 301.0 m from the first voice keep it waiting, because a stationary walker never drops one. One fix with unknown speed, 301.6 m away, drops it (fix 229). When the reply ends, the second voice plays. The third voice's file is missing, so it hands the turn straight back. |
| `pause-mid-voice-51n` | 51.5°N, 0.12°W; 713 m | A pause mid-voice pauses it. The engine clock holds, so the companion freezes. A waypoint still appears (fix 70) and a second voice queues. Resuming the walk resumes the voice. A repeated `paused: false` emits nothing. In a second pause the walker walks 300 m away and the paused voice is dropped (fix 258). Arrival counts none of the paused time: your 301.4 s. |
| `sit-mid-voice-64n` | 64.14°N, 21.94°W; 543 m with a 5 min sitting plateau | A sitting mid-voice pauses it, and a second voice queues. The engine clock and the companion keep running. One fix leaning 22 m ahead reaches a photo during the sitting (fix 153). The voice resumes when the sitting ends. Arrival counts the sitting: your 715.4 s. |

Latitudes run from 33.87°S through the equator to 64.14°N, so the distance pin is exercised where `tan φ` is near 0 and above 2.

## What `CLLocation.distance` computes

Measured on macOS 26.7.1 and the iOS 26.5 simulator, which agree bit for bit. `harness cache` checks the model below against CoreLocation over a seeded random walk of 200,000 calls, 1 to 3,000 m apart at |φ| ≤ 70°. The largest relative difference is 6.2e-16.

1. **The formula.** It uses the WGS84 radii of curvature at the pair's mean latitude φ: `d = hypot(M·Δφ, N·cos φ·Δλ)`. Here `M = a(1 − e²)/w^1.5`, `N = a/√w`, `w = 1 − e² sin² φ`, `a = 6,378,137 m`, and `f = 1/298.257223563`. Longitudes are first taken into [0°, 360°), then the difference is wrapped into ±180°. The `+ 360` rounds, which is visible in the ninth digit for western pairs. This holds to two ulps for pairs up to 150 km apart. Past about 200 km CoreLocation switches to another formula, which no Honor threshold can reach.
2. **The cache.** CoreLocation keeps the radii and `cos φ` it last computed, together with that pair's mean latitude. It reuses them for any later pair whose receiver (the `self` of `a.distance(from: b)`) is within 0.005° of latitude of the stored one, wherever the other point is. So iOS's value for a pair depends on the calls before it, anywhere in the process: `LocationManagement`, Seek, and the UI all call `distance`. The stale latitude is under 0.005° from the receiver, and so under `0.005° + |Δφ|/2` from the pair's mean. That moves the value by at most `d · (|tan φ| + 0.01) · (0.005° + |Δφ|/2)` in radians. At 42.88°N that bound is 2.5 mm at 30 m, 3.6 mm at 42 m, 5.2 mm at 60 m, and 3.1 cm at 300 m. Over the random walk, cached values differed from uncached ones by up to 2.7e-4 relative.

Android pins the stateless formula as `wgs84MidLatitudeMeters` in `domain/GeoDistance.kt`, the default `HonorDistance` of `HonorEngine` and `HonorMomentTracker`. It is iOS's value whenever CoreLocation recomputes the radii for the pair. No stateless function can follow iOS's cache, since that would mean replaying every `distance` call iOS makes. Haversine at 6,371 km, Seek's stand-in, is up to 0.56 % off: 23 cm at 42 m.

### Divergence over the corpus

Measured over all 978 distance calls the engines make at D1–D4 (`HonorGoldenTraceTest` asserts each bound):

| Compared | Largest difference |
|---|---|
| Android's pin vs `fresh` (CoreLocation, no history) | 1.1e-13 m, 3.9e-16 relative (test bound 1e-14 relative) |
| Android's pin vs `cl` (what iOS's engine got in the harness run) | 7.5 mm, at 51.5°N (`pause-mid-voice-51n`); at most 0.52 of the cache bound |
| Haversine vs `fresh` (for scale) | 0.82 m, 0.56 % |

| Trace | Distance calls | Largest cache effect | Closest call to a threshold | Its cache bound |
|---|---|---|---|---|
| `straight-42n` | 34 | 1.0 mm | `voice-3` at 41.445 m (fix 266) | 24.8 mm |
| `loop-long-0n` | 45 | 0.0 mm | `voice-2` at 42.530 m (fix 327) | 0.3 mm |
| `loop-short-60n` | 110 | 1.4 mm | `waypoint-1` at 59.342 m (fix 37) | 9.7 mm |
| `out-and-back-33s` | 48 | 2.6 mm | `photo-1` at 58.734 m (fix 245) | 3.6 mm |
| `detour-reacquire-47n` | 209 | 2.9 mm | `voice-1` at 42.232 m (fix 57) | 29.1 mm |
| `blackout-42n` | 154 | 1.9 mm | `voice-1` at 23.102 m (fix 32) | 24.6 mm |
| `stationary-voice-35n` | 173 | 3.4 mm | `voice-1` at 40.820 m (fix 63) | 19.0 mm |
| `pause-mid-voice-51n` | 187 | 7.5 mm | `voice-2` at 42.774 m (fix 163) | 33.9 mm |
| `sit-mid-voice-64n` | 18 | 3.0 mm | `end` at 29.159 m (fix 356) | 5.4 mm |

For a voice, the bound is taken at 300 m, whichever of its two thresholds is nearer, so it is conservative. The closest approach in the corpus is 23 cm from the 42 m radius, eight times the bound.

## Allowances

There are none. A threshold crossing could come out differently on iOS and Android only if CoreLocation's value with no history lay within the cache bound of the threshold. The test asserts, for every call in every trace, that the margin to each threshold the call decides is larger than that bound. So each of these event streams is what iOS produces whatever its cache holds, and Android reproduces it exactly. A corpus change that breaks this must move the fix, not loosen the check.

The test's other tolerances cover only last-bit rounding between Apple's libm and the JVM's `sin`, `cos`, `atan2`, and `hypot`. The largest differences measured were 2.2e-16 in a frac, 2.3e-13 m, and 3.3e-16 s. The test allows 1e-12, 1e-9 m, and 1e-9 s. A behavioral difference is orders of magnitude larger: a centimetre along a 1 km Way is 1e-5.

## Found along the way

- **`CLLocation.distance` caches by latitude** (above). Parity spec B, open question 1, did not know the formula. iOS's own decisions at the 30/42/60/300 m thresholds depend, to within the bound, on unrelated earlier `distance` calls.
- **The Begin fix is processed on the engine's default open gates.** `bind` delivers the replayed pre-Begin fix before the gates, and the gates arrive with `paused: true`, because the status has not reached the view model yet. So a voice within 42 m of Begin starts, is paused, and is resumed, all within the first main-queue turn (`straight-42n`, inputs 0–3). Parity spec B §2.4 says only that "the gates start closed and open a moment later". U17's session keeps this order if it feeds the Begin fix before the initial gates.
- **After a short loop arrives at Begin**, the tracking window stays at the closing leg. The walker who then walks the loop is off the Way for most of it until they come back round (`loop-short-60n`), so the glance reads "off the way" after arrival. This belongs to pilgrim-ios #100.
