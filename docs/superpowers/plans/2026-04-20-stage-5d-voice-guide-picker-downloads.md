# Stage 5-D Implementation Plan — Voice-Guide Pack Picker + Downloads

**Spec:** `docs/superpowers/specs/2026-04-20-stage-5d-voice-guide-picker-downloads-design.md`
**Worktree:** `.worktrees/stage-5d-voice-guide-picker-downloads`
**Branch:** `stage-5d/voice-guide-picker-downloads` off `main`

## Gradle test commands (one-liner prefix for all tests)

```sh
export JAVA_HOME=~/.asdf/installs/java/temurin-17.0.18+8 && export PATH=$JAVA_HOME/bin:$PATH
./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.data.voiceguide.*"
```

Full verification at each phase gate:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

## Task ordering

Each task ends in a working commit. Tests for a task ship with that task — no "tests come later" deferrals.

---

### Task 0 — Create worktree & commit spec + plan

```sh
cd /Users/rubberduck/GitHub/momentmaker/pilgrim-android
git worktree add .worktrees/stage-5d-voice-guide-picker-downloads -b stage-5d/voice-guide-picker-downloads main
cp docs/superpowers/specs/2026-04-20-stage-5d-voice-guide-picker-downloads-design.md .worktrees/stage-5d-voice-guide-picker-downloads/docs/superpowers/specs/
# (plan file copy same)
cd .worktrees/stage-5d-voice-guide-picker-downloads
git add docs/superpowers/specs/... docs/superpowers/plans/...
git commit -m "docs(plan): Stage 5-D voice-guide picker + downloads design + plan"
```

---

### Task 1 — Stage 5-C M2 fix: async loadLocalManifest

**Goal:** Stop blocking the Hilt-injection thread on cache disk I/O.

**File:** `app/src/main/java/org/walktalkmeditate/pilgrim/data/voiceguide/VoiceGuideManifestService.kt`

Change the `init` block + extract a `suspend fun loadLocalManifestSuspending`:

```kotlin
init {
    // Async so the constructor (typically called on the Hilt-injection
    // thread — often Main for @HiltViewModel consumers) doesn't block
    // on file I/O. Consumers observe `packs` which starts empty and
    // receives the cached value on first emission once I/O completes.
    scope.launch(Dispatchers.IO) { loadLocalManifestFromDisk() }
}

private fun loadLocalManifestFromDisk() {
    if (!localManifestFile.exists()) return
    try {
        val text = localManifestFile.readText()
        val manifest = json.decodeFromString<VoiceGuideManifest>(text)
        _packs.value = manifest.packs
    } catch (t: Throwable) {
        Log.w(TAG, "failed to load local manifest; treating as absent", t)
    }
}
```

Rename the existing private `loadLocalManifest` → `loadLocalManifestFromDisk` (the rename makes the test-verification-point name match behavior more precisely).

**File:** `app/src/test/java/org/walktalkmeditate/pilgrim/data/voiceguide/VoiceGuideManifestServiceTest.kt`

Add a test-only helper after `buildService()` to wait for the async init to complete:

```kotlin
private fun buildServiceAndWaitForInit(
    url: String = server.url("/manifest.json").toString(),
): VoiceGuideManifestService {
    val svc = buildService(url)
    awaitSync()   // init-launched coroutine is a child of the scope
    return svc
}
```

Replace all test-code `buildService()` invocations with `buildServiceAndWaitForInit()` EXCEPT the one in `init with no cache emits empty list` (where the init body returns early before any state update; original call still correct).

**Verify:**
```sh
./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.data.voiceguide.*"
```

All 16 Stage 5-C tests still green.

**Commit:**
```
fix(voiceguide): Stage 5-D prep — async loadLocalManifest

Move cache load off the Hilt-injection thread. Closes Stage 5-C
deferred M2. Consumers still observe `packs` which starts empty
and receives the cached catalog on first emission once I/O
completes.
```

---

### Task 2 — VoiceGuideFileStore + test

**Goal:** Filesystem source-of-truth for per-prompt and per-pack download status.

**New file:** `app/src/main/java/org/walktalkmeditate/pilgrim/data/voiceguide/VoiceGuideFileStore.kt`

