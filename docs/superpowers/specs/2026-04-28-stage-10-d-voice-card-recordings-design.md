# Stage 10-D: VoiceCard + RecordingsListScreen — Design Spec

**Status:** Drafted 2026-04-28. Awaiting CHECKPOINT 1 approval before plan-out.
**iOS references:**
- `pilgrim-ios/Pilgrim/Scenes/Settings/SettingsCards/VoiceCard.swift` (115 LoC)
- `pilgrim-ios/Pilgrim/Scenes/Settings/RecordingsListView.swift` (521 LoC)
- `pilgrim-ios/Pilgrim/Models/Preferences/UserPreferences.swift:54-74` (storage keys)
- `pilgrim-ios/docs/screenshots/09_settings.png` (visual reference, partial)

## Goal

Port iOS's VoiceCard and RecordingsListView to Android with **pixel-faithful parity**. Add the missing `voiceGuideEnabled` master gate to the orchestrator, wire `autoTranscribe` to gate the WorkManager scheduler with an **upgrade-preserving migration**, and ship a stand-alone Recordings screen reachable from the new VoiceCard.

## Why now

10-D was deferred to land after the foundational stages (10-A scaffold, 10-B sounds master + audio call-site gates, 10-C practice + units). The voice infrastructure (data layer, capture, transcription, picker, orchestrator, ExoPlayer controller) is all in place from Stages 2-A through 5-G — 10-D is purely the Settings-side surfacing + new Recordings UX.

## iOS reference inventory

### VoiceCard surface (top-down order, iOS-faithful — verified against `VoiceCard.swift:25-77`)

iOS layout:

1. **Card header** — `"Voice"` (heading) + `"Speaking and listening"` (caption, fog).
2. **Voice Guide toggle** — label `"Voice Guide"`, description `"Spoken prompts during walks and meditation"`, tint `.stone`. Always visible.
3. **Guide Packs nav row** — label `"Guide Packs"`, chevron right (caption, fog). **Conditional:** rendered only when Voice Guide toggle is ON, via `if voiceGuideEnabled { NavigationLink { … } }`. **No trailing divider** — the row sits between the toggle above and the unconditional divider below.
4. **Dynamic Voice toggle** — `"Dynamic Voice"` / `"Enhance clarity of your voice recordings"`. **Skipped on Android** per master spec (NoiseSuppressor / AcousticEchoCanceler are OEM-unreliable). Documented in the card's KDoc as a known iOS divergence.
5. **Divider** — unconditional. Sits between the Dynamic-Voice/voice-guide group and the Auto-transcribe group.
6. **Auto-transcribe toggle** — `"Auto-transcribe"` / `"Convert recordings to text after each walk"`.
7. **(iOS-only)** Model download progress row — Android bundles `ggml-tiny.en.bin` (~40 MB) in the APK so the model is always ready.
8. **Divider** — unconditional.
9. **Recordings nav row** — label `"Recordings"`, detail `"\(count) recording[s] • X.X MB"` (separator is ` U+2022 ` with a single space on each side), chevron right. **Always rendered** including the zero state — iOS unconditionally produces `"0 recordings • 0.0 MB"`.

iOS animation: `.animation(.easeInOut(duration: 0.2), value: voiceGuideEnabled)` is on the outer `VStack`. SwiftUI's value-keyed `.animation` modifier auto-animates the disappearance/reappearance of the conditional Guide Packs row when the keyed value changes. Compose has no equivalent card-level keyed-animation modifier; we replicate the visual via `AnimatedVisibility(visible = voiceGuideEnabled)` wrapping ONLY the Guide Packs row (no trailing divider — there isn't one in iOS).

Android-faithful layout:

1. CardHeader.
2. Voice Guide toggle row.
3. `AnimatedVisibility(visible = voiceGuideEnabled, enter = …, exit = …)` wrapping `SettingNavRow("Guide Packs", chevron, onOpenVoiceGuides)`.
4. Unconditional Divider.
5. Auto-transcribe toggle row.
6. Unconditional Divider.
7. Recordings nav row (always rendered, count/size detail).

### Storage keys (verbatim from iOS, for `.pilgrim` ZIP cross-platform parity)

| Key | Type | iOS default | Android port |
|---|---|---|---|
| `voiceGuideEnabled` | Bool | `false` | New DataStore preference; default `false`. |
| `selectedVoiceGuidePackId` | String? | `nil` | Already wired via `VoiceGuideSelectionRepository` (Stage 5-D). DataStore key currently is `selected_voice_guide_pack_id` — **rename to iOS-faithful `selectedVoiceGuidePackId` with read-fallback** to the legacy snake_case key for backwards compat. |
| `meditationGuideEnabled` | Bool | `true` | **NOT in Settings.** iOS toggles this only inside the meditation options sheet. Android: define a `private const val MEDITATION_GUIDE_ALWAYS_ENABLED = true` in `VoiceGuideOrchestrator.kt` (with a TODO referencing the future MeditationView parity stage). **Do not add a DataStore preference yet** — adding storage for a value read nowhere is YAGNI and risks the `.pilgrim` ZIP serializer picking it up before any UI writes it. The future MeditationView stage adds both the DataStore-backed pref + the per-session UI together. |
| `voiceGuideVolume` | Double | `0.8` | Park as a constant `0.8f` in code; surface in the picker only when a future stage ports `VoiceGuideSettingsView`. Out of 10-D scope. |
| `voiceGuideDuckLevel` | Double | `0.15` | Same — constant for now. |
| `dynamicVoiceEnabled` | Bool | `true` | **Skipped permanently** on Android. |
| `autoTranscribe` | Bool | `false` | New DataStore preference. **CRITICAL migration:** see below. |

