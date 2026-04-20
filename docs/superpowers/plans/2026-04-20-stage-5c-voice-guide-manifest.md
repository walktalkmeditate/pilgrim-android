# Stage 5-C Implementation Plan — Voice guide manifest layer

Spec: `docs/superpowers/specs/2026-04-20-stage-5c-voice-guide-manifest-design.md`.

Data-layer stage: fetch + parse + cache the voice-guide manifest. No UI changes. First introduction of OkHttp + kotlinx.serialization to the project.

---

## Task 1 — Add OkHttp + kotlinx.serialization to `libs.versions.toml`

**Edit:** `gradle/libs.versions.toml`

In `[versions]` block:
```toml
okhttp = "4.12.0"
kotlinxSerialization = "1.7.3"
```

In `[libraries]` block:
```toml
okhttp = { group = "com.squareup.okhttp3", name = "okhttp", version.ref = "okhttp" }
okhttp-mockwebserver = { group = "com.squareup.okhttp3", name = "mockwebserver", version.ref = "okhttp" }
kotlinx-serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "kotlinxSerialization" }
```

In `[plugins]` block:
```toml
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```

**Verify:** TOML parses (run `./gradlew help` after Task 2).

---

## Task 2 — Apply plugin + dependencies in `app/build.gradle.kts`

**Edit:** `app/build.gradle.kts`

In `plugins { ... }` block, after the existing plugins:
```kotlin
alias(libs.plugins.kotlin.serialization)
```

In `dependencies { ... }` block (find the place where other `implementation(...)` calls live — usually grouped):
```kotlin
implementation(libs.okhttp)
implementation(libs.kotlinx.serialization.json)

testImplementation(libs.okhttp.mockwebserver)
```

**Verify:** `./gradlew :app:dependencies | grep -iE "okhttp|serialization"` shows both libraries.

---

## Task 3 — `VoiceGuideManifest.kt` — data classes

**Create:** `app/src/main/java/org/walktalkmeditate/pilgrim/data/voiceguide/VoiceGuideManifest.kt`

Port the iOS shape 1:1:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.voiceguide

import kotlinx.serialization.Serializable

/**
 * Top-level voice-guide manifest document published at
 * [VoiceGuideConfig.MANIFEST_URL]. Ports iOS's
 * `VoiceGuideManifest` exactly — field names + order match the JSON
 * keys iOS produces. `kotlinx.serialization` uses the Kotlin property
 * name as the JSON key by default, which matches the camelCase keys
 * iOS produces — no `@SerialName` annotations needed.
 */
@Serializable
data class VoiceGuideManifest(
    val version: String,
    val packs: List<VoiceGuidePack>,
)

/**
 * One voice-guide pack (e.g., "Morning Walk"). Contains the pack's
 * metadata + pre-rendered prompts for walk narration and (optionally)
 * guided meditation.
 *
 * [totalSizeBytes] and [VoiceGuidePrompt.fileSizeBytes] are `Long`
 * (not `Int`): a pack with many prompts can plausibly exceed 2 GB at
 * edge cases, and `Long` costs nothing on the wire.
 */
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
    /** Mirrors iOS's `hasMeditationGuide` computed property. */
    val hasMeditationGuide: Boolean
        get() = !meditationPrompts.isNullOrEmpty()
}

/**
 * Per-pack prompt-scheduling parameters. Consumers of this data
 * (Stage 5-D scheduler) use it to pick how often and how densely
 * to surface prompts during a walk or meditation session.
 */
@Serializable
data class PromptDensity(
    val densityMinSec: Int,
    val densityMaxSec: Int,
    val minSpacingSec: Int,
    val initialDelaySec: Int,
    val walkEndBufferSec: Int,
)

/**
 * One audio prompt within a pack. `r2Key` is the CDN object key
 * (Cloudflare R2) — consumers resolve it to a download URL at fetch
 * time in Stage 5-D.
 */
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