```kotlin
@Singleton
class VoiceGuideFileStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val promptsRoot: File by lazy {
        File(context.filesDir, PROMPTS_DIR).also { it.mkdirs() }
    }

    // Broadcasts on deletePack so observers re-derive state.
    private val _invalidations = MutableSharedFlow<Unit>(
        replay = 0, extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val invalidations: SharedFlow<Unit> = _invalidations.asSharedFlow()

    /** Absolute file for a prompt's r2Key. Creates parent dirs. */
    fun fileForPrompt(r2Key: String): File {
        val file = File(promptsRoot, r2Key)
        file.parentFile?.mkdirs()
        return file
    }

    /** True iff the file exists AND its length matches the manifest size. */
    fun isPromptAvailable(prompt: VoiceGuidePrompt): Boolean {
        val f = fileForPrompt(prompt.r2Key)
        return f.exists() && f.length() == prompt.fileSizeBytes
    }

    fun allPrompts(pack: VoiceGuidePack): List<VoiceGuidePrompt> =
        pack.prompts + (pack.meditationPrompts ?: emptyList())

    fun missingPrompts(pack: VoiceGuidePack): List<VoiceGuidePrompt> =
        allPrompts(pack).filterNot { isPromptAvailable(it) }

    fun isPackDownloaded(pack: VoiceGuidePack): Boolean =
        allPrompts(pack).all { isPromptAvailable(it) }

    /** Recursive delete of the pack's directory, then broadcasts an invalidation. */
    fun deletePack(pack: VoiceGuidePack) {
        val dir = File(promptsRoot, pack.id)
        if (dir.exists()) dir.deleteRecursively()
        _invalidations.tryEmit(Unit)
    }

    private companion object {
        const val PROMPTS_DIR = "voice_guide_prompts"
    }
}
```

`deletePack` deletes by pack-id directory even though prompts are keyed by `r2Key`. This relies on the convention that `r2Key` starts with the pack id (iOS produces `packId/promptId.aac`). The spec chose this layout; the `filesDir/voice_guide_prompts/<packId>/...` structure comes from `r2Key` verbatim.

**New file:** `app/src/test/java/org/walktalkmeditate/pilgrim/data/voiceguide/VoiceGuideFileStoreTest.kt`

```kotlin
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VoiceGuideFileStoreTest {

    private lateinit var context: Application
    private lateinit var store: VoiceGuideFileStore
    private val promptsRoot get() = File(context.filesDir, "voice_guide_prompts")

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        promptsRoot.deleteRecursively()
        store = VoiceGuideFileStore(context)
    }
    @After fun tearDown() { promptsRoot.deleteRecursively() }

    private fun prompt(r2Key: String, size: Long) =
        VoiceGuidePrompt(id = r2Key, seq = 1, durationSec = 1.0,
                         fileSizeBytes = size, r2Key = r2Key)

    private fun pack(id: String, prompts: List<VoiceGuidePrompt>, med: List<VoiceGuidePrompt>? = null) =
        VoiceGuidePack(id = id, version = "1", name = id, tagline = "", description = "",
                       theme = "", iconName = "", type = "walk", walkTypes = emptyList(),
                       scheduling = PromptDensity(0, 0, 0, 0, 0),
                       totalDurationSec = 0.0, totalSizeBytes = 0L,
                       prompts = prompts, meditationPrompts = med)

    @Test fun `empty pack is considered downloaded`() {
        val p = pack("empty", emptyList())
        assertTrue(store.isPackDownloaded(p))
    }

    @Test fun `missing file marks pack not-downloaded`() {
        val p = pack("p", listOf(prompt("p/a.aac", 10)))
        assertFalse(store.isPackDownloaded(p))
        assertEquals(1, store.missingPrompts(p).size)
    }

    @Test fun `file with correct size marks prompt available`() {
        val pr = prompt("p/a.aac", 10)
        val f = store.fileForPrompt(pr.r2Key)
        f.writeBytes(ByteArray(10))
        assertTrue(store.isPromptAvailable(pr))
    }

    @Test fun `file with wrong size marks prompt unavailable`() {
        val pr = prompt("p/a.aac", 10)
        store.fileForPrompt(pr.r2Key).writeBytes(ByteArray(5))
        assertFalse(store.isPromptAvailable(pr))
    }

    @Test fun `meditation prompts counted in isPackDownloaded`() {
        val walk = prompt("p/w.aac", 5)
        val med = prompt("p/m.aac", 5)
        val p = pack("p", listOf(walk), listOf(med))
        store.fileForPrompt(walk.r2Key).writeBytes(ByteArray(5))
        assertFalse(store.isPackDownloaded(p))
        store.fileForPrompt(med.r2Key).writeBytes(ByteArray(5))
        assertTrue(store.isPackDownloaded(p))
    }

    @Test fun `deletePack removes directory and emits invalidation`() = runTest {
        val pr = prompt("p/a.aac", 5)
        val p = pack("p", listOf(pr))
        store.fileForPrompt(pr.r2Key).writeBytes(ByteArray(5))
        assertTrue(store.isPackDownloaded(p))

        store.invalidations.test {
            store.deletePack(p)
            assertEquals(Unit, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertFalse(File(promptsRoot, "p").exists())
        assertFalse(store.isPackDownloaded(p))
    }
}
```

