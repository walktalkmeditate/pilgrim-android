# Stage 13-XZ — AI Prompts button + Walk Summary reveal stagger

## Context

Two Walk Summary parity gaps remaining:

1. **13-X — AI Prompts button** (`WalkSummaryScreen.kt:447` placeholder). iOS section 17 — opens a sheet with 6 built-in prompt styles + up to 3 custom styles. Tapping a style opens a detail view with the assembled prompt text. NO LLM API call — fully local prompt-text assembly. User copies the assembled text to ChatGPT/Claude externally via deep-link pills.

2. **13-Z — Walk Summary reveal stagger** (`WalkSummaryScreen.kt:295-413`). Android currently fades all reveal-wrapped sections together via a single `AnimatedVisibility(fadeIn(REVEAL_FADE_MS))`. iOS staggers 7 sections individually with delays 0/0.2/0.3/0.4 and durations 0.6/0.8.

Both bundled per user request to ship final 13 parity items in one PR.

## Goal

Match iOS Walk Summary AI Prompts surface verbatim (button + sheet + detail view + 6 styles + custom-style store + full prompt template). Replace single-block fade with per-section staggered fade. Reuse Stage 6-A celestial calc + Stage 7-B photo analysis + Stage 13-Cel `recentFinishedWalksBefore` DAO query where possible.

## Non-goals

- **LLM API integration** — iOS doesn't have it either; user copies to external AI. Same on Android.
- **Custom-style icon picker beyond 20 SF Symbols** — match iOS list verbatim (translated to Material icons).
- **Geocoding rate-limit retry strategy** — match iOS 1.1s delay between two CLGeocoder calls verbatim. No retry on failure (silent omission of location section, matching iOS).
- **Background prompt pre-generation** — generation runs cold on every sheet reopen (matches iOS). Cached PhotoContext analysis minimizes the cost.
- **iOS PhotoContext animals field** — ML Kit has no `VNRecognizeAnimalsRequest` equivalent. Decision: omit `animals` field from Android PhotoContext, drop `Animals:` line from prompt template. Documented divergence.
- **iOS PhotoContext outdoor field** — ML Kit has no `VNDetectHorizonRequest` equivalent. Decision: derive `outdoor` from image-labeling tags (`outdoor`/`nature`/`sky`/`landscape`/`field`/`mountain`/`forest`/`park`/`beach`/`ocean` → true). Documented divergence.
- **iOS PhotoContext salientRegion** — ML Kit has no saliency API. Decision: drop the field entirely from `PhotoContext` data class AND drop the `Focal area:` line from the per-photo prompt template AND drop the "Visual narrative" + "Color progression" arc block from `formatPhotoSection`. Reason: a constant `"center"` would drive `PhotoNarrativeArcBuilder.computeAttentionArc` to always emit `"consistently_close"` → prompt would always say *"Consistently focused on close-up detail throughout"*, feeding wrong information to the LLM. Better to omit than mislead. Per-photo `Scene:` / `Text found:` / `People:` / `Outdoor:` lines remain. `dominantColor` per-photo also remains (Bitmap pixel-average works), but the cross-photo `Color progression:` arc line drops with the rest of the narrative arc block. **`PhotoNarrativeArcBuilder` + `NarrativeArc` types still ship as no-op stubs returning empty/none values** so the assembler's gates stay symmetric with iOS — they're just never consumed by the rendered prompt text on Android.
- **Sheet/Modal sheet UI library** — use Material 3 ModalBottomSheet, not full-screen route. Approximates iOS `.sheet` half-then-full-height behavior.
- **Animation curves below `timeBreakdown`** — sections 12-20 (favicon, activity timeline/insights/list, recordings, prompts, details, light reading, etegami, share) keep their current no-fade behavior on iOS — same on Android. Stagger applies ONLY to reveal-wrapped sections 4-11.

## Architecture

### Two parallel surfaces

**13-X (AI Prompts):**
- New `PromptStyle.kt` enum with 6 cases (matches iOS `PromptStyle`).
- New `WalkPromptVoices.kt` carrying the verbatim 12 strings (6 styles × 2 hasSpeech variants × 2 fields = preamble/instruction).
- New `CustomPromptStyle.kt` data class + `CustomPromptStyleStore` (DataStore Preferences, JSON via kotlinx-serialization, max 3).
- New `ContextFormatter.kt` porting iOS public format functions verbatim.
- New `PromptAssembler.kt` porting iOS template + section gates verbatim.
- New `PhotoContextAnalyzer` extending Stage 7-B `PhotoAnalysisRunner` with per-photo full ML Kit pipeline (image labeling + text recognition + face detection) + DataStore-cached `PhotoContext` keyed by photo URI.
- New `PhotoNarrativeArcBuilder.kt` porting iOS attention-arc + solitude + recurring-theme + color-progression algorithms.
- New `Geocoder` wrapper (`PromptGeocoder.kt`) using `android.location.Geocoder` with 1.1s delay + 500m gate.
- New `PromptListScreen` composable (Material 3 ModalBottomSheet) listing 6 built-in + N custom styles + "Create Your Own" row.
- New `PromptDetailScreen` composable with Copy + Share + AI deep-link pills (chat.openai.com / claude.ai/new).
- New `CustomPromptEditorScreen` composable (title + 20-icon Material grid + instruction TextEditor + counter).
- VM additions: `WalkSummaryViewModel.openPromptsSheet` state; lazy `ActivityContext` build on first sheet open.
- `AIPromptsRow` composable for the section-17 button (icon + dynamic subtitle + chevron).
- 14+ new strings.xml entries.

