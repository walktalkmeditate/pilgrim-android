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
- **WhisperKit-specific transcription progress UI**. iOS shows `TranscriptionService.state.downloadingModel(progress)` from WhisperKit's model download. Android uses bundled `whisper.cpp` `ggml-tiny.en` (39 MB shipped in APK or lazy-fetched on first use, depending on Stage 2-D's choice). The auto-transcribe toggle still ports; the model-download progress UI doesn't apply.
- **iCloud sync UI**. iOS doesn't have one either. Skip.
- **`bellHapticEnabled`, `bellVolume`, `soundscapeVolume`, `voiceGuideVolume`, `voiceGuideDuckLevel`** as user-facing prefs. These exist on iOS as advanced settings inside `SoundSettingsView`. Keep them as constants on Android for Stage 10-B; expose in 10-B only if the SoundSettingsView port turns out to need them.
- **`name`, `weight`, `gpsAccuracy`, `shouldShowMap`, `breathRhythm`, `lastSeenCollectiveWalks`, `lastPodcastSubmissionDate`** prefs. iOS keeps these for legacy reasons; Android doesn't need them.

## Architecture overview

### Shared infrastructure (Stage 10-A)

1. **`SettingsCardStyle.kt`** — a Compose `Modifier` extension `settingsCard()` matching iOS's `.settingsCard()`: `RoundedCornerShape(16.dp)` + `pilgrimColors.parchmentSecondary` background + `border(1.dp, fog × 0.2f)` + `padding(16.dp)`. Use the SAME visual style that 9.5-E's `AtmosphereCard` introduced (so the existing card's appearance becomes the canonical card style going forward). Refactor 9.5-E's inline `Modifier.clip(...).background(...).border(...).padding(...)` chain into this shared modifier as part of 10-A.

2. **Shared row builders**:
   - `CardHeader(title: String, subtitle: String)` composable — title in `pilgrimType.heading` ink, subtitle in `pilgrimType.caption` fog. 2dp internal spacing, 8dp bottom padding.
   - `SettingToggle(label, description, checked, onCheckedChange)` — row with label/desc on left, M3 `Switch` on right. Switch uses `stone` thumb when on, `fog` when off (matching iOS `.tint(.stone)`).
   - `SettingPicker(label, options, selected, onSelect)` — row with label on left, segmented row on right, max width 180dp on the picker. Segmented styling matches `AtmosphereCard`'s.
   - `SettingNavRow(label, detail = null, onClick)` — row with label, optional detail caption, chevron-right. Uses `pilgrimColors.fog` for chevron + detail.
   - `Divider()` — uses `pilgrimColors.fog.copy(alpha = 0.2f)`, 1px thickness, edge-to-edge of the card content (matching iOS).

3. **`SettingsScreen.kt`** restructured: drop the M3 `Scaffold` top bar entirely (iOS uses no nav bar — the title is centered inline at the top of the scroll content). Replace with a centered "Settings" title styled like the iOS `.toolbar`. Vertical stack of `LazyColumn` (so card-entrance staggered animations work cleanly). Horizontal padding 16dp.

4. **Card entrance animations** — staggered 0.1s delays per card, fade + 20dp slide-up. Compose: `AnimatedVisibility` driven by a `hasAppeared` state that flips after first `LaunchedEffect(Unit)`. Honor `LocalAccessibilityManager.current.isReducedMotionEnabled` if available, else default to no-animation in tests.

5. **Pull-to-reveal tagline** — "Every walk is a small pilgrimage." Shown when scroll offset > 40dp at the top of the scroll. Italic caption in fog. Compose port via `LazyListState.firstVisibleItemScrollOffset` reading.

### Preferences layer

Stage 10 introduces **9 new preferences** across the existing `pilgrim_prefs` DataStore. Each follows the Stage 9.5-E `AppearancePreferencesRepository` template (interface + `@Singleton` impl + Hilt `@Binds` + `Eagerly` StateFlow). Group them under one `UserPreferencesRepository` per domain to avoid module sprawl:

| Repo | Prefs | Defaults | Stage |
|---|---|---|---|
| `PracticePreferencesRepository` | `beginWithIntention: Boolean`, `celestialAwarenessEnabled: Boolean`, `zodiacSystem: ZodiacSystem (Tropical/Sidereal)`, `walkReliquaryEnabled: Boolean` | false / false / Tropical / false | 10-C |
| `UnitsPreferencesRepository` | `distanceUnits: Units (Metric/Imperial)`, `altitudeUnits` (derived), `speedUnits` (derived), `weightUnits` (derived), `energyUnits` (derived) | Metric, all derived. Single `applyUnitSystem(metric: Boolean)` method matches iOS. | 10-C |
| `SoundsPreferencesRepository` | `soundsEnabled: Boolean` | true | 10-B |
| `VoicePreferencesRepository` | `voiceGuideEnabled: Boolean`, `dynamicVoiceEnabled: Boolean`, `autoTranscribe: Boolean` | false / true / false | 10-D |
| `CollectiveOptInRepository` | already exists as `CollectiveRepository.optIn` | — | reuse |
| `AppearancePreferencesRepository` | `appearanceMode` (already exists from 9.5-E) | System | reuse |

**iOS-parity storage values** (so future export/import round-trips cleanly):
- `zodiacSystem`: `"tropical"` / `"sidereal"`
- `distanceUnits`: serialized as `"kilometers"` / `"miles"` (iOS `MeasurementUserPreference<UnitLength>` symbol)
- `appearanceMode`: `"system"` / `"light"` / `"dark"` (already aligned in 9.5-E)

### Sub-screens

| Sub-screen | iOS LoC | Android stage | Notes |
|---|---:|---|---|
| `SoundSettingsView` (bell-per-event picker, soundscape picker, volume sliders, haptic toggle) | 502 | 10-B | Compose port. Reuses Stage 5-B `MeditationBellObserver` + Stage 5-F `SoundscapeOrchestrator`. New: per-event bell selection (start-walk / end-walk / start-meditation / end-meditation). |
| `RecordingsListView` (global voice-recording list + per-recording playback, transcription, delete) | 521 | 10-D | Compose port. Reuses Stage 2-A `VoiceRecordingDao`. New screen; current Android only shows recordings inline on `WalkSummaryScreen`. |
| `DataSettingsView` + `ExportConfirmationSheet` + `JourneyViewerView` | 909 | 10-G | Compose port with `ZipOutputStream` / `ZipInputStream`. Date-range export. Cross-platform `.pilgrim` ZIP must round-trip with iOS — verify on real device. |
| `FeedbackView` | 239 | 10-F | Compose port. POSTs to the same `walk.pilgrimapp.org` feedback endpoint iOS uses. |
| `AboutView` | 453 | 10-H | Compose port. Includes version, GitHub link, license link, contributors, release notes. |

## Stage decomposition

Each stage is its own PR with its own spec + plan + checkpoints. The decomposition prioritizes shipping value early and isolating risk.

### Stage 10-A: Settings card scaffold + PracticeSummaryHeader
**Scope:** Visual rebuild of `SettingsScreen` to the card-based layout. Shared `SettingsCardStyle` modifier + row builders. PracticeSummaryHeader with season label, cycling personal stats (3 phases: walks/dist, meditation, walking-since), collective stats line, milestone strip, streak flame.

**Migrates:** Existing AtmosphereCard refactored to use the shared modifier. CollectiveStatsCard's content folds into PracticeSummaryHeader. Voice-guides + Soundscapes rows temporarily kept as standalone `SettingNavRow` calls below the cards (will be absorbed into VoiceCard / Atmosphere's "Bells & Soundscapes" nav in subsequent stages). Top bar removed.