**Commit:**
```
feat(voiceguide): Stage 5-D file store

Filesystem source-of-truth for per-prompt and per-pack download
status. `r2Key`-keyed layout (manifest authoritative, format-agnostic)
mirrors iOS. Broadcasts invalidations on deletePack so the catalog
layer can re-derive state reactively.
```

---

### Task 3 — VoiceGuideSelectionRepository + test

**Goal:** DataStore-backed single-selected-pack-id with atomic `selectIfUnset`.

**New file:** `app/src/main/java/org/walktalkmeditate/pilgrim/data/voiceguide/VoiceGuideSelectionRepository.kt`

(As in the spec; repeated here for clarity.)

```kotlin
@Qualifier @Retention(AnnotationRetention.BINARY)
annotation class VoiceGuideSelectionScope

@Singleton
class VoiceGuideSelectionRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    @VoiceGuideSelectionScope private val scope: CoroutineScope,
) {
    val selectedPackId: StateFlow<String?> = dataStore.data
        .catch { t ->
            Log.w(TAG, "selection datastore read failed; emitting empty", t)
            emit(emptyPreferences())
        }
        .map { it[KEY_SELECTED] }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000L), null)

    suspend fun select(packId: String) {
        dataStore.edit { it[KEY_SELECTED] = packId }
    }
    suspend fun deselect() {
        dataStore.edit { it.remove(KEY_SELECTED) }
    }
    suspend fun selectIfUnset(packId: String) {
        dataStore.edit { prefs ->
            if (prefs[KEY_SELECTED] == null) prefs[KEY_SELECTED] = packId
        }
    }

    private companion object {
        const val TAG = "VoiceGuideSelection"
        val KEY_SELECTED = stringPreferencesKey("selected_voice_guide_pack_id")
    }
}
```

**New file:** `app/src/test/java/org/walktalkmeditate/pilgrim/data/voiceguide/VoiceGuideSelectionRepositoryTest.kt`

Mirror `HemisphereRepositoryTest` pattern (Robolectric + real DataStore at a unique file path). Cases:

- `initial value is null`
- `select persists and emits`
- `deselect clears and emits`
- `selectIfUnset no-ops when already set`
- `selectIfUnset persists when unset`
- `distinctUntilChanged suppresses redundant emissions`

**Commit:**
```
feat(voiceguide): Stage 5-D selection repository

DataStore-backed `selectedPackId: StateFlow<String?>` with atomic
`selectIfUnset` for the auto-select-on-first-download flow.
```

---

### Task 4 — Download worker + scheduler + tests

**Goal:** Per-pack WorkManager downloads with constraints, idempotency, progress, and unique-work dedup.

**New file:** `app/src/main/java/org/walktalkmeditate/pilgrim/data/voiceguide/VoiceGuideDownloadWorker.kt`