**13-Z (stagger):**
- Replace single `AnimatedVisibility(visible = revealPhase == .Revealed, fadeIn(REVEAL_FADE_MS))` block with per-section `Modifier.alpha(animated)` derived from `animateFloatAsState(targetValue = if revealed 1f else 0f, tween(durationMs, delayMs, easing = EaseIn))` per iOS table.
- New `WalkSummaryRevealAnimations.kt` constants module: `REVEAL_DURATION_HERO_MS = 600`, `REVEAL_DURATION_QUOTE_MS = 800`, `REVEAL_DURATION_CALLOUT_MS = 800`, `REVEAL_DURATION_DEFAULT_MS = 600`, `REVEAL_DELAY_STATS_MS = 200`, `REVEAL_DELAY_CELESTIAL_MS = 300`, `REVEAL_DELAY_BREAKDOWN_MS = 400`. Reduce-motion: collapse to 0ms across all (matches existing reduceMotion handling).
- `durationHero` special: visible on `.Zoomed` (not just `.Revealed`) via `revealPhase != Hidden ? 1f : 0f` condition.

### iOS-Android type mapping

| iOS | Android |
|---|---|
| `PromptStyle` enum (6 cases) | `enum class PromptStyle` (6 cases) |
| `CustomPromptStyle: Codable` | `@Serializable data class CustomPromptStyle` |
| `CustomPromptStyleStore` (UserDefaults) | `CustomPromptStyleStore` (DataStore Prefs JSON) |
| `PhotoContext: Codable` | `@Serializable data class PhotoContext` |
| `PhotoContextAnalyzer` (CGImage + Vision) | `PhotoContextAnalyzer` (Bitmap + ML Kit) — extend Stage 7-B `PhotoAnalysisRunner` |
| `PhotoNarrativeArc` + builder | `PhotoNarrativeArc` + `PhotoNarrativeArcBuilder` (verbatim algorithm) |
| `ActivityContext` struct | `data class ActivityContext` |
| `RecordingContext` struct | `data class RecordingContext` |
| `WalkSnippet` struct | `data class WalkSnippet` |
| `MeditationContext` struct | `data class MeditationContext` |
| `PlaceContext` struct + role enum | `data class PlaceContext` + `enum class PlaceRole` |
| `ContextFormatter` static | `object ContextFormatter` |
| `PromptAssembler.assemble` | `object PromptAssembler.assemble` |
| `PromptGenerator.generateAll` | `object PromptGenerator.generateAll` |
| `WalkPromptVoice` protocol + 6 impls | `interface WalkPromptVoice` + 6 objects + `CustomPromptStyleVoice` class |
| `PromptListView` SwiftUI | `PromptListScreen` Compose ModalBottomSheet |
| `PromptDetailView` SwiftUI | `PromptDetailScreen` Compose modal route |
| `CustomPromptEditorView` SwiftUI | `CustomPromptEditorScreen` Compose modal route |
| `UIPasteboard.general.string` | `ClipboardManager.setPrimaryClip` |
| `UIActivityViewController` | `Intent.ACTION_SEND` chooser |
| `UIApplication.shared.open(url)` | `Intent.ACTION_VIEW` |
| SF Symbol `text.quote` | Material `Icons.Outlined.FormatQuote` |
| SF Symbol `leaf.fill` | Material `Icons.Outlined.Spa` (already used by WalkFavicon LEAF) — substitute |
| SF Symbol `eye.fill` | Material `Icons.Outlined.Visibility` |
| SF Symbol `paintbrush.fill` | Material `Icons.Outlined.Brush` |
| SF Symbol `heart.fill` | Material `Icons.Outlined.Favorite` |
| SF Symbol `books.vertical.fill` | Material `Icons.Outlined.MenuBook` |
| SF Symbol `pencil.and.scribble` | Material `Icons.Outlined.Edit` |
| 20 custom-icon SF Symbols | 20 Material icons (table in spec strings section) |
| `CLGeocoder.reverseGeocodeLocation` | `android.location.Geocoder.getFromLocation` (sync wrapped on Dispatchers.IO) |

