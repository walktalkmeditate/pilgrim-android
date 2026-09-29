# Pilgrim Android — Claude Code Notes

Native Kotlin + Jetpack Compose port of `../pilgrim-ios`. See `/Users/rubberduck/.claude/plans/so-we-want-to-merry-scroll.md` for the phased port plan.

## Project facts

- **Application ID**: `org.walktalkmeditate.pilgrim` (same namespace as iOS).
- **License**: GPL-3.0-or-later. **No OutRun mention anywhere** — this is framed as a fresh project, not a fork, per direct user instruction.
- **Copyright**: `Walk Talk Meditate contributors`.
- **Versioning**: Android's major.minor tracks the iOS release it is at parity with (Android 2.0.0 is iOS v2.0.0 parity); patch numbers are per-platform, so an Android-only hotfix is 2.0.1 whatever iOS does. versionCode follows the release pipeline (commit count). Releases 0.1.0 through 1.5.x predate this rule.
- **Min SDK**: 28. **Target SDK**: 36. **Java toolchain**: 17.

## Parity scope (frozen)

**Parity target: pilgrim-ios `v2.0.0` @ `7c200bf`** (2026-09-16, the Honor release). Phase 21 retargeted here from `33b0dbc`, the Android v1.5.0 anchor (iOS later re-pointed its `v1.11.0` tag to `bcdf538`, which is `33b0dbc` plus a build bump). iOS closed `pilgrim-ios` #80 (`intentionEcho`) on 2026-09-16 without a code change, so it stays unfixed on both platforms and Android stays parity-exact. Anything iOS shipped AT OR BEFORE `7c200bf` is in-scope for Android port. Anything iOS ships AFTER `7c200bf` is OUT OF SCOPE — with one bounded exception, the fold-in rule (R2 in `docs/brainstorms/2026-09-28-ios-v200-parity-retarget-requirements.md`): iOS deltas landing before Android 2.0.0 ships are re-diffed and triaged. Chores, hotfixes, and incremental refinements to Honor or any surface this release touches fold into Phase 21; new headline features, reverts of ported work, or redesigns require explicit user re-triage. The fold-in window closes when the parity gate (R24) starts; later iOS deltas are triaged the same way into Android 2.0.1, so the gate runs once against a fixed pin.

To list in-scope iOS history, and the post-pin deltas the fold-in rule triages:
```bash
cd ../pilgrim-ios && git log --oneline 7c200bf
cd ../pilgrim-ios && git fetch && git log --oneline 7c200bf..origin/main
```

Comparing to a future iOS HEAD past `7c200bf` is fine for context, but parity work targets `7c200bf` only.

## Architecture

- Jetpack Compose (Material 3 base, heavily themed).
- MVVM with `ViewModel` + `StateFlow` (direct Combine/`@Published` analogue).
- Hilt for DI.
- Room for persistence — **own migration chain (schema v8+, manual `MIGRATION_N_N+1` pattern); no schema import from iOS**.
- DataStore (Preferences) for settings.
- Navigation Compose, single Activity.
- Foreground service for long walks (`foregroundServiceType="location"`).

## iOS ↔ Android mapping quick-ref

| iOS | Android |
|---|---|
| SwiftUI | Jetpack Compose |
| Combine `@Published` | `StateFlow` |
| CoreData + CoreStore | Room (fresh schema) |
| WhisperKit | `whisper.cpp` via JNI |
| MapKit → Mapbox | Mapbox Android SDK |
| WeatherKit | Open-Meteo |
| Vision | ML Kit |
| Live Activities / Dynamic Island | Foreground-service notification |
| WidgetKit | Jetpack Glance |
| CoreHaptics | `VibrationEffect.Composition` |
| AVFoundation | AudioRecord + ExoPlayer |
| Photos framework | MediaStore |
| UserDefaults | DataStore Preferences |
| Keychain | EncryptedSharedPreferences (likely unused) |

## Backend URLs (reuse iOS endpoints, no changes)

- Collective counter: `https://walk.pilgrimapp.org/api/counter`
- Share worker: `https://walk.pilgrimapp.org` — `POST /api/share`, `PUT /api/share/{id}/{photos|audio}/{n}`, pages served at `/{id}`
- Voice guide / sound manifests: `https://pilgrimapp.org/*`

## Conventions