```kotlin
@Qualifier @Retention(AnnotationRetention.BINARY)
annotation class VoiceGuidePromptBaseUrl

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

        val all = fileStore.allPrompts(pack)
        val toFetch = fileStore.missingPrompts(pack)
        var completed = all.size - toFetch.size
        val total = all.size
        reportProgress(completed, total)

        for (prompt in toFetch) {
            if (isStopped) return Result.failure()
            val ok = downloadPrompt(pack.id, prompt) ||
                     (!isStopped && downloadPrompt(pack.id, prompt))
            if (ok) {
                completed += 1
                reportProgress(completed, total)
            }
        }
        return if (fileStore.isPackDownloaded(pack)) Result.success() else Result.retry()
    }

    private suspend fun downloadPrompt(packId: String, prompt: VoiceGuidePrompt): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val url = promptBaseUrl.trimEnd('/') + "/" + prompt.r2Key
                val request = Request.Builder().url(url).build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "prompt ${prompt.r2Key} non-2xx: ${response.code}")
                        return@use false
                    }
                    val body = response.body ?: return@use false
                    val out = fileStore.fileForPrompt(prompt.r2Key)
                    val tmp = File(out.parentFile, "${out.name}.tmp")
                    tmp.sink().buffer().use { sink -> sink.writeAll(body.source()) }
                    if (tmp.length() != prompt.fileSizeBytes) {
                        tmp.delete()
                        Log.w(TAG, "prompt ${prompt.r2Key} size mismatch: ${tmp.length()} vs ${prompt.fileSizeBytes}")
                        return@use false
                    }
                    if (!tmp.renameTo(out)) {
                        Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                    true
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "prompt ${prompt.r2Key} failed", t)
                false
            }
        }

    private suspend fun reportProgress(completed: Int, total: Int) {
        setProgress(workDataOf(KEY_COMPLETED to completed, KEY_TOTAL to total))
    }

    companion object {
        const val KEY_PACK_ID = "pack_id"
        const val KEY_COMPLETED = "completed"
        const val KEY_TOTAL = "total"
        private const val TAG = "VoiceGuideWorker"

        fun uniqueWorkName(packId: String): String = "voice_guide_download_$packId"
    }
}
```

Note the use of OkIO (`sink().buffer()`) — OkIO is an OkHttp transitive dependency already on the classpath.

**New file:** `app/src/main/java/org/walktalkmeditate/pilgrim/data/voiceguide/VoiceGuideDownloadScheduler.kt`

```kotlin
interface VoiceGuideDownloadScheduler {
    fun enqueue(packId: String)
    fun retry(packId: String)
    fun cancel(packId: String)
    fun observe(packId: String): Flow<DownloadProgress?>
}

data class DownloadProgress(
    val state: State,
    val completed: Int,
    val total: Int,
) {
    enum class State { Enqueued, Running, Succeeded, Failed, Cancelled }
}

@Singleton
class WorkManagerVoiceGuideDownloadScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : VoiceGuideDownloadScheduler {

    override fun enqueue(packId: String) =
        enqueueInternal(packId, ExistingWorkPolicy.KEEP)

    override fun retry(packId: String) =
        enqueueInternal(packId, ExistingWorkPolicy.REPLACE)

    override fun cancel(packId: String) {
        WorkManager.getInstance(context)
            .cancelUniqueWork(VoiceGuideDownloadWorker.uniqueWorkName(packId))
    }

    override fun observe(packId: String): Flow<DownloadProgress?> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkLiveData(VoiceGuideDownloadWorker.uniqueWorkName(packId))
            .asFlow()
            .map { infos -> infos.lastOrNull()?.toDownloadProgress() }
            .distinctUntilChanged()

    private fun enqueueInternal(packId: String, policy: ExistingWorkPolicy) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresStorageNotLow(true)
            .build()
        val request = OneTimeWorkRequestBuilder<VoiceGuideDownloadWorker>()
            .setInputData(workDataOf(VoiceGuideDownloadWorker.KEY_PACK_ID to packId))
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            VoiceGuideDownloadWorker.uniqueWorkName(packId), policy, request,
        )
    }

    private fun WorkInfo.toDownloadProgress(): DownloadProgress {
        val completed = progress.getInt(VoiceGuideDownloadWorker.KEY_COMPLETED, 0)
        val total = progress.getInt(VoiceGuideDownloadWorker.KEY_TOTAL, 0)
        val s = when (state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> DownloadProgress.State.Enqueued
            WorkInfo.State.RUNNING -> DownloadProgress.State.Running
            WorkInfo.State.SUCCEEDED -> DownloadProgress.State.Succeeded
            WorkInfo.State.FAILED -> DownloadProgress.State.Failed
            WorkInfo.State.CANCELLED -> DownloadProgress.State.Cancelled
        }
        return DownloadProgress(s, completed, total)
    }
}
```

