# Pilgrim Android — Claude Code Notes

Native Kotlin + Jetpack Compose port of `../pilgrim-ios`. See `/Users/rubberduck/.claude/plans/so-we-want-to-merry-scroll.md` for the phased port plan.

## Project facts

- **Application ID**: `org.walktalkmeditate.pilgrim` (same namespace as iOS).
- **License**: GPL-3.0-or-later. **No OutRun mention anywhere** — this is framed as a fresh project, not a fork, per direct user instruction.
- **Copyright**: `Walk Talk Meditate contributors`.
- **Starting version**: 0.1.0. Do not mirror iOS version numbers.
- **Min SDK**: 28. **Target SDK**: 36. **Java toolchain**: 17.

## Parity scope (frozen)

**Parity target: pilgrim-ios main @ `c1745e8`** (2026-07-14; post-v1.8.0, includes the 1.8.1-in-review journal scenery). Anything iOS shipped AT OR BEFORE that commit is in-scope for Android port. Anything iOS ships AFTER `c1745e8` is OUT OF SCOPE — with one bounded exception: deltas landing before Android v1.2.0 ships are re-diffed and triaged per the fold-in rule (R2 in `docs/brainstorms/2026-07-14-ios-main-parity-retarget-requirements.md`): chores, hotfixes, and incremental refinements to Seek Mode / journal scenery fold into the owning phase; new headline features, reverts of ported work, or redesigns require explicit user re-triage.

To diff iOS for in-scope work that hasn't landed on Android yet:
```bash
cd ../pilgrim-ios && git log --oneline c1745e8
```

Comparing to a future iOS HEAD past `c1745e8` is fine for context, but parity work targets `c1745e8` only.

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
- Share worker: `https://walk.pilgrimapp.org/share/*`
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
- Foreground service with `START_STICKY` and `ongoing notification` that updates live stats.
- Battery-optimization exemption request flow with clear "why" copy.
- Structured concurrency scoped to the service's lifecycle.
- Flush writes to Room on every significant sample, not only on walk finish.
- Audio session cleanup paths must be exhaustive.

## Dev environment notes

- Android SDK at `~/Library/Android/sdk` (platforms 34/35/36 installed).
- JDK 17 via asdf (`temurin-17.0.18+8`). If `./gradlew` fails on Java version, run `export PATH="$HOME/.asdf/shims:$PATH"`.
- Android Studio is not in `/Applications`. User may be working in VS Code; adjust tooling accordingly.

## Phasing — current state

Phases 0-15 shipped: 0-13 through Stage 13-XZ (PR #83) + v1.7.0 parity sweep (PRs #177–#193); **Phase 14 (Seek Mode) + Phase 15 (Journal Scenery)** landed 2026-07-16 (PRs #198/#199, plan `docs/plans/2026-07-14-001-feat-seek-mode-journal-scenery-plan.md`), released as v1.2.0. The R2 re-diff at release time found iOS main unmoved from `c1745e8` — parity is exact. Next up: Phase N future items (Health Connect, App Actions, screenshot tests). See the port plan + autopilot memory entries for stage-level history.
