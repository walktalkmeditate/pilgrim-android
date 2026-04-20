# Stage 5-D Design — Voice-Guide Pack Picker + Downloads

**Date:** 2026-04-20
**Author:** Pilgrim Android port
**Status:** Design — pending approval

## Context

Stage 5-C introduced `VoiceGuideManifestService`, which fetches the iOS-shape JSON catalog of voice-guide packs from `https://cdn.pilgrimapp.org/voiceguide/manifest.json` and exposes `StateFlow<List<VoiceGuidePack>>`. No UI consumes it yet.

Stage 5-D wires the manifest into a user-facing surface: **browse the catalog, download a pack, select one for future meditation/walk sessions**. This is the first HTTP + WorkManager-backed asset-download flow in the port, and the first "Settings" surface in the app. Actual playback of voice prompts during walks or meditation is **deferred to a later stage** (likely 5-E). Stage 5-D establishes the plumbing and picker UX only.

## Scope

**In scope:**
- Settings scaffold (new top-level screen) accessible via a gear icon on the Home top bar.
- Voice-guide picker screen: flat list of packs (walk + meditation combined per iOS) with icon, name, tagline, state indicator (downloaded / downloading with progress / not-downloaded), and "currently selected" checkmark.
- Voice-guide detail screen: pack description, duration, total size, download/delete/select actions, per-pack download progress.
- `VoiceGuideFileStore`: filesystem layout helper, prompt-existence checks, pack-deletion.
- `VoiceGuideDownloadWorker`: `@HiltWorker` that downloads all missing prompts for one pack. Idempotent (skips existing files with matching size). Reports progress via `setProgress`. Handles cancellation.
- `VoiceGuideDownloadScheduler`: enqueues/cancels the worker by `packId`, exposes progress as a Flow.
- `VoiceGuideSelectionRepository`: DataStore-backed `StateFlow<String?>` for the selected pack id.
- `VoiceGuideCatalogRepository`: joins manifest + filesystem + selection + in-flight downloads into `StateFlow<List<VoiceGuidePackState>>` for the picker.
- ViewModels + navigation routes for picker + detail.
- Fix Stage 5-C's deferred M2 item (`loadLocalManifest` on Hilt-injection thread) — move into `scope.launch(Dispatchers.IO)` fire-and-forget.
- Tests: unit tests for each repository/worker, Compose screen tests for picker + detail states, WorkManager test for `.build()` safety.