**Tests:**

- `VoiceGuideDownloadSchedulerTest` (Robolectric + `WorkManagerTestInitHelper`):
  - `.enqueue` uses KEEP — two calls → one WorkInfo
  - `.retry` uses REPLACE — second call replaces the first
  - `.cancel` cancels the work
  - `.build()` on the production request doesn't crash (mandatory per CLAUDE.md)

- `VoiceGuideDownloadWorkerTest` (Robolectric + MockWebServer + `TestWorkerBuilder`):
  - All prompts missing → 3 GETs, 3 files on disk
  - Some prompts present → only missing downloaded
  - Network 503 → single retry, then skip; `Result.retry()` because pack not fully downloaded
  - Size mismatch → tmp deleted, file treated as missing
  - Worker returns `Result.success` when isPackDownloaded, `Result.retry` when partial
  - Progress emitted via setProgress

**Commit:**
```
feat(voiceguide): Stage 5-D download worker + scheduler

`@HiltWorker` that downloads all missing prompts for one pack:
atomic write-to-tmp + rename per prompt, size verification, single
in-worker retry matching iOS, outer WorkManager retry on partial
success. Scheduler exposes enqueue (KEEP) / retry (REPLACE) /
cancel / observe-progress behind an interface.
```

---

### Task 5 — Catalog repository + download observer + tests

**Goal:** Reactive join of manifest + filesystem + selection + in-flight progress into picker DTOs. Auto-select-on-first-download observer.

**New file:** `app/src/main/java/org/walktalkmeditate/pilgrim/data/voiceguide/VoiceGuidePackState.kt`

(Sealed class per spec.)

**New file:** `app/src/main/java/org/walktalkmeditate/pilgrim/data/voiceguide/VoiceGuideCatalogRepository.kt`

```kotlin
@Qualifier @Retention(AnnotationRetention.BINARY)
annotation class VoiceGuideCatalogScope

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
        // Re-emit whenever files are deleted
        fileStore.invalidations.onStart { emit(Unit) },
    ) { packs, selectedId, _ ->
        packs.map { pack ->
            val selected = pack.id == selectedId
            if (fileStore.isPackDownloaded(pack)) {
                VoiceGuidePackState.Downloaded(pack, selected)
            } else {
                VoiceGuidePackState.NotDownloaded(pack, selected)
            }
        }
    }.flatMapLatest { bases ->
        if (bases.isEmpty()) flowOf(bases) else combineLatestProgress(bases)
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    private fun combineLatestProgress(
        bases: List<VoiceGuidePackState>,
    ): Flow<List<VoiceGuidePackState>> = combine(
        bases.map { base ->
            scheduler.observe(base.pack.id).onStart { emit(null) }.map { base to it }
        }
    ) { pairs ->
        pairs.map { (base, progress) -> applyProgress(base, progress) }
    }

    private fun applyProgress(
        base: VoiceGuidePackState,
        progress: DownloadProgress?,
    ): VoiceGuidePackState {
        if (progress == null) return base
        return when (progress.state) {
            DownloadProgress.State.Enqueued,
            DownloadProgress.State.Running ->
                VoiceGuidePackState.Downloading(
                    base.pack, base.isSelected,
                    completed = progress.completed, total = progress.total,
                )
            DownloadProgress.State.Failed ->
                VoiceGuidePackState.Failed(base.pack, base.isSelected, reason = "download_failed")
            DownloadProgress.State.Succeeded,
            DownloadProgress.State.Cancelled -> base
        }
    }

    fun download(packId: String) = scheduler.enqueue(packId)
    fun retry(packId: String) = scheduler.retry(packId)
    fun cancel(packId: String) = scheduler.cancel(packId)
    suspend fun select(packId: String) = selection.select(packId)
    suspend fun deselect() = selection.deselect()
    suspend fun delete(pack: VoiceGuidePack) {
        fileStore.deletePack(pack)
        if (selection.selectedPackId.value == pack.id) selection.deselect()
    }
}
```

**New file:** `app/src/main/java/org/walktalkmeditate/pilgrim/data/voiceguide/VoiceGuideDownloadObserver.kt`

App-scoped observer that triggers `selection.selectIfUnset` when a download succeeds. Same pattern as `MeditationBellObserver` (Stage 5-B) — initialized in `PilgrimApplication.onCreate`, held by an `@Singleton` service that subscribes to each pack's `scheduler.observe` stream.

