# Stage 5-F Implementation Plan — Soundscape Playback

**Spec:** `docs/superpowers/specs/2026-04-20-stage-5f-soundscape-playback-design.md`
**Worktree:** `/Users/rubberduck/GitHub/momentmaker/.worktrees/stage-5f-soundscape-playback`
**Branch:** `stage-5f/soundscape-playback` off `main`

## Prefix for every test run

```sh
export JAVA_HOME=~/.asdf/installs/java/temurin-17.0.18+8 && export PATH=$JAVA_HOME/bin:$PATH
```

Full phase-gate verify:
```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

## Task 0 — Worktree + commit spec + plan

```sh
git worktree add /Users/rubberduck/GitHub/momentmaker/.worktrees/stage-5f-soundscape-playback -b stage-5f/soundscape-playback main
cp docs/superpowers/specs/2026-04-20-stage-5f-soundscape-playback-design.md /Users/rubberduck/GitHub/momentmaker/.worktrees/stage-5f-soundscape-playback/docs/superpowers/specs/
cp docs/superpowers/plans/2026-04-20-stage-5f-soundscape-playback.md /Users/rubberduck/GitHub/momentmaker/.worktrees/stage-5f-soundscape-playback/docs/superpowers/plans/
```

Commit: `docs(plan): Stage 5-F soundscape playback design + plan`

---

## Task 1 — `AudioAsset` + `AudioManifest` data models + parsing tests

**New files:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/data/audio/AudioAsset.kt` — `@Serializable @Immutable` data class + `@Serializable` `AudioManifest`
- `app/src/main/java/org/walktalkmeditate/pilgrim/data/audio/AudioConfig.kt` — internal object with `MANIFEST_URL = "https://cdn.pilgrimapp.org/audio/manifest.json"` and `AUDIO_BASE_URL = "https://cdn.pilgrimapp.org/audio"`
- `app/src/test/java/.../data/audio/AudioManifestTest.kt` — 5 pure-JVM parsing cases (empty manifest, bell-only, soundscape-only, mixed bell+soundscape, unknown-type tolerance, round-trip)

Commit: `feat(audio): Stage 5-F audio manifest data models`

---

## Task 2 — `AudioManifestService` + tests

Based on `VoiceGuideManifestService`. Same async init + CAS dedup + atomic cache + `initialLoad: Deferred<Unit>`.

**New files:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/data/audio/AudioManifestService.kt` (@Singleton)
- `app/src/main/java/org/walktalkmeditate/pilgrim/data/audio/AudioManifestScope.kt` + `AudioManifestUrl.kt` qualifiers
- `app/src/test/java/.../data/audio/AudioManifestServiceTest.kt` — ~8 Robolectric + MockWebServer cases

**Modify:** `app/src/main/java/org/walktalkmeditate/pilgrim/di/NetworkModule.kt` — add `@AudioManifestUrl` provider + `@AudioManifestScope` provider (SupervisorJob + Dispatchers.Default).

Key API:
```kotlin
@Singleton
class AudioManifestService @Inject constructor(
    @ApplicationContext context: Context,
    httpClient: OkHttpClient,
    json: Json,
    @AudioManifestScope scope: CoroutineScope,
    @AudioManifestUrl manifestUrl: String,
) {
    val assets: StateFlow<List<AudioAsset>>
    val isSyncing: StateFlow<Boolean>
    val initialLoad: Deferred<Unit>

    fun soundscapes(): List<AudioAsset>  // filter type == "soundscape"
    fun asset(id: String): AudioAsset?
    fun syncIfNeeded()
}
```

Commit: `feat(audio): Stage 5-F audio manifest service`

---

## Task 3 — Promote `DownloadProgress` to shared `data.audio.download` package

Stage 5-D's `DownloadProgress` lives in `data.voiceguide`. Promote so both soundscape and voice-guide share it.

**Move:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/data/voiceguide/VoiceGuideDownloadScheduler.kt` → extract `DownloadProgress` to new file `app/src/main/java/org/walktalkmeditate/pilgrim/data/audio/download/DownloadProgress.kt`
- Update import in existing `VoiceGuideDownloadScheduler.kt`, `VoiceGuideCatalogRepository.kt`, and tests.