### Sheet navigation pattern

iOS: `.sheet(isPresented: $showPrompts) { NavigationStack { PromptListView } }` then `.sheet(item: $selectedPrompt) { PromptDetailView }`.

Android: ModalBottomSheet for the LIST, separate full-screen `Dialog` (or Activity-modal route) for the DETAIL. Custom editor opens as a third nested sheet/route. State machine in `WalkSummaryViewModel`:

```kotlin
sealed class PromptsSheetState {
    data object Closed : PromptsSheetState()
    data class Listing(val context: ActivityContext) : PromptsSheetState()
    data class Detail(val listing: Listing, val prompt: GeneratedPrompt) : PromptsSheetState()
    data class Editor(val listing: Listing, val editing: CustomPromptStyle?) : PromptsSheetState()
}
```

OR simpler: 3 separate `mutableStateOf<X?>(null)` flags. Spec leaves to implementation.

### `ActivityContext` lifecycle

- Built by `WalkSummaryViewModel.buildActivityContext()` suspend fn (`viewModelScope`) ON FIRST sheet open (lazy — never pre-built in `buildState()` since geocoding + photo analysis is expensive).
- Cached in VM-level `MutableStateFlow<ActivityContext?>`.
- Cache invalidation: combine `repository.observePhotosFor(walkId).map { it.size }.distinctUntilChanged()` AND `repository.observeVoiceRecordings(walkId).map { recs -> recs.count { r -> r.transcription != null } }.distinctUntilChanged()`. Either count change → null the cache, force re-build on next sheet open. **Voice-recording invalidation is critical** because Stage 2-D auto-transcription runs async after walk finish; user opening prompts pre-transcription then again post-transcription must NOT see a stale empty-transcription cache.
- Cold start every sheet reopen would re-geocode — VM-level cache eliminates this even within the same session. iOS doesn't cache; Android does because mobile latency is more painful.

### PhotoContext caching