**Verify:** `./gradlew :app:compileDebugKotlin` (plugin must be applied from Task 2 for `@Serializable` to work).

---

## Task 4 — `VoiceGuideConfig.kt`

**Create:** `app/src/main/java/org/walktalkmeditate/pilgrim/data/voiceguide/VoiceGuideConfig.kt`

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.voiceguide

/**
 * Endpoint + asset-path constants for the voice-guide catalog.
 * Mirrors iOS's `Config.VoiceGuide` group.
 */
internal object VoiceGuideConfig {
    /** Public manifest endpoint. Same URL iOS reads from. */
    const val MANIFEST_URL = "https://cdn.pilgrimapp.org/voiceguide/manifest.json"
}
```

**Verify:** compiles.

---

## Task 5 — `VoiceGuideManifestScope.kt` — qualifier annotation

**Create:** `app/src/main/java/org/walktalkmeditate/pilgrim/data/voiceguide/VoiceGuideManifestScope.kt`

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.voiceguide

import javax.inject.Qualifier

/**
 * Qualifier for the long-lived [kotlinx.coroutines.CoroutineScope]
 * that backs [VoiceGuideManifestService]'s sync coroutine. Separate
 * from `viewModelScope` / other short-lived scopes because a sync
 * request may outlive the screen that triggered it (user navigates
 * away mid-fetch; the fetch completes and updates the cache
 * regardless). `SupervisorJob` so one failed emission doesn't tear
 * the whole scope down. `Dispatchers.Default` since the work is
 * Flow state updates + file I/O hops via explicit
 * `withContext(Dispatchers.IO)` blocks. Same shape as
 * `MeditationBellScope` (Stage 5-B) and `HemisphereRepositoryScope`
 * (Stage 3-D).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class VoiceGuideManifestScope
```

---

## Task 6 — `NetworkModule.kt` — OkHttp + Json providers