- Package root: `org.walktalkmeditate.pilgrim`.
- File-level license: SPDX header only — `// SPDX-License-Identifier: GPL-3.0-or-later`. No per-file copyright block; copyright lives in `LICENSE` and `README`.
- No OutRun references in code, comments, commit messages, tests, or docs.
- Prefer `StateFlow` / `SharedFlow` over `LiveData`.
- Prefer Coroutines + Flow over RxJava.
- Prefer Compose over XML views (new screens).
- Tests: JUnit 4 + Turbine for Flow assertions + Robolectric where a real Android runtime matters. (Switch to JUnit 5 later if the harness investment pays off.)
- **Platform-object builder tests**: any PR that constructs `WorkRequest`, `AudioFocusRequest`, `Intent`, `NotificationChannel`, `MediaItem`, or other platform objects with runtime-validated builders MUST include at least one Robolectric test that calls `.build()` on the production class. Faking the surrounding scheduler/coordinator is still correct for testing callers, but the builder path itself needs real-world exercise — otherwise runtime rejection only manifests on-device. Precedent: `WorkManagerTranscriptionSchedulerTest` (caught the `Expedited + BatteryNotLow` crash that shipped through 6 review cycles + Stages 2-D and 2-E because every unit test used `FakeTranscriptionScheduler`).
- Commit style: same as iOS (`feat:`, `fix:`, `chore:`, `docs:` scope prefixes).

## Long-session reliability

The hardest part of this app is surviving a 45-90 minute walk with screen off, battery saver on, and the device in a backpack. Design the tracking pipeline with explicit teardown:
- Foreground service returning `START_REDELIVER_INTENT` (after a kill every delivered start replays, and the service never calls `stopSelf(startId)`), with an ongoing notification that updates live stats.
- Battery-optimization exemption request flow with clear "why" copy.
- Structured concurrency scoped to the service's lifecycle.
- Flush writes to Room on every significant sample, not only on walk finish.
- Audio session cleanup paths must be exhaustive.

## Dev environment notes

- Android SDK at `~/Library/Android/sdk` (platforms 34/35/36 installed).
- JDK 17 via asdf (`temurin-17.0.18+8`). If `./gradlew` fails on Java version, run `export PATH="$HOME/.asdf/shims:$PATH"`.
- Android Studio is not in `/Applications`. User may be working in VS Code; adjust tooling accordingly.

## Phasing — current state

Phases 0-15 shipped: 0-13 through Stage 13-XZ (PR #83) + v1.7.0 parity sweep (PRs #177–#193); **Phase 14 (Seek Mode) + Phase 15 (Journal Scenery)** landed 2026-07-16 (PRs #198/#199, plan `docs/plans/2026-07-14-001-feat-seek-mode-journal-scenery-plan.md`), released as v1.2.0 at exact parity with `c1745e8`. **Phases 16-18 (v1.9.0 parity) + the clearing-glyph fold-in shipped 2026-07-30** as v1.3.0 (code 590, PRs #203-#210, plan `docs/plans/2026-07-23-001-feat-ios-v190-parity-port-plan.md`) at parity with `b4decad`. **Phase 19 (Walk with Me interactive share, iOS v1.10.0 parity) shipped 2026-08-19** as v1.4.0 (code 650, PR #215 + active-walk map parity PR #227, plan `docs/plans/2026-08-14-001-feat-walk-with-me-interactive-share-plan.md`) at exact parity with `2ee1185` = the shipped iOS `v1.10.0` tag. See the port plan + autopilot memory entries for stage-level history. **Phase 20 (Thought Threads, iOS v1.11.0 parity) shipped 2026-08-31** as v1.5.0 (code 733, PR #229 plus #230–#232, plan `docs/plans/2026-08-25-001-feat-thought-threads-port-plan.md`) at parity with `33b0dbc`, reached through two pre-release fold-in hops (iOS PRs #72/#74, then #79). **Phase 21 (Honor, iOS v2.0.0 parity, target Android 2.0.0) is IN PROGRESS** per plan `docs/plans/2026-09-29-001-feat-honor-groundwork-own-shared-walks-plan.md` + requirements `docs/brainstorms/2026-09-28-ios-v200-parity-retarget-requirements.md`: Stage 21-0 (groundwork) ships first as 1.5.1; Stage 21-1 (Honor on your own and shared walks, Seek onto `:tracker`) lands on main behind the release flag; Stages 21-2 (pilgrimages), 21-3 (offline maps), and the parity gate + 2.0.0 release each get their own plan.