### `autoTranscribe` migration (CRITICAL)

Android currently transcribes every recording automatically (Stage 2-D's `WalkFinalizationObserver` schedules a `TranscriptionWorker` unconditionally on every walk finish). iOS's default is OFF.

If we ship 10-D with the iOS default, **existing Android users would silently lose transcription on upgrade.** Migration plan:

- New DataStore preference key: `autoTranscribe` (camelCase, iOS-faithful).
- On first read, if the key is **absent** AND **any pre-existing user-pref key exists**, seed `autoTranscribe = true` (preserves Android v0.x behavior for upgrading users). Probe keys (verbatim DataStore key strings as currently written by previous stages):
  - `"appearance_mode"` — written by `DataStoreAppearancePreferencesRepository` (Stage 9.5-E).
  - `"soundsEnabled"` — written by `DataStoreSoundsPreferencesRepository` (Stage 10-B).
  - `"selected_voice_guide_pack_id"` — written by `VoiceGuideSelectionRepository` (Stage 5-D, pre-rename).
  - `"selectedVoiceGuidePackId"` — the post-rename key (in case the migration in Section "Wiring updates — VoiceGuideSelectionRepository key rename" already ran).
  - Any of `beginWithIntention` / `celestialAwarenessEnabled` / `walkReliquaryEnabled` / `zodiacSystem` / `distanceUnits` (all camelCase, written by Stage 10-C).
- If the key is absent AND no pre-existing key (fresh install), default to `false` (iOS parity).
- Robolectric test required: covers (a) fresh install → false, (b) upgrade with `"appearance_mode"` set → seeded true, (c) explicit user write → preserved as written.

This migration runs once per install on first read of `autoTranscribe`. Implementation lives in `DataStoreVoicePreferencesRepository.kt`.

### RecordingsListView (iOS) — full surface

A standalone screen pushed from the VoiceCard's "Recordings" row.

**Top-level structure:**
- Title `"Recordings"`
- Background `Color.parchment`
- `.searchable` with prompt `"Search transcriptions"` (top of screen, system search bar)

**Empty state:** SF `waveform` icon (largeTitle, fog) + `"Your voice recordings will appear here"` (body, fog), centered.

**Sections:** grouped by walk, **most recent walk first.**

**Section header (tappable button → `WalkSummaryScreen(walkId)`):**
- Date format: `"MMMM d, h:mm a"` (e.g., `"April 28, 4:30 PM"`) — caption, ink.
- Subtitle: `"\(formatDuration(totalSeconds)) of recordings"` (e.g., `"3:45 of recordings"`) — caption, fog.
- Trailing chevron right (caption2, fog).

**Recording row (file-available state):**
- HStack header:
  - Play/pause button — `play.circle.fill` / `pause.circle.fill`, title2, stone, with `.symbolEffect(.replace)` crossfade.
  - Metadata column: `"Recording \(index)"` (1-indexed within section, body, ink). Below: `"\(duration) · \(sizeMB) MB"` + optional `" · Enhanced"` if `recording.isEnhanced`.
  - Trailing speed pill: `"1x"` → `"1.5x"` → `"2x"` cycle. At 1.0x: stone text on `stone.opacity(0.12)` background. At >1.0x: parchment text on solid stone background. Caption font, 6pt h-pad / 3pt v-pad, cornerRadius 4. **Format string**: matches iOS truncation — `if (speed % 1.0f == 0f) "%.0fx".format(Locale.US, speed) else "%.1fx".format(Locale.US, speed)`. Produces `"1x"` (not `"1.0x"`), `"1.5x"`, `"2x"` (not `"2.0x"`).
- Waveform bar (32 pt fixed height): inactive bars at fog/0.4, active bars (up to playback progress) at full stone. Tap/drag to seek. While loading: rounded rectangle at fog/0.15.
- Time labels (visible only when this row is currently playing): leading `currentTime`, trailing `totalDuration`, monospaced caption, fog.
- Transcription block (only when transcription is non-null and non-empty):
  - **View mode:** rounded box (`parchmentTertiary` background, cornerRadius 8, padding 8, body+ink text) with a small copy icon (`doc.on.doc`, caption, fog) on the right. Tap anywhere on the text → enters edit mode.
  - **Edit mode:** `TextEditor` (body+ink) with `parchmentTertiary` background + cornerRadius 8 + padding 4. Min height 60, max height 200. Below: `"Done"` button (caption, stone, on `stone.opacity(0.12)` background, cornerRadius 4) — tap commits to DB and exits edit mode. Tapping outside does NOT save.

**Recording row (file-unavailable state):**
- HStack: `waveform.slash` icon (fog) + `"File unavailable"` (caption, fog).

**Swipe actions:**
- Trailing (right swipe) → **Delete** (destructive, `trash` icon). Confirms via alert: title `"Delete this recording file? The transcription will be kept."`, buttons `"Delete"` (destructive) / `"Cancel"`. **Only the WAV file is deleted; the row remains with file-unavailable state and the transcription still readable.**
- Leading (left swipe) → **Retranscribe** (`arrow.clockwise`, stone tint). Triggers a single-recording transcription via `transcriptionService.transcribeSingle()`.
- Both `allowsFullSwipe = false`.

**Bottom button:** `"Delete All Recording Files"` (body, destructive role). Confirms via alert: `"Delete all recording files? Transcriptions will be kept."` Buttons `"Delete All"` (destructive) / `"Cancel"`. Same kept-transcription semantic.

**Search:** transcription text only (case-insensitive). Walk dates, walk titles, recording indices are NOT searched. When `filteredSections.isEmpty` due to search, shows `"No recordings match"` (body, fog, centered).

**Lifecycle:**
- On appear → fetch all walks-with-recordings.
- On disappear → stop playback.
- Waveform samples computed async per-recording, cached in a `Map<UUID, FloatArray>`.
- Transcription edits commit immediately on `Done` tap.

### Wiring (iOS)

- `voiceGuideEnabled`: gates Active-walk and Meditation voice-guide spawn at the top of `startVoiceGuideIfEnabled()` and `autoStartGuideIfEnabled()`.
- `meditationGuideEnabled`: a SECOND, independent gate inside meditation only.
- `autoTranscribe`: `MainCoordinatorView` checks it post-walk-finish and conditionally calls `transcriptionService.transcribeSingle()` per recording. Per-recording **manual** retranscribe always available via the swipe action.

## Non-goals

- **Dynamic Voice toggle.** `NoiseSuppressor`/`AcousticEchoCanceler` on Android are OEM-unreliable; no port.
- **Meditation Guide toggle in Settings.** iOS doesn't put it there either; the toggle lives in MeditationView. Park persistence in 10-D (DataStore-backed) but don't surface UI yet.
- **Voice guide volume + duck-level sliders** (`voiceGuideVolume`, `voiceGuideDuckLevel`). iOS surfaces these in `VoiceGuideSettingsView`. Android already runs the picker as a separate screen — adding the sliders is a **future polish** stage on `VoiceGuidePickerScreen`. Out of 10-D.
- **Transcription model download progress row.** Android bundles `ggml-tiny.en.bin` in the APK, so we don't need iOS's "Downloading model 87%" UI.
- **Bulk multi-select delete.** Not in iOS.
- **Pull-to-refresh.** Not in iOS.
- **Reorder / sort controls.** Not in iOS (fixed sort).
- **Per-row Share / Export.** Not in iOS (only the in-edit-mode copy-icon for transcription text).

## Architecture overview

### Preferences layer

**New file:** `app/src/main/java/org/walktalkmeditate/pilgrim/data/voice/VoicePreferencesRepository.kt`

```kotlin
interface VoicePreferencesRepository {
    val voiceGuideEnabled: StateFlow<Boolean>
    val autoTranscribe: StateFlow<Boolean>
    suspend fun setVoiceGuideEnabled(enabled: Boolean)
    suspend fun setAutoTranscribe(enabled: Boolean)
}
```

**Impl:** `DataStoreVoicePreferencesRepository.kt` (mirrors `DataStorePracticePreferencesRepository.kt`).

- Hilt `@Singleton`, scoped CoroutineScope (`@VoicePreferencesScope`, `SupervisorJob() + Dispatchers.Default` — matches existing pattern).
- StateFlows are `SharingStarted.Eagerly`. **This is load-bearing**: `WalkFinalizationObserver.runFinalize` reads `voicePreferences.autoTranscribe.value` synchronously when a walk finishes, which can happen with no UI subscribers (FGS background, screen off). `WhileSubscribed` would silently return the default (`false`) in this case, breaking auto-transcribe for any walk that finishes off-screen. Same reasoning applies to `voiceGuideEnabled` — read synchronously by the orchestrator's combine block.
- Both flows include `.catch { emit(emptyPreferences()) }` to handle transient DataStore I/O errors (matches `DataStoreSoundsPreferencesRepository`, `DataStoreAppearancePreferencesRepository`, `VoiceGuideSelectionRepository`).
- Keys: `"voiceGuideEnabled"`, `"autoTranscribe"`.
- **`autoTranscribe` upgrade migration:** see "Critical migration" above. Implemented inside the StateFlow's first-read code path.

**Hilt module:** new `VoicePreferencesModule.kt` providing the binding + scope qualifier.

**Tests:**
- `VoicePreferencesRepositoryTest.kt` — DataStore round-trip, default values, migration matrix (3 cases).
- `FakeVoicePreferencesRepository.kt` — test double for VM/orchestrator tests.

### VoiceCard composable

**New file:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/voice/VoiceCard.kt`

Public function: `VoiceCard(state: VoiceCardState, onSetVoiceGuideEnabled: ..., onSetAutoTranscribe: ..., onOpenVoiceGuides: ..., onOpenRecordings: ..., modifier: Modifier = Modifier)`.

`VoiceCardState`: `voiceGuideEnabled: Boolean, autoTranscribe: Boolean, recordingsCount: Int, recordingsSizeBytes: Long`.

Layout matches PracticeCard / AtmosphereCard:
- `Column` with theme background (`pilgrimColors.parchmentSecondary`), 12.dp corner radius, 16.dp padding.
- `CardHeader` ("Voice" + "Speaking and listening").
- `SettingToggleRow("Voice Guide", "Spoken prompts during walks and meditation", voiceGuideEnabled, onSetVoiceGuideEnabled)`.
- `AnimatedVisibility(visible = voiceGuideEnabled, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically(), animationSpec = tween(200, easing = EaseInOut))` wrapping `SettingNavRow("Guide Packs", chevron, onOpenVoiceGuides)` + the trailing `Divider`.
- `SettingToggleRow("Auto-transcribe", "Convert recordings to text after each walk", autoTranscribe, onSetAutoTranscribe)`.
- `Divider`.
- `SettingNavRow("Recordings", detail = formatRecordingsDetail(count, sizeBytes), chevron, onOpenRecordings)`.

`formatRecordingsDetail(count, bytes)` — iOS unconditionally renders the row at every count (verified at `VoiceCard.swift:65-72`):

- `count == 0` → `"0 recordings • 0.0 MB"` (matches iOS).
- `count == 1` → `"1 recording • X.X MB"` (singular).
- `count >= 2` → `"\(count) recordings • X.X MB"` (plural).
- Separator: `" \u{2022} "` — U+2022 with one space on each side (verified verbatim at `VoiceCard.swift:70`).
- MB format: `String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)` (matching iOS — `1_000_000` not `1_048_576`; iOS uses MB-as-decimal for user-facing display).
- Locale: `Locale.US` for the numeric format → ASCII digits regardless of device locale (matches Stage 6-A `Locale.ROOT` lesson).

**Tests:** `VoiceCardTest.kt` covering each row's render + interaction + AnimatedVisibility behavior + recordings detail formatting (zero-state included: `"0 recordings • 0.0 MB"`).

**Recordings count + size aggregation:** `SettingsViewModel` exposes a new `recordingsAggregate: StateFlow<RecordingsAggregate>` where `RecordingsAggregate(count: Int, sizeBytes: Long)`. Computed via:

```kotlin
recordingsAggregate = walkRepository.observeAllVoiceRecordings()
    .map { recs -> RecordingsAggregate(
        count = recs.size,
        sizeBytes = recs.sumOf { fileSystem.fileSizeBytes(it.fileRelativePath) },
    ) }
    .flowOn(Dispatchers.IO)
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecordingsAggregate(0, 0L))
```

`flowOn(Dispatchers.IO)` is required because `fileSizeBytes` does file-stat syscalls. Re-emits when the recordings list changes (insert / update / delete).

### RecordingsListScreen

**New files:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/recordings/RecordingsListScreen.kt`
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/recordings/RecordingsListViewModel.kt`
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/recordings/RecordingRow.kt` (extracted; per-row composable + edit-mode subcomponent)
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/recordings/WaveformBar.kt` (Compose Canvas waveform)
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/recordings/WaveformLoader.kt` (async waveform sample extractor)
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/recordings/RecordingsSection.kt` (data class + grouping)

**ViewModel:**

```kotlin
@HiltViewModel
class RecordingsListViewModel @Inject constructor(
    private val walkRepository: WalkRepository,
    private val playbackController: VoicePlaybackController,
    private val transcriptionScheduler: TranscriptionScheduler,
    private val fileSystem: VoiceRecordingFileSystem,
    @ApplicationContext context: Context,
) : ViewModel() {
    val state: StateFlow<RecordingsListUiState>
    fun onPlay(recordingId: Long)
    fun onPause()
    fun onSeek(recordingId: Long, fraction: Float)
    fun onSpeedCycle()                            // 1.0 → 1.5 → 2.0 → 1.0 (global, sticky)
    fun onSearchChange(query: String)
    fun onTranscriptionEdit(recordingId: Long, newText: String)
    fun onDeleteFile(recordingId: Long)           // file only, transcription kept
    fun onDeleteAllFiles()                         // bulk, transcriptions kept
    fun onRetranscribe(recordingId: Long)
}