Run tests to verify nothing broke.

Commit: `chore(audio): Stage 5-F promote DownloadProgress to shared package`

---

## Task 4 — `SoundscapeFileStore` + tests

**New files:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/data/soundscape/SoundscapeFileStore.kt`
- `app/src/test/java/.../data/soundscape/SoundscapeFileStoreTest.kt` — ~4 cases (presence, size mismatch, delete emits invalidation, missing file handling)

Structure matches `VoiceGuideFileStore`:
- Root: `filesDir/audio/soundscape/` (flat, `<assetId>.aac`)
- `fileFor(asset)` creates parent dirs
- `isAvailable(asset)`: exists + length matches `fileSizeBytes`
- `delete(asset)` is `suspend` — hops to `Dispatchers.IO` (Stage 5-D lesson: never block Main)
- `invalidations: SharedFlow<Unit>` with DROP_OLDEST

Commit: `feat(soundscape): Stage 5-F file store`

---

## Task 5 — `SoundscapeSelectionRepository` + tests

**New files:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/data/soundscape/SoundscapeSelectionRepository.kt`
- `app/src/main/java/org/walktalkmeditate/pilgrim/data/soundscape/SoundscapeSelectionScope.kt` qualifier
- `app/src/test/java/.../data/soundscape/SoundscapeSelectionRepositoryTest.kt` — ~4 cases (initial null, select persists, deselect, survives re-construction)

Same shape as `VoiceGuideSelectionRepository`:
- `stringPreferencesKey("selected_soundscape_id")`
- `selectedSoundscapeId: StateFlow<String?>` via `stateIn(WhileSubscribed(5_000L))`
- `select(id)`, `deselect()`, `selectIfUnset(id)` (last used for forward-compat)
- `.catch { emit(emptyPreferences()) }` on read-side

Commit: `feat(soundscape): Stage 5-F selection repository`

---

## Task 6 — `SoundscapeDownloadWorker` + scheduler + tests

**New files:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/data/soundscape/SoundscapeDownloadWorker.kt` — `@HiltWorker`
- `app/src/main/java/org/walktalkmeditate/pilgrim/data/soundscape/SoundscapeDownloadScheduler.kt` — interface + `WorkManagerSoundscapeDownloadScheduler` impl
- `app/src/main/java/org/walktalkmeditate/pilgrim/data/soundscape/SoundscapeBaseUrl.kt` qualifier
- `app/src/test/java/.../data/soundscape/SoundscapeDownloadWorkerTest.kt` — ~5 cases
- `app/src/test/java/.../data/soundscape/WorkManagerSoundscapeDownloadSchedulerTest.kt` — ~3 cases with `WorkManagerTestInitHelper` (MANDATORY `.build()` exercise per CLAUDE.md)

Simpler than `VoiceGuideDownloadWorker`: single file per asset (no prompts loop).

```kotlin
@HiltWorker
class SoundscapeDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val manifestService: AudioManifestService,
    private val fileStore: SoundscapeFileStore,
    private val httpClient: OkHttpClient,
    @SoundscapeBaseUrl private val baseUrl: String,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ASSET_ID) ?: return Result.failure()
        manifestService.initialLoad.await()
        val asset = manifestService.asset(id)
            ?.takeIf { it.type == "soundscape" }
            ?: return Result.failure()
        if (fileStore.isAvailable(asset)) return Result.success()  // already there

        val ok = downloadAsset(asset) || (!isStopped && downloadAsset(asset))  // single retry
        return if (ok && fileStore.isAvailable(asset)) Result.success() else Result.retry()
    }

    private suspend fun downloadAsset(asset: AudioAsset): Boolean =
        withContext(Dispatchers.IO) {
            val url = baseUrl.trimEnd('/') + "/soundscape/${asset.id}.aac"
            val target = fileStore.fileFor(asset)
            val tmp = File(target.parentFile, "${target.name}.tmp")
            try {
                val request = Request.Builder().url(url).build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use false
                    val body = response.body ?: return@use false
                    tmp.sink().buffer().use { sink -> sink.writeAll(body.source()) }
                    if (tmp.length() != asset.fileSizeBytes) {
                        tmp.delete()
                        return@use false
                    }
                    if (!tmp.renameTo(target)) {
                        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                    true
                }
            } catch (ce: CancellationException) {
                tmp.delete(); throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "soundscape asset ${asset.id} failed", t); tmp.delete(); false
            }
        }

    companion object {
        const val KEY_ASSET_ID = "asset_id"
        fun uniqueWorkName(id: String) = "soundscape_download_$id"
    }
}
```

Scheduler mirrors `WorkManagerVoiceGuideDownloadScheduler` exactly — `enqueue(KEEP)`, `retry(REPLACE)`, `cancel`, `observe` via `getWorkInfosForUniqueWorkLiveData().asFlow()`.

Commit: `feat(soundscape): Stage 5-F download worker + scheduler`

---

## Task 7 — `SoundscapeState` + `SoundscapeCatalogRepository` + tests

**New files:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/data/soundscape/SoundscapeState.kt` — sealed class with 4 variants
- `app/src/main/java/org/walktalkmeditate/pilgrim/data/soundscape/SoundscapeCatalogRepository.kt`
- `app/src/main/java/org/walktalkmeditate/pilgrim/data/soundscape/SoundscapeCatalogScope.kt` qualifier
- `app/src/test/java/.../data/soundscape/SoundscapeCatalogRepositoryTest.kt` — ~5 cases

