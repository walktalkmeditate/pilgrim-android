# Stage 5-C — Voice guide manifest layer

**Status:** design
**Stage in port plan:** Phase 5 (Meditation + voice guides) — 5-C (third sub-stage). Scoped down from the original 5-A breakdown which included downloads; see "Scope refinement" below.
**Depends on:** none new at runtime (introduces OkHttp + kotlinx.serialization as first-use dependencies)
**iOS reference:** `../pilgrim-ios/Pilgrim/Models/Audio/VoiceGuide/VoiceGuideManifest.swift` (60 lines — data classes), `../pilgrim-ios/Pilgrim/Models/Audio/VoiceGuide/VoiceGuideManifestService.swift` (76 lines — service), `../pilgrim-ios/Pilgrim/Models/Config.swift:89` (manifest URL).

## Goal

Fetch the voice-guide catalog from `https://cdn.pilgrimapp.org/voiceguide/manifest.json`, parse it (same JSON shape iOS uses), cache the result locally, and expose the parsed `List<VoiceGuidePack>` as a `StateFlow<List<VoiceGuidePack>>` for future UI consumers. On cold start, load the local cache immediately (non-blocking); when the user navigates to a surface that needs the catalog, a caller invokes `syncIfNeeded()` which fetches the remote and replaces the cache if the version changed.

No UI changes. No downloads. Pure data-layer infrastructure that Stage 5-D (voice guide UI + downloads + rhythm picker) will consume.

## Scope refinement

The Stage 5-A sub-stage breakdown originally wrote:
> 5-C: Voice guide catalog — OkHttp/Ktor + manifest JSON parsing + Room catalog table + WorkManager downloads. Data only, no UI.

After examining the iOS implementation: **iOS uses NO Room equivalent and NO scheduled downloads**. The manifest is a JSON file on disk. Downloads are triggered lazily from the UI when the user picks a pack (via `VoiceGuideDownloadManager`). The 5-A breakdown overshot.

Refined scope for 5-C = **manifest layer only** (fetch + parse + cache + StateFlow). Downloads, download-progress state, and file-store move to Stage 5-D where they pair naturally with the pack-picker UI that triggers them.

This keeps 5-C a small, cohesive infrastructure stage — the FIRST introduction of HTTP + JSON parsing dependencies to the project — without also wrapping in download orchestration. 5-B already showed that audio-stack stages deserve careful review; splitting 5-C and 5-D keeps each PR reviewable.

## Non-goals (deferred)