Actually simpler: observe the `packStates` directly and detect the `Downloading → Downloaded` transition.

```kotlin
@Singleton
class VoiceGuideDownloadObserver @Inject constructor(
    private val catalog: VoiceGuideCatalogRepository,
    private val selection: VoiceGuideSelectionRepository,
    @VoiceGuideCatalogScope private val scope: CoroutineScope,
) {
    fun start() {
        scope.launch { observe() }
    }

    private suspend fun observe() {
        var previous: Map<String, VoiceGuidePackState> = emptyMap()
        catalog.packStates.collect { list ->
            val current = list.associateBy { it.pack.id }
            current.forEach { (id, state) ->
                val prev = previous[id]
                val becameDownloaded =
                    state is VoiceGuidePackState.Downloaded &&
                    prev != null && prev !is VoiceGuidePackState.Downloaded
                if (becameDownloaded) {
                    selection.selectIfUnset(id)
                }
            }
            previous = current
        }
    }
}
```

Registered in `PilgrimApplication.onCreate`:
```kotlin
@Inject lateinit var voiceGuideDownloadObserver: VoiceGuideDownloadObserver
// ... in onCreate:
voiceGuideDownloadObserver.start()
```

**Tests:**

- `VoiceGuideCatalogRepositoryTest`: fake each dep, verify state permutations (selected+downloaded, unselected+not-downloaded, selected+downloading with progress, selected+failed, empty manifest).
- `VoiceGuideDownloadObserverTest`: fake catalog flow + fake selection repository, verify `selectIfUnset` called exactly when transitioning to Downloaded from non-Downloaded.

**Commit:**
```
feat(voiceguide): Stage 5-D catalog repository + download observer

Join manifest + filesystem + selection + in-flight progress into
picker-ready DTOs. App-scoped observer auto-selects the first
pack to complete download (iOS parity).
```

---

### Task 6 — ViewModels + tests

**Goal:** Compose-visible ViewModels for picker and detail, tied to catalog.

**New files:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/voiceguide/VoiceGuidePickerViewModel.kt`
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/voiceguide/VoiceGuidePackDetailViewModel.kt`

Picker: passthrough `packStates` + action delegates.

Detail:

```kotlin
@HiltViewModel
class VoiceGuidePackDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val catalog: VoiceGuideCatalogRepository,
) : ViewModel() {
    private val packId: String = requireNotNull(savedStateHandle[ARG_PACK_ID])

    val uiState: StateFlow<UiState> = catalog.packStates
        .map { it.firstOrNull { s -> s.pack.id == packId } }
        .map { if (it == null) UiState.NotFound else UiState.Loaded(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), UiState.Loading)

    fun download() = catalog.download(packId)
    fun retry() = catalog.retry(packId)
    fun cancel() = catalog.cancel(packId)
    fun select() = viewModelScope.launch { catalog.select(packId) }
    fun deselect() = viewModelScope.launch { catalog.deselect() }
    fun delete() {
        val state = (uiState.value as? UiState.Loaded) ?: return
        viewModelScope.launch { catalog.delete(state.state.pack) }
    }

    sealed class UiState {
        object Loading : UiState()
        data class Loaded(val state: VoiceGuidePackState) : UiState()
        object NotFound : UiState()
    }

    companion object { const val ARG_PACK_ID = "packId" }
}
```

**Tests:** Turbine + fake catalog repository.

**Commit:**
```
feat(voiceguide): Stage 5-D picker + detail ViewModels
```

---

### Task 7 — Compose screens + screen tests

**Goal:** Picker list, pack detail, and minimal Settings scaffold.