**New files:** ~6 source + 4 test.
**Estimated effort:** ~6–8 hours.

### Stage 10-B: Atmosphere completion + SoundSettingsView
**Scope:** Add Sounds master toggle to `AtmosphereCard` (gates every audio call site: bells, haptics, soundscapes, voice-guide playback). Add "Bells & Soundscapes" nav row when sounds enabled. Build `SoundSettingsScreen` (per-event bell picker, soundscape picker, volume sliders, haptic toggle).

**Critical:** "Sounds master toggle" wires through `BellPlayer`, `SoundscapeOrchestrator`, `MeditationBellObserver`, `VoiceGuideOrchestrator`, Stage 5-B haptic call sites, Stage 4-D milestone celebration sites. ~10 call sites to gate. Each MUST short-circuit when `soundsEnabled.value == false`. Exhaustive test coverage required.

**New files:** ~4 source + 5 test.
**Estimated effort:** ~8–10 hours.

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
**Scope:** VoiceCard with Voice Guide enable toggle, Guide Packs nav row (when enabled, links to existing Stage 5-D picker), Dynamic Voice toggle, Auto-transcribe toggle, Recordings nav row with detail (count + size). RecordingsListView Compose screen.

**Decisions to make:**
- `voiceGuideEnabled` master toggle: when off, suppress voice-guide playback during walks (Stage 5-E orchestrator). Independent of pack download state.
- `dynamicVoiceEnabled`: iOS uses this to post-process recordings via Apple's audio enhancement API. Android equivalent: `MediaPlayer` doesn't have built-in enhancement, would need a DSP library. For Stage 10-D, the toggle ports to UI but the audio path is a no-op — flag as TODO for the user, or skip the toggle entirely. **Recommendation: skip the toggle on Android (visual-only would mislead users); add to Non-goals if user agrees.**
- `autoTranscribe`: Android currently transcribes every recording automatically (Stage 2-D). When toggle is OFF, skip the WorkManager schedule. Per-recording transcribe-on-demand button in the recordings list.

