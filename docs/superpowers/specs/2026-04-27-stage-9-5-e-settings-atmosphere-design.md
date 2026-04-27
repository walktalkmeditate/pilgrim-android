# Stage 9.5-E: Settings Atmosphere card — design spec

**Status:** Draft → CHECKPOINT 1
**Date:** 2026-04-27
**Author:** autopilot (Claude Opus 4.7)
**Predecessor:** Stage 9.5-D follow-ups (PR #61, merged 2026-04-27 as commit `065a8ba`)

## Goal

Add the Settings → Atmosphere card with an appearance-mode picker (Auto / Light / Dark). The user explicitly flagged "in-app dark mode toggle missing" during Stage 9.5-B device QA and we punted through 9.5-C and 9.5-D. This stage closes that gap.

## Non-goals

- **Sounds toggle.** iOS `AtmosphereCard` has a master mute for bells, haptics, and soundscapes. Implementing requires gating every audio call site (MeditationBellObserver, VoiceGuideOrchestrator, SoundscapeOrchestrator, Stage 5-B haptics, etc.). Substantial scope; defer to a dedicated stage if user-requested.
- **Atmospheric flourishes.** iOS doesn't have animated background gradients on the Settings screen; if it ever ships them, port in a future stage.
- **Other Atmosphere-adjacent prefs** (`celestialAwarenessEnabled`, `beginWithIntention`). iOS surfaces these on PracticeCard, not AtmosphereCard. Out of 9.5-E scope.
- **Card-based layout for the WHOLE Settings screen.** iOS uses cards for visual grouping (Atmosphere, Voice, Practice, Connect, Data, Permissions). Android currently uses rows. Refactoring all of Settings to cards is a bigger UX project; 9.5-E adds ONE Atmosphere card at the top and leaves existing rows as-is.

## Architecture

### Files to CREATE

1. **`app/src/main/java/org/walktalkmeditate/pilgrim/data/appearance/AppearancePreferencesRepository.kt`** — DataStore-backed Singleton, mirroring the `VoiceGuideSelectionRepository` template. Exposes:
   ```kotlin
   enum class AppearanceMode { System, Light, Dark }

   interface AppearancePreferencesRepository {
       val appearanceMode: StateFlow<AppearanceMode>
       suspend fun setAppearanceMode(mode: AppearanceMode)
   }
   ```
   Concrete `DataStoreAppearancePreferencesRepository` writes a string key `appearance_mode` to the existing `pilgrim_prefs` DataStore. Default value: `System`. `Eagerly` `stateIn` so `MainActivity` reads `.value` synchronously at composition time without missing the first emission.

2. **`app/src/main/java/org/walktalkmeditate/pilgrim/data/appearance/AppearancePreferencesModule.kt`** — Hilt `@Binds` for the interface + `@Provides` for an `@AppearanceScope CoroutineScope`.

3. **`app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/AtmosphereCard.kt`** — composable rendered at the top of `SettingsScreen`. Layout:
   - Card surface: `pilgrimColors.parchmentSecondary` background, `RoundedCornerShape(16.dp)`, 12-dp elevation? No — matches iOS's flat card with subtle ink-stroke border. `Modifier.border(1.dp, pilgrimColors.fog.copy(alpha=0.2f), RoundedCornerShape(16.dp))` + alpha-0.5 background.
   - Header row: "Atmosphere" (`pilgrimType.heading`, ink) + "Look and feel" subtitle (`pilgrimType.caption`, fog).
   - Appearance picker: 3-segment `SingleChoiceSegmentedButtonRow` (Material 3) with options `Auto | Light | Dark`. Selected option highlighted with `pilgrimColors.stone` background + `pilgrimColors.parchment` text; unselected uses `pilgrimColors.fog` text on transparent. iOS-faithful 3-option toggle.

4. **Tests:**
   - `app/src/test/java/org/walktalkmeditate/pilgrim/data/appearance/FakeAppearancePreferencesRepository.kt` — in-memory `MutableStateFlow`-backed test double.
   - `app/src/test/java/org/walktalkmeditate/pilgrim/ui/settings/AtmosphereCardTest.kt` — Compose tests: renders all 3 segments, tap fires `onSelect(mode)`, current selection visually highlighted.
   - `app/src/test/java/org/walktalkmeditate/pilgrim/data/appearance/DataStoreAppearancePreferencesRepositoryTest.kt` — Robolectric: persists across instance rebuilds; default is `System`; invalid stored value (manual edit) gracefully falls back to `System`.

### Files to MODIFY

1. **`app/src/main/java/org/walktalkmeditate/pilgrim/ui/theme/Theme.kt`** — `PilgrimTheme` accepts `appearanceMode: AppearanceMode = AppearanceMode.System`. Compute `darkTheme = when (appearanceMode) { System → isSystemInDarkTheme(); Light → false; Dark → true }`. Existing callers without the param continue to work via the default value.

2. **`app/src/main/java/org/walktalkmeditate/pilgrim/MainActivity.kt`** — inject `AppearancePreferencesRepository`; in `onCreate`'s `setContent`, collect the StateFlow and pass through to `PilgrimTheme(appearanceMode = ...)`. The `Eagerly` start strategy means the first composition reads the persisted value (no flash from default → user-preference).

3. **`app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/SettingsScreen.kt`** — render `AtmosphereCard` at the top of the Column (before `CollectiveStatsCard`). Pass `currentMode` from a new `SettingsViewModel.appearanceMode: StateFlow<AppearanceMode>` and `onSelectAppearance: (AppearanceMode) -> Unit` callback that delegates to `viewModelScope.launch { repo.setAppearanceMode(mode) }`.

4. **`app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/SettingsViewModel.kt`** — inject the repo; expose `appearanceMode` StateFlow + `setAppearanceMode` method.

5. **`app/src/main/res/values/strings.xml`** — add:
   - `settings_atmosphere_title = "Atmosphere"`
   - `settings_atmosphere_subtitle = "Look and feel"`
   - `settings_appearance_label = "Appearance"`
   - `settings_appearance_auto = "Auto"`
   - `settings_appearance_light = "Light"`
   - `settings_appearance_dark = "Dark"`

## Data flow

```
User taps a segment in the Atmosphere card
  ↓
AtmosphereCard onSelect(AppearanceMode.Light) callback
  ↓
SettingsViewModel.setAppearanceMode(Light)
  ↓
viewModelScope.launch { repository.setAppearanceMode(Light) }
  ↓
DataStore write — appearance_mode key = "light"
  ↓
StateFlow emits AppearanceMode.Light
  ↓
MainActivity's setContent collects new value, recomposes PilgrimTheme(Light)
  ↓
PilgrimTheme computes darkTheme=false, applies pilgrimLightColors() + light M3 ColorScheme
  ↓
All consumers (LocalPilgrimColors, MaterialTheme.colorScheme) read the new colors
  ↓
Animations: M3 ColorScheme is not animated by default; the recomposition is one-frame.
```

iOS uses a 0.2s easeInOut animation when toggling. Compose's default behavior is instant — acceptable for a contemplative app where instant feedback is appropriate. If we wanted iOS-faithful, we'd `animateColorAsState` on every color slot, which is heavy. **Decision: instant transition.**

## Edge cases

- **Cold launch with persisted preference.** `Eagerly` start strategy; `repo.appearanceMode.value` is read synchronously at MainActivity's `setContent` Composable. First composition uses the persisted value. ✓
- **Default preference (fresh install).** `appearance_mode` key absent → repo defaults to `System` → matches `isSystemInDarkTheme()` via the theme's branch. Same behavior as before this stage. ✓
- **Invalid stored value (e.g., user edited DataStore proto manually, or future code shipped a new enum value that this version doesn't recognize).** Repo's `map { it[KEY] }` parses the string; `valueOf` throws `IllegalArgumentException` for unknown values. Fix: use `runCatching` + fall back to `System`. ✓ Tested.
- **Rotation while Settings screen is open with the segmented control mid-tap.** ViewModel survives the rotation; the StateFlow emits the same value; segmented control re-renders with the same selection. ✓
- **Rapid toggle (user taps Auto → Light → Dark → Auto in 2 seconds).** Each tap fires `setAppearanceMode`; DataStore writes are coalesced via Compose's `derivedStateOf`-style flow. Final value wins. Theme recomposes 4 times (acceptable — all the work is reading from a CompositionLocal + applying a new ColorScheme). ✓
- **Configuration change OS-wide (system flips dark mode).** When `appearanceMode == System`, `isSystemInDarkTheme()` re-evaluates on the recomposition triggered by config change. Theme updates. ✓ When `appearanceMode != System`, system change is ignored — user's explicit choice wins. ✓
- **Settings tab not yet visited.** Repository is instantiated lazily on first inject. MainActivity is the first injection site (cold launch reads it for theme). The first read is the persisted value. ✓

## Tests

1. **DataStoreAppearancePreferencesRepositoryTest** (Robolectric):
   - `default value is System when no key written`
   - `setAppearanceMode persists across new repository instance`
   - `unknown stored value falls back to System`
   - `appearanceMode StateFlow emits new value after setAppearanceMode`
2. **AtmosphereCardTest** (Compose):
   - `renders all three segments labeled Auto, Light, Dark`
   - `current selection is visually highlighted` (assert via test-tag + content description)
   - `tapping a segment fires onSelect with the right AppearanceMode`
3. **PilgrimThemeAppearanceTest** (Compose):
   - `appearanceMode=Light forces light colors regardless of isSystemInDarkTheme`
   - `appearanceMode=Dark forces dark colors regardless of isSystemInDarkTheme`
   - `appearanceMode=System defers to isSystemInDarkTheme`
4. **SettingsViewModelAppearanceTest** (JUnit + FakeAppearancePreferencesRepository):
   - `appearanceMode StateFlow reflects repo value`
   - `setAppearanceMode delegates to repo`

## Dependencies / risks

- **Stage 9.5-A inset trap.** AtmosphereCard inside SettingsScreen's Scaffold; the Scaffold sets `contentWindowInsets = WindowInsets(0)` so Compose Padding inside the Column won't double-count. New card is a child of the existing Column — same Padding scheme applies, no new issues.
- **Theme recomposition cost.** ~12 TextStyle allocations + ColorScheme materialization on every appearance change. Already cached via `remember { pilgrimTypography() }`; `pilgrimColors()` is a 14-field data class that allocates per call. Three taps in quick succession → three allocations. Trivial.
- **DataStore singleton.** Re-uses `pilgrim_prefs` DataStore (same DataStore instance shared with `selected_voice_guide_pack_id`, `selected_soundscape_id`, `recovered_walk_id`, etc.). One more key. ✓
- **Material 3 SingleChoiceSegmentedButtonRow** is in `androidx.compose.material3:material3` (not the experimental package); supported on M3 1.0+. Project currently on M3 1.4.x — verify.
- **Theme override timing.** First composition uses `repo.appearanceMode.value` (Eagerly StateFlow). If DataStore reads block briefly on first launch (rare), `Eagerly` falls back to the initial value `System` until DataStore returns. Visual impact: brief flash of system theme before user-pref applies. Acceptable; the catch-clause emits `System` for any read failure too.

## Approvable summary

One Atmosphere card on Settings with a 3-option Appearance picker (Auto / Light / Dark), persisted via DataStore, applied app-wide via PilgrimTheme. ~6 new files (3 source + 3 test) + 4 modified source files + strings. Estimated ~3-4 hours implementation + polish. Defers Sounds toggle and other Atmosphere-adjacent UX to later stages.