- **Downloads + WorkManager worker + file store** — Stage 5-D
- **Pack picker UI + checkmark rendering + download progress bars** — Stage 5-D
- **Rhythm picker** — Stage 5-D (paired with voice-guide picker sheet)
- **History tracking** (which prompts have been played for deduplication) — Stage 5-D or later, iOS has `VoiceGuideHistory` as a separate concern
- **Audio playback** — Stage 5-D (ExoPlayer)
- **Scheduler** (iOS's `VoiceGuideScheduler` picks prompts to play at specific intervals) — Stage 5-D, depends on the download layer
- **Shared HTTP infrastructure for Phase 8 (Collective Counter + Share Worker)** — the port plan mentions these want HTTP too. We establish OkHttp here; Phase 8 can reuse the same module. Interceptors, retry policies, and auth concerns for those endpoints can be added when those endpoints actually exist.
- **Seed/bundled manifest** — iOS doesn't bundle one. First install shows an empty list until the first fetch completes. We match — keeps the bundled APK smaller and the semantics simpler.

## Experience arc

1. Fresh install. `VoiceGuideManifestService` constructs (via Hilt `@Singleton`). Its `init` loads the local cache file if present; on first launch there's no cache, so `packs.value = emptyList()`.
2. No user-visible change in this stage. A future Stage 5-D surface (e.g., the meditation options sheet) will call `manifestService.syncIfNeeded()` when displayed.
3. `syncIfNeeded()`: if `isSyncing.value` is already true, no-op (dedupe concurrent calls). Otherwise fetch from `https://cdn.pilgrimapp.org/voiceguide/manifest.json` over OkHttp. On 200 + valid JSON: compare the returned `version` to the locally-cached version; if different, atomically write the new manifest to `filesDir/voice_guide_manifest.json` and publish `packs.value = remote.packs`. On any failure (network, 4xx/5xx, parse error): keep the local cache, log the error, transition `isSyncing` back to false.
4. Later (Stage 5-D), UI queries `manifestService.pack(byId)` to render the pack picker, and starts a download via (future) download manager.

## Architecture

### New dependencies

```toml
# gradle/libs.versions.toml — additions
[versions]
okhttp = "4.12.0"          # 4.x stable; 5.x is current but has breaking API changes
kotlinxSerialization = "1.7.3"

[libraries]
okhttp = { group = "com.squareup.okhttp3", name = "okhttp", version.ref = "okhttp" }
okhttp-mockwebserver = { group = "com.squareup.okhttp3", name = "mockwebserver", version.ref = "okhttp" }
kotlinx-serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "kotlinxSerialization" }

[plugins]
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```

In `app/build.gradle.kts`:
```kotlin
plugins {
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.okhttp.mockwebserver)
}
```

**Why OkHttp 4.12.0 over 5.x:** 4.x is API-stable and battle-tested. 5.x is recent and introduces `Call.executeAsync()` which is the Kotlin-idiomatic path — but 5.x also drops some method signatures. For a first introduction of HTTP to this codebase, stability > latest-features. Phase 8 can bump if 5.x APIs are worth adopting project-wide.

**Why kotlinx.serialization over Moshi/Gson:** Kotlin-native, compile-time serializers, nullability-aware, no reflection. Kotlin 2.3.20 toolchain already in use.

**Why not Ktor:** Ktor shines for multiplatform; we're Android-only. OkHttp has better Android-specific ergonomics (call cancellation tied to coroutines via `Call.await()` extension, MockWebServer for tests).

### New: `data/voiceguide/VoiceGuideManifest.kt` — data classes

```kotlin
@Serializable
data class VoiceGuideManifest(
    val version: String,
    val packs: List<VoiceGuidePack>,
)

@Serializable
data class VoiceGuidePack(
    val id: String,
    val version: String,
    val name: String,
    val tagline: String,
    val description: String,
    val theme: String,
    val iconName: String,
    val type: String,
    val walkTypes: List<String>,
    val scheduling: PromptDensity,
    val totalDurationSec: Double,
    val totalSizeBytes: Long,
    val prompts: List<VoiceGuidePrompt>,
    val meditationScheduling: PromptDensity? = null,
    val meditationPrompts: List<VoiceGuidePrompt>? = null,
) {
    val hasMeditationGuide: Boolean
        get() = !meditationPrompts.isNullOrEmpty()
}

@Serializable
data class PromptDensity(
    val densityMinSec: Int,
    val densityMaxSec: Int,
    val minSpacingSec: Int,
    val initialDelaySec: Int,
    val walkEndBufferSec: Int,
)

@Serializable
data class VoiceGuidePrompt(
    val id: String,
    val seq: Int,
    val durationSec: Double,
    val fileSizeBytes: Long,
    val r2Key: String,
    val phase: String? = null,
)
```

**Field naming:** matches iOS exactly (camelCase). kotlinx.serialization defaults to the Kotlin property name for the JSON key, so no `@SerialName` annotations needed as long as JSON keys are camelCase (they are — iOS produces the manifest).

**`totalSizeBytes`/`fileSizeBytes`:** `Long` on Android (iOS uses `Int`; Kotlin's `Int` is 32-bit and a voice-guide pack could exceed 2 GB in theory, though in practice a pack is ~50 MB). Long is safe.

**`hasMeditationGuide`:** computed property — not serialized. Same semantics as iOS.

**`PromptPhase` enum (iOS):** not ported in 5-C. `phase: String?` is sufficient until Stage 5-D needs to dispatch on phase.

### New: `data/voiceguide/VoiceGuideConfig.kt` — URL constant

```kotlin
internal object VoiceGuideConfig {
    const val MANIFEST_URL = "https://cdn.pilgrimapp.org/voiceguide/manifest.json"
}
```

Internal visibility; the URL is a single constant that `VoiceGuideManifestService` consumes.

### New: `data/voiceguide/VoiceGuideManifestService.kt` — `@Singleton`

```kotlin
@Singleton
class VoiceGuideManifestService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: OkHttpClient,
    private val json: Json,
    @VoiceGuideManifestScope private val scope: CoroutineScope,
) {
    private val _packs = MutableStateFlow<List<VoiceGuidePack>>(emptyList())
    val packs: StateFlow<List<VoiceGuidePack>> = _packs.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val localManifestFile: File by lazy {
        File(context.filesDir, "voice_guide_manifest.json").also {
            it.parentFile?.mkdirs()
        }
    }

    init {
        // Load the local cache eagerly so UIs observing `packs` don't
        // see a transient empty list on cold start before syncIfNeeded
        // completes.
        loadLocalManifest()
    }

    fun pack(byId id: String): VoiceGuidePack? = _packs.value.firstOrNull { it.id == id }

    fun syncIfNeeded() {
        if (_isSyncing.value) return        // dedupe concurrent syncs
        scope.launch {
            _isSyncing.value = true
            try {
                val remote = fetchRemoteManifest()
                if (remote != null) {
                    val localVersion = readLocalVersion()
                    if (remote.version != localVersion) {
                        saveLocalManifest(remote)
                    }
                    _packs.value = remote.packs
                }
            } finally {
                _isSyncing.value = false
            }
        }
    }

    private suspend fun fetchRemoteManifest(): VoiceGuideManifest? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(VoiceGuideConfig.MANIFEST_URL).build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "manifest fetch non-2xx: ${response.code}")
                    return@use null
                }
                val body = response.body?.string() ?: return@use null
                json.decodeFromString<VoiceGuideManifest>(body)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "manifest fetch failed", t)
            null
        }
    }

    private fun loadLocalManifest() {
        if (!localManifestFile.exists()) return
        try {
            val text = localManifestFile.readText()
            val manifest = json.decodeFromString<VoiceGuideManifest>(text)
            _packs.value = manifest.packs
        } catch (t: Throwable) {
            Log.w(TAG, "failed to load local manifest; treating as absent", t)
        }
    }

    private fun readLocalVersion(): String? {
        if (!localManifestFile.exists()) return null
        return try {
            val text = localManifestFile.readText()
            json.decodeFromString<VoiceGuideManifest>(text).version
        } catch (t: Throwable) {
            null
        }
    }

    private fun saveLocalManifest(manifest: VoiceGuideManifest) {
        // Atomic write: write-to-tmp + rename so a partial write can't
        // leave a corrupt manifest behind on disk (crash / power loss
        // mid-write). Matches iOS's AtomicWrite semantics via Data.write.
        val tmp = File(localManifestFile.parentFile, "${localManifestFile.name}.tmp")
        try {
            tmp.writeText(json.encodeToString(manifest))
            if (!tmp.renameTo(localManifestFile)) {
                // Fall back to overwrite if rename fails (rare — typically
                // same-filesystem rename is atomic on Android).
                Files.move(tmp.toPath(), localManifestFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "failed to save local manifest; in-memory state retains it", t)
            tmp.delete()
        }
    }

    private companion object {
        const val TAG = "VoiceGuideManifest"
    }
}
```

### New: `data/voiceguide/VoiceGuideManifestScope.kt` — qualifier annotation

```kotlin
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class VoiceGuideManifestScope
```

Same pattern as `MeditationBellScope` (Stage 5-B) + `HemisphereRepositoryScope` (Stage 3-D).

### Hilt DI module

New: `di/NetworkModule.kt` (or extend an existing module):

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            // Intentionally NO retry interceptor. OkHttp's default
            // `retryOnConnectionFailure = true` already handles
            // transient DNS/connection failures. For a manifest fetch
            // that's user-triggered (not background-critical), one
            // retry on connection failure is enough; exponential
            // backoff belongs in Phase 8's shared worker policy.
            .build()

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        // Tolerate new fields the Android client doesn't know about —
        // iOS may add keys to the manifest without bumping our JSON
        // schema. Without this, unknown keys would throw.
        ignoreUnknownKeys = true
        // Missing optional fields (like `meditationPrompts`) get
        // their default values.
        explicitNulls = false
    }
}
```

Extend `AudioModule` or create a new module for the manifest scope:

```kotlin
@Provides
@Singleton
@VoiceGuideManifestScope
fun provideVoiceGuideManifestScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.Default)
```

## Testing

### `VoiceGuideManifestTest` (plain JUnit — pure JSON parsing)

Parse sample manifest JSON fixtures into `VoiceGuideManifest` + assert round-trip. Include:
- Minimal manifest (empty packs)
- Full manifest with meditation prompts
- Manifest with unknown fields (forward-compatibility — ensures `ignoreUnknownKeys = true` works)
- Manifest missing optional `meditationPrompts` / `meditationScheduling` (optional-field handling)
- Round-trip: parse → encode → parse → structural equal

### `VoiceGuideManifestServiceTest` (Robolectric + MockWebServer)

- `syncIfNeeded fetches and stores manifest on first sync` — MockWebServer returns 200 + valid JSON, assert `packs.value` populated, assert local file exists.
- `syncIfNeeded does not overwrite when version unchanged` — pre-seed local cache with `version=1.0`, MockWebServer returns 200 + `version=1.0`, assert file last-modified unchanged (or use a spy to count writes).
- `syncIfNeeded overwrites when version changed` — pre-seed `version=1.0`, remote returns `version=2.0`, assert file written + `packs.value` reflects remote.
- `syncIfNeeded on network failure keeps local cache` — MockWebServer closes connection or returns 500; local `packs` unchanged.
- `syncIfNeeded dedupes concurrent calls` — call twice back-to-back while first is in-flight; assert only one HTTP call landed on MockWebServer.
- `init loads local cache if present` — pre-seed file before constructing service; assert `packs.value` is non-empty immediately after construction.
- `init treats corrupt local cache as absent` — pre-seed file with garbage JSON; assert `packs.value` is empty (no crash).
- `atomic write leaves no partial file on exception` — inject a failing `json.encodeToString` (or use a filesystem spy to throw mid-write); assert the existing `.json` file is unchanged and no `.tmp` remains.

### Integration confidence

OkHttp + MockWebServer is a tight integration test — exercises the real HTTP stack against a local server. That plus the JSON round-trip test covers the core infrastructure.

## What's on the commit

- New: `app/src/main/java/org/walktalkmeditate/pilgrim/data/voiceguide/VoiceGuideManifest.kt`
- New: `app/src/main/java/org/walktalkmeditate/pilgrim/data/voiceguide/VoiceGuideConfig.kt`
- New: `app/src/main/java/org/walktalkmeditate/pilgrim/data/voiceguide/VoiceGuideManifestService.kt`
- New: `app/src/main/java/org/walktalkmeditate/pilgrim/data/voiceguide/VoiceGuideManifestScope.kt`
- New: `app/src/main/java/org/walktalkmeditate/pilgrim/di/NetworkModule.kt`
- Modified: `gradle/libs.versions.toml` — add OkHttp, MockWebServer, kotlinx.serialization, kotlin-serialization plugin
- Modified: `app/build.gradle.kts` — apply plugin, implementations + testImplementation
- Modified: `di/AudioModule.kt` OR new file for the `@VoiceGuideManifestScope` provider (pick whichever keeps diffs cleanest — small additions to `AudioModule`'s `companion object` is fine since it already hosts `@MeditationBellScope`)
- New tests: `VoiceGuideManifestTest.kt`, `VoiceGuideManifestServiceTest.kt`

No `AndroidManifest.xml` changes needed — `android.permission.INTERNET` already declared (verified Stage 2-D).

## Risks and mitigations

- **Atomic-write semantics of `renameTo`:** `File.renameTo` is not guaranteed atomic across filesystems; on Android the `filesDir` is on the app's internal partition (same filesystem) so it should be atomic in practice. The `Files.move` fallback with `REPLACE_EXISTING` is a defense against the rare rename failure.
- **OkHttp 4.x vs 5.x API drift:** we pick 4.12.0; Phase 8 can bump. Both APIs support the core `Call.execute()` + `Response.body.string()` used here, so migration is low-cost.
- **kotlinx.serialization plugin setup:** requires adding the plugin to `app/build.gradle.kts`. This is a one-time project change; CI must pick up the new plugin. Standard Gradle behavior.
- **CDN unavailability at first launch:** user sees empty pack list. Acceptable — the voice guide UI will show "no packs available" with a retry affordance (Stage 5-D scope). First-install users without connectivity don't expect content anyway.
- **Manifest shape drift from iOS:** iOS is the source of truth for the JSON. If iOS adds new fields, `ignoreUnknownKeys = true` prevents breakage; Android decodes what it knows. New REQUIRED fields would break; `@Serializable` with no default would throw at decode. Optional fields (marked `= null` in Kotlin) are forward-compatible.
- **HTTP timeout tuning:** `connect 10s + read 30s + call 45s` are defensive defaults. A slow network could cause `syncIfNeeded` to take ~45s; since it's background and user-triggered later (Stage 5-D), not blocking UI, acceptable.
- **First-launch race: UI reads `packs` before `init` completes:** `init` is synchronous (`loadLocalManifest` is a file read). The Hilt `@Singleton` constructor runs on whatever thread triggers it — typically the main thread on first `@Inject`. For a small file (~KBs) this is fast; a slow filesystem could block Main for tens of ms. Acceptable on first-launch where there's no cache to load anyway.

## Success criteria

- `./gradlew :app:testDebugUnitTest` green (~10 new test cases).
- `./gradlew :app:lintDebug` green; no new warnings.
- `./gradlew :app:assembleDebug` builds successfully with OkHttp + kotlinx.serialization in the dependency graph.
- No UI changes — the app is observably identical to Stage 5-B from the user's perspective.
- A future Stage 5-D surface that injects `VoiceGuideManifestService` and calls `syncIfNeeded()` will populate `packs` from the CDN. Device QA for 5-D covers the actual fetch against real `pilgrimapp.org`.

## Open questions (answered)

- *Downloads in 5-C or separate?* Separate. iOS's download manager is a thin wrapper that makes sense paired with the UI that triggers downloads. Folding them together enlarges 5-C's blast radius without benefit.
- *Room or filesystem cache?* Filesystem — matches iOS, simpler, smaller surface area. If Stage 5-D needs to query "which packs are downloaded" via Flow, that's file-existence checks, not a Room table.
- *OkHttp or Ktor?* OkHttp — Android-first, battle-tested, excellent test support via MockWebServer.
- *kotlinx.serialization or Moshi/Gson?* kotlinx.serialization — Kotlin-native, compile-time, aligned with the 2.3.20 toolchain.
- *Bundled seed manifest?* No. Matches iOS. Keeps APK smaller; empty-state UI in 5-D handles first-launch-no-cache.
- *Retry policy?* OkHttp default `retryOnConnectionFailure = true` is enough for a user-triggered sync. Exponential backoff is Phase 8 territory.
- *Auth / API keys?* The manifest endpoint is public. No auth needed.
- *Worker / WorkManager?* Not needed — sync is on-demand when user opens the voice-guide picker. Stage 5-D may add a periodic refresh worker later if on-device QA finds the on-open latency objectionable.
- *Seed the manifest on app first-launch via bundled JSON + Background sync?* Out of scope. User waits for one fetch.