**New files:** ~3 source + 4 test. RecordingsListView is large (~500 iOS LoC).
**Estimated effort:** ~10–12 hours.

### Stage 10-E: PermissionsCard
**Scope:** PermissionsCard with location, microphone, motion (step counter / activity recognition) status rows. Status dot + Grant/Settings/checkmark action per row. Refresh on `Lifecycle.Event.ON_RESUME`.

**Wiring:** Existing `PermissionsRepository` provides the underlying state. Add a `PermissionStatusViewModel` (or reuse) that exposes a StateFlow per permission. Open-Settings intent uses `Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)`.

**New files:** ~3 source + 3 test.
**Estimated effort:** ~4–6 hours.

### Stage 10-F: ConnectCard + FeedbackView
**Scope:** ConnectCard with Pilgrim-on-the-Path link (Custom Tabs to podcast.pilgrimapp.org), Leave a Trail Note (FeedbackView), Rate Pilgrim (Play Store deep link), Share Pilgrim (`Intent.ACTION_SEND`). FeedbackView Compose port (POST to `walk.pilgrimapp.org/feedback`).

**Decisions:**
- "Rate Pilgrim" deep link: `market://details?id=org.walktalkmeditate.pilgrim` with `https://play.google.com/store/apps/details?id=...` fallback.
- "Pilgrim on the Path" podcast link: open in Custom Tabs (matches iOS SafariView UX).
- Feedback POST endpoint: same backend as iOS, no changes needed.

**New files:** ~4 source + 3 test.
**Estimated effort:** ~6–8 hours.

### Stage 10-G: DataCard + DataSettingsView (export/import)
**Scope:** DataCard with single "Export & Import" nav row. DataSettingsView with date-range picker, ExportConfirmationSheet, JourneyViewerView. `.pilgrim` ZIP format match with iOS — round-trip verified on device.

**This is also Phase 10 of the original port plan**. Big stage. Touches Room (export every entity to JSON), file system (write ZIP), share intent (export the file via `ACTION_SEND`), and import (parse ZIP back into Room transaction). Cross-platform format check: iOS-exported ZIP must import on Android and vice versa.

**Estimated effort:** ~12–16 hours.

### Stage 10-H: About link + AboutView
**Scope:** About row at bottom of Settings (with PilgrimLogoView animated breathing icon, app version, chevron). AboutView Compose port (mission, contributors, GitHub link, license, release notes).

**New files:** ~2 source + 1 test.
**Estimated effort:** ~3–4 hours.

### Stage 10-I (optional polish): Card entrance animations + pull-to-reveal tagline
**Scope:** Polish pass once all cards are in place. Staggered fade+slide on appear, pull-to-reveal "Every walk is a small pilgrimage." tagline.

**Estimated effort:** ~2–3 hours.

## Total estimate

~9 stages, ~62–83 hours of focused work spread across multiple sessions. Each stage shippable independently. Each lands as its own PR with its own spec + plan + checkpoints (matching the autopilot workflow).

## Recommended order

10-A (foundation) → 10-E (Permissions; standalone, low-risk) → 10-B (Atmosphere completion + SoundSettings; medium-risk) → 10-D (VoiceCard + RecordingsList; medium-risk) → 10-F (Connect; standalone) → 10-H (About; trivial) → 10-C (Practice card with units audit; high-risk, save for last UI stage) → 10-G (Export/Import; biggest, save for end). 10-I polish anywhere after 10-A.

## Open questions for CHECKPOINT 1

1. **Approve the Non-goals?** Especially: skip whispers, skip podcast intake, skip Dynamic Voice toggle (keep Android always-on), skip iOS-only volume slider prefs.
2. **Confirm storage values for iOS parity.** `zodiacSystem` = "tropical"/"sidereal", `distanceUnits` = "kilometers"/"miles", `appearanceMode` = "system"/"light"/"dark" already done. Anything else needs cross-platform string-key alignment?
3. **Order of stages?** The recommended order above prioritizes foundation → low-risk → high-risk → biggest. Open to user reordering.
4. **Card entrance animations + pull-to-reveal tagline** — bundle with 10-A or save for 10-I polish? iOS includes them on day one; bundling matches iOS-parity intent but adds 2–3 hours to 10-A.

## Approvable summary

Master spec for the full Settings rebuild. 9 sub-stages over 60–80 hours. Ports 9 new preferences, 5 new sub-screens, 6 cards, the personal-stats header, and (optionally) entrance animations. Each sub-stage has its own subsequent spec + plan + PR cycle. Defers whispers + podcast + Dynamic Voice toggle + iOS-only volume slider prefs.