Mirrors `VoiceGuideCatalogRepository`:
- `combine(manifestService.assets, selection.selectedSoundscapeId, fileStore.invalidations.onStart { emit(Unit) })` → filter to `type == "soundscape"` → base states
- `flatMapLatest { combineLatestProgress(bases) }` overlays progress
- `applyProgress(base, progress)` re-reads filesystem on Succeeded/Cancelled (with `withContext(Dispatchers.IO)`)
- Actions: `download`, `retry`, `cancel`, `select`, `deselect`, `delete` (cancels in-flight worker first per Stage 5-D)

Commit: `feat(soundscape): Stage 5-F catalog repository`

---

## Task 8 — `SoundscapePlayer` + `ExoPlayerSoundscapePlayer` + tests

**New files:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/audio/soundscape/SoundscapePlayer.kt` — interface + State sealed class
- `app/src/main/java/org/walktalkmeditate/pilgrim/audio/soundscape/ExoPlayerSoundscapePlayer.kt` — @Singleton impl
- `app/src/test/java/.../audio/soundscape/ExoPlayerSoundscapePlayerTest.kt` — ~4 Robolectric cases

Key implementation points (from ExoPlayerVoiceGuidePlayer as template):
- Lazy ExoPlayer creation on main handler
- `AudioAttributes(USAGE_MEDIA + CONTENT_TYPE_MUSIC)`, `handleAudioFocus = false`
- `setRepeatMode(Player.REPEAT_MODE_ONE)` before prepare
- `AudioFocusRequest(AUDIOFOCUS_GAIN)` — long-term; NOT transient
- `setWillPauseWhenDucked(false)` — OS auto-ducks on voice-guide MAY_DUCK
- Focus listener:
  - `LOSS` → `internalStop()` + `abandonFocus()`
  - `LOSS_TRANSIENT` → pause (keep focus? Android docs: transient loss means we'll get focus back automatically via GAIN, so don't abandon). State = Idle, remember we were paused.
  - `LOSS_TRANSIENT_CAN_DUCK` → no-op (OS ducks automatically)
  - `GAIN` → if we were paused-due-to-transient-loss, resume
- `BECOMING_NOISY` receiver registered on play, unregistered on stop/release (Stage 5-E mandate)
- No single-fire onFinished contract (ambient loops indefinitely); no completion tracking

Paused-for-transient-loss state tracked via `@Volatile private var wasPlayingBeforeTransientLoss: Boolean` (cross-thread because focus listener runs on main handler, same as play/stop).

Tests:
1. `play constructs ExoPlayer with REPEAT_MODE_ONE + AudioFocus(GAIN) without crashing`
2. `stop abandons focus and transitions state to Idle`
3. `release is idempotent and cleans up ExoPlayer`
4. `play with missing file transitions to Error`

Commit: `feat(soundscape): Stage 5-F ExoPlayer-backed player`

---

## Task 9 — `SoundscapeOrchestrator` + tests

**New files:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/audio/soundscape/SoundscapeOrchestrator.kt` — @Singleton
- `app/src/main/java/org/walktalkmeditate/pilgrim/audio/soundscape/SoundscapePlaybackScope.kt` qualifier
- `app/src/main/java/org/walktalkmeditate/pilgrim/audio/soundscape/SoundscapeObservedWalkState.kt` qualifier
- `app/src/main/java/org/walktalkmeditate/pilgrim/audio/soundscape/SoundscapeSelectedAssetId.kt` qualifier
- `app/src/test/java/.../audio/soundscape/SoundscapeOrchestratorTest.kt` — ~6 cases

