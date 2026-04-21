# Stage 5-F Design — Soundscape Playback

**Date:** 2026-04-20
**Author:** Pilgrim Android port
**Status:** Design — pending approval

## Context

Phase 5's last unshipped deliverable is soundscape playback ("Bell and soundscape playback (bundled + lazy-download from existing pilgrimapp.org manifest)" — bell shipped in 5-B; soundscape is what's left). A soundscape is an ambient looping audio track that plays quietly during meditation sessions (rain, forest, bell chimes, etc.). The voice-guide player's audio focus request (`GAIN_TRANSIENT_MAY_DUCK` + `setWillPauseWhenDucked(false)`, set up in Stage 5-E) already configures the OS to auto-duck any background stream holding `GAIN` — so Stage 5-E's soundscape contract is met by configuring the soundscape player correctly, no changes to voice-guide.

**Key iOS finding**: soundscape plays during **meditation only**, not during active walking. iOS's `SoundManagement.onWalkStart()` plays only the bell, while `onMeditationStart()` plays the bell, waits 0.5s, then starts soundscape. The intent's "soundscape during walks" framing was wrong — correcting to match iOS: soundscape is a **meditation accompaniment**, not a walk backdrop.

## Scope

**In scope:**
- **Unified `AudioManifest` + `AudioAsset` models** matching iOS's `AudioAsset.swift` shape (type-tagged `bell` / `soundscape`). Android filters to `soundscape` at the repository layer; bells continue to ship bundled per Stage 5-B.
- **`AudioManifestService`** — fetches `https://cdn.pilgrimapp.org/audio/manifest.json`, caches locally, exposes `StateFlow<List<AudioAsset>>`. Same shape as Stage 5-C's `VoiceGuideManifestService` (async init, atomic cache write, `initialLoad: Deferred<Unit>`).
- **`SoundscapeFileStore`** — on-disk layout under `filesDir/audio/soundscape/<asset.id>.aac` (matching iOS's flat-per-asset scheme). Size-verified presence check.
- **`SoundscapeDownloadWorker`** + `WorkManagerSoundscapeDownloadScheduler` — per-asset WorkManager downloads, unique-work namespace `soundscape_download_<id>` (separate from voice-guide's `voice_guide_download_*`). Single in-worker retry + atomic tmp-and-rename + size verification, mirroring Stage 5-D's `VoiceGuideDownloadWorker`. URL scheme: `<base>/soundscape/<asset.id>.aac` (iOS's convention — not `r2Key` verbatim because iOS reconstructs the path from id).
- **`SoundscapeSelectionRepository`** — DataStore-backed `selectedSoundscapeId: StateFlow<String?>`. Separate key (`selected_soundscape_id`) from voice-guide so user can combine independent selections. No auto-select-on-first-download (iOS doesn't either; user explicitly picks).
- **`SoundscapeCatalogRepository`** — joins manifest + filesystem + selection + in-flight progress into `List<SoundscapeState>` DTOs for the picker. Same shape as Stage 5-D's `VoiceGuideCatalogRepository` (including terminal-state filesystem re-read, manifest-worker-side-effect handling, and `isActive` job-guard).
- **`SoundscapePlayer`** — interface + `ExoPlayerSoundscapePlayer` impl. ExoPlayer with `REPEAT_MODE_ONE`, standalone `AudioFocusRequest(AUDIOFOCUS_GAIN)`, `handleAudioFocus = false`, `BECOMING_NOISY` receiver (Stage 5-E lesson), `AudioAttributes(USAGE_MEDIA + CONTENT_TYPE_MUSIC)`.
- **`SoundscapeOrchestrator`** — `@Singleton` observing `WalkController.state`. Starts soundscape on `Meditating` state (with a 500ms delay to let the bell ring), stops on `Meditating → anything-else`.
- **Settings picker** — extend `SettingsScreen` with a second row ("Soundscapes"). New `SoundscapePickerScreen` — single list, tap-to-select (downloaded) or tap-to-download (not-downloaded), long-press for delete. Simpler than Stage 5-D's picker+detail split — iOS's UI is a one-screen list.
- **Tests**: manifest parsing, file store, download worker (`.build()` mandate), scheduler, catalog repository join, player (ExoPlayer builder + REPEAT_MODE + focus request smoke test), orchestrator state-transition tests.

**NOT in scope (deferred):**
- **User-adjustable soundscape / duck-level volume sliders** (iOS has these; Android uses fixed defaults — soundscape at 1.0, OS-managed duck level). Phase 10.
- **Crossfade between soundscapes** when switching mid-session (iOS has 4s crossfade). ExoPlayer supports multiple instances but adds complexity; MVP uses hard cut on switch.
- **Fade-in on start / fade-out on stop** (iOS does 2s fade). MVP does hard start/stop. Soundscape starts 500ms after the meditation bell which acts as a natural transition cue.
- **Gapless loop at boundary** — ExoPlayer's `REPEAT_MODE_ONE` is continuous, but gap-free-ness depends on the audio file having appropriate metadata. Any click is an audio-engineering fix, not an app-code fix.
- **Auto-select on first download** (iOS doesn't do it for soundscape either).
- **In-picker audition playback** (iOS has play/stop per row). Defer.
- **Auto-resume on `AUDIOFOCUS_GAIN`** after transient loss. MVP: if paused on loss, next orchestrator spawn restarts.
- **Pack-switch mid-meditation** (iOS supports via WalkOptionsSheet). MVP: user switches outside meditation.
- **Device QA pass (Stage 5-G)**.

## Recommended approach

A parallel-stack mirror of Stage 5-D for soundscape, with two deliberate divergences: (1) OS-level ducking instead of manual volume fades — the Android audio-focus matrix already delivers this correctly, and the iOS manual fade is UX polish we can defer; (2) simplified one-screen picker (list + tap + long-press delete) instead of picker+detail — matches iOS's single-sheet UI and trims ~150 LOC. Orchestrator spawns on `Meditating` state only, not `Active`, matching iOS's meditation-accompaniment semantics.

### Why this approach

Mirroring Stage 5-D's successful parallel stack (`VoiceGuide*` family → `Soundscape*` family) is proven — the voice-guide layer shipped clean through 4 review cycles. Attempting a generic "asset pack" abstraction now (before a third asset type exists) would be premature factoring that slows this stage without payoff. The two simplifications are intentional scope reductions backed by explicit reasoning: the OS ducking handles the correctness requirement (voice-guide fires → soundscape volume dips → voice-guide done → soundscape restores) — the only thing we lose is the 0.5s fade curve, which is a UX nice-to-have. The one-screen picker matches iOS exactly; the voice-guide picker+detail split was because voice-guide packs have rich metadata (duration, meditation-included flag, description) worth a dedicated screen — soundscapes are just a name + file, a list row is enough.

### What was considered and rejected

- **Shared generic asset-pack abstraction** — premature factoring. Rejected until Phase 8 or a third asset type materializes.
- **Separate soundscape manifest endpoint** — iOS uses a unified audio manifest. Matching iOS's shape means we parse one JSON and filter; introduces `AudioManifest`/`AudioAsset` types that are forward-compatible if Android ever downloads bells too.
- **Manual volume-dip ducking (iOS-parity)** — needs a timer or `ValueAnimator` for the 0.5s fade + volume-state tracking across focus events. OS auto-duck is zero-code and semantically correct. Can polish later with an animator if on-device QA shows the instant duck is jarring.
- **Lifecycle tied to `Active` walk state (per intent's original framing)** — iOS's soundscape plays during meditation only. Following iOS keeps both apps consistent and dramatically simplifies the ducking story (soundscape and walking voice-guide prompts never overlap, because walk-context voice prompts fire only in `Active` and soundscape fires only in `Meditating`; only meditation-context voice prompts can overlap with soundscape, and those are the relevant duck targets).
- **Picker-with-detail-screen (Stage 5-D parity)** — too heavy for iOS's simpler per-asset model (just name + id). Collapsing to one screen matches iOS and saves files.

## Architecture

### Data flow

```
AudioManifestService.assets: StateFlow<List<AudioAsset>>
   │  filters `type == soundscape`
   ▼
SoundscapeCatalogRepository (@Singleton)
   ◇ joins with ◇
   │    SoundscapeFileStore (filesystem source of truth)
   │    SoundscapeDownloadScheduler.observe(assetId): Flow<DownloadProgress?>
   │    SoundscapeSelectionRepository.selectedSoundscapeId: StateFlow<String?>
   ▼
StateFlow<List<SoundscapeState>>
   ▼
SoundscapePickerViewModel → SoundscapePickerScreen

WalkController.state: StateFlow<WalkState>
   │    on Meditating
   ▼
SoundscapeOrchestrator (@Singleton, @VoiceGuidePlaybackScope-style scope)
   │    waits 500ms (let bell ring)
   │    resolves selected + downloaded asset
   ▼
SoundscapePlayer.play(file)
   │    ExoPlayer REPEAT_MODE_ONE + AUDIOFOCUS_GAIN
   │    OS auto-ducks when voice-guide fires GAIN_TRANSIENT_MAY_DUCK
   ▼
Player loops until orchestrator calls stop() on Meditating→other transition
```

### Key types

#### `AudioAsset` / `AudioManifest` (new, `@Serializable`)

```kotlin
@Serializable
data class AudioManifest(
    val version: String,
    val assets: List<AudioAsset>,
)

@Immutable
@Serializable
data class AudioAsset(
    val id: String,
    val type: String,            // "bell" | "soundscape"
    val name: String,
    val displayName: String,
    val durationSec: Double,
    val r2Key: String,
    val fileSizeBytes: Long,
    val usageTags: List<String> = emptyList(),
)
```

Notes:
- `@Immutable` for Compose stability (Stage 5-D lesson).
- Forward-compat: Android parses bell entries and silently ignores them; Stage 5-B bells are bundled, not downloaded. If we ever want to download bells, the infrastructure is already there.

#### `AudioManifestService` (new, `@Singleton`)

Same shape as Stage 5-C's `VoiceGuideManifestService`:
- Async `init { scope.launch(Dispatchers.IO) { loadLocalManifestFromDisk() } }` + `initialLoad: Deferred<Unit>`
- `syncIfNeeded()` with CAS dedup
- Atomic cache write at `filesDir/audio_manifest.json`
- `assets: StateFlow<List<AudioAsset>>`
- Helper: `fun soundscapes(): List<AudioAsset>` = `assets.value.filter { it.type == "soundscape" }`
- Cancellation-safe catch blocks (re-throw CE in all `try/catch(Throwable)` paths — Stage 5-C lesson)

#### `SoundscapeFileStore` (new, `@Singleton`)

```kotlin
class SoundscapeFileStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val root: File = File(context.filesDir, "audio/soundscape").also { it.mkdirs() }
    private val _invalidations = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val invalidations: SharedFlow<Unit> = _invalidations.asSharedFlow()

    fun fileFor(asset: AudioAsset): File = File(root, "${asset.id}.aac").also { it.parentFile?.mkdirs() }
    fun isAvailable(asset: AudioAsset): Boolean {
        val f = fileFor(asset)
        return f.exists() && f.length() == asset.fileSizeBytes
    }
    suspend fun delete(asset: AudioAsset) {
        withContext(Dispatchers.IO) { fileFor(asset).delete() }
        _invalidations.tryEmit(Unit)
    }
}
```

#### `SoundscapeDownloadWorker` + Scheduler (new, parallel to voice-guide)

Same shape as `VoiceGuideDownloadWorker`:
- `@HiltWorker` + `CoroutineWorker`
- Input: `KEY_ASSET_ID`
- Single asset to download (simpler than voice-guide's per-pack multi-prompt loop)
- Atomic write-to-tmp + rename + size verify
- Single in-worker retry
- `Result.success()` if file present; `Result.retry()` otherwise
- `CancellationException` re-throw + tmp cleanup
- `manifestService.initialLoad.await()` before asset lookup (Stage 5-D lesson)

Constraints: `NetworkType.CONNECTED` + `setRequiresStorageNotLow(true)`. Not expedited.

URL: `<prompt-base-url-equivalent>/soundscape/<assetId>.aac`. Adds a new config constant `AUDIO_BASE_URL = "https://cdn.pilgrimapp.org/audio"` and a qualifier `@SoundscapeBaseUrl`.

Scheduler interface identical to `VoiceGuideDownloadScheduler`:
- `enqueue(assetId)` — KEEP
- `retry(assetId)` — REPLACE
- `cancel(assetId)` — cancel
- `observe(assetId): Flow<DownloadProgress?>` — reuse existing `DownloadProgress` type from 5-D (it's in `data.voiceguide`; could promote to a shared `data.audio` package — propose doing that here).

Unique-work name: `soundscape_download_<assetId>`.

#### `SoundscapeSelectionRepository` (new, `@Singleton`, DataStore)

Same shape as `VoiceGuideSelectionRepository`. Different key:
- `stringPreferencesKey("selected_soundscape_id")`
- `selectedSoundscapeId: StateFlow<String?>`
- `select(id)`, `deselect()`, `selectIfUnset(id)` (last unused in MVP but provides forward-compat if we enable auto-select later)

#### `SoundscapeState` (new, sealed class, `@Immutable`)

```kotlin
@Immutable
sealed class SoundscapeState {
    abstract val asset: AudioAsset
    abstract val isSelected: Boolean

    @Immutable data class NotDownloaded(override val asset: AudioAsset, override val isSelected: Boolean) : SoundscapeState()
    @Immutable data class Downloading(override val asset: AudioAsset, override val isSelected: Boolean) : SoundscapeState()
    @Immutable data class Downloaded(override val asset: AudioAsset, override val isSelected: Boolean) : SoundscapeState()
    @Immutable data class Failed(override val asset: AudioAsset, override val isSelected: Boolean, val reason: String) : SoundscapeState()
}
```

Simpler than voice-guide's state (no completion counts — single file).

#### `SoundscapeCatalogRepository` (new, `@Singleton`)

Mirror of `VoiceGuideCatalogRepository` with simpler state:
- `combine(manifestService.assets, selection.selectedSoundscapeId, fileStore.invalidations.onStart { emit(Unit) })` → base states
- `flatMapLatest { combine per-asset scheduler.observe flows }` → applies progress overlay
- `applyProgress(base, progress)` re-reads filesystem on terminal `Succeeded`/`Cancelled` (Stage 5-D lesson)
- Wraps filesystem-read in `withContext(Dispatchers.IO)` inside the combine (Stage 5-D lesson)
- Actions pass through: `download`, `retry`, `cancel`, `select`, `deselect`, `delete` (delete cancels in-flight worker first — Stage 5-D defense-in-depth)

Filters to `type == "soundscape"` when consuming `manifestService.assets`.

#### `SoundscapePlayer` interface + `ExoPlayerSoundscapePlayer` impl (new, `@Singleton`)

```kotlin
interface SoundscapePlayer {
    val state: StateFlow<State>
    fun play(file: File)
    fun stop()
    fun release()

    sealed class State {
        data object Idle : State()
        data object Playing : State()
        data class Error(val reason: String) : State()
    }
}
```

Implementation:
- ExoPlayer lazy-init on main handler (Stage 2-F pattern)
- `setRepeatMode(Player.REPEAT_MODE_ONE)` before prepare
- `AudioAttributes(USAGE_MEDIA + CONTENT_TYPE_MUSIC)` — distinguishes from voice-guide's `CONTENT_TYPE_SPEECH`
- `handleAudioFocus = false` (we own the focus path)
- Standalone `AudioFocusRequest(AUDIOFOCUS_GAIN)` — LONG-TERM ownership, not transient
- `setWillPauseWhenDucked(false)` — we want OS auto-duck, no pause
- Focus listener:
  - `LOSS` → full stop (user app took focus permanently)
  - `LOSS_TRANSIENT` → pause (call / nav prompt); state = Idle
  - `LOSS_TRANSIENT_CAN_DUCK` → no-op (OS auto-ducks; we don't pause or fade)
  - `GAIN` → if player exists but is not playing, resume (transient loss ended). Otherwise no-op.
- `BECOMING_NOISY` receiver (Stage 5-E lesson) registered in `play()`, unregistered in `stop()`/`release()`. API 33+ uses `RECEIVER_NOT_EXPORTED`.
- No `onFinished` callback (ambient, no completion concept); release only on process death.

#### `SoundscapeOrchestrator` (new, `@Singleton`, `@SoundscapePlaybackScope`)

```kotlin
@Singleton
class SoundscapeOrchestrator @Inject constructor(
    @SoundscapeObservedWalkState private val walkState: StateFlow<@JvmSuppressWildcards WalkState>,
    @SoundscapeSelectedAssetId private val selectedAssetId: StateFlow<@JvmSuppressWildcards String?>,
    private val manifestService: AudioManifestService,
    private val fileStore: SoundscapeFileStore,
    private val player: SoundscapePlayer,
    @SoundscapePlaybackScope private val scope: CoroutineScope,
) {
    fun start() { scope.launch { observe() } }

    private suspend fun observe() {
        var playJob: Job? = null
        walkState.collect { state ->
            when (state) {
                is WalkState.Meditating -> {
                    if (playJob?.isActive != true) {
                        val asset = eligibleAssetOrNullSync()
                        if (asset != null) {
                            playJob = scope.launch {
                                try {
                                    // iOS delays 500ms after bell to let it
                                    // land before soundscape starts — matches.
                                    delay(MEDITATION_START_DELAY_MS)
                                    if (!currentCoroutineContext().isActive) return@launch
                                    val file = fileStore.fileFor(asset)
                                    if (file.exists() && file.length() > 0L) {
                                        player.play(file)
                                    }
                                } catch (ce: CancellationException) { throw ce }
                                catch (t: Throwable) { Log.w(TAG, "soundscape start failed", t) }
                            }
                        }
                    }
                }
                is WalkState.Active, is WalkState.Paused,
                WalkState.Idle, is WalkState.Finished -> {
                    playJob?.cancel(); playJob = null
                    safeStopPlayer()
                }
            }
        }
    }

    private fun eligibleAssetOrNullSync(): AudioAsset? {
        val id = selectedAssetId.value ?: return null
        return manifestService.soundscapes()
            .firstOrNull { it.id == id }
            ?.takeIf { fileStore.isAvailable(it) }
    }

    private fun safeStopPlayer() {
        try { player.stop() } catch (t: Throwable) { Log.w(TAG, "player.stop failed", t) }
    }

    private companion object {
        const val TAG = "SoundscapeOrch"
        const val MEDITATION_START_DELAY_MS = 500L
    }
}
```

Simpler than `VoiceGuideOrchestrator`:
- No post-meditation silence buffer (voice-guide-specific)
- No meditation vs walk context split (single context)
- No 30-sec tick loop (player loops internally)
- No scheduler
- `Job?.isActive != true` guard (Stage 5-E lesson)
- Non-suspend `eligibleAssetOrNullSync` (Stage 5-E lesson — avoid StateFlow conflation via suspend calls inside `collect`)

#### UI: `SoundscapePickerScreen` (new)

Single-screen list, matching iOS's soundscape picker sheet:
- `LazyColumn` of soundscapes
- Per row: icon, name, trailing state indicator (download-cloud / spinner / checkmark / error)
- Tap row:
  - `NotDownloaded` → download (scheduler.enqueue)
  - `Downloading` → no-op (or cancel via long-press)
  - `Downloaded` → toggle selection (if selected, deselect; else select)
  - `Failed` → retry (scheduler.retry)
- Long-press:
  - `Downloaded` → delete dialog
- Selected row gets a visual highlight + checkmark

No detail screen. No audition playback. No size/description (soundscapes don't have rich metadata in iOS's `AudioAsset`).

#### `SettingsScreen` extension

Add a second `SettingsRow`:

```kotlin
SettingsRow(
    icon = Icons.Default.Spa,  // or similar ambient icon
    title = "Soundscapes",
    subtitle = "Ambient accompaniment for meditation",
    onClick = onOpenSoundscapes,
)
```

New nav route `Routes.SOUNDSCAPE_PICKER = "soundscapes"`.

### DI updates

- New `SoundscapeModule` with `@Binds SoundscapePlayer`, `@Binds SoundscapeDownloadScheduler`, scope providers (`@SoundscapePlaybackScope`, `@SoundscapeSelectionScope`, `@SoundscapeCatalogScope`), and the `@Provides` for `StateFlow` observations (`@SoundscapeObservedWalkState`, `@SoundscapeSelectedAssetId`) mirroring Stage 5-E's pattern.
- `AudioManifestService` gets its own scope + URL qualifier (`@AudioManifestScope`, `@AudioManifestUrl`). Following Stage 5-C's `NetworkModule` pattern — the URL provider lives in `NetworkModule` alongside voice-guide's.

### `PilgrimApp.onCreate` wiring

Inject + `start()` both new observers:
- `AudioManifestDownloadObserver` (if we add auto-select later; skip for MVP — match voice-guide's observer pattern where we DID add it, but iOS explicitly doesn't auto-select soundscape. Skip entirely.)
- `SoundscapeOrchestrator.start()` — required; mirrors `VoiceGuideOrchestrator.start()`.

### Ducking contract verification

Voice-guide's existing `ExoPlayerVoiceGuidePlayer.requestFocus()` (Stage 5-E):
```kotlin
val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
    .setAudioAttributes(...USAGE_MEDIA + CONTENT_TYPE_SPEECH...)
    .setWillPauseWhenDucked(false)  // OS handles ducking
    ...
```

Soundscape's new `ExoPlayerSoundscapePlayer.requestFocus()`:
```kotlin
val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
    .setAudioAttributes(...USAGE_MEDIA + CONTENT_TYPE_MUSIC...)
    .setWillPauseWhenDucked(false)
    .setOnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AUDIOFOCUS_LOSS -> stop()
            AUDIOFOCUS_LOSS_TRANSIENT -> pause()
            AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> Unit  // OS auto-ducks
            AUDIOFOCUS_GAIN -> resume-if-paused
        }
    }
```

When voice-guide fires MAY_DUCK with `setWillPauseWhenDucked(false)` while soundscape holds `GAIN`, the OS (per Android docs):
1. Reduces the soundscape stream volume (ducks) automatically.
2. Fires `LOSS_TRANSIENT_CAN_DUCK` to soundscape's listener (informational).
3. Voice-guide plays at full volume.
4. Voice-guide abandons → OS restores soundscape volume.
5. Fires `GAIN` to soundscape's listener (informational).

No manual volume fades needed on either side.

Integration test coverage: spawn both players, verify soundscape player's listener receives `LOSS_TRANSIENT_CAN_DUCK` when voice-guide requests focus. (Robolectric's `ShadowAudioManager` may or may not simulate this end-to-end; fallback is a unit test of the listener logic with manually-invoked focus changes.)

## Testing strategy

- **`AudioManifestTest`** — pure JSON parsing (minimal manifest, bell+soundscape mix, unknown type tolerance, round-trip). ~5 cases.
- **`AudioManifestServiceTest`** — Robolectric + MockWebServer (same pattern as Stage 5-C's `VoiceGuideManifestServiceTest`). ~8 cases including async init, atomic rewrite, CAS dedup, soundscape filtering.
- **`SoundscapeFileStoreTest`** — Robolectric. ~4 cases (presence, size mismatch, delete broadcasts invalidation).
- **`SoundscapeSelectionRepositoryTest`** — Robolectric + real DataStore. ~4 cases.
- **`SoundscapeDownloadWorkerTest`** — Robolectric + MockWebServer + TestListenableWorkerBuilder. ~5 cases (success, skip-present, 503 retry, size mismatch, missing asset id).
- **`WorkManagerSoundscapeDownloadSchedulerTest`** — Robolectric + `WorkManagerTestInitHelper`. Mandatory `.build()` exercise per CLAUDE.md. ~3 cases (enqueue KEEP, retry REPLACE, cancel).
- **`SoundscapeCatalogRepositoryTest`** — Robolectric with real FileStore + Selection + fake scheduler. ~5 cases.
- **`ExoPlayerSoundscapePlayerTest`** — Robolectric. Exercise ExoPlayer `REPEAT_MODE_ONE` + focus request `GAIN` builder. ~4 cases (constructs without crashing; stop abandons focus; release is idempotent; missing file transitions to Error).
- **`SoundscapeOrchestratorTest`** — Robolectric with `MutableStateFlow<WalkState>` + fake player. ~6 cases:
  - No selection → no spawn on Meditating
  - Selection + not-downloaded → no spawn
  - Selection + downloaded → spawn after 500ms delay
  - Meditating → Active → stop
  - Meditating → Finished → stop
  - Meditating → Paused → stop
- **`SoundscapePickerScreenTest`** — Compose + Robolectric. ~3 cases covering the four state variants.

Tests use `runCurrent()` pattern from Stage 5-E for the 500ms-delay coroutine, + `advanceTimeBy(500)` to cross the delay threshold.

## Sequencing

1. `AudioAsset` + `AudioManifest` data models + parsing tests.
2. `AudioManifestService` + tests.
3. `SoundscapeFileStore` + tests.
4. `SoundscapeSelectionRepository` + tests.
5. `SoundscapeDownloadWorker` + `SoundscapeDownloadScheduler` + tests.
6. `SoundscapeState` + `SoundscapeCatalogRepository` + tests.
7. `SoundscapePlayer` interface + `ExoPlayerSoundscapePlayer` + tests.
8. `SoundscapeOrchestrator` + tests.
9. `SoundscapePickerViewModel` + `SoundscapePickerScreen`.
10. `SettingsScreen` extension + navigation wiring + `PilgrimApp` wiring.
11. DI modules finalized + full build verify.

Scope: ~16 new classes, ~9 test files. Comparable to Stage 5-D.

## Risks + open items

- **Promoted shared `DownloadProgress` type**: currently in `data.voiceguide`. Either:
  - (a) Leave it there and soundscape imports it across package boundary (ugly).
  - (b) Promote to `data.audio` or similar shared package (cleanest, touches Stage 5-D files).
  - **Decision**: promote to a new `data.audio.download` package as part of this stage.
- **Asset vs Pack naming**: voice-guide calls them "packs"; iOS soundscape calls them "assets." I'll use "asset" to match iOS and distinguish semantically.
- **Auto-resume on GAIN after transient loss**: if user receives a phone call during meditation and the soundscape pauses, should it resume when the call ends? iOS's AVAudioPlayer auto-resumes via session interruption. For Android, the GAIN branch of our focus listener can call `player.play()` again IF we stashed the player's paused state. Simpler: on LOSS_TRANSIENT we fully stop the player (not pause); on GAIN we do nothing, and the orchestrator's next Meditating re-evaluation restarts it. **But**: WalkState doesn't re-emit Meditating just because the phone call ended. So a brief call during meditation would kill the soundscape for the rest of the session. Trade-off: pause-and-resume is nicer UX; stop-and-wait-for-meditation-restart is simpler. **Decision**: pause-and-resume via focus listener — small enough complexity, matches user expectation.

## References

- Stage 5-B bell player: `app/src/main/java/org/walktalkmeditate/pilgrim/audio/BellPlayer.kt`
- Stage 5-C manifest service: `app/src/main/java/org/walktalkmeditate/pilgrim/data/voiceguide/VoiceGuideManifestService.kt`
- Stage 5-D download stack: `app/src/main/java/org/walktalkmeditate/pilgrim/data/voiceguide/*`
- Stage 5-E player + orchestrator: `app/src/main/java/org/walktalkmeditate/pilgrim/audio/voiceguide/*`
- iOS soundscape: `../pilgrim-ios/Pilgrim/Models/Audio/SoundscapePlayer.swift`, `AudioAsset.swift`, `AudioManifestService.swift`, `SoundManagement.swift`
- iOS settings UI: `../pilgrim-ios/Pilgrim/Scenes/Settings/SoundSettingsView.swift`