**Create:** `app/src/main/java/org/walktalkmeditate/pilgrim/di/NetworkModule.kt`

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/**
 * First-introduction HTTP + JSON infrastructure for Stage 5-C. The
 * `OkHttpClient` and `Json` here are project-wide `@Singleton`
 * instances so future Phase 8 work (Collective Counter, Share
 * Worker) can reuse them without re-establishing the stack.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /**
     * Default OkHttp client for read-only JSON manifest fetches.
     *
     * Timeouts are defensive defaults for a user-triggered background
     * sync: 10s connect, 30s read, 45s total. No retry interceptor —
     * OkHttp's built-in `retryOnConnectionFailure = true` (default)
     * handles transient DNS / connection resets once; exponential
     * backoff belongs in Phase 8's shared worker policy when
     * background uploads / writes arrive.
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SEC, TimeUnit.SECONDS)
            .build()

    /**
     * Project-wide `Json` instance with forward-compatibility defaults.
     *  - `ignoreUnknownKeys`: iOS may add new manifest fields without
     *    breaking Android — we decode what we recognize, skip the rest.
     *  - `explicitNulls = false`: missing optional fields in JSON
     *    (e.g., a pack without `meditationPrompts`) decode to their
     *    Kotlin default (`null`), instead of throwing.
     */
    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private const val CONNECT_TIMEOUT_SEC = 10L
    private const val READ_TIMEOUT_SEC = 30L
    private const val CALL_TIMEOUT_SEC = 45L
}
```

**Verify:** `./gradlew :app:compileDebugKotlin` — Hilt should generate the factories.

---

## Task 7 — `VoiceGuideManifestService.kt`

**Create:** `app/src/main/java/org/walktalkmeditate/pilgrim/data/voiceguide/VoiceGuideManifestService.kt`

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.voiceguide

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Fetches the voice-guide manifest from
 * [VoiceGuideConfig.MANIFEST_URL], parses the iOS-shaped JSON, and
 * exposes the resulting [VoiceGuidePack] list via
 * [packs]. Cold-start behavior: load the local cache file
 * synchronously in `init` so UIs observing [packs] see the
 * last-known-good catalog immediately. Call [syncIfNeeded] from a
 * user surface (Stage 5-D picker) to refresh against the CDN.
 *
 * Thread safety: [_packs] and [_isSyncing] are `MutableStateFlow`;
 * concurrent `syncIfNeeded` calls are deduped via the
 * [_isSyncing.value] guard before any work is launched. Network I/O
 * hops to `Dispatchers.IO`; file I/O (load/save cache) is
 * synchronous on the calling thread — small file (~KBs), acceptable
 * even on Main.
 *
 * Cache policy: writes the manifest to
 * `filesDir/voice_guide_manifest.json` atomically (write-to-tmp +
 * rename). Only rewrites when the fetched `version` differs from the
 * cached one, so an unchanged remote doesn't churn the filesystem.
 *
 * See `docs/superpowers/specs/2026-04-20-stage-5c-voice-guide-manifest-design.md`.
 */
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
        File(context.filesDir, LOCAL_MANIFEST_NAME)
    }

    init {
        loadLocalManifest()
    }

    /** Lookup a pack by id in the currently-cached catalog. */
    fun pack(byId id: String): VoiceGuidePack? =
        _packs.value.firstOrNull { it.id == id }

    /**
     * Fetch the remote manifest; if the version differs from the
     * local cache (or no cache exists), persist it and publish the
     * new pack list. On network or parse failure, the local cache
     * and in-memory state remain unchanged.
     *
     * Dedupes concurrent calls — if a sync is in flight, subsequent
     * calls no-op until it completes. The pending sync picks up the
     * latest remote state; there's no value to queuing a second
     * identical fetch.
     */
    fun syncIfNeeded() {
        if (_isSyncing.value) return
        scope.launch {
            _isSyncing.value = true
            try {
                val remote = fetchRemoteManifest() ?: return@launch
                val localVersion = readLocalVersion()
                if (remote.version != localVersion) {
                    saveLocalManifest(remote)
                }
                _packs.value = remote.packs
            } finally {
                _isSyncing.value = false
            }
        }
    }

    private suspend fun fetchRemoteManifest(): VoiceGuideManifest? =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(VoiceGuideConfig.MANIFEST_URL)
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "manifest fetch non-2xx: ${response.code}")
                        return@use null
                    }
                    val body = response.body?.string()
                    if (body.isNullOrEmpty()) {
                        Log.w(TAG, "manifest body empty")
                        return@use null
                    }
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
            // Corrupt cache (partial write interrupted by crash, manual
            // file edit, schema mismatch): treat as absent. The next
            // syncIfNeeded rewrites the file atomically.
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
        // Atomic write: write-to-tmp + rename. Protects against a
        // partial write (app kill / power loss mid-write) leaving a
        // corrupt manifest on disk.
        val tmp = File(localManifestFile.parentFile, "$LOCAL_MANIFEST_NAME.tmp")
        try {
            tmp.writeText(json.encodeToString(manifest))
            if (!tmp.renameTo(localManifestFile)) {
                // Rare — same-filesystem rename on Android's internal
                // storage is typically atomic. Fall back to REPLACE.
                Files.move(
                    tmp.toPath(),
                    localManifestFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "failed to save local manifest; in-memory state retains it", t)
            tmp.delete()
        }
    }

    private companion object {
        const val TAG = "VoiceGuideManifest"
        const val LOCAL_MANIFEST_NAME = "voice_guide_manifest.json"
    }
}
```

**Verify:** `./gradlew :app:compileDebugKotlin`.

---

## Task 8 — Add `@VoiceGuideManifestScope` provider to `AudioModule.kt`

**Edit:** `app/src/main/java/org/walktalkmeditate/pilgrim/di/AudioModule.kt`

Add to the `companion object` (alongside `@MeditationBellScope`):

```kotlin
@Provides
@Singleton
@VoiceGuideManifestScope
fun provideVoiceGuideManifestScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.Default)
```