**NOT in scope (deferred):**
- Actual prompt playback during walk/meditation (Stage 5-E).
- Soundscape catalog or downloads (Stage 5-E).
- Pack versions + auto-upgrade (iOS doesn't do this either).
- "My packs" tab, favorites, reorder.
- Bundled starter pack.
- Per-user metered-network preference (Phase 10 polish).
- Full Settings screen content (weather opt-in, haptics, etc. arrive in Phase 10).
- Device QA pass (Stage 5-F).

## Recommended approach

A **filesystem-backed download state model + DataStore selection + WorkManager per-pack jobs + a catalog repository that joins all three into picker-ready DTOs**. Matches iOS's architectural choices exactly (no Room) while using idiomatic Android primitives for scheduling and persistence. Settings scaffold is minimal — just a screen shell with a single "Voice Guides" row — and grows with Phase 10.

### Why this approach

iOS has proven this shape works: filesystem as the source of truth for download state is self-healing (user deletes `Audio/voiceguide/` → state recomputes on next tick). A Room table tracking "pack X is downloaded" would duplicate filesystem state and drift. WorkManager gives us lifecycle-correct background downloads with cancellation, constraint-aware scheduling, and unique-work dedup that matches iOS's "one active task per pack" model. DataStore for the single selected-pack-id is overkill for one key but matches project precedent (Stage 3-D hemisphere) and forward-compatible if we add per-user download preferences later.

### What was considered and rejected

- **Room for download state**: duplicates filesystem; drift risk when user clears app data or offloads files via OS.
- **In-memory only download state** (no persistence): progress is lost across process death; user re-opens picker and sees zero progress for a running worker. WorkManager's own state tracking via `getWorkInfoByIdFlow` solves this without us persisting.
- **Single huge download worker**: one worker per pack isolates retries and cancellation. A combined worker would fight our "cancel pack X but keep pack Y downloading" semantics.
- **DownloadManager system service**: heavier API surface, no Hilt integration, unnecessary for in-app CDN fetches. WorkManager is already a dependency.
- **Bundled seed pack**: adds ~30MB to APK with unclear retention value. iOS doesn't bundle, and the port stays consistent by not bundling either.
- **Settings as a bottom sheet**: the picker needs detail navigation (list → detail). A full screen fits the pattern from Stages 3-A and 4-C; a bottom sheet would force inline expansion and awkward nav.

## Architecture

### Data flow

```
VoiceGuideManifestService (5-C, @Singleton)
   │    StateFlow<List<VoiceGuidePack>>
   ▼
VoiceGuideCatalogRepository (NEW, @Singleton)
   ◇ joins with ◇
   │   VoiceGuideFileStore.observePackStatus(packId) : StateFlow<Downloaded | NotDownloaded>
   │   VoiceGuideDownloadScheduler.observeProgress(packId) : Flow<InFlight(completed, total)>
   │   VoiceGuideSelectionRepository.selectedPackId : StateFlow<String?>
   ▼
StateFlow<List<VoiceGuidePackState>> // picker-ready DTOs
   ▼
VoiceGuidePickerViewModel → VoiceGuidePickerScreen (Compose)
                              │
                              ▼ tap row
                         VoiceGuidePackDetailScreen (Compose)
                              │
                              ├─ Download / Cancel → VoiceGuideDownloadScheduler
                              ├─ Select / Deselect → VoiceGuideSelectionRepository
                              └─ Delete → VoiceGuideFileStore
```

### Key types

#### `VoiceGuidePackState` (Compose-visible DTO, new)

```kotlin
sealed class VoiceGuidePackState {
    abstract val pack: VoiceGuidePack
    abstract val isSelected: Boolean

    data class NotDownloaded(
        override val pack: VoiceGuidePack,
        override val isSelected: Boolean,
    ) : VoiceGuidePackState()

    data class Downloading(
        override val pack: VoiceGuidePack,
        override val isSelected: Boolean,
        val completed: Int,
        val total: Int,
    ) : VoiceGuidePackState() {
        val fraction: Float
            get() = if (total == 0) 0f else completed.toFloat() / total.toFloat()
    }

    data class Downloaded(
        override val pack: VoiceGuidePack,
        override val isSelected: Boolean,
    ) : VoiceGuidePackState()

    data class Failed(
        override val pack: VoiceGuidePack,
        override val isSelected: Boolean,
        val reason: String,
    ) : VoiceGuidePackState()
}
```

`Failed` state: shown if the most recent `WorkInfo.State` is `FAILED`. User can retry via tapping Download again (scheduler will re-enqueue with `ExistingWorkPolicy.REPLACE` when explicitly retrying — see scheduler section).

#### `VoiceGuideFileStore` (new, @Singleton)

Filesystem layout:

```
filesDir/
  voice_guide_manifest.json              # Stage 5-C
  voice_guide_prompts/
    <r2Key>                              # e.g. "morning-walk/p1.aac"
```

The `r2Key` string from the manifest IS used verbatim as both the URL suffix (appended to `VoiceGuideConfig.PROMPT_BASE_URL`) and the on-disk relative path. This matches "r2Key is the authoritative CDN object key" and sidesteps iOS's hardcoded `.aac` assumption — if iOS ever ships `.opus` prompts, Android keeps working without code changes.

Public API:

```kotlin
@Singleton
class VoiceGuideFileStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Absolute file for a prompt's r2Key. Creates parent dirs on call. */
    fun fileForPrompt(r2Key: String): File

    /** True if file exists AND its length matches expected. */
    fun isPromptAvailable(prompt: VoiceGuidePrompt): Boolean

    /** All walk + meditation prompts whose files are missing or size-mismatched. */
    fun missingPrompts(pack: VoiceGuidePack): List<VoiceGuidePrompt>

    /** All walk + meditation prompts for a pack. */
    fun allPrompts(pack: VoiceGuidePack): List<VoiceGuidePrompt>

    /** Recursively delete the pack's directory. Best-effort. */
    fun deletePack(pack: VoiceGuidePack)

    /** Pack is "downloaded" iff every walk + meditation prompt is available. */
    fun isPackDownloaded(pack: VoiceGuidePack): Boolean
}
```

Size verification (not just existence) is a small upgrade over iOS — catches truncated downloads without a hash. `VoiceGuidePrompt.fileSizeBytes` is in the manifest.

#### `VoiceGuideDownloadWorker` (new, `@HiltWorker`)

```kotlin
@HiltWorker
class VoiceGuideDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val manifestService: VoiceGuideManifestService,
    private val fileStore: VoiceGuideFileStore,
    private val httpClient: OkHttpClient,
    @VoiceGuidePromptBaseUrl private val promptBaseUrl: String,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val packId = inputData.getString(KEY_PACK_ID) ?: return Result.failure()
        val pack = manifestService.pack(id = packId) ?: return Result.failure()

        val toFetch = fileStore.missingPrompts(pack)
        val total = fileStore.allPrompts(pack).size
        var completed = total - toFetch.size
        setProgress(workDataOf(KEY_COMPLETED to completed, KEY_TOTAL to total))

        for (prompt in toFetch) {
            if (isStopped) return Result.failure()
            val ok = downloadPrompt(prompt) ||
                     (!isStopped && downloadPrompt(prompt)) // single retry
            if (ok) {
                completed += 1
                setProgress(workDataOf(KEY_COMPLETED to completed, KEY_TOTAL to total))
            }
            // If !ok after retry, skip — fileStore.isPromptAvailable will still
            // be false, so isPackDownloaded will stay false and the user can
            // retry. Matches iOS "single retry, no error surfaced".
        }
        return if (fileStore.isPackDownloaded(pack)) Result.success()
               else Result.retry()
    }

    private suspend fun downloadPrompt(prompt: VoiceGuidePrompt): Boolean = // ...
}
```

Key behaviors:

- **Cancellation**: checks `isStopped` between prompts and returns `failure()` to avoid reporting `success` on a partial download.
- **Retry policy**: in-worker single-retry per prompt (matches iOS); outer `Result.retry()` from WorkManager only if the pack finished partial — exponential backoff handles transient network loss across worker invocations.
- **Progress**: `setProgress(workDataOf(...))` after each prompt. UI observes via `getWorkInfoByIdFlow`.
- **Concurrency**: prompts downloaded sequentially within one pack (simpler; iOS uses 2 parallel but OkHttp's default pool handles cross-pack concurrency if multiple packs queued at once). **Deferred complexity**: if throughput matters later, switch to `awaitAll` with `Semaphore(2)`.
- **Idempotency**: `missingPrompts` filters already-available files, so re-enqueue after partial failure picks up where it left off.

Constraints (set at scheduling, not in the worker):

- `NetworkType.CONNECTED` — any network is fine for user-initiated downloads.
- `setRequiresStorageNotLow(true)` — don't fill the user's disk.
- Not expedited — these are user-initiated but not time-critical, so regular priority. (Memory from Stage 2-D: expedited jobs have narrow allowed-constraint sets.)

#### `VoiceGuideDownloadScheduler` (new, interface + WorkManager impl)

```kotlin
interface VoiceGuideDownloadScheduler {
    /** Enqueue a download for the pack. No-op if already in flight
     *  (ExistingWorkPolicy.KEEP). */
    fun enqueue(packId: String)

    /** Force-replace (for explicit user retry after Failed state). */
    fun retry(packId: String)

    /** Cancel the in-flight download. Partial files remain on disk. */
    fun cancel(packId: String)

    /** Progress + terminal state for a pack. Null when no work is tracked. */
    fun observe(packId: String): Flow<DownloadProgress?>
}

data class DownloadProgress(
    val state: State, // Enqueued | Running | Succeeded | Failed | Cancelled
    val completed: Int,
    val total: Int,
)
```

`observe` implementation uses `WorkManager.getWorkInfosForUniqueWorkLiveData(uniqueName(packId)).asFlow()` and maps the most recent `WorkInfo` to our own `DownloadProgress`.

Unique work name: `"voice_guide_download_$packId"`.

#### `VoiceGuideSelectionRepository` (new, @Singleton, DataStore)

```kotlin
@Singleton
class VoiceGuideSelectionRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    @VoiceGuideSelectionScope private val scope: CoroutineScope,
) {
    val selectedPackId: StateFlow<String?> =
        dataStore.data
            .catch { emit(emptyPreferences()) }
            .map { it[KEY_SELECTED] }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000L), null)

    suspend fun select(packId: String) {
        dataStore.edit { it[KEY_SELECTED] = packId }
    }

    suspend fun deselect() {
        dataStore.edit { it.remove(KEY_SELECTED) }
    }

    /** Auto-select iff no pack is currently selected. Atomic TOCTOU. */
    suspend fun selectIfUnset(packId: String) {
        dataStore.edit { prefs ->
            if (prefs[KEY_SELECTED] == null) prefs[KEY_SELECTED] = packId
        }
    }

    private companion object {
        val KEY_SELECTED = stringPreferencesKey("selected_voice_guide_pack_id")
    }
}
```

`selectIfUnset` fires when a download completes and the user has no current selection — matches iOS's "first download wins".

#### `VoiceGuideCatalogRepository` (new, @Singleton, join-only)

Reactively joins manifest, per-pack file-status (via polling on each emission OR filesystem observer — see below), selection, and download progress.

```kotlin
@Singleton
class VoiceGuideCatalogRepository @Inject constructor(
    private val manifestService: VoiceGuideManifestService,
    private val fileStore: VoiceGuideFileStore,
    private val selection: VoiceGuideSelectionRepository,
    private val scheduler: VoiceGuideDownloadScheduler,
    @VoiceGuideCatalogScope private val scope: CoroutineScope,
) {
    val packStates: StateFlow<List<VoiceGuidePackState>> = combine(
        manifestService.packs,
        selection.selectedPackId,
    ) { packs, selectedId ->
        packs.map { pack ->
            val isSelected = pack.id == selectedId
            when {
                fileStore.isPackDownloaded(pack) -> VoiceGuidePackState.Downloaded(pack, isSelected)
                else -> VoiceGuidePackState.NotDownloaded(pack, isSelected)
            }
        }
    }.flatMapLatest { baseList ->
        // Merge in per-pack in-flight progress.
        val progressFlows = baseList.map { state ->
            scheduler.observe(state.pack.id).map { state to it }
        }
        combine(progressFlows) { pairs ->
            pairs.map { (base, progress) ->
                when (progress?.state) {
                    DownloadProgress.State.Running, DownloadProgress.State.Enqueued ->
                        VoiceGuidePackState.Downloading(
                            base.pack, base.isSelected,
                            progress.completed, progress.total
                        )
                    DownloadProgress.State.Failed ->
                        VoiceGuidePackState.Failed(base.pack, base.isSelected, "download_failed")
                    else -> base
                }
            }
        }
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    // Action pass-through (so ViewModels have one injection point):
    fun download(packId: String) = scheduler.enqueue(packId)
    fun cancel(packId: String) = scheduler.cancel(packId)
    fun retry(packId: String) = scheduler.retry(packId)
    suspend fun select(packId: String) = selection.select(packId)
    suspend fun deselect() = selection.deselect()
    suspend fun delete(pack: VoiceGuidePack) {
        fileStore.deletePack(pack)
        if (selection.selectedPackId.value == pack.id) selection.deselect()
    }
}
```

A new `VoiceGuideDownloadObserver` (@Singleton, app-scoped) listens for `DownloadProgress.State.Succeeded` transitions and calls `selection.selectIfUnset(packId)` — matching iOS's auto-select-on-first-download behavior.

**Known limitation**: filesystem state is read synchronously in the `combine` lambda, not observed. If a pack is deleted mid-session via the UI, the `delete()` call should trigger a re-emission by poking the manifest service's packs (no-op rewrite) OR by adding a `MutableStateFlow<Unit>` tick in the file store that `deletePack` emits to. **Chosen approach**: add a `_invalidations: MutableSharedFlow<Unit>` in `VoiceGuideFileStore` with `onBufferOverflow = DROP_OLDEST` and `replay = 0`; `deletePack` emits to it; the catalog repository merges it into its combine chain.

#### ViewModels

- `VoiceGuidePickerViewModel`: `packStates: StateFlow<List<VoiceGuidePackState>>` (direct passthrough), actions on tap.
- `VoiceGuidePackDetailViewModel`: `SavedStateHandle.get<String>(ARG_PACK_ID)`, derives a single `VoiceGuidePackState` + full `VoiceGuidePack` metadata. Three-state `UiState`: `Loading | Loaded(state, pack) | NotFound`.

#### Screens

- `SettingsScreen`: scaffold with a single `ListItem` "Voice Guides" → navigate.
- `VoiceGuidePickerScreen`: `LazyColumn(packStates)`. Each row renders per state:
  - `NotDownloaded`: cloud-arrow-down icon, tap row → detail.
  - `Downloading`: circular progress with `state.fraction`, tap row → detail (cancel available there).
  - `Downloaded`: checkmark icon if `isSelected`, else empty trailing. Tap row → detail.
  - `Failed`: small warning + retry affordance (via detail).
  Long-press → quick-actions menu (select, delete) per iOS parity.
- `VoiceGuidePackDetailScreen`: name, tagline, description, duration (formatted `hh:mm:ss`), size (formatted MB), walk-types list, "Has meditation guide" badge, primary action button (Download / Cancel / Select / Deselect), secondary action (Delete if downloaded).

### Navigation

New routes added to `PilgrimNavHost`:

```kotlin
object Routes {
    const val SETTINGS = "settings"
    const val VOICE_GUIDE_PICKER = "voice_guides"
    const val VOICE_GUIDE_DETAIL_PREFIX = "voice_guide"
    const val VOICE_GUIDE_DETAIL_PATTERN = "$VOICE_GUIDE_DETAIL_PREFIX/{${VoiceGuidePackDetailViewModel.ARG_PACK_ID}}"
    fun voiceGuideDetail(packId: String) = "$VOICE_GUIDE_DETAIL_PREFIX/$packId"
}
```

Home tab top-bar gains a gear icon that navigates to `Routes.SETTINGS`. Settings screen's "Voice Guides" row navigates to `Routes.VOICE_GUIDE_PICKER`. Picker rows navigate to `Routes.voiceGuideDetail(packId)` with `launchSingleTop = true`.

### DI modules

- `VoiceGuideModule` (new): provides `VoiceGuideDownloadScheduler` binding (interface → impl), `@VoiceGuideCatalogScope` + `@VoiceGuideSelectionScope` coroutine scopes, `@VoiceGuidePromptBaseUrl String`.
- `NetworkModule`: add `@VoiceGuidePromptBaseUrl` qualifier + provider pointing at `https://cdn.pilgrimapp.org/voiceguide/`.

### Stage 5-C deferred-item fixes applied here

- **M2 (init-block IO)**: `VoiceGuideManifestService.init` changes from `loadLocalManifest()` direct call to `scope.launch(Dispatchers.IO) { loadLocalManifestSuspending() }`. `_packs` starts as `emptyList()` (unchanged); consumers already handle empty-first-emission. Tests may need a `runBlocking { scope.coroutineContext.job.children.forEach { it.join() } }` bridge in setUp to let the init-launch complete before assertions — existing Stage 5-C tests will catch the regression if not addressed.
- **M1, M3, M4**: deferred (see intent rationale).

## Error & edge-case behaviors

- **No network at picker open**: manifest service falls back to cached `_packs`. If no cache, empty list → picker empty state with a "Pull to refresh" hint calling `manifestService.syncIfNeeded()`.
- **Network drop mid-download**: worker's own attempt fails, `Result.retry()` triggers WorkManager's default backoff (exponential, starting 10s, capped at 5h by default). If the user cancels, WorkManager cancels cleanly.
- **Disk fills mid-download**: `tmp.writeText` in the worker (actually `sink.writeAll` via OkHttp) throws `IOException`. The current prompt is abandoned; subsequent prompts likely fail the same way. Worker returns `Result.retry()`. **StorageNotLow constraint prevents the worker from running again until disk frees up** — matches user expectation.
- **Pack deleted mid-download**: user selects Delete → `fileStore.deletePack` removes the directory. If a worker is in-flight, it'll keep downloading into a now-recreated directory (file store creates parent dirs on write). That's a race we accept: the user's intent was to delete; if they also want to cancel the download, Delete from UI calls `scheduler.cancel` first. Detail-screen Delete button is only enabled when state is `Downloaded`.
- **Selected pack's files deleted externally** (user clears app data, OS offloads): filesystem state check makes `isPackDownloaded` return false on next `combine` emission, so the pack's UI state flips to `NotDownloaded`. Selection is independently persisted — if the user re-downloads the same pack, selection is restored.
- **Manifest sync replaces a pack id**: if a pack-id disappears from the manifest, its on-disk files become orphans. Not handled in this stage (iOS doesn't either). A Phase 10 `OrphanVoiceGuideSweeper` can clean up later.
- **Concurrent picker + detail subscribing**: both ViewModels subscribe to the same `catalog.packStates`. StateFlow's subscription sharing (via `WhileSubscribed`) keeps one upstream collector alive across both.

## Testing strategy

- `VoiceGuideFileStoreTest`: create pack dir, verify isPackDownloaded true/false with all/some files; size-mismatch detection; deletePack recursion.
- `VoiceGuideDownloadWorkerTest`: Robolectric + MockWebServer. Verify:
  - Fresh pack with 3 prompts → 3 GETs, all files on disk.
  - 2 of 3 exist → 1 GET.
  - Network failure on prompt 2 → retry, then success OR skip.
  - Cancellation mid-download (via `WorkManager.cancelUniqueWork`) → Result.failure() surfaced.
  - `.build()` on the production `OneTimeWorkRequest` via the real scheduler (CLAUDE.md mandate).
- `VoiceGuideDownloadSchedulerTest`: enqueue → KEEP semantics (second enqueue no-op); retry → REPLACE semantics.
- `VoiceGuideSelectionRepositoryTest`: Robolectric + real DataStore. select, deselect, selectIfUnset TOCTOU (parallel call with existing selection is a no-op).
- `VoiceGuideCatalogRepositoryTest`: fake the three deps, verify state composition for all permutations (selected + downloaded, selected + missing, unselected + downloading, etc.).
- `VoiceGuidePickerViewModelTest`: passthrough, delegation to catalog actions.
- `VoiceGuidePackDetailViewModelTest`: NotFound path when pack-id unknown; Loaded state composition; actions route to catalog.
- `VoiceGuidePickerScreenTest` (Compose + Robolectric): render all four row states, assert icons/progress present.
- `VoiceGuidePackDetailScreenTest`: render states + action buttons.
- `VoiceGuideManifestServiceTest`: add a `runBlocking { join }` in setUp to ensure the async init load completes before assertions. Possibly add a regression test for the async init path.

## Sequencing

1. **Stage 5-C deferred fix**: move `loadLocalManifest` to async launch. Verify all Stage 5-C tests still green.
2. **File store** (`VoiceGuideFileStore`) + test.
3. **Selection repository** + test.
4. **Download worker** + scheduler + tests.
5. **Catalog repository** + test.
6. **Download observer** (auto-select on first success) + test.
7. **ViewModels** + tests.
8. **Settings scaffold** + picker screen + detail screen + screen tests.
9. **Navigation wiring** + top-bar gear icon.
10. **DI modules** + qualifiers.
11. **End-to-end sanity test**: build + lint + assembleDebug.

## Open questions (answered inline, here for audit)

- **Bundled starter pack?** No — iOS doesn't, user gets empty picker on first launch with a sync.
- **Use `r2Key` or hardcoded `packId/promptId.aac`?** `r2Key` verbatim — manifest-authoritative, format-agnostic.
- **Room for pack-download state?** No — filesystem is source of truth (iOS parity, self-healing).
- **Parallel prompt downloads per pack?** No — sequential. Defer to later if throughput matters.
- **Auto-select on first download?** Yes — `selectIfUnset` called from a download observer.
- **Metered-network opt-in?** No — all downloads are user-initiated. Phase 10 polish if needed.

## References

- Stage 5-C spec: `docs/superpowers/specs/2026-04-20-stage-5c-voice-guide-manifest-design.md`
- iOS download manager: `../pilgrim-ios/Pilgrim/Models/Audio/VoiceGuide/VoiceGuideDownloadManager.swift`
- iOS file store: `../pilgrim-ios/Pilgrim/Models/Audio/VoiceGuide/VoiceGuideFileStore.swift`
- iOS settings UI: `../pilgrim-ios/Pilgrim/Scenes/Settings/VoiceGuideSettingsView.swift`
- WorkManager precedent: Stage 2-D (`TranscriptionWorker`, `WorkManagerTranscriptionScheduler`)
- DataStore precedent: Stage 3-D (`HemisphereRepository`)
