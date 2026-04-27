# Stage 10: Settings parity with iOS — design spec

**Status:** Draft → CHECKPOINT 1
**Date:** 2026-04-27
**Author:** autopilot (Claude Opus 4.7)
**Predecessor:** Stage 9.5-E (PR #62, merged 2026-04-27 as `10178d4`)
**iOS reference:** `pilgrim-ios/Pilgrim/Scenes/Settings/` (~3,257 LoC across 13 files)

## Goal

Bring the Android Settings tab to **exact functional and visual parity** with the iOS counterpart: a fully card-based layout (Practice / Atmosphere / Voice / Permissions / Data / Connect / About) with a personal-stats summary header, every iOS preference toggle ported with matching defaults, every iOS-side sub-screen (Recordings list, About, Feedback, Sound settings, Voice guide settings, Data export/import) rebuilt in Compose.

Stage 9.5-E landed the `AppearanceMode` foundation. Stage 10 extends that pattern to the rest of Settings.

## Why now

User explicit ask after PR #62 review: "have synced exactly how the ios settings screen looks like to ours? … triple check that everything is exactly alike and if not, make it so please." The 9.5-E spec deliberately deferred this work because the scope is enormous. Stage 10 captures it as a multi-PR initiative under one master spec, with each card landing as its own sub-stage.

## iOS reference inventory

| iOS file | LoC | Maps to |
|---|---:|---|
| `SettingsView.swift` | 163 | Stage 10-A scaffold |
| `PracticeSummaryHeader.swift` | 161 | Stage 10-A header |
| `SettingsCards/SettingsCardStyle.swift` | 91 | Stage 10-A shared `settingsCard` modifier + `cardHeader` / `settingToggle` / `settingPicker` / `settingNavRow` builders |
| `SettingsCards/PracticeCard.swift` | 122 | Stage 10-C |
| `SettingsCards/AtmosphereCard.swift` | 45 | Stage 10-B (extend the 9.5-E partial) |
| `SettingsCards/VoiceCard.swift` | 114 | Stage 10-D |
| `SettingsCards/PermissionsCard.swift` | 96 | Stage 10-E |
| `SettingsCards/DataCard.swift` | 17 | Stage 10-G (entry only) |
| `SettingsCards/ConnectCard.swift` | 75 | Stage 10-F |
| `SoundSettingsView.swift` | 502 | Stage 10-B sub-screen |
| `VoiceGuideSettingsView.swift` | 222 | already exists on Android (Stage 5-D) |
| `RecordingsListView.swift` | 521 | Stage 10-D sub-screen |
| `DataSettingsView.swift` + `ExportConfirmationSheet.swift` + `ExportDateRangeFormatter.swift` | 674 | Stage 10-G (export/import, full) |
| `JourneyViewerView.swift` | 235 | Stage 10-G companion (re-import preview) |
| `FeedbackView.swift` | 239 | Stage 10-F sub-screen |
| `AboutView.swift` | 453 | Stage 10-H |
| `PermissionStatusViewModel.swift` | 87 | Stage 10-E (port to PermissionsRepository observation) |
| **Total** | **3,257** | |

## Non-goals

- **Whispers** (`autoPlayWhisperOnProximity` + nearby-whisper playback). Whispers are an iOS-only Phase 9.5-F+ feature; Android has no whisper system yet. Toggle and supporting infrastructure deferred to whatever stage introduces whispers on Android.
- **Podcast intake** (`podcastConsentGiven` + Pilgrim-on-the-Path SafariView). The iOS podcast flow uses `SFSafariViewController`; Android equivalent is `CustomTabsClient`. Defer until the user asks for it; the iOS version sees minimal use.
- **WhisperKit-specific transcription progress UI**. iOS shows `TranscriptionService.state.downloadingModel(progress)` from WhisperKit's model download. Android already ships `ggml-tiny.en.bin` (~39 MB) bundled in APK assets and `WhisperModelInstaller` copies it to `filesDir` on first call (no network). Verified at `app/src/main/assets/models/ggml-tiny.en.bin`. The auto-transcribe toggle still ports; the model-download progress UI is genuinely N/A on Android.
- **Dynamic Voice toggle** (`dynamicVoiceEnabled`). iOS uses Apple's audio-enhancement API to clean recordings post-capture. Android `AudioRecord` exposes `NoiseSuppressor` / `AcousticEchoCanceler` / `AutomaticGainControl` effects, but they apply at capture time and have unreliable cross-OEM behavior (Pixel works; many Samsung devices silently no-op). Surfacing a toggle whose audible effect is OEM-dependent is worse than not surfacing it. Decision: omit from VoiceCard. If the user later validates the AcousticEchoCanceler path on a real device matrix, revisit.
- **iCloud sync UI**. iOS doesn't have one either. Skip.
- **`voiceGuideVolume`, `voiceGuideDuckLevel`** as user-facing prefs. These exist on iOS as internal constants (no UI surface). Keep as Android constants in `VoiceGuideOrchestrator`. **NOT** to be confused with `bellVolume` / `soundscapeVolume`, which ARE user-visible inside iOS `SoundSettingsView` and ARE in scope for Stage 10-B (see correction in Architecture).
- **`name`, `weight`, `gpsAccuracy`, `shouldShowMap`, `lastSeenCollectiveWalks`, `lastPodcastSubmissionDate`** prefs. iOS keeps these for legacy reasons; Android doesn't need them.
- **App icon picker in AboutView**. iOS uses `UIApplication.shared.setAlternateIconName(...)` to swap between Default / Dark / per-voice-guide icon variants. Android dynamic-icon swap is launcher-dependent (`PackageManager.setComponentEnabledSetting` on activity-aliases) — works on stock launchers, unreliable on many OEM launchers, and can cause home-screen widget loss. Decision: omit on Android. Document in Stage 10-H Non-goals.

## Architecture overview

### Shared infrastructure (Stage 10-A)

1. **`SettingsCardStyle.kt`** — a Compose `Modifier` extension `settingsCard()` matching iOS's `.settingsCard()` exactly. iOS source (`SettingsCardStyle.swift`):
   ```swift
   .padding(Constants.UI.Padding.normal)        // 16dp
   .background(Color.parchmentSecondary)
   .cornerRadius(Constants.UI.CornerRadius.normal)  // 16dp
   ```
   No border. Stage 10-A's shared modifier is `Modifier.clip(RoundedCornerShape(16.dp)).background(pilgrimColors.parchmentSecondary).padding(16.dp)`. **9.5-E's AtmosphereCard added a `border(1.dp, fog × 0.2f, ...)` that does NOT exist on iOS — it was an Android-specific design choice during the initial port.** Stage 10-A drops this border when refactoring AtmosphereCard onto the shared modifier so all 6+ cards match iOS visually. Capture this as an explicit migration line in 10-A's plan.

2. **Shared row builders**:
   - `CardHeader(title: String, subtitle: String)` composable — title in `pilgrimType.heading` ink, subtitle in `pilgrimType.caption` fog. 2dp internal spacing, 8dp bottom padding.
   - `SettingToggle(label, description, checked, onCheckedChange)` — row with label/desc on left, M3 `Switch` on right. Switch uses `stone` thumb when on, `fog` when off (matching iOS `.tint(.stone)`).
   - `SettingPicker(label, options, selected, onSelect)` — row with label on left, segmented row on right, max width 180dp on the picker. Segmented styling matches `AtmosphereCard`'s.
   - `SettingNavRow(label, detail = null, leadingIcon = null, external = false, onClick)` — row with optional leading icon, label, optional detail caption, trailing chevron-right OR `arrow.up.right` when `external = true`. Uses `pilgrimColors.fog` for chevron/detail; leading icon tinted `stone`, 24dp aligned. The leading-icon param is needed for ConnectCard (waveform / pencil.line / heart / square.and.arrow.up icons per iOS `connectRow`).
   - `Divider()` — uses `pilgrimColors.fog.copy(alpha = 0.2f)`, 1px thickness, edge-to-edge of the card content (matching iOS).

3. **`SettingsScreen.kt`** restructured: drop the M3 `Scaffold` top bar entirely (iOS uses no nav bar — the title is centered inline at the top of the scroll content). Replace with a centered "Settings" title styled like the iOS `.toolbar`. Vertical stack of `LazyColumn` (so card-entrance staggered animations work cleanly). Horizontal padding 16dp.

   **Navigation pattern (decision committed in 10-A):** the Settings tab will accumulate ~10 nav destinations across the cards (Bells & Soundscapes, Voice Guides, Recordings, Permissions actions, Export & Import, Journey Viewer, Feedback, About, Pilgrim on the Path, Rate, Share). A 10-parameter callback signature on `SettingsScreen` is unmaintainable. Stage 10-A introduces a sealed `SettingsAction` class:
   ```kotlin
   sealed interface SettingsAction {
       data object OpenBellsAndSoundscapes : SettingsAction
       data object OpenVoiceGuides : SettingsAction
       data object OpenRecordings : SettingsAction
       data class GrantPermission(val permission: PilgrimPermission) : SettingsAction
       data object OpenAppPermissionSettings : SettingsAction
       data object OpenExportImport : SettingsAction
       data object OpenJourneyViewer : SettingsAction
       data object OpenFeedback : SettingsAction
       data object OpenAbout : SettingsAction
       data object OpenPodcast : SettingsAction
       data object OpenPlayStoreReview : SettingsAction
       data object SharePilgrim : SettingsAction
   }
   ```
   `SettingsScreen` takes `onAction: (SettingsAction) -> Unit`. The composable surface in `PilgrimNavHost` maps each action to the appropriate `navController.navigate(...)` call or system-intent invocation. This pattern is mandatory for every card added in Stages 10-B through 10-H.

4. **Card entrance animations** — staggered 0.1s delays per card, fade + 20dp slide-up. Compose: `AnimatedVisibility` driven by a `hasAppeared` state that flips after first `LaunchedEffect(Unit)`. Honor `LocalAccessibilityManager.current.isReducedMotionEnabled` if available, else default to no-animation in tests.

5. **Pull-to-reveal tagline** — "Every walk is a small pilgrimage." Shown when scroll offset > 40dp at the top of the scroll. Italic caption in fog. Compose port via `LazyListState.firstVisibleItemScrollOffset` reading.

### Preferences layer

Stage 10 introduces **17 new preferences** across the existing `pilgrim_prefs` DataStore. Each follows the Stage 9.5-E `AppearancePreferencesRepository` template (interface + `@Singleton` impl + Hilt `@Binds` + `Eagerly` StateFlow). Group them under one `UserPreferencesRepository` per domain to avoid module sprawl:

| Repo | Prefs | Defaults | Stage |
|---|---|---|---|
| `PracticePreferencesRepository` | `beginWithIntention: Boolean`, `celestialAwarenessEnabled: Boolean`, `zodiacSystem: ZodiacSystem (Tropical/Sidereal)`, `walkReliquaryEnabled: Boolean`, `hemisphereOverride: Int? (-1 / +1 / null)` | false / false / Tropical / false / null | 10-C (header reads hemisphere starting in 10-A; the pref is created in 10-A as a small foundational addition, populated by 10-C UI later) |
| `UnitsPreferencesRepository` | `distanceUnits: Units (Metric/Imperial)`, `altitudeUnits`, `speedUnits`, `weightUnits`, `energyUnits` (last four derived from `distanceUnits`) | Metric, all derived via single `applyUnitSystem(metric: Boolean)` matching iOS | 10-C |
| `SoundsPreferencesRepository` | `soundsEnabled: Boolean`, `bellHapticEnabled: Boolean`, `bellVolume: Float`, `soundscapeVolume: Float`, `walkStartBellId: String?`, `walkEndBellId: String?`, `meditationStartBellId: String?`, `meditationEndBellId: String?`, `selectedSoundscapeId: String?`, `breathRhythm: Int` | true / true / 0.7 / 0.4 / null × 5 / 0 | 10-B (all surfaced inside `SoundSettingsView`) |
| `VoicePreferencesRepository` | `voiceGuideEnabled: Boolean`, `meditationGuideEnabled: Boolean`, `autoTranscribe: Boolean` | false / true / false | 10-D |
| `CollectiveOptInRepository` | already exists as `CollectiveRepository.optIn` (= iOS `contributeToCollective`) | — | reuse |
| `VoiceGuideSelectionRepository` | already exists as `selectedVoiceGuidePackId` (Stage 5-D) | — | reuse |
| `AppearancePreferencesRepository` | `appearanceMode` (already exists from 9.5-E) | System | reuse |

**`bellHapticEnabled`, `bellVolume`, `soundscapeVolume` are user-visible** — iOS `SoundSettingsView` shows them as a toggle and two sliders. These are NOT in Non-goals; they are scoped to Stage 10-B's `SoundSettingsScreen` port.

**`hemisphereOverride` is not user-visible** in iOS Settings (it's set elsewhere in the app). Spec captures it because `PracticeSummaryHeader` reads it for the season label; Stage 10-A creates the DataStore key + repo, and Stage 10-C wires up the (still hidden) UI if needed.

**iOS-parity storage values** (every key below MUST match the iOS `UserDefaults` key string verbatim so `.pilgrim` ZIP exports round-trip cleanly with iOS):
- `appearanceMode`: `"system"` / `"light"` / `"dark"` ✓ (aligned in 9.5-E)
- `zodiacSystem`: `"tropical"` / `"sidereal"`
- `distanceMeasurementType`: `"kilometers"` / `"miles"` (use the EXACT iOS UserDefaults key — `distanceMeasurementType`, NOT `distanceUnits`)
- `walkStartBellId`, `walkEndBellId`, `meditationStartBellId`, `meditationEndBellId`: nullable strings — bell asset IDs from the bundled audio manifest. Keys must match iOS verbatim.
- `selectedSoundscapeId`: nullable string — soundscape asset ID. Key matches iOS.
- `selectedVoiceGuidePackId`: nullable string. Already aligned (Stage 5-D).
- `bellVolume`, `soundscapeVolume`: Float in [0.0, 1.0]. Keys match iOS verbatim.
- `bellHapticEnabled`, `soundsEnabled`, `voiceGuideEnabled`, `meditationGuideEnabled`, `autoTranscribe`, `beginWithIntention`, `celestialAwarenessEnabled`, `walkReliquaryEnabled`, `contributeToCollective`: Boolean. Keys match iOS verbatim.
- `breathRhythm`: Int (enum index). Key matches iOS.
- `hemisphereOverride`: Int? (`-1` / `1` / null). Key matches iOS.

**Migration concern (Stage 10-D — `autoTranscribe` introduction):** Android currently transcribes every recording automatically (Stage 2-D, no preference). iOS default is `autoTranscribe = false`. When 10-D wires the gate, existing Android users who upgraded would suddenly stop seeing transcripts because the new pref defaults to false, matching iOS. **Mitigation:** Stage 10-D MUST run a one-shot migration on first launch after upgrade — if the pref key is absent (fresh install OR pre-10-D upgrade), seed it to `true` for upgrades and `false` for fresh installs. Use the Android `package_install_time` ↔ DataStore-key-presence heuristic: if `firstLaunchVersion` shows a build older than 10-D's release tag and `autoTranscribe` key is absent, seed to `true`. Document in 10-D's plan as a dedicated migration task with a Robolectric test.

### Sub-screens

| Sub-screen | iOS LoC | Android stage | Notes |
|---|---:|---|---|
| `SoundSettingsView` | 502 | 10-B | Six sections per iOS: (1) `mainToggleSection` — `soundsEnabled` master toggle + `bellHapticEnabled` toggle; (2) `walkSection` — Start Bell + End Bell pickers (open `BellPickerSheet`); (3) `meditationSection` — Start Bell + End Bell pickers + Breath Rhythm picker; (4) `volumeSection` — Bell Volume slider + Soundscape Volume slider; (5) `storageSection` — bundled-vs-downloaded asset list with per-asset delete; (6) Soundscape picker. Reuses Stage 5-B `MeditationBellObserver`, Stage 5-F `SoundscapeOrchestrator`. New surface: per-event bell selection sheet, breath-rhythm picker sheet, volume sliders, asset storage management. |
| `RecordingsListView` | 521 | 10-D | Eight surfaces per iOS: per-walk section headers (tap → opens `WalkSummaryScreen`), per-recording playback transport (play/pause/scrub), playback speed cycling (1.0x / 1.25x / 1.5x / 2.0x), transcription text display, **inline transcription editing via `TextField` with Done button + DB persistence**, transcription `searchable(text:prompt:)` (Compose: `TextField` filter on the LazyColumn data), delete with confirmation, optional waveform visualization. "Enhanced" badge omitted on Android (Dynamic Voice pref deferred per Non-goals). Reuses Stage 2-A `VoiceRecordingDao`. **New screen entirely** — Android currently shows recordings only inline on `WalkSummaryScreen`. |
| `DataSettingsView` + `ExportConfirmationSheet` + `ExportDateRangeFormatter` + `JourneyViewerView` | 909 | 10-G | Three sections per iOS: (1) **Walks** — Export My Data (auto-detected date range, oldest→newest walk; `ExportConfirmationSheet` shows the read-only range summary; produces `.pilgrim` ZIP on confirm) + Import Data (file picker → parse ZIP → preview → commit Room transaction); (2) **View My Journey** — nav to `JourneyViewerView`, an in-app WebView showing `view.pilgrimapp.org/?token=…`; (3) **Audio** — Export Recordings (separate ZIP of all `.m4a`/`.wav` recording files, conditionally shown when `recordingCount > 0`). Cross-platform `.pilgrim` ZIP must round-trip with iOS — verify on real device with iOS-exported fixture. **Spec correction:** there is no user-facing date-range *picker* on iOS; the range is auto-computed and displayed read-only in the confirmation sheet. |
| `FeedbackView` | 239 | 10-F | Compose port. POSTs to the same `walk.pilgrimapp.org` feedback endpoint iOS uses. |
| `AboutView` | 453 | 10-H | Beyond mission/contributors/GitHub link/license/release notes, iOS includes: (1) **`statsWhisper`** — tap-cycling personal stats (3 phases: distance / walk count / walking-since) with `contentTransition(.numericText())` animation; (2) **`footprintTrail`** — 4 alternating footprint shapes as decorative graphic; (3) `SceneryItemView` seasonal vignette (reuses Stage 3-D engine); (4) **5 staggered section-entrance animations** (`sectionAppear(index:)` with cumulative delays); (5) "walk · talk · meditate" pillars with 3 icon-tinted rows; (6) Open Source section with GitHub + license rows + inline Rate button. **App-icon picker deferred** per Non-goals (Android dynamic-icon swap unreliable). |

## Stage decomposition

Each stage is its own PR with its own spec + plan + checkpoints. The decomposition prioritizes shipping value early and isolating risk.

### Stage 10-A: Settings card scaffold + PracticeSummaryHeader
**Scope:** Visual rebuild of `SettingsScreen` to the card-based layout. Shared `SettingsCardStyle` modifier + row builders. PracticeSummaryHeader with season label, cycling personal stats (3 phases: walks/dist, meditation, walking-since), collective stats line, milestone strip, streak flame.

**Migrates:** Existing AtmosphereCard refactored to use the shared modifier. CollectiveStatsCard's content folds into PracticeSummaryHeader. Voice-guides + Soundscapes rows temporarily kept as standalone `SettingNavRow` calls below the cards (will be absorbed into VoiceCard / Atmosphere's "Bells & Soundscapes" nav in subsequent stages). Top bar removed.

**New files:** ~6 source + 4 test.
**Estimated effort:** ~6–8 hours.

### Stage 10-B: Atmosphere completion + SoundSettingsView
**Scope:** Add Sounds master toggle to `AtmosphereCard` (gates every audio call site: bells, haptics, soundscapes, voice-guide playback). Add "Bells & Soundscapes" nav row when sounds enabled. Build `SoundSettingsScreen` with all six iOS sections (master toggle + haptic toggle, walk bell pickers, meditation bell pickers + breath rhythm picker, volume sliders, asset storage management, soundscape picker).

**Critical:** "Sounds master toggle" wires through `BellPlayer`, `SoundscapeOrchestrator`, `MeditationBellObserver`, `VoiceGuideOrchestrator`, Stage 5-B haptic call sites, Stage 4-D milestone celebration sites. ~10 call sites to gate. Each MUST short-circuit when `soundsEnabled.value == false`. Exhaustive test coverage required.

**New surfaces beyond what existed before:**
- Per-event bell selection (start-walk / end-walk / start-meditation / end-meditation). The bell-asset registry exists from Stage 5-B; UI for per-event mapping is new. Each picker opens a bottom-sheet `BellPickerSheet` showing the bundled asset list with previews.
- Breath rhythm picker (`BreathRhythm` enum with name/label/description per variant — port the iOS `BreathRhythm` enum verbatim).
- Bell volume + soundscape volume sliders (range [0.0, 1.0], stored as Float in `SoundsPreferencesRepository`). Wires into existing `BellPlayer.play(asset, volume)` and `ExoSoundscapePlayer.setVolume(...)`.
- Asset storage section: list bundled vs. downloaded sounds with size + delete button per downloaded asset. Reuses Stage 5-F `SoundscapeFileStore`.
- Bell haptic toggle (`bellHapticEnabled`, default true) — gates the `VibrationEffect.Composition` calls bound to bell triggers.

**New files:** ~7 source (AtmosphereCard delta, SoundSettingsScreen, BellPickerSheet, BreathRhythmPickerSheet, SoundsPreferencesRepository + scope + module) + 7 test.
**Estimated effort:** ~14–18 hours (revised up from 8–10; SoundSettingsView is 502 LoC of iOS surface and the master toggle's call-site audit is non-trivial).

### Stage 10-C: PracticeCard + units + celestial preferences
**Scope:** PracticeCard with all toggles (Begin with intention, Celestial awareness, Zodiac system picker when celestial enabled, Units segmented row, Walk-with-collective toggle, Gather walk photos toggle). Excludes "Auto-play nearby whispers" — deferred per Non-goals.

**Wiring required:**
- `beginWithIntention` → consumed by `WalkStartScreen` to show the intention prompt before tap-to-start (currently always-show; gate behind preference).
- `celestialAwarenessEnabled` → consumed by `LightReadingCard` (Stage 6-B) — when off, suppress the post-walk astronomy card.
- `zodiacSystem` → consumed by celestial calculator's zodiac math (Stage 6-A is currently tropical-only).
- `distanceUnits` → consumed by every km↔mi conversion site. ~30 call sites. Audit + introduce a `UnitFormatter` helper that reads the StateFlow.
- `walkReliquaryEnabled` → consumed by Stage 7-A photo picker auto-suggestion (currently always-show; gate behind preference).

**This is the riskiest stage.** Touches walk-start flow, post-walk presentation, and every numeric display in the app. Allocate extra review cycles.

**New files:** ~3 source + 5 test. Plus ~30 modified files for the units audit.
**Estimated effort:** ~10–14 hours.

### Stage 10-D: VoiceCard + RecordingsListView
**Scope:** VoiceCard with Voice Guide master toggle, Guide Packs nav row (when enabled, links to existing Stage 5-D picker), Auto-transcribe toggle, Recordings nav row with detail (count + size). RecordingsListView Compose screen with per-walk section headers, playback transport, speed cycling, inline transcription editing, search, delete. **Dynamic Voice toggle deferred** to Non-goals (Android equivalents are OEM-unreliable).

**Wiring:**
- `voiceGuideEnabled` master toggle: when off, suppress voice-guide playback during walks (Stage 5-E orchestrator's spawn-decision gate). Independent of pack download / selection state.
- `meditationGuideEnabled`: gates whether voice-guide plays during the meditation phase of a walk. Default `true` (matches iOS). Wires into the existing `VoiceGuideOrchestrator`.
- `autoTranscribe`: when ON, schedule transcription via existing Stage 2-D `WorkManagerTranscriptionScheduler`. When OFF, skip scheduling. Per-recording transcribe-on-demand button in the recordings list (manual trigger).
- **`autoTranscribe` migration (CRITICAL):** Android currently transcribes every recording automatically. When 10-D wires the gate, existing users would silently lose transcription on upgrade. Migration: on first launch after 10-D, if `autoTranscribe` key is absent AND a previous app launch is detected (DataStore has any pre-existing key, e.g., `appearance_mode` set to non-default), seed `autoTranscribe = true`. Fresh installs default to `false` (iOS parity). Robolectric test required.

**RecordingsListView surfaces (all from iOS, all in scope):** per-walk section headers (tap → `WalkSummaryScreen`), play/pause/scrub transport, playback speed cycling, transcription display + inline edit (TextField + Done + DB persist), search filter on transcription text, delete with confirmation. Optional: waveform visualization (defer to polish if time-boxed).

**New files:** ~6 source + 6 test (RecordingsListView alone needs 3+ test files for transport / edit / search / delete).
**Estimated effort:** ~14–18 hours (revised up from 10–12; RecordingsListView is 521 LoC of iOS surface, with non-trivial inline-edit and search).

### Stage 10-E: PermissionsCard
**Scope:** PermissionsCard with location, microphone, motion (step counter / activity recognition) status rows. Each row: colored status dot + label + subtitle + trailing action (Grant button / Settings button / checkmark icon / "Restricted" text). Refresh on `Lifecycle.Event.ON_RESUME`.

**Four permission states (matches iOS verbatim):**
- `Granted` → moss dot + checkmark icon
- `NotDetermined` → dawn dot + "Grant" button (triggers `ActivityResultContracts.RequestPermission`)
- `Denied` → rust dot + "Settings" button (opens app settings via `Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)`)
- `Restricted` → fog dot + "Restricted" caption (no action — Android equivalent: enterprise-managed device with `DevicePolicyManager` restrictions, or rare cases where parental controls block the permission)

The Android equivalent of iOS's "denied" vs "restricted" requires a small heuristic: `permission == DENIED && shouldShowRequestPermissionRationale == true` → user previously denied; offer "Settings" path. `permission == DENIED && shouldShowRequestPermissionRationale == false && hasBeenRequestedBefore` → permanently denied; offer "Settings" path. `Restricted` is rarer but real on managed Android Enterprise devices.

**Wiring:** Existing `PermissionsRepository` provides the underlying state. Port iOS `PermissionStatusViewModel` (87 LoC) to Android `PermissionStatusViewModel` that exposes a `StateFlow<PermissionState>` per permission, plus `requestX()` / `openSettings()` methods. Refresh observer: `LifecycleEventObserver` on `ON_RESUME`.

**New files:** ~4 source (PermissionsCard, PermissionStatusViewModel, PermissionState enum, permission-grant intent helpers) + 4 test.
**Estimated effort:** ~5–7 hours.

### Stage 10-F: ConnectCard + FeedbackView
**Scope:** ConnectCard with Pilgrim-on-the-Path link (Custom Tabs to podcast.pilgrimapp.org), Leave a Trail Note (FeedbackView), Rate Pilgrim (Play Store deep link), Share Pilgrim (`Intent.ACTION_SEND`). FeedbackView Compose port (POST to `walk.pilgrimapp.org/feedback`).

**Decisions:**
- "Rate Pilgrim" deep link: `market://details?id=org.walktalkmeditate.pilgrim` with `https://play.google.com/store/apps/details?id=...` fallback.
- "Pilgrim on the Path" podcast link: open in Custom Tabs (matches iOS SafariView UX).
- Feedback POST endpoint: same backend as iOS, no changes needed.

**New files:** ~4 source + 3 test.
**Estimated effort:** ~6–8 hours.

### Stage 10-G: DataCard + DataSettingsView (export/import)
**Scope:** DataCard with single "Export & Import" nav row. DataSettingsView with three iOS sections: Walks (Export My Data + Import Data), View My Journey (in-app WebView), Audio (Export Recordings ZIP, conditional on recordingCount > 0). `.pilgrim` ZIP format match with iOS — round-trip verified on real device with iOS-exported fixture.

**Spec correction (verified against iOS source):** there is no user-facing date-range *picker*. The export operation auto-detects the date range from oldest→newest walk in Room, and `ExportConfirmationSheet` displays the range as read-only summary text before the user taps Confirm. `ExportDateRangeFormatter` (29 LoC) handles the display formatting; port verbatim.

**This is also Phase 10 of the original port plan**. Big stage. Touches Room (export every entity to JSON), file system (`ZipOutputStream`), share intent (`ACTION_SEND` with `ContentResolver` URI for the produced ZIP), import (file picker → `ZipInputStream` → preview → commit Room transaction). Cross-platform format check: iOS-exported ZIP must import on Android and vice versa. JourneyViewerView is an in-app WebView (`AndroidView` wrapping `android.webkit.WebView`) showing `view.pilgrimapp.org/?token=…` with a per-export shared token.

**Audio export sub-feature:** separate ZIP producer that bundles every `.wav` recording from `filesDir/recordings/` (Stage 2-B / 2-C output). Conditionally rendered only when `voiceRecordingDao.count() > 0`.

**New files:** ~10 source (DataCard, DataSettingsScreen, ExportConfirmationSheet, JourneyViewerScreen, AudioExportSheet, PilgrimZipExporter, PilgrimZipImporter, ExportDateRangeFormatter, repository + interfaces) + 10 test (each ZIP path needs round-trip + iOS-fixture-compatibility tests).
**Estimated effort:** ~18–24 hours (revised up from 12–16; 909 LoC of iOS surface + cross-platform fixture validation + WebView integration + audio-export sub-feature push the upper bound up).

### Stage 10-H: About link + AboutView
**Scope:** About row at bottom of Settings (with PilgrimLogoView animated breathing icon, app version, chevron). AboutView Compose port covering all 6 iOS surfaces: mission text, statsWhisper (tap-cycling 3-phase personal stats), footprintTrail (decorative graphic), seasonal vignette (reuses Stage 3-D engine), 5 staggered section-entrance animations, "walk · talk · meditate" pillars, Open Source section (GitHub + license + inline Rate button), contributors, release notes.

**App-icon picker deferred** — Android dynamic-icon swap via `PackageManager.setComponentEnabledSetting` on activity-aliases is launcher-dependent and can cause home-screen widget loss on many OEM launchers. Listed in master Non-goals.

**Animations:** `sectionAppear(index:)` modifier with cumulative delays (0.0, 0.1, 0.2, 0.3, 0.4 seconds) — each section fades + slides 20dp on first composition. Honor `LocalAccessibilityManager`'s reduced-motion setting.

**`statsWhisper` Compose port:** tap-cycling state machine for 3 phases (distance, walk count, walking-since). Use `Crossfade` (or `AnimatedContent` with content-transition) for the numeric-text rotation matching iOS `contentTransition(.numericText())`. Pure UI — no new prefs.

**`footprintTrail`:** 4 decorative footprint shapes alternating left/right. Pure Compose `Canvas` drawing with `pilgrimColors.fog.copy(alpha = 0.15f)`.

**New files:** ~5 source (AboutScreen, PilgrimLogoView animated, footprintTrail Canvas, statsWhisper composable, sectionAppear modifier) + 4 test.
**Estimated effort:** ~8–12 hours (revised up from 3–4; iOS AboutView is 453 LoC of surface and the animated logo + statsWhisper + footprintTrail + 5 staggered animations are all non-trivial Compose work).

### Stage 10-I (optional polish): Card entrance animations + pull-to-reveal tagline
**Scope:** Polish pass once all cards are in place. Staggered fade+slide on appear, pull-to-reveal "Every walk is a small pilgrimage." tagline.

**Estimated effort:** ~2–3 hours.

## Total estimate

~9 stages, ~85–115 hours of focused work spread across multiple sessions (revised up from 62–83 after the spec review surfaced under-scoping in 10-B, 10-D, 10-G, and 10-H). Each stage shippable independently. Each lands as its own PR with its own spec + plan + checkpoints (matching the autopilot workflow).

| Stage | Scope summary | Estimate (h) |
|---|---|---:|
| 10-A | Card scaffold + PracticeSummaryHeader + SettingsAction navigation + shared row builders | 6–8 |
| 10-B | Atmosphere Sounds toggle + audio call-site gates + full SoundSettingsView (6 sections) | 14–18 |
| 10-C | PracticeCard + UnitsPreferencesRepository + ~30-site units audit | 10–14 |
| 10-D | VoiceCard + autoTranscribe migration + RecordingsListView (8 surfaces) | 14–18 |
| 10-E | PermissionsCard with 4-state model + PermissionStatusViewModel | 5–7 |
| 10-F | ConnectCard with leading icons + FeedbackView | 6–8 |
| 10-G | DataCard + DataSettingsView (Walks export+import / Journey WebView / Audio export) + cross-platform ZIP | 18–24 |
| 10-H | About link + full AboutView (6 surfaces, 5 staggered animations) | 8–12 |
| 10-I | Card entrance animations + pull-to-reveal tagline (optional polish) | 2–3 |
| **Total** | | **83–112** |

## Recommended order

10-A (foundation, also lands `SettingsAction` navigation pattern + `hemisphereOverride` pref) → 10-E (PermissionsCard; standalone, low-risk) → 10-F (ConnectCard; standalone, validates the leading-icon row builder) → 10-H (AboutView; mostly visual; lets us shake out the animation infrastructure) → 10-B (Atmosphere completion + SoundSettings; medium-risk audio call-site audit) → 10-D (VoiceCard + RecordingsList; medium-risk; depends on 10-B's `SoundsPreferencesRepository` shape only loosely) → 10-C (Practice card with units audit; high-risk, save for last UI stage so units changes don't have to be redone in already-written cards) → 10-G (Export/Import; biggest, depends on stable preference layer from all previous stages so the ZIP serializer captures every key in one pass).

Polish stage 10-I bundles entrance animations + pull-to-reveal tagline. Place after 10-A if energy is high; otherwise hold for end-of-arc polish.

## Open questions for CHECKPOINT 1

1. **Approve the Non-goals?** Specifically:
   - Skip whispers (Android has no whisper system yet).
   - Skip podcast intake (`podcastConsentGiven` + Pilgrim-on-the-Path podcast submission flow). The "Pilgrim on the Path" link in ConnectCard still ports — it just opens the podcast in Custom Tabs, no submission UI.
   - Skip Dynamic Voice toggle (Android `NoiseSuppressor`/`AcousticEchoCanceler` are OEM-unreliable).
   - Skip in-app App Icon picker (`alternateIconName`-style dynamic icons are launcher-dependent on Android).
   - Keep `voiceGuideVolume` + `voiceGuideDuckLevel` as constants (iOS does too).

2. **Confirm storage-key alignment.** Every key in the preferences-layer table above must match iOS `UserDefaults` keys verbatim for `.pilgrim` ZIP round-trips. Keys explicitly aligned in this spec: `appearanceMode`, `zodiacSystem`, `distanceMeasurementType`, `walkStartBellId`, `walkEndBellId`, `meditationStartBellId`, `meditationEndBellId`, `selectedSoundscapeId`, `selectedVoiceGuidePackId`, `bellVolume`, `soundscapeVolume`, `bellHapticEnabled`, `soundsEnabled`, `voiceGuideEnabled`, `meditationGuideEnabled`, `autoTranscribe`, `beginWithIntention`, `celestialAwarenessEnabled`, `walkReliquaryEnabled`, `contributeToCollective`, `breathRhythm`, `hemisphereOverride`. Anything missing from this list?

3. **`autoTranscribe` migration default.** Spec recommends seeding `true` for upgrades and `false` for fresh installs (preserves current Android behavior for existing users; matches iOS for new ones). Approve, or change to a user-prompted opt-in dialog on upgrade?

4. **Order of stages?** The recommended order is 10-A → 10-E → 10-F → 10-H → 10-B → 10-D → 10-C → 10-G. Foundation first, low-risk standalones next, high-risk and biggest at the end. Open to user reordering.

5. **Card entrance animations + pull-to-reveal tagline (10-I)** — bundle with 10-A or hold for end-of-arc polish? iOS includes them on day one; bundling matches iOS-parity intent but adds 2–3 hours to 10-A.

6. **Card border** — confirm dropping the 9.5-E-introduced 1dp ink-stroke border to match iOS exactly. Existing AtmosphereCard will visually change in 10-A.

## Approvable summary

Master spec for the full Settings rebuild. 9 sub-stages over 83–112 hours (revised up after spec review). Ports **17 new preferences** (was 9 in v1 of this spec; extra 8 added after sub-screen audit), 5 new sub-screens (full surface area documented per screen), 6 cards (with corrected ConnectCard icon-row builder, PermissionsCard 4-state model, AtmosphereCard border removal), the PracticeSummaryHeader (with stat-cycling + collective stats + milestone + streak flame), and (optionally) entrance animations. Each sub-stage has its own subsequent spec + plan + PR cycle.

**Defers (with rationale):** whispers, podcast intake, Dynamic Voice toggle, App-icon picker, `voiceGuideVolume` + `voiceGuideDuckLevel` user-facing prefs. Each deferral is tied to either an Android-platform constraint or a feature gap on the Android side that's not yet built.

**Critical decisions captured:**
- Card border (1dp fog × 0.2f) introduced in 9.5-E is **not iOS** — drop in 10-A.
- `SettingsAction` sealed class **mandatory** for navigation across all 10+ destinations.
- `autoTranscribe` migration **must seed true for upgrades** to preserve current behavior.
- All storage keys **must match iOS UserDefaults verbatim** (full list enumerated above).
- 4-state `PermissionState` (not 3) needed for Android equivalent of iOS Restricted.
- AboutView app-icon picker **not portable** to Android.