Add the import:
```kotlin
import org.walktalkmeditate.pilgrim.data.voiceguide.VoiceGuideManifestScope
```

(Other imports — `CoroutineScope`, `Dispatchers`, `SupervisorJob` — are already present from the Stage 5-B bell-scope provider.)

**Verify:** `./gradlew :app:compileDebugKotlin`.

---

## Task 9 — `VoiceGuideManifestTest.kt` — pure JSON parsing

**Create:** `app/src/test/java/org/walktalkmeditate/pilgrim/data/voiceguide/VoiceGuideManifestTest.kt`

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.voiceguide

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JSON parsing tests for [VoiceGuideManifest]. Validates that
 * the Kotlin data-class shape matches the iOS-produced JSON, and
 * that forward-compatibility defaults (`ignoreUnknownKeys`,
 * `explicitNulls = false`) work as configured.
 */
class VoiceGuideManifestTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Test fun `parses minimal manifest with empty packs`() {
        val payload = """{"version":"1.0","packs":[]}"""
        val manifest = json.decodeFromString<VoiceGuideManifest>(payload)
        assertEquals("1.0", manifest.version)
        assertTrue(manifest.packs.isEmpty())
    }

    @Test fun `parses pack with prompts and no meditation guide`() {
        val payload = """
            {
              "version":"1.0",
              "packs":[
                {
                  "id":"morning",
                  "version":"1.0",
                  "name":"Morning Walk",
                  "tagline":"Gentle start",
                  "description":"A gentle start to your day.",
                  "theme":"dawn",
                  "iconName":"sun.rise",
                  "type":"narrated",
                  "walkTypes":["walk"],
                  "scheduling":{
                    "densityMinSec":60,"densityMaxSec":120,
                    "minSpacingSec":30,"initialDelaySec":10,
                    "walkEndBufferSec":30
                  },
                  "totalDurationSec":300.5,
                  "totalSizeBytes":5242880,
                  "prompts":[
                    {"id":"p1","seq":0,"durationSec":12.0,
                     "fileSizeBytes":192000,"r2Key":"morning/p1.wav"}
                  ]
                }
              ]
            }
        """.trimIndent()
        val manifest = json.decodeFromString<VoiceGuideManifest>(payload)
        val pack = manifest.packs.single()
        assertEquals("morning", pack.id)
        assertEquals("Morning Walk", pack.name)
        assertEquals(1, pack.prompts.size)
        assertEquals("p1", pack.prompts[0].id)
        assertEquals("morning/p1.wav", pack.prompts[0].r2Key)
        assertNull(pack.prompts[0].phase)
        assertNull(pack.meditationScheduling)
        assertNull(pack.meditationPrompts)
        assertFalse(pack.hasMeditationGuide)
    }

    @Test fun `parses pack with meditation prompts`() {
        val payload = """
            {
              "version":"1.0",
              "packs":[
                {
                  "id":"stillness",
                  "version":"1.0",
                  "name":"Stillness",
                  "tagline":"Sit in silence",
                  "description":"Sit in silence with light guidance.",
                  "theme":"moss",
                  "iconName":"leaf",
                  "type":"narrated",
                  "walkTypes":["meditate"],
                  "scheduling":{
                    "densityMinSec":60,"densityMaxSec":120,
                    "minSpacingSec":30,"initialDelaySec":10,
                    "walkEndBufferSec":30
                  },
                  "totalDurationSec":600.0,
                  "totalSizeBytes":10485760,
                  "prompts":[],
                  "meditationScheduling":{
                    "densityMinSec":30,"densityMaxSec":90,
                    "minSpacingSec":15,"initialDelaySec":5,
                    "walkEndBufferSec":10
                  },
                  "meditationPrompts":[
                    {"id":"m1","seq":0,"durationSec":8.0,
                     "fileSizeBytes":128000,"r2Key":"stillness/m1.wav",
                     "phase":"settling"}
                  ]
                }
              ]
            }
        """.trimIndent()
        val manifest = json.decodeFromString<VoiceGuideManifest>(payload)
        val pack = manifest.packs.single()
        assertTrue(pack.hasMeditationGuide)
        assertEquals(1, pack.meditationPrompts?.size)
        assertEquals("settling", pack.meditationPrompts?.first()?.phase)
    }

    @Test fun `parses manifest with unknown fields (forward-compat)`() {
        // iOS might add fields (e.g., a top-level "publishedAt" or a
        // new pack attribute) without breaking Android clients. With
        // `ignoreUnknownKeys = true` we decode what we recognize and
        // skip the rest.
        val payload = """
            {
              "version":"2.0",
              "publishedAt":"2026-04-20T00:00:00Z",
              "nextRefreshAt":"2026-04-27T00:00:00Z",
              "packs":[]
            }
        """.trimIndent()
        val manifest = json.decodeFromString<VoiceGuideManifest>(payload)
        assertEquals("2.0", manifest.version)
    }

    @Test fun `round-trip preserves structural equality`() {
        val original = VoiceGuideManifest(
            version = "1.2.3",
            packs = listOf(
                VoiceGuidePack(
                    id = "test",
                    version = "1",
                    name = "Test",
                    tagline = "t",
                    description = "d",
                    theme = "dawn",
                    iconName = "x",
                    type = "narrated",
                    walkTypes = listOf("walk"),
                    scheduling = PromptDensity(60, 120, 30, 10, 30),
                    totalDurationSec = 10.0,
                    totalSizeBytes = 1_000L,
                    prompts = listOf(
                        VoiceGuidePrompt("p", 0, 5.0, 500L, "t/p.wav"),
                    ),
                ),
            ),
        )
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<VoiceGuideManifest>(encoded)
        assertEquals(original, decoded)
    }
}
```

**Verify:** `./gradlew :app:testDebugUnitTest --tests "*VoiceGuideManifestTest"`.

---

## Task 10 — `VoiceGuideManifestServiceTest.kt` — MockWebServer integration

**Create:** `app/src/test/java/org/walktalkmeditate/pilgrim/data/voiceguide/VoiceGuideManifestServiceTest.kt`

Use Robolectric so `Context.filesDir` works, MockWebServer for the HTTP side, real OkHttp + Json. The service's constructor accepts these directly, so the test wiring is straightforward.

Key subtlety: the service's ctor reads `VoiceGuideConfig.MANIFEST_URL` which is a hardcoded `cdn.pilgrimapp.org` URL. For tests, we need to **override that URL** — otherwise tests hit the real CDN. Two options:

A) Make `VoiceGuideManifestService` take the URL as a constructor parameter (production gets `VoiceGuideConfig.MANIFEST_URL`, tests get MockWebServer's URL).
B) Make `VoiceGuideConfig.MANIFEST_URL` a `var` or computed value overridable at test time.

Option A is cleaner (dependency injection) — add `manifestUrl: String = VoiceGuideConfig.MANIFEST_URL` as a default-valued constructor parameter. Production code doesn't change. Tests supply `mockWebServer.url("/manifest.json").toString()`.

**Revise Task 7** — update the service constructor:

```kotlin
@Singleton
class VoiceGuideManifestService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: OkHttpClient,
    private val json: Json,
    @VoiceGuideManifestScope private val scope: CoroutineScope,
) {
    // Manifest URL — overridable for tests via the ctor-overload below.
    // Production always uses VoiceGuideConfig.MANIFEST_URL.
    private var manifestUrl: String = VoiceGuideConfig.MANIFEST_URL

    // Test-only constructor: takes an override URL.
    internal constructor(
        context: Context,
        httpClient: OkHttpClient,
        json: Json,
        scope: CoroutineScope,
        manifestUrl: String,
    ) : this(context, httpClient, json, scope) {
        this.manifestUrl = manifestUrl
    }
    // ...
}
```

Hmm actually this is awkward. The cleaner approach is to make the URL a true parameter with a default value and let Hilt use the default (no change needed in DI). Hilt works with optional `= ...` defaulted params via `@Inject` reflection (kapt/ksp handles them). But `@Inject constructor` with defaults is FINE for Hilt as long as all non-defaulted params are satisfied.

Wait, I need to double-check. In recent Hilt/Dagger, `@Inject constructor(...)` with default-valued parameters: does Hilt respect the default? Or does it require an explicit binding?

Per Dagger docs: default arguments in `@Inject` constructors are ignored; Dagger requires every parameter to be bindable. So adding a defaulted `manifestUrl: String = ...` would either need a binding for `String` (wrong — too broad) or a qualified `@VoiceGuideManifestUrl String` binding.

Cleanest approach: add a qualifier `@VoiceGuideManifestUrl` and provide `VoiceGuideConfig.MANIFEST_URL` for it in production. Tests inject their own string. Verbose but correct.

OR simpler: leave URL as the hardcoded const inside `VoiceGuideConfig`, and accept that the test hits MockWebServer via a wrapper technique. Actually since OkHttpClient can be configured with an interceptor that rewrites URLs... no, that's ugly.

Final decision: add `@VoiceGuideManifestUrl` qualifier. Production binding in `NetworkModule`. Tests supply their own via direct-construction bypassing Hilt (Robolectric unit tests don't use Hilt graph; they construct the service manually).

Let me rewrite simply: expose the URL as a ctor parameter. Production calls Hilt which invokes the `@Inject constructor`. For Hilt to satisfy the `String` parameter, we qualify it.

```kotlin
// In VoiceGuideManifestScope.kt (or a new Qualifiers.kt):
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class VoiceGuideManifestUrl