Implementation per spec. Key bits:
- Observer lambda is non-suspend where possible (Stage 5-E lesson on StateFlow conflation)
- `Job?.isActive != true` guard (Stage 5-E canonical idiom)
- 500ms delay inside the launched coroutine (post-bell landing) — tested with `runCurrent()` + `advanceTimeBy(500)`
- Eligibility check (`eligibleAssetOrNullSync`) is non-suspend — reads `manifestService.assets.value`, `selectedAssetId.value`, `fileStore.isAvailable`
- On any non-Meditating state: cancel job + `safeStopPlayer()`

Tests (using pattern from `VoiceGuideOrchestratorTest`):
1. `Meditating with no selection does not spawn player`
2. `Meditating with selection but not-downloaded does not spawn`
3. `Meditating with eligible asset plays after 500ms delay`
4. `Meditating → Active stops player`
5. `Meditating → Finished stops player`
6. `Meditating → Paused stops player`

Commit: `feat(soundscape): Stage 5-F orchestrator`

---

## Task 10 — Picker VM + Screen + tests

**New files:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/soundscape/SoundscapePickerViewModel.kt`
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/soundscape/SoundscapePickerScreen.kt`
- `app/src/test/java/.../ui/settings/soundscape/SoundscapePickerScreenTest.kt` — ~3 cases

VM:
```kotlin
@HiltViewModel
class SoundscapePickerViewModel @Inject constructor(
    private val catalog: SoundscapeCatalogRepository,
    private val manifestService: AudioManifestService,
) : ViewModel() {
    init { manifestService.syncIfNeeded() }
    val soundscapes: StateFlow<List<SoundscapeState>> = catalog.soundscapeStates

    fun onRowTap(state: SoundscapeState) = viewModelScope.launch {
        when (state) {
            is SoundscapeState.NotDownloaded -> catalog.download(state.asset.id)
            is SoundscapeState.Failed -> catalog.retry(state.asset.id)
            is SoundscapeState.Downloading -> Unit
            is SoundscapeState.Downloaded -> {
                if (state.isSelected) catalog.deselect() else catalog.select(state.asset.id)
            }
        }
    }

    fun onRowDelete(state: SoundscapeState) = viewModelScope.launch {
        if (state is SoundscapeState.Downloaded) catalog.delete(state.asset)
    }
}
```

Screen: `LazyColumn` of rows. Per-row content:
- Leading: icon (spa/graphic-eq fallback — same as voice-guide)
- Headline: `asset.displayName`
- Supporting: status text ("Not downloaded" / "Downloading..." / "Downloaded · Selected" / "Downloaded" / "Download failed")
- Trailing: `CloudDownload` / `CircularProgressIndicator(indeterminate)` / `Check` / `ErrorOutline`
- `Modifier.combinedClickable(onClick = { vm.onRowTap(state) }, onLongClick = { showDeleteDialog = state })`
- Long-press → `AlertDialog` asking "Delete soundscape?"