- DataStore `Preferences` keyed by `"photo_context_<photoUri-sanitized>"`. JSON via kotlinx-serialization.
- Sanitize URI: replace `/` with `_` (matches iOS pattern).
- Cache hit on subsequent `analyzeSync(uri)` calls — instant. Cold cost ~600ms (image labeling + text + face detection in parallel via ML Kit's coroutine wrappers).
- Cache invalidation: never. Photo content is immutable per URI in MediaStore.

### Geocoder pipeline

- `PromptGeocoder.geocodeStart(LatLng)` always called.
- `PromptGeocoder.geocodeEnd(LatLng, distanceFromStartMeters)` called only if distance > 500m.
- Both run via `withContext(Dispatchers.IO)` wrapping synchronous `Geocoder.getFromLocation` (deprecated but works on all API levels we support; the API-33+ async variant is OPTIONAL upgrade for a future stage).
- 1.1s `delay()` between start and end calls (matches iOS rate-limit mitigation).
- Output: `PlaceContext(name = "${address.featureName}, ${address.locality}", role = Start|End)`.
- Failure: silent — return empty list (location section omitted from prompt).

### Recent walks snippets

Reuse Stage 13-Cel `WalkRepository.recentFinishedWalksBefore(currentStart, limit = 20)`. Filter to walks with at least one transcribed voice recording. Take first 3.

```kotlin
suspend fun buildRecentWalkSnippets(currentWalk: Walk): List<WalkSnippet> =
    repository.recentFinishedWalksBefore(currentWalk.startTimestamp, limit = 20)
        .filter { walk ->
            repository.voiceRecordingsFor(walk.id).any { it.transcription != null }
        }
        .take(3)
        .map { walk ->
            val recordings = repository.voiceRecordingsFor(walk.id)
            val combined = recordings.mapNotNull { it.transcription }.joinToString(" ")
            val preview = combined.truncatedAtWordBoundary(maxLength = 200)
            val celestialSummary = if (practicePreferences.celestialAwarenessEnabled.value) {
                buildCelestialSummary(walk)
            } else null
            WalkSnippet(
                date = walk.startTimestamp,
                placeName = null,  // iOS hardcodes nil
                weatherCondition = walk.weatherCondition,
                celestialSummary = celestialSummary,
                transcriptionPreview = preview,
            )
        }
```

`buildCelestialSummary(walk)` reuses `CelestialSnapshotCalc.snapshot(walk.startTimestamp)` — extract Sun + Moon zodiac signs. Format: `"Sun in {sign}, Moon in {sign}"`.

### Reveal stagger refactor

Current `WalkSummaryScreen.kt` block (around line 305-413):

```kotlin
AnimatedVisibility(visible = revealPhase == RevealPhase.Revealed, enter = fadeIn(...)) {
    Column { /* sections 4-15 */ }
}
```

New shape: replace `AnimatedVisibility` wrapper with a plain `Column`; each child applies its own `Modifier.alpha(animatedAlpha)`:

```kotlin
@Composable
private fun rememberRevealAlpha(
    revealPhase: RevealPhase,
    durationMs: Int,
    delayMs: Int,
    reduceMotion: Boolean,
    fireOnZoomed: Boolean = false,  // durationHero special
): Float {
    val target = when {
        fireOnZoomed -> if (revealPhase != RevealPhase.Hidden) 1f else 0f
        else -> if (revealPhase == RevealPhase.Revealed) 1f else 0f
    }
    val anim by animateFloatAsState(
        targetValue = target,
        animationSpec = if (reduceMotion) tween(0)
                        else tween(durationMs, delayMillis = delayMs, easing = EaseIn),
        label = "reveal-alpha-$durationMs-$delayMs",
    )
    return anim
}
```

Apply per section (table maps verbatim to iOS):

| Section | Duration | Delay | Special |
|---|---|---|---|
| `ElevationProfile` | — | — | NO reveal opacity (iOS doesn't either) |
| `WalkJourneyQuote` | 800ms | 0ms | EaseIn |
| `WalkDurationHero` | 600ms | 0ms | `fireOnZoomed = true` (visible on Zoomed) |
| `MilestoneCalloutRow` | 800ms | 300ms | EaseIn |
| `WalkStatsRow` | 600ms | 200ms | EaseIn |
| `WalkSummaryWeatherLine` | 600ms | 200ms | EaseIn |
| `CelestialLineRow` | 600ms | 300ms | EaseIn |
| `WalkTimeBreakdownGrid` | 600ms | 400ms | EaseIn |
| `FaviconSelectorCard` | — | — | NO reveal opacity (iOS doesn't either) |
| Sections 13-20 | — | — | NO reveal opacity (iOS doesn't either) |

Sections currently inside the `AnimatedVisibility` wrapper that AREN'T in the table above (favicon, activity timeline, etc.) move OUTSIDE the wrapper into the parent Column. They render immediately, no fade.

NOTE: Stage 13-EFG `remember(s.summary.altitudeSamples)` perf fix relied on the parent Column lambda invalidating only inside the AnimatedVisibility. After removing the wrapper, the projection still goes inside the parent Column so the `remember` keying remains effective (parent Column lambda still re-evaluates only when state changes, not per-frame).

## Files to create

### 13-X (AI Prompts)
| File | Purpose |
|---|---|
| `core/prompt/PromptStyle.kt` | Enum (6 cases) + title + description + iconRes |
| `core/prompt/WalkPromptVoice.kt` | Interface |
| `core/prompt/voices/ContemplativeVoice.kt` | object impl with 4 verbatim strings |
| `core/prompt/voices/ReflectiveVoice.kt` | same |
| `core/prompt/voices/CreativeVoice.kt` | same |
| `core/prompt/voices/GratitudeVoice.kt` | same |
| `core/prompt/voices/PhilosophicalVoice.kt` | same |
| `core/prompt/voices/JournalingVoice.kt` | same |
| `core/prompt/CustomPromptStyle.kt` | `@Serializable data class` (id, title, icon, instruction) |
| `core/prompt/CustomPromptStyleStore.kt` | DataStore Prefs wrapper, max=3 |
| `core/prompt/voices/CustomPromptStyleVoice.kt` | Wraps CustomPromptStyle as WalkPromptVoice |
| `core/prompt/PhotoContext.kt` | `@Serializable data class` (tags/detectedText/people/outdoor/dominantColor) — NO animals field, NO salientRegion field per Android divergences |
| `core/prompt/PhotoContextAnalyzer.kt` | ML Kit pipeline + DataStore cache |
| `core/prompt/NarrativeArc.kt` | data class (attentionArc/solitude/recurringTheme/dominantColors) |
| `core/prompt/PhotoNarrativeArcBuilder.kt` | builder with iOS algorithms verbatim |
| `core/prompt/ActivityContext.kt` | data class with all 14 fields per iOS audit |
| `core/prompt/RecordingContext.kt` | data class (uuid/timestamp/start+endCoordinate/wpm/text) |
| `core/prompt/MeditationContext.kt` | data class (startDate/endDate/durationSeconds) |
| `core/prompt/PlaceContext.kt` | data class + `enum class PlaceRole { Start, End }` |
| `core/prompt/WaypointContext.kt` | data class (label/icon/timestamp/coordinate). Source: Stage 7-A `WalkRepository.waypointsFor(walkId)` returning Waypoint Room entities |
| `core/prompt/PhotoContextEntry.kt` | data class (index/distanceIntoWalkMeters/time/coordinate/context: PhotoContext). Wraps PhotoContext with per-walk metadata |
| `core/prompt/RouteSampleProjection.kt` | Helpers: `closestCoordinate(samples, atTimestamp): LatLng?`, `distanceAtTimestamp(samples, atTimestamp): Double` (cumulative haversine sum up to closest sample). Verbatim port of iOS `PromptListView.swift:222,272` |
| `core/prompt/WalkSnippet.kt` | data class (date/placeName/weatherCondition/celestialSummary/transcriptionPreview) |
| `core/prompt/ContextFormatter.kt` | object with all iOS public format functions verbatim |
| `core/prompt/PromptAssembler.kt` | object with iOS template verbatim |
| `core/prompt/PromptGenerator.kt` | object with `generateAll(context)` |
| `core/prompt/GeneratedPrompt.kt` | data class (style/customStyle/title/subtitle/text/icon) |
| `core/prompt/PromptGeocoder.kt` | Geocoder wrapper with 500m gate + 1.1s delay. `@Suppress("DEPRECATION")` on `Geocoder.getFromLocation` sync call; API-33+ async variant deferred |
| `core/prompt/PromptsCoordinator.kt` | Facade aggregating CustomPromptStyleStore + PhotoContextAnalyzer + PromptGeocoder. Exposes `buildContext(walkId)` + `customStyles` + save/delete. Single VM-injected dep |
| `core/prompt/StringExtensions.kt` | `String.truncatedAtWordBoundary(maxLength)` |
| `ui/walk/summary/AIPromptsRow.kt` | Section-17 button composable |
| `ui/walk/summary/PromptListSheet.kt` | ModalBottomSheet |
| `ui/walk/summary/PromptDetailDialog.kt` | Modal full-screen dialog |
| `ui/walk/summary/CustomPromptEditorDialog.kt` | Modal full-screen dialog |
| Test files for: each voice (verbatim string assertions), CustomPromptStyleStore (max=3, persistence), ContextFormatter (each format fn), PromptAssembler (full template + each gate), PromptGenerator (6 styles), PhotoNarrativeArcBuilder (each algorithm), PromptGeocoder (gate + delay + failure), AIPromptsRow rendering, PromptListSheet rendering, PromptDetailDialog Copy/Share/Pills, ActivityContext lifecycle on VM | as listed |

### 13-Z (stagger)
| File | Purpose |
|---|---|
| `ui/walk/summary/WalkSummaryRevealAnimations.kt` | Constants object + `rememberRevealAlpha` Composable |
| Test: `WalkSummaryRevealAnimationsTest.kt` | rememberRevealAlpha returns expected target on each phase + reduceMotion |

## Files to modify

| File | Change |
|---|---|
| `ui/walk/WalkSummaryViewModel.kt` | Add `promptsSheetState`, `buildActivityContext()` suspend fn, `openPromptsSheet()` / `closePromptsSheet()` / `openPromptDetail()` / `openCustomEditor()` / `saveCustomPrompt()` / `deleteCustomPrompt()` actions. Inject **a single `PromptsCoordinator` facade** that internally holds `CustomPromptStyleStore` + `PhotoContextAnalyzer` + `PromptGeocoder` (consolidate 3 deps into 1 to keep the VM constructor manageable — it already has 11 deps). Coordinator exposes `suspend fun buildContext(walkId): ActivityContext`, `customStyles: StateFlow<List<CustomPromptStyle>>`, `suspend fun saveCustomStyle(s)`, `suspend fun deleteCustomStyle(s)`. `PracticePreferencesRepository.zodiacSystem` (already injected) consumed for celestial-summary in WalkSnippet |
| `ui/walk/WalkSummaryScreen.kt` | Replace section-17 placeholder with `AIPromptsRow(...)`. Remove `AnimatedVisibility` wrapper around sections 4-11 — apply `Modifier.alpha(rememberRevealAlpha(...))` per section. Move sections 12-20 (favicon onwards) outside the (now-removed) wrapper into parent Column |
| `data/WalkRepository.kt` | Use existing `recentFinishedWalksBefore` (Stage 13-Cel). No new method needed |
| `di/PromptModule.kt` | NEW Hilt module: provides `CustomPromptStyleStore`, `PhotoContextAnalyzer`, `PromptGeocoder`, json instance reuse |
| `app/src/main/res/values/strings.xml` | ~25 new strings + 2 plurals — see "Strings (English)" section below |
| `gradle/libs.versions.toml` | Add ML Kit Text Recognition + Face Detection dependencies if not present from Stage 7-B |

## Strings (English)

```xml
<!-- Stage 13-X AI Prompts -->
<string name="prompts_button_title">Generate AI Prompts</string>
<string name="prompts_button_subtitle_no_speech">Reflect on your walk</string>
<plurals name="prompts_button_subtitle_with_speech">
    <item quantity="one">%d transcription available</item>
    <item quantity="other">%d transcriptions available</item>
</plurals>
<string name="prompts_sheet_title">AI Prompts</string>

<string name="prompt_style_contemplative_title">Contemplative</string>
<string name="prompt_style_contemplative_desc">Sit with what emerged from movement</string>
<string name="prompt_style_reflective_title">Reflective</string>
<string name="prompt_style_reflective_desc">Identify patterns and emotional undercurrents</string>
<string name="prompt_style_creative_title">Creative</string>
<string name="prompt_style_creative_desc">Transform thoughts into poetry or metaphor</string>
<string name="prompt_style_gratitude_title">Gratitude</string>
<string name="prompt_style_gratitude_desc">Find thanksgiving in observations</string>
<string name="prompt_style_philosophical_title">Philosophical</string>
<string name="prompt_style_philosophical_desc">Explore deeper meaning and wisdom</string>
<string name="prompt_style_journaling_title">Journaling</string>
<string name="prompt_style_journaling_desc">Structure raw thoughts into a journal entry</string>

<string name="prompt_detail_copy">Copy</string>
<string name="prompt_detail_copied">Copied!</string>
<string name="prompt_detail_share">Share</string>
<string name="prompt_detail_paste_in_ai">Paste in your favorite AI</string>
<string name="prompt_detail_pill_chatgpt">ChatGPT</string>
<string name="prompt_detail_pill_claude">Claude</string>

<string name="custom_prompt_create_title">Create Your Own</string>
<string name="custom_prompt_edit_title">Edit Custom Prompt</string>
<string name="custom_prompt_title_label">Title</string>
<string name="custom_prompt_title_placeholder">e.g., Letter to Future Self</string>
<string name="custom_prompt_icon_label">Icon</string>
<string name="custom_prompt_instruction_label">Instruction</string>
<string name="custom_prompt_instruction_placeholder">Describe how the AI should respond to your walking thoughts…</string>
<string name="custom_prompt_save">Save</string>
<string name="custom_prompt_delete">Delete</string>
<string name="custom_prompt_edit_action">Edit</string>
<plurals name="custom_prompt_counter">
    <item quantity="one">%d of 3 custom styles</item>
    <item quantity="other">%d of 3 custom styles</item>
</plurals>
```

URLs (constants in code, not strings):
- ChatGPT: `https://chat.openai.com/`
- Claude: `https://claude.ai/new`

Voice strings (12 verbatim, 6 styles × 2 hasSpeech variants × 2 fields = 24 strings... actually 4 per style = 24 total) — kept inline in voice objects as Kotlin String literals (NOT in strings.xml — matches iOS hardcoding pattern; localization deferred).

## Material icon mapping (built-in styles)

| Style | Material icon |
|---|---|
| Contemplative | `Icons.Outlined.Spa` (matches iOS leaf.fill spirit) |
| Reflective | `Icons.Outlined.Visibility` |
| Creative | `Icons.Outlined.Brush` |
| Gratitude | `Icons.Outlined.Favorite` |
| Philosophical | `Icons.AutoMirrored.Outlined.MenuBook` |
| Journaling | `Icons.Outlined.Edit` |

## Material icon mapping (20 custom-style picker icons)

| iOS SF Symbol | Material icon |
|---|---|
| pencil.line | `Icons.Outlined.Edit` |
| text.quote | `Icons.Outlined.FormatQuote` |
| envelope.fill | `Icons.Outlined.Email` |
| lightbulb.fill | `Icons.Outlined.Lightbulb` |
| flame.fill | `Icons.Filled.LocalFireDepartment` |
| leaf.fill | `Icons.Outlined.Spa` |
| wind | `Icons.Outlined.Air` |
| drop.fill | `Icons.Outlined.WaterDrop` |
| sun.max.fill | `Icons.Outlined.WbSunny` |
| moon.fill | `Icons.Outlined.NightsStay` |
| star.fill | `Icons.Filled.Star` |
| sparkles | `Icons.Rounded.AutoAwesome` |
| figure.walk | `Icons.AutoMirrored.Outlined.DirectionsWalk` |
| mountain.2.fill | `Icons.Outlined.Terrain` |
| water.waves | `Icons.Outlined.Waves` |
| bird.fill | `Icons.Outlined.Pets` (closest available) |
| hands.clap.fill | `Icons.Outlined.Celebration` (semantic match for "applause/celebration"; SignLanguage was misleading) |
| brain.head.profile | `Icons.Outlined.Psychology` |
| book.fill | `Icons.Outlined.MenuBook` |
| music.note | `Icons.Outlined.MusicNote` |

## Implementation order

20 tasks split for sequential subagent dispatch.

1. **Spec + plan**.
2. **PromptStyle enum + 6 voices + WalkPromptVoice interface** — pure data, fully independent.
3. **CustomPromptStyle data class + CustomPromptStyleStore + tests** — DataStore JSON wrapper. Tests for max=3 cap + persistence + JSON round-trip.
4. **PhotoContext data class + Android divergences documented** — pure data.
5. **PhotoContextAnalyzer + DataStore cache + tests** — extends Stage 7-B Runner with full ML Kit pipeline. Tests for cache hit/miss + each ML Kit field extraction (mock ML Kit results).
6. **PhotoNarrativeArcBuilder + tests** — verbatim algorithm port. Tests for each attentionArc/solitude/recurringTheme branch.
7. **PlaceContext + RecordingContext + MeditationContext + WalkSnippet + ActivityContext + GeneratedPrompt + NarrativeArc** — pure data.
8. **PromptGeocoder + tests** — 500m gate + 1.1s delay + failure handling.
9. **ContextFormatter + tests** — verbatim port of every iOS public fn. Tests for each fn against iOS-equivalent inputs (use iOS audit's documented format strings as expected values).
10. **PromptAssembler + tests** — verbatim template port. Tests for: every section gate ON/OFF, photo block per-field rendering, voice preamble + instruction wrapping, intention appended to instruction.
11. **PromptGenerator + tests** — generateAll returns 6 GeneratedPrompts in PromptStyle.entries order.
12. **WalkSummaryViewModel ActivityContext lifecycle + promptsSheetState** — `buildActivityContext()` suspend fn + state machine + cache. Tests.
13. **AIPromptsRow composable + tests** — section-17 button visual.
14. **PromptListSheet (Material 3 ModalBottomSheet) + tests** — 6 built-in + N custom + "Create Your Own" row.
15. **PromptDetailDialog + tests** — Copy + Share + AI pills + 8s auto-revert + scale bounce.
16. **CustomPromptEditorDialog + tests** — 20-icon grid + max-3 cap UI.
17. **Strings.xml entries**.
18. **WalkSummaryRevealAnimations.kt + tests** — constants + rememberRevealAlpha. **Must precede Task 19** since 19 calls into rememberRevealAlpha.
19. **WalkSummaryScreen wiring** — replace section-17 placeholder + remove AnimatedVisibility wrapper around sections 4-11 + apply Modifier.alpha per section using rememberRevealAlpha + move sections 12-20 outside.
20. **Final integration** — full assembleDebug + lintDebug + testDebugUnitTest. Manual QA: tap "Generate AI Prompts" on a finished walk → list renders → tap Contemplative → detail shows full prompt → Copy → AI pills appear → tap ChatGPT → opens browser.

Tasks 2-17 are mostly independent and can be dispatched sequentially with no inter-task blocking. Tasks 18 → 19 must sequence (19 depends on 18). 20 last.

## Tests (high-level)

- **Voices** (Task 2): 24 verbatim string assertions (6 voices × 2 fields × 2 hasSpeech variants).
- **CustomPromptStyleStore** (Task 3): max=3, JSON round-trip, persist across instances, default empty.
- **PhotoContextAnalyzer** (Task 5): cache hit returns instantly, cache miss runs ML Kit + persists, divergence handling (no animals field, no salientRegion field, outdoor derived from labels).
- **PhotoNarrativeArcBuilder** (Task 6): each attentionArc branch (single/detail-to-wide/wide-to-detail/consistently-close/consistently-wide/mixed), solitude branches (alone/with-others/mixed), recurringTheme threshold + top-5 cap, dominantColors order preserved.
- **PromptGeocoder** (Task 8): 500m gate (start always, end only if > 500m), 1.1s delay between calls, silent failure returns empty list.
- **ContextFormatter** (Task 9): every fn against iOS-equivalent fixture inputs.
- **PromptAssembler** (Task 10): full template with all gates ON; full template with all gates OFF; per-gate ON/OFF combinations; intention appended to instruction.
- **PromptGenerator** (Task 11): generateAll returns 6 prompts in enum order, each prompt has correct style + text.
- **VM lifecycle** (Task 12): openPromptsSheet → buildActivityContext runs once → cached → reopen instant; closePromptsSheet → state Closed; openPromptDetail/openCustomEditor → state transitions.
- **AIPromptsRow** (Task 13): renders title + dynamic subtitle + chevron + tap fires callback.
- **PromptListSheet** (Task 14): renders 6 built-in styles + N custom styles + Create Your Own row.
- **PromptDetailDialog** (Task 15): tap Copy → clipboard.primaryClip = prompt text; pills appear; 8s auto-revert; tap pill → ACTION_VIEW intent dispatched.
- **CustomPromptEditorDialog** (Task 16): 20-icon grid renders; selecting icon updates state; tap Save adds to store; cap=3 disables Create button.
- **WalkSummaryRevealAnimations** (Task 19): rememberRevealAlpha returns 0f at Hidden, expected interpolated value at Zoomed/Revealed; reduceMotion collapses to 0ms.

Total estimated: ~80-100 new tests.

## Verification

- `./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest` clean.
- Manual on-device walkthrough:
  - Open Walk Summary → tap "Generate AI Prompts" → sheet renders within ~2-3s (geocoding + photo analysis on first open)
  - Reopen sheet → instant (cached ActivityContext)
  - Tap Contemplative → full prompt text displayed, includes celestial line if pref ON, intention if set, transcriptions if walk had voice recordings
  - Tap Copy → "Copied!" feedback + ChatGPT/Claude pills slide up
  - Tap ChatGPT → opens browser to chat.openai.com
  - 8 seconds later: feedback reverts, pills slide down
  - Re-tap Copy → resets 8s timer
  - Tap Share → Android share sheet appears with text
  - Tap "Create Your Own" → editor opens, fill title + select icon + write instruction → tap Save → returns to list with new custom row
  - Swipe-edit on custom row → editor opens with values pre-filled
  - Swipe-delete on custom row → custom row removed
  - Add 3 custom styles → "Create Your Own" row disabled
  - Walk Summary reveal: visually verify staggered fade — durationHero appears first (with map zoom), journeyQuote follows, statsRow + weatherLine together at 200ms, milestoneCallout + celestialLine at 300ms, timeBreakdown last at 400ms

## Open questions for review

1. **PhotoContext animals field**: iOS has it; Android omits per ML Kit gap. Drop the line entirely from prompt template OR keep `Animals: <none>` line for layout consistency? Decision: DROP entirely (matches iOS conditional-render behavior).
2. **PhotoContext outdoor derivation**: deriving from image labels (`outdoor`/`nature`/etc.) is fuzzy. Acceptable for v1?
3. **PhotoContext salientRegion field dropped entirely** (no ML Kit saliency API; constant-center value would mislead LLM via PhotoNarrativeArcBuilder feedback loop): Acceptable v1 simplification?
4. **Sheet dismiss + reopen behavior**: Android cache ActivityContext between sheet opens within same VM lifetime (better UX than iOS cold-restart). Confirm this divergence is acceptable.
5. **`Bird` SF symbol → `Icons.Outlined.Pets`**: closest available. Acceptable substitution?
6. **`hands.clap.fill` SF symbol → `Icons.Outlined.Celebration`**: chosen over `SignLanguage` (semantically wrong) and `ThumbUp` (loses the "celebration" connotation).
7. **Geocoder API-33+ async variant**: ship sync wrapper now, upgrade later? Acceptable.
8. **Plurals XML for transcription count + custom style counter**: matches Material localization conventions, deviates slightly from iOS `"\(n) transcription\(n == 1 ? "" : "s")"` pattern but is the Android-native idiom.
9. **Reveal stagger durationHero `fireOnZoomed`**: confirm iOS `revealPhase == .hidden ? 0 : 1` semantic translates to Android `revealPhase != Hidden`. Verified equivalent.

## Documented iOS deviations (in code comments)

- **Recent walks DAO order**: Android `recentFinishedWalksBefore` orders by `end_timestamp DESC` (Stage 13-Cel). iOS `computeRecentWalkSnippets` orders by `_startDate DESC`. For the top-3 case the divergence is small but real on long-walk-then-short-walk pairs. Documented; defer dedicated query to follow-up.
- **N+1 transcript filter**: spec's `recentFinishedWalksBefore(currentStart, limit = 20).filter { hasTranscription(it) }` does up to 20 voiceRecording queries on `Dispatchers.IO`. Acceptable on first sheet open. Future: add `walkDao.getRecentFinishedBeforeWithTranscribed(currentStart, limit = 3)` JOIN query.

- PhotoContext: NO `animals` field (ML Kit gap). `Animals:` line dropped from prompt template entirely.
- PhotoContext: `outdoor` derived from image-labeling tags (proxy for VNDetectHorizonRequest).
- PhotoContext: `salientRegion` field dropped entirely (no ML Kit saliency API; constant-center value would feed misleading attentionArc to the LLM via PhotoNarrativeArcBuilder).
- ActivityContext: cached at VM scope across sheet opens within same VM lifetime (iOS recreates per sheet open). Cleared on photo pin/unpin only.
- Geocoder: Android `android.location.Geocoder` synchronous wrapper on `Dispatchers.IO`. iOS uses CLGeocoder async.
- Reveal stagger: parameters per iOS `WalkSummaryView.swift` lines 320-542. EaseIn easing matches Compose `EaseIn`.
- Custom-style icon picker: 20 Material icons substituted for iOS SF Symbols. One loose substitution (Pets for bird); hands.clap.fill resolved via `Icons.Outlined.Celebration`.
- `formatPace` locale: iOS uses `Locale.current.measurementSystem == .us`; Android uses `unitsPreferences.distanceUnits` (matches Stage 13-Cel TotalDistance pattern, more consistent than system-locale).