sealed interface RecordingsListUiState {
    data object Loading : RecordingsListUiState
    data class Loaded(
        val visibleSections: List<RecordingsSection>,  // post-search-filter sections
        val hasAnyRecordings: Boolean,                 // for empty-state vs no-match-state branch
        val searchQuery: String,
        val playingRecordingId: Long?,
        val playbackPositionFraction: Float,           // 0..1 driven by playbackPositionMillis
        val playbackSpeed: Float,
        val editingRecordingId: Long?,
    ) : RecordingsListUiState
}
```

VM API notes:
- **Entity lookup at the play seam.** The existing `VoicePlaybackController.play()` takes a `VoiceRecording` entity (not an ID). `onPlay(recordingId)` looks up the entity from the loaded sections by ID, then calls `controller.play(entity)`. If lookup fails (rare race — recording deleted between collect and tap), no-op.
- **Speed is global**, matching iOS's single `audioPlayer.playbackSpeed`. `onSpeedCycle()` takes no arguments; the speed pill on each row reads the same global `playbackSpeed` from state. Tapping the speed pill on row B while row A is playing updates the speed for whichever row plays next (and the currently-playing row immediately).
- `visibleSections` carries post-filter sections; `hasAnyRecordings` is a separate Boolean for the empty-vs-no-match state branch (avoids carrying two full list copies in every emission).

Source flows: `combine(walkRepository.observeAllVoiceRecordings(), walkRepository.observeAllWalks())` — group recordings by `walkId`, sort walks by `startTimestamp` desc. Grouping happens in-memory after the Flow joins; with ~1000 walks at most this is cheap. `SharingStarted.Eagerly` (NOT `WhileSubscribed`) so that the playback-position tick keeps the flow hot when the user backgrounds the screen mid-playback — `WhileSubscribed(5_000)` would unsubscribe and resubscribe, dropping the position cursor.

**`VoiceRecordingFileSystem`:**

```kotlin
@Singleton
class VoiceRecordingFileSystem @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun absolutePath(relativePath: String): File
    fun fileExists(relativePath: String): Boolean
    fun fileSizeBytes(relativePath: String): Long       // 0 if missing
    suspend fun deleteFile(relativePath: String): Boolean
}
```

**Canonical path resolver.** Today `ExoPlayerVoicePlaybackController.play()` resolves `File(context.filesDir, recording.fileRelativePath)` directly; `WhisperEngine` and `TranscriptionRunner` do their own resolution. 10-D introduces `VoiceRecordingFileSystem` as the single source of truth for recording paths, and migrates `ExoPlayerVoicePlaybackController` to call `fileSystem.absolutePath(recording.fileRelativePath)`. Per Stage 5-D memory: "Delete operations MUST drive their path computation through the SAME function the write path used." We don't migrate the `WhisperEngine`/`TranscriptionRunner` callers in 10-D (out of scope, they read absolute paths post-resolution), but we add the path-resolver alignment for the playback path so the delete-via-VoiceRecordingFileSystem and play-via-VoiceRecordingFileSystem agree.

Used by VoiceCard's recordings detail aggregation, RecordingsListViewModel's deletes + file-availability checks, and (newly) ExoPlayerVoicePlaybackController's playback-file resolution.

**Playback controller extension:**

`VoicePlaybackController` (interface) and `ExoPlayerVoicePlaybackController` (impl) gain:

```kotlin
fun setPlaybackSpeed(rate: Float)  // 0.5..2.0 — ExoPlayer.setPlaybackParameters
fun seek(fraction: Float)          // 0.0..1.0 — relative to current item duration
val playbackSpeed: StateFlow<Float>
val playbackPositionMillis: StateFlow<Long>
```

Implementation:
- `setPlaybackSpeed`: posts to main looper, calls `player.setPlaybackParameters(PlaybackParameters(rate))` (default `pitch = 1.0` so 1.5x/2.0x doesn't pitch-shift the recording). Updates `playbackSpeed: StateFlow<Float>` (new field, separate from `PlaybackState`). State is persisted across `play()` calls so cycling speed once stays sticky for subsequent recordings (matches iOS `audioPlayer.playbackSpeed` semantics).
- `seek`: posts to main looper. **Crash guard required**: if `player == null || player.currentMediaItem == null || player.duration == C.TIME_UNSET` → return without seeking. Otherwise `player.seekTo((fraction * player.duration).toLong().coerceIn(0L, player.duration))`. Without this guard, dragging the waveform before tapping play computes `fraction * Long.MIN_VALUE` and crashes ExoPlayer.
- `playbackPositionMillis`: emitted on a 100ms tick while playing (existing position-poll pattern from `WalkSummaryViewModel`'s playback view). Drives the waveform progress overlay.

**`PlaybackState` is NOT modified** — `playbackSpeed` is a separate StateFlow on the controller. This avoids a schema change to `PlaybackState` (which `WalkSummaryViewModel` and its tests already pattern-match against — keeping `PlaybackState` shape-stable means no Stage 2-E test churn).

**Waveform:**

- `WaveformLoader.load(relativePath: String): FloatArray` — decodes the WAV header, reads PCM samples, downsamples to N bars (e.g., 64). Cached in an LRU `Map<String, FloatArray>` keyed on relativePath. **Off the main thread** (`Dispatchers.IO`).
- `WaveformBar(samples: FloatArray, progress: Float, onSeek: (Float) -> Unit, modifier: Modifier)` — Compose `Canvas` rendering each sample as a vertical line. Active bars (≤progress) drawn in `pilgrimColors.stone`, inactive in `pilgrimColors.fog.copy(alpha = 0.4f)`. `pointerInput` for tap + drag with `awaitEachGesture`, calling `onSeek(fraction)` **continuously on drag-update** (matches iOS `WaveformBarView` which fires `onSeek` inside `.onChanged { … }`, not `.onEnded`). Tap-to-seek fires once on tap-up. **Reduced-motion compliance:** if user has `accessibilityReduceMotion`, we skip the active-bar reveal animation but keep the static state (the bar still functions as a static seek bar).

**Search:**

A top-app-bar `OutlinedTextField` with leading search icon + clear-X trailing icon (matches Material 3 search idiom — Compose has no `.searchable` equivalent, so we replicate the visual). Filter happens on the VM side (`searchQuery: String` debounced via `.debounce(150)`).

**Swipe actions:**

`SwipeToDismissBox` (Material 3, BOM `2026.03.01` — graduated from experimental, available). With:

- `enableDismissFromStartToEnd = true` (leading swipe → retranscribe).
- `enableDismissFromEndToStart = true` (trailing swipe → delete).
- `positionalThreshold = { distance: Float -> distance * 0.5f }` — 50% of the dismissable width (M3's API is a `(totalDistance: Float) -> Float` lambda; iOS's `allowsFullSwipe = false` corresponds to a high-but-not-full threshold).
- **`confirmValueChange`**: returns `false` for both `StartToEnd` and `EndToStart` — so the box does NOT auto-dismiss. Inside the same lambda, before returning false, we trigger the action: leading → `vm.onRetranscribe(recordingId)`; trailing → set `pendingDeleteId = recordingId` to surface the confirmation `AlertDialog`. The user-visible behavior matches iOS: swipe reveals the action affordance, swipe-completion past threshold fires the action, but the row stays in place (no actual dismiss-the-row animation). Test note: `confirmValueChange` returning false is what keeps the row pinned; failing to wire this produces the iOS "swipe-to-fully-dismiss" behavior we explicitly don't want.

Background composables match the iOS color/icon: leading = stone + `Icons.Filled.Refresh`, trailing = error red + `Icons.Filled.Delete`.

Confirmation dialogs: Material 3 `AlertDialog` with iOS copy verbatim:
- Single delete: `"Delete this recording file? The transcription will be kept."`
- Delete all: `"Delete all recording files? Transcriptions will be kept."`

**Transcription edit mode:**

Tap-to-edit pattern via `Modifier.clickable { editingRecordingId = recording.id }`. In edit mode the row swaps the `Text` for an `OutlinedTextField` configured with:

```kotlin
keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
keyboardActions = KeyboardActions(onDone = { commitTranscription() }),
```

A trailing `"Done"` button (matches iOS) below the field also commits. Tapping outside does NOT auto-save (iOS parity — verified at `RecordingsListView.swift:301-321`).

**Delete-all bottom button:**

Last item in the LazyColumn. Material 3 `TextButton` with error color + body label `"Delete All Recording Files"`. Confirms via dialog above.

**Empty state:**

When `!state.hasAnyRecordings && state.searchQuery.isEmpty()`:

- Centered `Column`: `Icon(Icons.Filled.GraphicEq, fog, size 64dp)` + `Text("Your voice recordings will appear here", body, fog)`.

When `state.searchQuery.isNotEmpty() && state.visibleSections.isEmpty()`:

- Centered `Text("No recordings match", body, fog)`.

(Material 3 has no exact `waveform` icon — `Icons.Filled.GraphicEq` is the closest single-glyph match. Documented as an iOS divergence below.)

**Tests:**
- `RecordingsListViewModelTest.kt` (~12 cases): grouping, search filter, play/pause toggling, speed cycle, transcription commit, delete-file, delete-all, retranscribe.
- `RecordingsListScreenTest.kt` (~6 cases): empty state, populated state, search-no-match, edit-mode, swipe actions, delete-all button.
- `RecordingRowTest.kt` (~5 cases): file-available render, file-unavailable render, speed pill states, transcription edit mode toggle, copy icon click.
- `WaveformBarTest.kt` (~3 cases): drawing pattern (Robolectric Path-bounds), seek-on-tap, seek-on-drag.
- `WaveformLoaderTest.kt` (~3 cases): WAV decode + downsample, 0-byte fallback, malformed-header fallback.
- `VoiceCardTest.kt` (~6 cases): toggle interactions, conditional Guide Packs row, recordings detail formatting (4 cases for count/size), AnimatedVisibility.
- `VoicePreferencesRepositoryTest.kt` (~6 cases): defaults, round-trip, autoTranscribe migration matrix (fresh / upgrade with `"appearance_mode"` set / explicit-write).
- `WalkFinalizationObserverAutoTranscribeTest.kt` (~3 cases): autoTranscribe=true → schedules, =false → does not, manual `onRetranscribe` always works.

### Wiring updates

**1. `VoiceGuideOrchestrator`** (`audio/voiceguide/VoiceGuideOrchestrator.kt`):
- New constructor param: `private val voicePreferences: VoicePreferencesRepository`.
- Combine flow extends from 2-way to 3-way:

```kotlin
combine(walkState, soundsPreferences.soundsEnabled, voicePreferences.voiceGuideEnabled) {
    state, soundsOk, voiceOk -> Triple(state, soundsOk, voiceOk)
}.collect { (state, soundsOk, voiceOk) ->
    val enabled = soundsOk && voiceOk
    when (state) {
        is WalkState.Active -> { /* existing logic, gated by `enabled` */ }
        is WalkState.Meditating -> { /* existing logic, gated by `enabled` */ }
        else -> { /* unchanged */ }
    }
}
```

- Spawn-decision becomes: `enabled && pack != null`. Same `exitingMeditation` semantics.
- **Defensive `.value` race-window guard inside `attemptPlay()` and `playOrSkip()`**: `if (!soundsPreferences.soundsEnabled.value || !voicePreferences.voiceGuideEnabled.value) return`. Mirrors the existing soundsEnabled defensive read.

**2. `WalkFinalizationObserver`** (`walk/WalkFinalizationObserver.kt`):
- Read `voicePreferences.autoTranscribe.value` synchronously inside the Finished handler.
- If true: existing `transcriptionScheduler.scheduleForWalk(walkId)` call.
- If false: skip scheduling; recordings remain transcription-pending and surfaced in the Recordings list with the "Transcribing…" placeholder replaced by an actionable retranscribe state.

**3. `VoiceGuideSelectionRepository`** key rename:
- Current key `selected_voice_guide_pack_id` → `selectedVoiceGuidePackId`.
- Read-fallback: on first read, check both keys; if only the legacy key has a value, copy it into the new key in the same `dataStore.edit { }` transaction (TOCTOU-safe). Subsequent writes use the new key only.
- Test: `VoiceGuideSelectionRepositoryMigrationTest.kt`.

**4. `WhileSubscribed(5_000)` audit on the Recordings list:**
- Verify VM doesn't have any `.value` reads off the StateFlow that would silently return stale values when the screen is unsubscribed. Per the Stage 5-G memory entry, this is the kind of place that bites.

### Routes + nav

- New route: `Routes.RECORDINGS_LIST = "recordings"`.
- Wire `SettingsAction.OpenRecordings` → `PilgrimNavHost` → `RecordingsListScreen`.
- Section-header tap on the recordings screen → `Routes.walkSummary(walkId)` (existing).

### Strings

New `res/values/strings.xml` entries (English-only for now, matching project convention):

```xml
<string name="settings_voice_card_header_title">Voice</string>
<string name="settings_voice_card_header_subtitle">Speaking and listening</string>
<string name="settings_voice_guide_label">Voice Guide</string>
<string name="settings_voice_guide_description">Spoken prompts during walks and meditation</string>
<string name="settings_voice_guide_packs_row">Guide Packs</string>
<string name="settings_auto_transcribe_label">Auto-transcribe</string>
<string name="settings_auto_transcribe_description">Convert recordings to text after each walk</string>
<string name="settings_recordings_row">Recordings</string>
<string name="settings_recordings_detail_zero">No recordings</string>
<string name="settings_recordings_detail_one">1 recording • %1$s MB</string>
<string name="settings_recordings_detail_many">%1$d recordings • %2$s MB</string>
<string name="recordings_screen_title">Recordings</string>
<string name="recordings_search_prompt">Search transcriptions</string>
<string name="recordings_empty_title">Your voice recordings will appear here</string>
<string name="recordings_search_no_match">No recordings match</string>
<string name="recordings_section_header_subtitle">%1$s of recordings</string>
<string name="recordings_row_index">Recording %1$d</string>
<string name="recordings_row_meta">%1$s · %2$s MB</string>
<string name="recordings_row_meta_enhanced">%1$s · %2$s MB · Enhanced</string>
<string name="recordings_row_unavailable">File unavailable</string>
<string name="recordings_action_retranscribe">Retranscribe</string>
<string name="recordings_action_delete">Delete</string>
<string name="recordings_action_done">Done</string>
<string name="recordings_action_delete_all">Delete All Recording Files</string>
<string name="recordings_dialog_delete_one_title">Delete this recording file? The transcription will be kept.</string>
<string name="recordings_dialog_delete_all_title">Delete all recording files? Transcriptions will be kept.</string>
<string name="recordings_dialog_delete_confirm">Delete</string>
<string name="recordings_dialog_delete_all_confirm">Delete All</string>
<string name="recordings_dialog_cancel">Cancel</string>
```

## File-create / file-modify summary

**New (12 source + 10 test):**

Source:
1. `data/voice/VoicePreferencesRepository.kt`
2. `data/voice/DataStoreVoicePreferencesRepository.kt`
3. `data/voice/VoicePreferencesScope.kt`
4. `di/VoicePreferencesModule.kt`
5. `data/voice/VoiceRecordingFileSystem.kt`
6. `ui/settings/voice/VoiceCard.kt`
7. `ui/recordings/RecordingsListScreen.kt`
8. `ui/recordings/RecordingsListViewModel.kt`
9. `ui/recordings/RecordingRow.kt`
10. `ui/recordings/WaveformBar.kt`
11. `ui/recordings/WaveformLoader.kt`
12. `ui/recordings/RecordingsSection.kt`

Test (Robolectric where Compose / Android resources / ExoPlayer / AudioFocusCoordinator are touched):
1. `VoicePreferencesRepositoryTest.kt` — Robolectric (DataStore needs Application context).
2. `FakeVoicePreferencesRepository.kt` — plain JVM (test double, no test cases).
3. `VoiceCardTest.kt` — Robolectric (Compose UI tests).
4. `RecordingsListViewModelTest.kt` — Robolectric (uses Room InMemoryDatabase + ExoPlayer fakes).
5. `RecordingsListScreenTest.kt` — Robolectric (Compose UI + AudioFocusCoordinator path on `onPlay`).
6. `RecordingRowTest.kt` — Robolectric (Compose UI).
7. `WaveformBarTest.kt` — Robolectric (Compose Canvas Path bounds).
8. `WaveformLoaderTest.kt` — plain JVM with synthetic WAV byte array fixtures (no Android deps).
9. `WalkFinalizationObserverAutoTranscribeTest.kt` — Robolectric (WorkManager test infra).
10. `VoiceGuideSelectionRepositoryMigrationTest.kt` — Robolectric (DataStore).

**Modified (10):**
1. `audio/voiceguide/VoiceGuideOrchestrator.kt` — 3-way combine + defensive `.value` reads + `MEDITATION_GUIDE_ALWAYS_ENABLED` constant.
2. `audio/voiceguide/VoiceGuideOrchestratorTest.kt` — update existing tests' constructor calls (new `voicePreferences` parameter).
3. `walk/WalkFinalizationObserver.kt` — `autoTranscribe` gate.
4. `walk/WalkFinalizationObserverTest.kt` — add cases for autoTranscribe=true / false.
5. `data/voiceguide/VoiceGuideSelectionRepository.kt` — key rename + read-fallback (switch StateFlow to `Eagerly` post-migration so synchronous `.value` reads from the orchestrator never see the not-yet-migrated null).
6. `audio/VoicePlaybackController.kt` (interface) — add `setPlaybackSpeed` + `seek` + `playbackSpeed` + `playbackPositionMillis` flows.
7. `audio/ExoPlayerVoicePlaybackController.kt` — implement speed (PlaybackParameters with pitch=1.0), seek (with `C.TIME_UNSET` guard), position-tick poller, route playback file resolution through `VoiceRecordingFileSystem`.
8. `ui/settings/SettingsScreen.kt` — drop the transitional voice-guides nav row, slot in `VoiceCard`.
9. `ui/settings/SettingsViewModel.kt` — add VoicePreferences StateFlow + `setVoiceGuideEnabled` / `setAutoTranscribe` + `recordingsAggregate: StateFlow<RecordingsAggregate>`.
10. `PilgrimNavHost.kt` — new `Routes.RECORDINGS_LIST` + `SettingsAction.OpenRecordings` nav action.
11. `res/values/strings.xml` — ~30 new entries.

(11 modified — 22 new + 11 modified = 33 touched. Earlier count "21 new + 9 modified = 30 touched" was off; corrected.)

## iOS divergences (documented)

1. **No Dynamic Voice toggle.** Android can't honor it reliably across OEMs.
2. **Auto-transcribe migration default differs from iOS.** Android upgrade preserves prior auto-transcribe behavior; fresh installs match iOS (off).
3. **Meditation Guide toggle deferred.** Stays as a code constant in the orchestrator (`MEDITATION_GUIDE_ALWAYS_ENABLED = true`); no DataStore preference yet. Future MeditationView parity stage adds both the pref + per-session UI together.
4. **No model-download progress row.** Android bundles the whisper model.
5. **Storage key migration on `selectedVoiceGuidePackId`.** iOS has always used camelCase; Android's earlier snake_case key is migrated with a one-time read-fallback.
6. **Speed pill icon set.** Material 3 doesn't have a 1:1 `Heart.fill`-style filled circle play icon. Use `Icons.Filled.PlayCircle` / `Icons.Filled.PauseCircle`. Visual close-enough.
7. **Waveform fallback.** If WAV decode fails (truncated header, unsupported encoding) we render a flat-line placeholder — iOS skips the bar entirely. Defensive.
8. **Empty-state icon.** Material 3 has no `waveform` glyph. Use `Icons.Filled.GraphicEq` (spectrum analyzer bars) — closest single-glyph match. Fog color, 64dp.
9. **Section-header navigation: push, not sheet.** iOS uses `.sheet(item:)` to present `WalkSummaryView` modally over the recordings list. Android uses a regular nav-stack push to the same `WalkSummaryScreen`. Justification: Android's nav idiom is back-stack push; modal sheets for full-screen content feel out of place. The destination is identical; only the presentation transition differs.

## Quality bar

- Lint clean. assembleDebug clean.
- Full unit suite green (heap+fork CI fix from PR #65 should keep us under timeout).
- All 28+ new tests green.
- `voiceGuideEnabled = false` mid-walk: voice guide stops within ~1s (panic-mute UX).
- `autoTranscribe = false`: walks finish without auto-transcribe; manual retranscribe via swipe works.
- Recordings list with 100+ rows: scroll smooth at 60fps, waveform render budget < 50ms per row on a mid-tier device (caching helps after first scroll).
- TalkBack reads each row label + duration + transcription preview correctly.
- Dark mode: every surface flips colors via `pilgrimColors`.

## Stage size

- LoC estimate: ~1500 net (RecordingsListView is the biggest single screen we've built).
- Files: 22 new + 11 modified = 33 touched.
- Tests: ~28 cases across 10 new test files.
- Estimated effort: **14–18 hours** per master spec; bundled into one PR per the precedent set by 10-B and 10-C.

## Recommended order within 10-D

1. Preferences layer + tests (small, foundational).
2. Orchestrator + finalizer wiring (smallest behavioral surface; defends against build/test breakage early).
3. Selection repository key migration + test.
4. ExoPlayer rate + seek + tests.
5. VoiceCard + tests.
6. SettingsScreen integration.
7. RecordingsListScreen + sub-composables + VM + tests.
8. Strings + nav route.
9. Polish loop (`/polish` → fresh initial review → fresh adversarial review).
10. Manual device QA on OnePlus 13.

## Open questions for CHECKPOINT 1

1. **Approve the autoTranscribe migration?** Specifically: seed `true` if any pre-existing pref key is present (probe keys `"appearance_mode"`, `"soundsEnabled"`, `"selected_voice_guide_pack_id"`, `"selectedVoiceGuidePackId"`, plus the Stage 10-C camelCase keys), else `false` (fresh install).
2. **Confirm Dynamic Voice is permanently skipped** (not even a stubbed-out toggle).
3. **Approve the bundled-PR shape** (one PR for all of 10-D, ~1500 LoC, 33 files).
4. **Waveform: real WAV decode in 10-D, or stub-bars-now-real-decode-later?** Recommended: real, since WAV is simple (16-bit PCM, fixed sample rate from VoiceRecorder); ~80 LoC for the decoder + downsampler.
5. **Speed pill: cycle order 1.0 → 1.5 → 2.0 → 1.0 (iOS exact), or include 0.75 as a slow option?** Recommended: iOS-exact for parity (no 0.75).