**New files:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/SettingsScreen.kt`
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/voiceguide/VoiceGuidePickerScreen.kt`
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/voiceguide/VoiceGuidePackDetailScreen.kt`

Layouts per spec. Key decisions:

- **Row layout** (picker): `ListItem` with leading icon (Material icon picked by `pack.iconName` or a default), headline (name), supporting text (tagline), trailing slot per state:
  - `NotDownloaded` → `Icons.Default.CloudDownload` icon button (tappable directly, not just via row-tap)
  - `Downloading` → `CircularProgressIndicator(progress = fraction)` with count centered in a subtle text row below
  - `Downloaded` + selected → `Icons.Default.Check`
  - `Downloaded` + unselected → empty
  - `Failed` → `Icons.Default.ErrorOutline`
- **Row tap** always navigates to detail.
- **Detail primary button** swaps by state: Download / Cancel / Select (when downloaded-unselected) / Deselect (when downloaded-selected).
- **Detail secondary** (Delete) shown when state is `Downloaded` (enabled whether selected or not; selecting delete on the currently-selected pack also deselects).
- **Loading state**: centered `CircularProgressIndicator` on picker if catalog `packStates` is empty AND manifest sync in progress; otherwise an empty-state card suggesting "Pull to refresh".
- **Formatting**: duration via `DurationFormatter.format(seconds)`; size via `SizeFormatter.format(bytes)`. Both small helpers defined locally or reused from existing formatters.

**Screen tests** (Robolectric + Compose test harness):
- Picker renders all four row states
- Detail Loading / Loaded / NotFound transitions
- Primary button text matches state

**Commit:**
```
feat(voiceguide): Stage 5-D picker + detail Compose screens

Settings scaffold + voice-guide picker list + pack detail screen.
```

---

### Task 8 — Navigation + Home top-bar gear + DI wiring

**Goal:** Plumbing to make the feature reachable.

**Files modified:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/navigation/PilgrimNavHost.kt` — add routes + composables
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/HomeScreen.kt` — add `IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, ...) }` in the TopAppBar `actions` slot
- `app/src/main/java/org/walktalkmeditate/pilgrim/MainActivity.kt` — wire `onOpenSettings = { navController.navigate(Routes.SETTINGS) }`
- `app/src/main/java/org/walktalkmeditate/pilgrim/di/NetworkModule.kt` — add `@VoiceGuidePromptBaseUrl` provider (`const val PROMPT_BASE_URL = "https://cdn.pilgrimapp.org/voiceguide/"` added to `VoiceGuideConfig`)
- `app/src/main/java/org/walktalkmeditate/pilgrim/di/VoiceGuideModule.kt` (new) — binds `VoiceGuideDownloadScheduler`, provides catalog + selection scopes
- `app/src/main/java/org/walktalkmeditate/pilgrim/PilgrimApplication.kt` — inject + start `VoiceGuideDownloadObserver`

**Verify:**
```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Manual sanity: install debug APK on OnePlus 13, open Home, tap gear, navigate to Voice Guides, observe empty state (offline) or manifest-loaded packs (online).

**Commit:**
```
feat(voiceguide): Stage 5-D navigation + DI wiring

Home top-bar gear → Settings → Voice Guides. App-scoped download
observer started from PilgrimApplication.onCreate for auto-select.
```

---

## Self-review checklist

- [x] Spec coverage: every scoped item has a task
- [x] No placeholders (no TBDs, no "implement X later")
- [x] Type consistency: `VoiceGuidePackState` is used by both repository and UI; `DownloadProgress` shared between scheduler and catalog
- [x] Each task ends in a commit
- [x] Test commands included
- [x] WorkRequest.build() test called out explicitly (CLAUDE.md mandate)
- [x] Stage 5-C M2 fix scheduled as Task 1 (before any new consumers of the service land)
- [x] CancellationException re-throw pattern applied in new suspend-in-try blocks (download worker)
- [x] Atomic DataStore.edit for TOCTOU (selection.selectIfUnset)

## Risks noted

- **Compose stability**: `VoiceGuidePackState` holds `VoiceGuidePack` which holds `List<VoiceGuidePrompt>`. If any type lacks `@Immutable`/`@Stable`, LazyColumn scroll could jank. Mitigate: annotate DTOs.
- **WorkManager initialization**: the project's existing `AndroidManifest.xml` has `removeNode="true"` on WorkManagerInitializer, implying a custom initialization path. Verify the custom config still works with `@HiltWorker` addition.
- **`fileStore.invalidations` + `.onStart(emit(Unit))`**: the combine needs an initial emission or it won't start. The spec's `onStart { emit(Unit) }` handles this.
- **`WhileSubscribed(5_000L)` on the catalog StateFlow**: if the picker backgrounds for >5s, the upstream joins stop. Reacquire cost is a fresh filesystem walk, which is cheap.