Tests (Robolectric + Compose test):
1. Empty list renders empty-state copy
2. Soundscape in each of four states renders correct affordance
3. Tap on `NotDownloaded` triggers download (verified by VM-injected fake catalog)

Commit: `feat(soundscape): Stage 5-F picker screen + VM`

---

## Task 11 — DI module + `SettingsScreen` extension + `PilgrimApp` wiring

**New file:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/di/SoundscapeModule.kt` — mirrors `VoiceGuideModule`:
  - `@Binds SoundscapeDownloadScheduler` → `WorkManagerSoundscapeDownloadScheduler`
  - `@Binds SoundscapePlayer` → `ExoPlayerSoundscapePlayer`
  - `@Provides @SoundscapePlaybackScope CoroutineScope`
  - `@Provides @SoundscapeCatalogScope CoroutineScope`
  - `@Provides @SoundscapeSelectionScope CoroutineScope`
  - `@Provides @SoundscapeObservedWalkState StateFlow<WalkState>` ← from WalkController.state
  - `@Provides @SoundscapeSelectedAssetId StateFlow<String?>` ← from selectionRepo.selectedSoundscapeId

**Modify:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/di/NetworkModule.kt` — add `@AudioManifestUrl` provider + `@SoundscapeBaseUrl` provider + `@AudioManifestScope` provider
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/SettingsScreen.kt` — add second `SettingsRow` for "Soundscapes" + new `onOpenSoundscapes: () -> Unit` parameter
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/navigation/PilgrimNavHost.kt` — add `Routes.SOUNDSCAPE_PICKER = "soundscapes"` + composable route + thread new Settings param
- `app/src/main/java/org/walktalkmeditate/pilgrim/PilgrimApp.kt` — inject + `start()` `SoundscapeOrchestrator`
- `app/src/main/res/values/strings.xml` — soundscape picker strings

Full verify:
```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Commit: `feat(soundscape): Stage 5-F DI + Settings/Nav/App wiring`

---

## Self-review checklist

- [x] Every spec-scoped item has a task.
- [x] No placeholders.
- [x] Type consistency: `AudioAsset` used by manifest → filestore → download worker → catalog → state → VM → screen; `SoundscapeState` used by catalog → VM → screen → orchestrator.
- [x] `DownloadProgress` promoted to shared package before soundscape consumes it (Task 3).
- [x] Each task commits individually.
- [x] `.build()` test on real WorkRequest per CLAUDE.md (Task 6).
- [x] `.build()` test on real ExoPlayer + AudioFocusRequest per CLAUDE.md (Task 8).
- [x] Stage 5-D M2 pattern (`async init` with `initialLoad: Deferred`) applied to `AudioManifestService` (Task 2).
- [x] Stage 5-E canonical `Job?.isActive != true` guard in orchestrator (Task 9).
- [x] Stage 5-E `BECOMING_NOISY` receiver in player (Task 8).
- [x] Stage 5-E non-suspend eligibility snapshot in observer (Task 9).

## Risks

- **ExoPlayer `REPEAT_MODE_ONE` gap at loop boundary**: file-encoding-dependent. If we hit on-device clicks in 5-G QA, remediation is audio-engineering (re-encode with gapless metadata), not code. Document.
- **OS ducking behavior variance**: not every Android OEM implements `setWillPauseWhenDucked(false)` identically. On some devices, soundscape may still pause fully instead of ducking. If on-device QA reveals this, add a manual volume-dip in the `LOSS_TRANSIENT_CAN_DUCK` branch. For MVP, trust the stock Android behavior.
- **`AudioManifestService` introduces a NEW singleton whose init blocks the DI graph on Hilt-construction thread**. Same pattern as Stage 5-C with the same async-init-via-Deferred resolution. Apply from day one.
- **DataStore upstream is the same as Stage 5-D's selection — two independent selections = two independent `stateIn(WhileSubscribed)` pipelines**. Each pipeline needs its own warm subscriber path. The picker VM subscribes when open; in idle state, the `.value` is safe because DataStore emits via auto-managed collection on read. Same timing-safety argument as Stage 5-D's closing-review memo applies.