// In NetworkModule (or a provider):
@Provides
@Singleton
@VoiceGuideManifestUrl
fun provideVoiceGuideManifestUrl(): String = VoiceGuideConfig.MANIFEST_URL

// In VoiceGuideManifestService:
@Singleton
class VoiceGuideManifestService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: OkHttpClient,
    private val json: Json,
    @VoiceGuideManifestScope private val scope: CoroutineScope,
    @VoiceGuideManifestUrl private val manifestUrl: String,
) {
    // ...
    private suspend fun fetchRemoteManifest(): VoiceGuideManifest? =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(manifestUrl).build()
            // ...
        }
}
```

Tests construct the service directly with a MockWebServer URL — Hilt isn't involved in the test. Clean.

**Updated Task list:**
- Task 5 also creates `VoiceGuideManifestUrl` qualifier (add to the same file)
- Task 6 adds the `@VoiceGuideManifestUrl` provider
- Task 7 updates the service ctor

The test now looks like:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.voiceguide

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VoiceGuideManifestServiceTest {

    private lateinit var context: Application
    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient
    private lateinit var json: Json
    private lateinit var scope: CoroutineScope
    private lateinit var localFile: File

    private val sampleManifest = """
        {"version":"1.0","packs":[
          {"id":"p","version":"1","name":"P","tagline":"t","description":"d",
           "theme":"dawn","iconName":"x","type":"narrated","walkTypes":["walk"],
           "scheduling":{"densityMinSec":60,"densityMaxSec":120,
             "minSpacingSec":30,"initialDelaySec":10,"walkEndBufferSec":30},
           "totalDurationSec":10.0,"totalSizeBytes":1000,
           "prompts":[]}
        ]}
    """.trimIndent()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        server = MockWebServer().apply { start() }
        http = OkHttpClient.Builder().build()
        json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher())
        localFile = File(context.filesDir, "voice_guide_manifest.json")
        if (localFile.exists()) localFile.delete()
    }

    @After
    fun tearDown() {
        server.shutdown()
        scope.coroutineContext[Job]?.cancel()
        if (localFile.exists()) localFile.delete()
    }

    private fun newService(url: String = server.url("/manifest.json").toString()) =
        VoiceGuideManifestService(context, http, json, scope, url)

    @Test
    fun `init with no local cache - packs is empty`() {
        val service = newService()
        assertTrue(service.packs.value.isEmpty())
    }

    @Test
    fun `init with valid local cache - packs is populated`() {
        localFile.writeText(sampleManifest)
        val service = newService()
        assertEquals(1, service.packs.value.size)
        assertEquals("p", service.packs.value[0].id)
    }

    @Test
    fun `init with corrupt local cache - packs is empty (no crash)`() {
        localFile.writeText("{not valid json")
        val service = newService()
        assertTrue(service.packs.value.isEmpty())
    }

    @Test
    fun `syncIfNeeded - fetches and stores manifest on first sync`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(sampleManifest))
        val service = newService()
        service.syncIfNeeded()
        advanceUntilIdle()
        assertEquals(1, service.packs.value.size)
        assertTrue(localFile.exists())
    }

    @Test
    fun `syncIfNeeded - keeps local cache on network failure`() = runTest {
        localFile.writeText(sampleManifest)
        server.enqueue(MockResponse().setResponseCode(500))
        val service = newService()
        service.syncIfNeeded()
        advanceUntilIdle()
        // Local cache preserved; in-memory state unchanged.
        assertEquals("p", service.packs.value[0].id)
        assertTrue(localFile.exists())
    }

    @Test
    fun `syncIfNeeded - does not re-write when version unchanged`() = runTest {
        localFile.writeText(sampleManifest)
        val lastModified = localFile.lastModified()
        server.enqueue(MockResponse().setResponseCode(200).setBody(sampleManifest))
        val service = newService()
        // Sleep briefly so a write-triggered mtime bump would be detectable.
        Thread.sleep(50)
        service.syncIfNeeded()
        advanceUntilIdle()
        assertEquals(lastModified, localFile.lastModified())
    }

    @Test
    fun `syncIfNeeded - overwrites when remote version differs`() = runTest {
        localFile.writeText(sampleManifest)
        val v2 = sampleManifest.replaceFirst("\"1.0\"", "\"2.0\"")
        server.enqueue(MockResponse().setResponseCode(200).setBody(v2))
        val service = newService()
        service.syncIfNeeded()
        advanceUntilIdle()
        // File now contains v2.
        val stored = json.decodeFromString<VoiceGuideManifest>(localFile.readText())
        assertEquals("2.0", stored.version)
    }

    @Test
    fun `syncIfNeeded - dedupes concurrent calls`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(sampleManifest))
        val service = newService()
        service.syncIfNeeded()
        service.syncIfNeeded()
        service.syncIfNeeded()
        advanceUntilIdle()
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `syncIfNeeded - isSyncing flow emits true then false`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(sampleManifest))
        val service = newService()
        service.isSyncing.test {
            assertFalse(awaitItem())              // initial
            service.syncIfNeeded()
            assertTrue(awaitItem())               // true during sync
            advanceUntilIdle()
            assertFalse(awaitItem())              // false after
            cancelAndIgnoreRemainingEvents()
        }
    }
}
```

**Verify:** `./gradlew :app:testDebugUnitTest --tests "*VoiceGuideManifestServiceTest"`.

---

## Task 11 — Build + test

```bash
export PATH="$HOME/.asdf/shims:$PATH" JAVA_HOME="$(asdf where java 2>/dev/null)"
cd <worktree>
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

**Verify:** all green. Test suite ~13 new cases. No new lint warnings. `:assembleDebug` succeeds (catches any plugin/dependency misconfiguration).

---

## Task 12 — Pre-commit audit

- `git diff --stat` — confirm 5 new + 3 modified files (versions.toml, app/build.gradle.kts, AudioModule.kt)
- Verify SPDX on all new `.kt` files
- No OutRun references
- No `// TODO` without stage reference
