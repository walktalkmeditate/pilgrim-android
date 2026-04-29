# Stage 10-D Implementation Plan: VoiceCard + RecordingsListScreen

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port iOS's VoiceCard and RecordingsListView to Android with pixel-faithful parity. Add `voiceGuideEnabled` master gate, `autoTranscribe` gate with upgrade-preserving migration, and a new Recordings screen.

**Architecture:** New `VoicePreferencesRepository` (Eagerly-shared StateFlows). VoiceCard slots into `SettingsScreen` above PracticeCard, replacing the transitional "Voice guides" nav row. New `RecordingsListScreen` reachable from VoiceCard's Recordings nav row. ExoPlayer controller gains `setPlaybackSpeed` + `seek` + position-tick. VoiceGuideOrchestrator extends to a 3-way combine. WalkFinalizationObserver gates auto-scheduling on the new pref. iOS-faithful UserDefaults keys for `.pilgrim` ZIP cross-platform parity.

**Tech Stack:** Jetpack Compose + Material 3, Hilt, Room, DataStore Preferences, ExoPlayer (existing), kotlinx.coroutines.test, Robolectric, JUnit 4, Turbine.

**Reference spec:** `docs/superpowers/specs/2026-04-28-stage-10-d-voice-card-recordings-design.md`

---

## File structure

### New files (22)

**Data layer (5):**
- `data/voice/VoicePreferencesRepository.kt` — interface
- `data/voice/DataStoreVoicePreferencesRepository.kt` — DataStore impl with autoTranscribe upgrade migration
- `data/voice/VoicePreferencesScope.kt` — Hilt qualifier
- `data/voice/VoiceRecordingFileSystem.kt` — canonical recording-file path resolver + delete
- `di/VoicePreferencesModule.kt` — Hilt @Binds + scope provider

**Settings UI (1):**
- `ui/settings/voice/VoiceCard.kt` — composable + state

**Recordings UI (6):**
- `ui/recordings/RecordingsListScreen.kt` — top-level screen
- `ui/recordings/RecordingsListViewModel.kt` — VM
- `ui/recordings/RecordingRow.kt` — per-row composable
- `ui/recordings/WaveformBar.kt` — Canvas seek bar
- `ui/recordings/WaveformLoader.kt` — WAV decoder + downsampler
- `ui/recordings/RecordingsSection.kt` — data class for grouping

**Tests (10):**
- `data/voice/VoicePreferencesRepositoryTest.kt` (Robolectric)
- `data/voice/FakeVoicePreferencesRepository.kt` (test double)
- `data/voice/VoiceRecordingFileSystemTest.kt` (Robolectric)
- `data/voiceguide/VoiceGuideSelectionRepositoryMigrationTest.kt` (Robolectric)
- `walk/WalkFinalizationObserverAutoTranscribeTest.kt` (Robolectric)
- `audio/voiceguide/VoiceGuideOrchestratorVoiceEnabledTest.kt` (plain JVM)
- `audio/ExoPlayerVoicePlaybackControllerSpeedSeekTest.kt` (Robolectric)
- `ui/settings/voice/VoiceCardTest.kt` (Robolectric)
- `ui/recordings/RecordingsListViewModelTest.kt` (Robolectric)
- `ui/recordings/RecordingsListScreenTest.kt` (Robolectric)
- `ui/recordings/RecordingRowTest.kt` (Robolectric)
- `ui/recordings/WaveformBarTest.kt` (Robolectric)
- `ui/recordings/WaveformLoaderTest.kt` (plain JVM)

(Test count is 13 files; spec said 10 — the audit added 3 we missed: `VoiceRecordingFileSystemTest`, `VoiceGuideOrchestratorVoiceEnabledTest`, `ExoPlayerVoicePlaybackControllerSpeedSeekTest`. All warranted by the new-surface-area policy.)

### Modified files (11)

1. `audio/voiceguide/VoiceGuideOrchestrator.kt` — 3-way combine + defensive `.value` reads + `MEDITATION_GUIDE_ALWAYS_ENABLED` constant.
2. `audio/voiceguide/VoiceGuideOrchestratorTest.kt` — update existing tests' constructor calls.
3. `walk/WalkFinalizationObserver.kt` — autoTranscribe gate.
4. `data/voiceguide/VoiceGuideSelectionRepository.kt` — key rename + read-fallback + Eagerly.
5. `audio/VoicePlaybackController.kt` (interface) — add speed + seek + position-tick.
6. `audio/ExoPlayerVoicePlaybackController.kt` — implement + route through `VoiceRecordingFileSystem`.
7. `ui/settings/SettingsScreen.kt` — drop the transitional voice-guides row, slot in VoiceCard.
8. `ui/settings/SettingsViewModel.kt` — add VoicePreferences StateFlow + setters + `recordingsAggregate`.
9. `ui/walk/WalkSummaryViewModel.kt` — verify PlaybackState consumers don't need updates (read-only audit).
10. `PilgrimNavHost.kt` — `Routes.RECORDINGS_LIST` + `SettingsAction.OpenRecordings`.
11. `res/values/strings.xml` — ~30 new entries.

---

## Task ordering rationale

The order below puts FOUNDATION (preferences, file system, key migration) first, BEHAVIOR (orchestrator + finalizer + ExoPlayer) second, and UI (VoiceCard, RecordingsListScreen) third — exactly as the spec recommends. This keeps each PR-task buildable and testable in isolation, so a build/test failure surfaces as soon as it's introduced rather than at the end.

---

## Task 1: VoicePreferencesRepository foundation

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/data/voice/VoicePreferencesScope.kt`
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/data/voice/VoicePreferencesRepository.kt`
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/data/voice/DataStoreVoicePreferencesRepository.kt`
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/di/VoicePreferencesModule.kt`
- Create: `app/src/test/java/org/walktalkmeditate/pilgrim/data/voice/FakeVoicePreferencesRepository.kt`
- Test: `app/src/test/java/org/walktalkmeditate/pilgrim/data/voice/VoicePreferencesRepositoryTest.kt`

- [ ] **Step 1: Write failing test for default values + round-trip + upgrade migration**

`app/src/test/java/org/walktalkmeditate/pilgrim/data/voice/VoicePreferencesRepositoryTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.voice

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VoicePreferencesRepositoryTest {

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var scope: CoroutineScope
    private lateinit var storeFile: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val name = "voice-prefs-test-${UUID.randomUUID()}"
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        storeFile = File(context.filesDir, "datastore/$name.preferences_pb")
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { storeFile }
    }

    @After
    fun tearDown() {
        scope.cancel()
        storeFile.delete()
    }

    @Test
    fun `defaults — voiceGuideEnabled false, autoTranscribe false on fresh install`() = runTest {
        val repo = DataStoreVoicePreferencesRepository(dataStore, scope)
        // First read: no pre-existing keys → fresh-install branch.
        assertFalse(repo.voiceGuideEnabled.value)
        assertFalse(repo.autoTranscribe.value)
    }

    @Test
    fun `setVoiceGuideEnabled persists across instances`() = runTest {
        val repo1 = DataStoreVoicePreferencesRepository(dataStore, scope)
        repo1.setVoiceGuideEnabled(true)
        // Allow Eagerly StateFlow to observe the write.
        runBlocking { kotlinx.coroutines.delay(50) }
        val repo2 = DataStoreVoicePreferencesRepository(dataStore, scope)
        runBlocking { kotlinx.coroutines.delay(50) }
        assertTrue(repo2.voiceGuideEnabled.value)
    }

    @Test
    fun `setAutoTranscribe persists across instances`() = runTest {
        val repo1 = DataStoreVoicePreferencesRepository(dataStore, scope)
        repo1.setAutoTranscribe(true)
        runBlocking { kotlinx.coroutines.delay(50) }
        val repo2 = DataStoreVoicePreferencesRepository(dataStore, scope)
        runBlocking { kotlinx.coroutines.delay(50) }
        assertTrue(repo2.autoTranscribe.value)
    }

    @Test
    fun `autoTranscribe migration — fresh install seeds false`() = runTest {
        val repo = DataStoreVoicePreferencesRepository(dataStore, scope)
        runBlocking { kotlinx.coroutines.delay(50) }
        assertFalse(repo.autoTranscribe.value)
        // Persisted seed: re-read confirms.
        val repo2 = DataStoreVoicePreferencesRepository(dataStore, scope)
        runBlocking { kotlinx.coroutines.delay(50) }
        assertFalse(repo2.autoTranscribe.value)
    }

    @Test
    fun `autoTranscribe migration — upgrade with appearance_mode set seeds true`() = runTest {
        // Pre-seed: simulate a Stage 9.5-E user who has appearance_mode written.
        dataStore.edit { it[stringPreferencesKey("appearance_mode")] = "system" }
        runBlocking { kotlinx.coroutines.delay(50) }
        val repo = DataStoreVoicePreferencesRepository(dataStore, scope)
        runBlocking { kotlinx.coroutines.delay(100) }
        assertTrue(repo.autoTranscribe.value)
    }

    @Test
    fun `autoTranscribe migration — explicit user write preserved`() = runTest {
        // Pre-seed: previous user explicitly set autoTranscribe=false (overriding any seed).
        dataStore.edit { it[booleanPreferencesKey("autoTranscribe")] = false }
        // Also pre-seed an upgrade-indicator key — without the explicit write,
        // the migration would try to seed true. The explicit write must win.
        dataStore.edit { it[stringPreferencesKey("appearance_mode")] = "system" }
        runBlocking { kotlinx.coroutines.delay(50) }
        val repo = DataStoreVoicePreferencesRepository(dataStore, scope)
        runBlocking { kotlinx.coroutines.delay(100) }
        assertFalse(repo.autoTranscribe.value)
    }
}
```

- [ ] **Step 2: Run test to verify it fails (no production code yet)**

```
./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.data.voice.VoicePreferencesRepositoryTest" --no-configuration-cache
```

Expected: FAIL — `Unresolved reference: DataStoreVoicePreferencesRepository`.

- [ ] **Step 3: Create scope qualifier**

`app/src/main/java/org/walktalkmeditate/pilgrim/data/voice/VoicePreferencesScope.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.voice

import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class VoicePreferencesScope
```

- [ ] **Step 4: Create interface**

`app/src/main/java/org/walktalkmeditate/pilgrim/data/voice/VoicePreferencesRepository.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.voice

import kotlinx.coroutines.flow.StateFlow

/**
 * Voice-related user preferences. All keys match iOS UserDefaults names verbatim
 * for `.pilgrim` ZIP cross-platform parity.
 *
 * StateFlows are `SharingStarted.Eagerly` because the orchestrator and walk-finalize
 * observer read `.value` synchronously from background contexts (no UI subscriber).
 * `WhileSubscribed` would silently return defaults in those paths.
 */
interface VoicePreferencesRepository {
    val voiceGuideEnabled: StateFlow<Boolean>
    val autoTranscribe: StateFlow<Boolean>
    suspend fun setVoiceGuideEnabled(enabled: Boolean)
    suspend fun setAutoTranscribe(enabled: Boolean)
}
```

- [ ] **Step 5: Create DataStore implementation with migration**

`app/src/main/java/org/walktalkmeditate/pilgrim/data/voice/DataStoreVoicePreferencesRepository.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.voice

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Singleton
class DataStoreVoicePreferencesRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    @VoicePreferencesScope private val scope: CoroutineScope,
) : VoicePreferencesRepository {

    override val voiceGuideEnabled: StateFlow<Boolean> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs -> prefs[VOICE_GUIDE_ENABLED] ?: DEFAULT_VOICE_GUIDE_ENABLED }
        .stateIn(scope, SharingStarted.Eagerly, DEFAULT_VOICE_GUIDE_ENABLED)

    override val autoTranscribe: StateFlow<Boolean> by lazy {
        // Run the upgrade migration on first lazy access, then expose the flow.
        scope.launch { runAutoTranscribeMigrationIfNeeded() }
        dataStore.data
            .catch { emit(emptyPreferences()) }
            .map { prefs -> prefs[AUTO_TRANSCRIBE] ?: DEFAULT_AUTO_TRANSCRIBE }
            .stateIn(scope, SharingStarted.Eagerly, DEFAULT_AUTO_TRANSCRIBE)
    }

    override suspend fun setVoiceGuideEnabled(enabled: Boolean) {
        dataStore.edit { it[VOICE_GUIDE_ENABLED] = enabled }
    }

    override suspend fun setAutoTranscribe(enabled: Boolean) {
        dataStore.edit { it[AUTO_TRANSCRIBE] = enabled }
    }

    /**
     * Stage 10-D autoTranscribe migration. Android currently auto-transcribes every
     * recording (via WalkFinalizationObserver). iOS default is false. To preserve
     * existing-user behavior on upgrade while matching iOS for fresh installs:
     *
     * - If `autoTranscribe` key is already present → no-op (user has expressed a
     *   preference, or migration already ran).
     * - Else if any pre-existing user-pref key is detected → seed `autoTranscribe = true`.
     * - Else → seed `autoTranscribe = false` (fresh install, iOS parity).
     *
     * The migration is TOCTOU-safe by running inside `dataStore.edit { }` and re-checking
     * the key inside the transaction.
     */
    private suspend fun runAutoTranscribeMigrationIfNeeded() {
        val current = dataStore.data.first()
        if (current.contains(AUTO_TRANSCRIBE)) return
        val isUpgrade = UPGRADE_PROBE_BOOL_KEYS.any { current.contains(it) } ||
            UPGRADE_PROBE_STRING_KEYS.any { current.contains(it) }
        dataStore.edit { prefs ->
            // Re-check inside the transaction in case a concurrent setter wrote first.
            if (!prefs.contains(AUTO_TRANSCRIBE)) {
                prefs[AUTO_TRANSCRIBE] = isUpgrade
            }
        }
    }

    private companion object {
        // Verbatim iOS UserDefaults keys for `.pilgrim` ZIP cross-platform parity.
        val VOICE_GUIDE_ENABLED = booleanPreferencesKey("voiceGuideEnabled")
        val AUTO_TRANSCRIBE = booleanPreferencesKey("autoTranscribe")
        const val DEFAULT_VOICE_GUIDE_ENABLED = false
        const val DEFAULT_AUTO_TRANSCRIBE = false

        // Upgrade probe keys — written by previous stages.
        val UPGRADE_PROBE_BOOL_KEYS = listOf(
            booleanPreferencesKey("soundsEnabled"),                 // Stage 10-B
            booleanPreferencesKey("beginWithIntention"),            // Stage 10-C
            booleanPreferencesKey("celestialAwarenessEnabled"),     // Stage 10-C
            booleanPreferencesKey("walkReliquaryEnabled"),          // Stage 10-C
        )
        val UPGRADE_PROBE_STRING_KEYS = listOf(
            stringPreferencesKey("appearance_mode"),                 // Stage 9.5-E (snake_case in DataStore)
            stringPreferencesKey("selected_voice_guide_pack_id"),    // Stage 5-D pre-rename
            stringPreferencesKey("selectedVoiceGuidePackId"),        // post-rename
            stringPreferencesKey("zodiacSystem"),                    // Stage 10-C
            stringPreferencesKey("distanceUnits"),                   // Stage 10-C
        )
    }
}
```

- [ ] **Step 6: Create Hilt module**

`app/src/main/java/org/walktalkmeditate/pilgrim/di/VoicePreferencesModule.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.walktalkmeditate.pilgrim.data.voice.DataStoreVoicePreferencesRepository
import org.walktalkmeditate.pilgrim.data.voice.VoicePreferencesRepository
import org.walktalkmeditate.pilgrim.data.voice.VoicePreferencesScope

private val Context.voicePreferencesDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "voice_preferences")

@Module
@InstallIn(SingletonComponent::class)
abstract class VoicePreferencesModule {
    @Binds
    @Singleton
    abstract fun bindVoicePreferencesRepository(
        impl: DataStoreVoicePreferencesRepository,
    ): VoicePreferencesRepository

    companion object {
        @Provides
        @Singleton
        @VoicePreferencesScope
        fun provideVoicePreferencesScope(): CoroutineScope =
            CoroutineScope(SupervisorJob() + Dispatchers.Default)

        @Provides
        @Singleton
        @JvmStatic
        @VoicePreferencesDataStore
        fun provideVoicePreferencesDataStore(
            @ApplicationContext context: Context,
        ): DataStore<Preferences> = context.voicePreferencesDataStore
    }
}

@javax.inject.Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class VoicePreferencesDataStore
```

(Note: each pref repo gets its own DataStore file, mirroring `AppearancePreferencesRepository` / `SoundsPreferencesRepository` pattern. Update `DataStoreVoicePreferencesRepository`'s constructor to take a `@VoicePreferencesDataStore DataStore<Preferences>` instead of an unqualified one.)

- [ ] **Step 7: Update DataStoreVoicePreferencesRepository constructor to use the qualifier**

Edit `data/voice/DataStoreVoicePreferencesRepository.kt`:

```kotlin
class DataStoreVoicePreferencesRepository @Inject constructor(
    @org.walktalkmeditate.pilgrim.di.VoicePreferencesDataStore
    private val dataStore: DataStore<Preferences>,
    @VoicePreferencesScope private val scope: CoroutineScope,
) : VoicePreferencesRepository {
    // ... rest unchanged
```

- [ ] **Step 8: Create FakeVoicePreferencesRepository test double**

`app/src/test/java/org/walktalkmeditate/pilgrim/data/voice/FakeVoicePreferencesRepository.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeVoicePreferencesRepository(
    initialVoiceGuideEnabled: Boolean = false,
    initialAutoTranscribe: Boolean = false,
) : VoicePreferencesRepository {
    private val _voiceGuideEnabled = MutableStateFlow(initialVoiceGuideEnabled)
    private val _autoTranscribe = MutableStateFlow(initialAutoTranscribe)

    override val voiceGuideEnabled: StateFlow<Boolean> = _voiceGuideEnabled.asStateFlow()
    override val autoTranscribe: StateFlow<Boolean> = _autoTranscribe.asStateFlow()

    override suspend fun setVoiceGuideEnabled(enabled: Boolean) {
        _voiceGuideEnabled.value = enabled
    }

    override suspend fun setAutoTranscribe(enabled: Boolean) {
        _autoTranscribe.value = enabled
    }
}
```

- [ ] **Step 9: Run tests to verify pass**

```
./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.data.voice.VoicePreferencesRepositoryTest" --no-configuration-cache
```

Expected: PASS — 6 tests green.

- [ ] **Step 10: Commit**

```
git add app/src/main/java/org/walktalkmeditate/pilgrim/data/voice/ app/src/main/java/org/walktalkmeditate/pilgrim/di/VoicePreferencesModule.kt app/src/test/java/org/walktalkmeditate/pilgrim/data/voice/
git commit -m "feat(voice): VoicePreferencesRepository with autoTranscribe upgrade migration"
```

---

## Task 2: VoiceRecordingFileSystem (canonical path resolver)

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/data/voice/VoiceRecordingFileSystem.kt`
- Test: `app/src/test/java/org/walktalkmeditate/pilgrim/data/voice/VoiceRecordingFileSystemTest.kt`

- [ ] **Step 1: Write failing test**

`app/src/test/java/org/walktalkmeditate/pilgrim/data/voice/VoiceRecordingFileSystemTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.voice

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VoiceRecordingFileSystemTest {

    private lateinit var context: Application
    private lateinit var fileSystem: VoiceRecordingFileSystem

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        fileSystem = VoiceRecordingFileSystem(context)
    }

    @Test
    fun `absolutePath joins filesDir with relative path`() {
        val abs = fileSystem.absolutePath("recordings/walk-uuid/rec-uuid.wav")
        assertEquals(File(context.filesDir, "recordings/walk-uuid/rec-uuid.wav"), abs)
    }

    @Test
    fun `fileExists false when file missing`() {
        assertFalse(fileSystem.fileExists("recordings/missing.wav"))
    }

    @Test
    fun `fileExists true after writing file`() {
        val rel = "recordings/test.wav"
        val abs = fileSystem.absolutePath(rel)
        abs.parentFile?.mkdirs()
        abs.writeText("hi")
        assertTrue(fileSystem.fileExists(rel))
    }

    @Test
    fun `fileSizeBytes returns 0 when file missing`() {
        assertEquals(0L, fileSystem.fileSizeBytes("recordings/missing.wav"))
    }

    @Test
    fun `fileSizeBytes returns actual size when file exists`() {
        val rel = "recordings/sized.wav"
        val abs = fileSystem.absolutePath(rel)
        abs.parentFile?.mkdirs()
        abs.writeBytes(ByteArray(1024) { it.toByte() })
        assertEquals(1024L, fileSystem.fileSizeBytes(rel))
    }

    @Test
    fun `deleteFile returns true and removes file`() = runTest {
        val rel = "recordings/del.wav"
        val abs = fileSystem.absolutePath(rel)
        abs.parentFile?.mkdirs()
        abs.writeText("bye")
        assertTrue(fileSystem.deleteFile(rel))
        assertFalse(abs.exists())
    }

    @Test
    fun `deleteFile returns false when file already missing`() = runTest {
        assertFalse(fileSystem.deleteFile("recordings/never.wav"))
    }
}
```

- [ ] **Step 2: Run to verify fail**

```
./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.data.voice.VoiceRecordingFileSystemTest" --no-configuration-cache
```

Expected: FAIL — `Unresolved reference: VoiceRecordingFileSystem`.

- [ ] **Step 3: Create the class**

`app/src/main/java/org/walktalkmeditate/pilgrim/data/voice/VoiceRecordingFileSystem.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.voice

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Canonical path resolver for voice recording files. Stage 10-D introduced this
 * helper to centralize what was previously scattered across `ExoPlayerVoicePlaybackController`,
 * `WhisperEngine`, and `TranscriptionRunner`. Per Stage 5-D memory: delete operations
 * MUST drive their path computation through the SAME function the write path used.
 *
 * Callers pass the entity's `fileRelativePath` (e.g., `"recordings/<walkUuid>/<recUuid>.wav"`).
 */
@Singleton
class VoiceRecordingFileSystem @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun absolutePath(relativePath: String): File =
        File(context.filesDir, relativePath)

    fun fileExists(relativePath: String): Boolean =
        absolutePath(relativePath).exists()

    fun fileSizeBytes(relativePath: String): Long {
        val f = absolutePath(relativePath)
        return if (f.exists()) f.length() else 0L
    }

    suspend fun deleteFile(relativePath: String): Boolean = withContext(Dispatchers.IO) {
        absolutePath(relativePath).delete()
    }
}
```

- [ ] **Step 4: Run to verify pass**

```
./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.data.voice.VoiceRecordingFileSystemTest" --no-configuration-cache
```

Expected: PASS — 7 tests green.

- [ ] **Step 5: Commit**

```
git add app/src/main/java/org/walktalkmeditate/pilgrim/data/voice/VoiceRecordingFileSystem.kt app/src/test/java/org/walktalkmeditate/pilgrim/data/voice/VoiceRecordingFileSystemTest.kt
git commit -m "feat(voice): canonical VoiceRecordingFileSystem path resolver"
```

---

## Task 3: VoiceGuideSelectionRepository key migration

**Files:**
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/data/voiceguide/VoiceGuideSelectionRepository.kt`
- Test: `app/src/test/java/org/walktalkmeditate/pilgrim/data/voiceguide/VoiceGuideSelectionRepositoryMigrationTest.kt`

- [ ] **Step 1: Read current implementation**

Read `app/src/main/java/org/walktalkmeditate/pilgrim/data/voiceguide/VoiceGuideSelectionRepository.kt` to confirm current key string and StateFlow shape.

- [ ] **Step 2: Write failing migration test**

`app/src/test/java/org/walktalkmeditate/pilgrim/data/voiceguide/VoiceGuideSelectionRepositoryMigrationTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.voiceguide

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VoiceGuideSelectionRepositoryMigrationTest {

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var scope: CoroutineScope
    private lateinit var storeFile: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        storeFile = File(context.filesDir, "datastore/vgsr-test-${UUID.randomUUID()}.preferences_pb")
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { storeFile }
    }

    @After
    fun tearDown() {
        scope.cancel()
        storeFile.delete()
    }

    @Test
    fun `legacy snake_case key value is read on first construction`() = runTest {
        dataStore.edit { it[stringPreferencesKey("selected_voice_guide_pack_id")] = "pack-a" }
        runBlocking { delay(50) }
        val repo = VoiceGuideSelectionRepository(dataStore, scope)
        runBlocking { delay(100) }
        assertEquals("pack-a", repo.selectedPackId.value)
    }

    @Test
    fun `legacy key copied into new key after migration`() = runTest {
        dataStore.edit { it[stringPreferencesKey("selected_voice_guide_pack_id")] = "pack-b" }
        runBlocking { delay(50) }
        val repo = VoiceGuideSelectionRepository(dataStore, scope)
        runBlocking { delay(100) }
        // After migration: new key should hold the value.
        val prefs = dataStore.data.first()
        assertEquals("pack-b", prefs[stringPreferencesKey("selectedVoiceGuidePackId")])
    }

    @Test
    fun `new key takes precedence when both are present`() = runTest {
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey("selected_voice_guide_pack_id")] = "old"
            prefs[stringPreferencesKey("selectedVoiceGuidePackId")] = "new"
        }
        runBlocking { delay(50) }
        val repo = VoiceGuideSelectionRepository(dataStore, scope)
        runBlocking { delay(100) }
        assertEquals("new", repo.selectedPackId.value)
    }

    @Test
    fun `select writes only to new camelCase key`() = runTest {
        val repo = VoiceGuideSelectionRepository(dataStore, scope)
        repo.select("pack-c")
        runBlocking { delay(50) }
        val prefs = dataStore.data.first()
        assertEquals("pack-c", prefs[stringPreferencesKey("selectedVoiceGuidePackId")])
    }

    @Test
    fun `fresh install — both keys absent yields null`() = runTest {
        val repo = VoiceGuideSelectionRepository(dataStore, scope)
        runBlocking { delay(100) }
        assertNull(repo.selectedPackId.value)
    }
}
```

- [ ] **Step 3: Run to verify fail**

```
./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.data.voiceguide.VoiceGuideSelectionRepositoryMigrationTest" --no-configuration-cache
```

Expected: FAIL — at least one test fails (the new-key write or the migration copy).

- [ ] **Step 4: Update VoiceGuideSelectionRepository**

Edit `app/src/main/java/org/walktalkmeditate/pilgrim/data/voiceguide/VoiceGuideSelectionRepository.kt`:

- Change key from `"selected_voice_guide_pack_id"` to `"selectedVoiceGuidePackId"` (rename `SELECTED_PACK_ID_KEY` constant).
- Add a `LEGACY_SELECTED_PACK_ID_KEY = stringPreferencesKey("selected_voice_guide_pack_id")` constant.
- Change `SharingStarted.WhileSubscribed(5_000L)` → `SharingStarted.Eagerly` for the `selectedPackId` flow.
- In the flow's `.map { prefs -> ... }`, prefer the new key, fall back to the legacy key:
  ```kotlin
  prefs[SELECTED_PACK_ID_KEY] ?: prefs[LEGACY_SELECTED_PACK_ID_KEY]
  ```
- Add a one-time migration `init { scope.launch { migrateLegacyKeyIfNeeded() } }`:
  ```kotlin
  private suspend fun migrateLegacyKeyIfNeeded() {
      dataStore.edit { prefs ->
          val legacy = prefs[LEGACY_SELECTED_PACK_ID_KEY]
          val current = prefs[SELECTED_PACK_ID_KEY]
          if (legacy != null && current == null) {
              prefs[SELECTED_PACK_ID_KEY] = legacy
              // Don't remove the legacy key — leaving it lets a downgrade still work.
              // Future cleanup stage can remove if desired.
          }
      }
  }
  ```
- All `select()` / `deselect()` / `selectIfUnset()` writes use only the new key.

- [ ] **Step 5: Run to verify pass**

```
./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.data.voiceguide.VoiceGuideSelectionRepository*" --no-configuration-cache
```

Expected: PASS. All existing `VoiceGuideSelectionRepositoryTest` tests should still pass (they didn't depend on the specific key string).

- [ ] **Step 6: Commit**

```
git add app/src/main/java/org/walktalkmeditate/pilgrim/data/voiceguide/VoiceGuideSelectionRepository.kt app/src/test/java/org/walktalkmeditate/pilgrim/data/voiceguide/VoiceGuideSelectionRepositoryMigrationTest.kt
git commit -m "feat(voiceguide): rename selectedVoiceGuidePackId key for iOS parity"
```

---

## Task 4: VoiceGuideOrchestrator 3-way combine

**Files:**
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/audio/voiceguide/VoiceGuideOrchestrator.kt`
- Modify: `app/src/test/java/org/walktalkmeditate/pilgrim/audio/voiceguide/VoiceGuideOrchestratorTest.kt`
- Test: `app/src/test/java/org/walktalkmeditate/pilgrim/audio/voiceguide/VoiceGuideOrchestratorVoiceEnabledTest.kt`

- [ ] **Step 1: Write new test exercising voiceGuideEnabled gate**

`app/src/test/java/org/walktalkmeditate/pilgrim/audio/voiceguide/VoiceGuideOrchestratorVoiceEnabledTest.kt`:

Replicate the test setup from `VoiceGuideOrchestratorTest.kt` (read it for the test scaffolding: `acc`, `seedManifest`, `writePromptFiles`, `pack()`, `CapturingVoiceGuidePlayer`). Test class header:

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VoiceGuideOrchestratorVoiceEnabledTest {
    // ... copy the setUp/tearDown/helpers from VoiceGuideOrchestratorTest

    @Test fun `voiceGuideEnabled = false prevents spawn on Active`() = runTest {
        val pk = pack()
        seedManifest(listOf(pk))
        writePromptFiles(pk)
        val walkState = MutableStateFlow<WalkState>(WalkState.Active(acc))
        val selectedPackId = MutableStateFlow<String?>("p")
        val s = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            VoiceGuideOrchestrator(
                walkState, selectedPackId, manifestService, fileStore,
                capturingPlayer, FixedClock(),
                FakeSoundsPreferencesRepository(initialSoundsEnabled = true),
                FakeVoicePreferencesRepository(initialVoiceGuideEnabled = false),
                s,
            ).start()
            runCurrent()
            assertEquals(0, capturingPlayer.playCount)
        } finally { s.cancel() }
    }

    @Test fun `voiceGuideEnabled flip false-to-true mid-walk spawns scheduler`() = runTest {
        val pk = pack()
        seedManifest(listOf(pk))
        writePromptFiles(pk)
        val walkState = MutableStateFlow<WalkState>(WalkState.Active(acc))
        val selectedPackId = MutableStateFlow<String?>("p")
        val voicePrefs = FakeVoicePreferencesRepository(initialVoiceGuideEnabled = false)
        val s = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            VoiceGuideOrchestrator(
                walkState, selectedPackId, manifestService, fileStore,
                capturingPlayer, FixedClock(),
                FakeSoundsPreferencesRepository(initialSoundsEnabled = true),
                voicePrefs, s,
            ).start()
            runCurrent()
            assertEquals(0, capturingPlayer.playCount)

            voicePrefs.setVoiceGuideEnabled(true)
            runCurrent()
            assertTrue(
                "expected play after voiceGuideEnabled→true, got ${capturingPlayer.playCount}",
                capturingPlayer.playCount >= 1,
            )
        } finally { s.cancel() }
    }

    @Test fun `voiceGuideEnabled flip true-to-false mid-walk cancels scheduler`() = runTest {
        val pk = pack()
        seedManifest(listOf(pk))
        writePromptFiles(pk)
        val walkState = MutableStateFlow<WalkState>(WalkState.Active(acc))
        val selectedPackId = MutableStateFlow<String?>("p")
        val voicePrefs = FakeVoicePreferencesRepository(initialVoiceGuideEnabled = true)
        val s = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            VoiceGuideOrchestrator(
                walkState, selectedPackId, manifestService, fileStore,
                capturingPlayer, FixedClock(),
                FakeSoundsPreferencesRepository(initialSoundsEnabled = true),
                voicePrefs, s,
            ).start()
            runCurrent()
            val baseline = capturingPlayer.playCount
            assertTrue(baseline >= 1)

            voicePrefs.setVoiceGuideEnabled(false)
            runCurrent()
            assertTrue(capturingPlayer.stopCount >= 1)
        } finally { s.cancel() }
    }
}
```

- [ ] **Step 2: Run new test — fail with constructor signature mismatch**

```
./gradlew :app:compileDebugUnitTestKotlin --no-configuration-cache
```

Expected: FAIL — `VoiceGuideOrchestrator` constructor doesn't take a `VoicePreferencesRepository`.

- [ ] **Step 3: Update VoiceGuideOrchestrator**

In `app/src/main/java/org/walktalkmeditate/pilgrim/audio/voiceguide/VoiceGuideOrchestrator.kt`:

1. Add import: `import org.walktalkmeditate.pilgrim.data.voice.VoicePreferencesRepository`.
2. Add constructor param: `private val voicePreferences: VoicePreferencesRepository,` (just before `@VoiceGuidePlaybackScope private val scope: CoroutineScope`).
3. Add a top-level companion constant near `TICK_INTERVAL_MS`:
   ```kotlin
   /**
    * Stage 10-D: meditation-guide gate is hardcoded ON pending a future MeditationView
    * parity stage that adds both the per-session UI toggle and the DataStore-backed
    * `meditationGuideEnabled` preference. iOS toggles this only inside the meditation
    * options sheet (not in Settings).
    */
   private const val MEDITATION_GUIDE_ALWAYS_ENABLED = true
   ```
4. Update the combine to be 3-way:
   ```kotlin
   combine(
       walkState,
       soundsPreferences.soundsEnabled,
       voicePreferences.voiceGuideEnabled,
   ) { state, soundsOk, voiceOk ->
       Triple(state, soundsOk, voiceOk)
   }.collect { (state, soundsOk, voiceOk) ->
       val enabled = soundsOk && voiceOk
       // ... rest unchanged: replace `enabled` references with the local val
   }
   ```
5. For the `Meditating` branch: gate on `enabled && MEDITATION_GUIDE_ALWAYS_ENABLED` (currently always true; future stage flips this).
6. Add defensive `.value` re-check inside `playOrSkip()` (mirror existing `soundsEnabled.value` defense):
   ```kotlin
   if (!soundsPreferences.soundsEnabled.value || !voicePreferences.voiceGuideEnabled.value) return
   ```

- [ ] **Step 4: Update existing VoiceGuideOrchestratorTest**

In `app/src/test/java/org/walktalkmeditate/pilgrim/audio/voiceguide/VoiceGuideOrchestratorTest.kt`:

- Add import: `import org.walktalkmeditate.pilgrim.data.voice.FakeVoicePreferencesRepository`.
- For every `VoiceGuideOrchestrator(...)` call, add `FakeVoicePreferencesRepository(initialVoiceGuideEnabled = true),` between the `FakeSoundsPreferencesRepository(...)` argument and the trailing `s,` argument.

This is mechanical: ~12 sites in the file. Verify by `grep -c "VoiceGuideOrchestrator(" VoiceGuideOrchestratorTest.kt`.

- [ ] **Step 5: Update Hilt-injected callers**

Search for any other constructor injection sites (production app):

```
grep -rn "VoiceGuideOrchestrator(" --include="*.kt" app/src/main/
```

Expected: only Hilt-generated factories. Hilt picks up the new dependency automatically since `VoicePreferencesRepository` is `@Singleton` bound. No manual changes needed.

- [ ] **Step 6: Run all orchestrator tests**

```
./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.audio.voiceguide.*" --no-configuration-cache
```

Expected: PASS — all original tests + 3 new voice-enabled tests green.

- [ ] **Step 7: Commit**

```
git add app/src/main/java/org/walktalkmeditate/pilgrim/audio/voiceguide/VoiceGuideOrchestrator.kt app/src/test/java/org/walktalkmeditate/pilgrim/audio/voiceguide/
git commit -m "feat(voiceguide): voiceGuideEnabled master gate via 3-way combine"
```

---

## Task 5: WalkFinalizationObserver autoTranscribe gate

**Files:**
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/walk/WalkFinalizationObserver.kt`
- Modify: `app/src/test/java/org/walktalkmeditate/pilgrim/walk/WalkFinalizationObserverTest.kt`
- Test: `app/src/test/java/org/walktalkmeditate/pilgrim/walk/WalkFinalizationObserverAutoTranscribeTest.kt`

- [ ] **Step 1: Read current WalkFinalizationObserver**

Locate the call to `transcriptionScheduler.scheduleForWalk(walkId)`. Identify the function and surrounding context.

- [ ] **Step 2: Write failing autoTranscribe-gate test**

Pattern after the existing `WalkFinalizationObserverTest`. Inject `FakeVoicePreferencesRepository` plus a `FakeTranscriptionScheduler` (scheduler test double from Stage 2-D — read existing test for the pattern). Three cases:

```kotlin
@Test fun `autoTranscribe = true schedules transcription`() = runTest {
    val voicePrefs = FakeVoicePreferencesRepository(initialAutoTranscribe = true)
    val scheduler = FakeTranscriptionScheduler()
    // ... build observer, finish a walk
    assertEquals(1, scheduler.scheduledWalkIds.size)
}

@Test fun `autoTranscribe = false skips scheduling`() = runTest {
    val voicePrefs = FakeVoicePreferencesRepository(initialAutoTranscribe = false)
    val scheduler = FakeTranscriptionScheduler()
    // ... build observer, finish a walk
    assertEquals(0, scheduler.scheduledWalkIds.size)
}

@Test fun `autoTranscribe flip mid-finalize uses value at scheduling time`() = runTest {
    val voicePrefs = FakeVoicePreferencesRepository(initialAutoTranscribe = false)
    val scheduler = FakeTranscriptionScheduler()
    // ... build observer
    voicePrefs.setAutoTranscribe(true)
    // Now finish a walk — observer should pick up the updated value.
    assertEquals(1, scheduler.scheduledWalkIds.size)
}
```

- [ ] **Step 3: Run to verify fail**

Expected: FAIL — observer constructor doesn't take VoicePreferencesRepository.

- [ ] **Step 4: Update WalkFinalizationObserver**

- Add constructor param: `private val voicePreferences: VoicePreferencesRepository`.
- Wrap the `transcriptionScheduler.scheduleForWalk(walkId)` call in `if (voicePreferences.autoTranscribe.value)`.
- Add a comment explaining the synchronous `.value` read is safe (Eagerly StateFlow).

- [ ] **Step 5: Update existing WalkFinalizationObserverTest constructor calls**

Mechanically add `FakeVoicePreferencesRepository(initialAutoTranscribe = true)` arg to every `WalkFinalizationObserver(...)` call to preserve current test behavior (auto-transcribe stays on for these cases). New autoTranscribe-specific cases live in the new file.

- [ ] **Step 6: Run all walk finalize tests**

```
./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.walk.WalkFinalize*" --no-configuration-cache
```

Expected: PASS.

- [ ] **Step 7: Commit**

```
git add app/src/main/java/org/walktalkmeditate/pilgrim/walk/WalkFinalizationObserver.kt app/src/test/java/org/walktalkmeditate/pilgrim/walk/
git commit -m "feat(walk): autoTranscribe gate on transcription scheduling"
```

---

## Task 6: ExoPlayer playback speed + seek + position

**Files:**
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/audio/VoicePlaybackController.kt` (interface)
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/audio/ExoPlayerVoicePlaybackController.kt`
- Test: `app/src/test/java/org/walktalkmeditate/pilgrim/audio/ExoPlayerVoicePlaybackControllerSpeedSeekTest.kt`

- [ ] **Step 1: Write failing test for speed + seek + position**

`app/src/test/java/org/walktalkmeditate/pilgrim/audio/ExoPlayerVoicePlaybackControllerSpeedSeekTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ExoPlayerVoicePlaybackControllerSpeedSeekTest {

    private lateinit var controller: ExoPlayerVoicePlaybackController

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        // Provide minimal collaborators — read existing controller's tests for the
        // shape; AudioFocusCoordinator + VoiceRecordingFileSystem injected.
        controller = ExoPlayerVoicePlaybackController(
            context,
            audioFocusCoordinator = /* fake */,
            fileSystem = VoiceRecordingFileSystem(context),
        )
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
    }

    @Test
    fun `setPlaybackSpeed default is 1_0`() {
        assertEquals(1.0f, controller.playbackSpeed.value, 0.001f)
    }

    @Test
    fun `setPlaybackSpeed updates StateFlow`() {
        controller.setPlaybackSpeed(1.5f)
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        assertEquals(1.5f, controller.playbackSpeed.value, 0.001f)
    }

    @Test
    fun `seek with no media item is a safe no-op`() {
        // Should not crash even though no recording is loaded.
        controller.seek(0.5f)
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        // No assertion — just confirms no crash.
    }

    @Test
    fun `playbackPositionMillis default is 0`() {
        assertEquals(0L, controller.playbackPositionMillis.value)
    }
}
```

- [ ] **Step 2: Run to verify fail**

Expected: FAIL — interface methods don't exist.

- [ ] **Step 3: Update VoicePlaybackController interface**

```kotlin
interface VoicePlaybackController {
    val state: StateFlow<PlaybackState>
    val playbackSpeed: StateFlow<Float>          // NEW
    val playbackPositionMillis: StateFlow<Long>  // NEW
    fun play(recording: VoiceRecording)
    fun pause()
    fun stop()
    fun release()
    fun setPlaybackSpeed(rate: Float)             // NEW
    fun seek(fraction: Float)                     // NEW
}
```

- [ ] **Step 4: Update ExoPlayerVoicePlaybackController**

In `app/src/main/java/org/walktalkmeditate/pilgrim/audio/ExoPlayerVoicePlaybackController.kt`:

1. Inject `private val fileSystem: VoiceRecordingFileSystem` via constructor (replaces inline `File(context.filesDir, ...)`).
2. Add `private val _playbackSpeed = MutableStateFlow(1.0f); override val playbackSpeed = _playbackSpeed.asStateFlow()`.
3. Add `private val _playbackPositionMillis = MutableStateFlow(0L); override val playbackPositionMillis = _playbackPositionMillis.asStateFlow()`.
4. Implement `setPlaybackSpeed(rate: Float)`:
   ```kotlin
   override fun setPlaybackSpeed(rate: Float) {
       mainHandler.post {
           player?.setPlaybackParameters(
               androidx.media3.common.PlaybackParameters(rate.coerceIn(0.5f, 2.0f), 1.0f),
           )
           _playbackSpeed.value = rate
       }
   }
   ```
5. Implement `seek(fraction: Float)`:
   ```kotlin
   override fun seek(fraction: Float) {
       mainHandler.post {
           val p = player ?: return@post
           if (p.currentMediaItem == null) return@post
           val dur = p.duration
           if (dur == androidx.media3.common.C.TIME_UNSET) return@post
           p.seekTo((fraction.coerceIn(0f, 1f) * dur).toLong().coerceIn(0L, dur))
       }
   }
   ```
6. Add a position-tick poller: when state transitions to `Playing`, start a `mainHandler.postDelayed { ... }` loop that updates `_playbackPositionMillis.value = player?.currentPosition ?: 0L` every 100ms; cancel the loop on `Paused` / `Stopped` / `Error`.
7. Migrate `play()` to use `fileSystem.absolutePath(recording.fileRelativePath)` instead of `File(context.filesDir, recording.fileRelativePath)`.

- [ ] **Step 5: Update Hilt provider**

In wherever `ExoPlayerVoicePlaybackController` is `@Inject constructor`-annotated (search for it), the constructor signature now includes `VoiceRecordingFileSystem` — Hilt picks it up automatically since `VoiceRecordingFileSystem` is `@Singleton`.

- [ ] **Step 6: Run tests**

```
./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.audio.ExoPlayerVoicePlaybackController*" --no-configuration-cache
```

Expected: PASS.

- [ ] **Step 7: Commit**

```
git add app/src/main/java/org/walktalkmeditate/pilgrim/audio/VoicePlaybackController.kt app/src/main/java/org/walktalkmeditate/pilgrim/audio/ExoPlayerVoicePlaybackController.kt app/src/test/java/org/walktalkmeditate/pilgrim/audio/ExoPlayerVoicePlaybackControllerSpeedSeekTest.kt
git commit -m "feat(audio): playback speed + seek + position-tick on ExoPlayer controller"
```

---

## Task 7: WaveformLoader (WAV decoder + downsampler)

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/recordings/WaveformLoader.kt`
- Test: `app/src/test/java/org/walktalkmeditate/pilgrim/ui/recordings/WaveformLoaderTest.kt`

- [ ] **Step 1: Write failing test (synthetic WAV in memory, no Android deps)**

`app/src/test/java/org/walktalkmeditate/pilgrim/ui/recordings/WaveformLoaderTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.recordings

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Rule

class WaveformLoaderTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun `decodes 16-bit PCM mono WAV and downsamples to N bars`() {
        // Synthesize 16 kHz mono PCM with a 1-second triangle wave.
        val sampleRate = 16_000
        val numSamples = sampleRate
        val pcm = ByteArray(numSamples * 2)
        val bb = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until numSamples) {
            val t = i.toDouble() / sampleRate
            val v = (Math.sin(2.0 * Math.PI * 440.0 * t) * 16_384).toInt().toShort()
            bb.putShort(v)
        }
        val wav = buildWavFile(pcm, sampleRate)
        val f = tmp.newFile("test.wav")
        f.writeBytes(wav)

        val samples = WaveformLoader.load(f, barCount = 64)
        assertEquals(64, samples.size)
        // All samples should be in [0, 1] (RMS-normalized magnitude).
        samples.forEach { assertTrue("sample out of range: $it", it in 0f..1f) }
        // For a constant-amplitude sine, all bars should be roughly equal.
        val avg = samples.average().toFloat()
        samples.forEach { assertTrue(Math.abs(it - avg) < 0.2f) }
    }

    @Test fun `returns flat-line FloatArray on truncated header`() {
        val f = tmp.newFile("bad.wav")
        f.writeBytes(byteArrayOf(0x52, 0x49, 0x46, 0x46))  // just "RIFF"
        val samples = WaveformLoader.load(f, barCount = 64)
        assertEquals(64, samples.size)
        samples.forEach { assertEquals(0f, it, 0.001f) }
    }

    @Test fun `returns flat-line on missing file`() {
        val f = File(tmp.root, "missing.wav")
        val samples = WaveformLoader.load(f, barCount = 32)
        assertEquals(32, samples.size)
        samples.forEach { assertEquals(0f, it, 0.001f) }
    }

    /** Wrap [pcm] in a minimal RIFF WAV header (16-bit mono). */
    private fun buildWavFile(pcm: ByteArray, sampleRate: Int): ByteArray {
        val byteRate = sampleRate * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(36 + pcm.size)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1)         // PCM
        header.putShort(1)         // mono
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(2)         // block align
        header.putShort(16)        // bits per sample
        header.put("data".toByteArray())
        header.putInt(pcm.size)
        return header.array() + pcm
    }
}
```

- [ ] **Step 2: Run to verify fail**

```
./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.ui.recordings.WaveformLoaderTest" --no-configuration-cache
```

Expected: FAIL — `Unresolved reference: WaveformLoader`.

- [ ] **Step 3: Create WaveformLoader**

`app/src/main/java/org/walktalkmeditate/pilgrim/ui/recordings/WaveformLoader.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.recordings

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Decodes a 16-bit PCM mono WAV file and downsamples to [barCount] RMS-normalized
 * magnitudes in [0, 1]. Used by [WaveformBar] to render the seek bar.
 *
 * VoiceRecorder writes 16 kHz mono 16-bit PCM in WAV — this decoder targets that
 * exact format. Other formats fall back to a flat-line FloatArray.
 */
object WaveformLoader {
    fun load(file: File, barCount: Int): FloatArray {
        if (!file.exists() || file.length() < 44) return FloatArray(barCount)
        return try {
            val bytes = file.readBytes()
            val pcm = parsePcm(bytes) ?: return FloatArray(barCount)
            downsampleRms(pcm, barCount)
        } catch (t: Throwable) {
            FloatArray(barCount)
        }
    }

    /** Parse RIFF/WAVE header; return PCM samples as ShortArray, or null on malformed input. */
    private fun parsePcm(bytes: ByteArray): ShortArray? {
        if (bytes.size < 44) return null
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        // RIFF chunk
        if (bytes[0] != 'R'.code.toByte() || bytes[1] != 'I'.code.toByte() ||
            bytes[2] != 'F'.code.toByte() || bytes[3] != 'F'.code.toByte()) return null
        if (bytes[8] != 'W'.code.toByte() || bytes[9] != 'A'.code.toByte() ||
            bytes[10] != 'V'.code.toByte() || bytes[11] != 'E'.code.toByte()) return null
        // Walk chunks looking for "fmt " then "data".
        var pos = 12
        var bitsPerSample = -1
        var channels = -1
        var dataOffset = -1
        var dataSize = -1
        while (pos + 8 <= bytes.size) {
            val chunkId = String(bytes, pos, 4)
            val chunkSize = bb.getInt(pos + 4)
            when (chunkId) {
                "fmt " -> {
                    val audioFormat = bb.getShort(pos + 8).toInt()
                    if (audioFormat != 1) return null   // 1 = PCM
                    channels = bb.getShort(pos + 10).toInt()
                    bitsPerSample = bb.getShort(pos + 22).toInt()
                }
                "data" -> {
                    dataOffset = pos + 8
                    dataSize = chunkSize
                    break
                }
            }
            pos += 8 + chunkSize
        }
        if (dataOffset < 0 || bitsPerSample != 16 || channels != 1) return null
        val sampleCount = dataSize / 2
        val out = ShortArray(sampleCount)
        for (i in 0 until sampleCount) {
            out[i] = bb.getShort(dataOffset + i * 2)
        }
        return out
    }

    /** RMS-bucket a ShortArray PCM stream into [barCount] normalized magnitudes. */
    private fun downsampleRms(pcm: ShortArray, barCount: Int): FloatArray {
        if (pcm.isEmpty()) return FloatArray(barCount)
        val out = FloatArray(barCount)
        val perBucket = pcm.size.toDouble() / barCount
        for (i in 0 until barCount) {
            val start = (i * perBucket).toInt()
            val end = ((i + 1) * perBucket).toInt().coerceAtMost(pcm.size)
            if (end <= start) continue
            var sumSq = 0.0
            for (j in start until end) {
                val s = pcm[j].toDouble() / Short.MAX_VALUE
                sumSq += s * s
            }
            val rms = sqrt(sumSq / (end - start))
            out[i] = rms.toFloat().coerceIn(0f, 1f)
        }
        return out
    }
}
```

- [ ] **Step 4: Run to verify pass**

Expected: PASS.

- [ ] **Step 5: Commit**

```
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/recordings/WaveformLoader.kt app/src/test/java/org/walktalkmeditate/pilgrim/ui/recordings/WaveformLoaderTest.kt
git commit -m "feat(recordings): WAV decoder + RMS downsampler for waveform bars"
```

---

## Task 8: WaveformBar composable

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/recordings/WaveformBar.kt`
- Test: `app/src/test/java/org/walktalkmeditate/pilgrim/ui/recordings/WaveformBarTest.kt`

- [ ] **Step 1: Write failing Compose test**

`app/src/test/java/org/walktalkmeditate/pilgrim/ui/recordings/WaveformBarTest.kt`:

Tests: composes the bar, asserts no crash, exercises a tap-to-seek (use `composeRule.onRoot().performTouchInput { down(...); moveTo(...); up() }` and verify `onSeek` callback fires with expected fraction).

- [ ] **Step 2: Run to verify fail**

Expected: FAIL — `Unresolved reference: WaveformBar`.

- [ ] **Step 3: Create WaveformBar**

`app/src/main/java/org/walktalkmeditate/pilgrim/ui/recordings/WaveformBar.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.recordings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

@Composable
fun WaveformBar(
    samples: FloatArray,
    progress: Float,
    inactiveColor: Color,
    activeColor: Color,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier
            .pointerInput(samples) {
                detectTapGestures { offset ->
                    val frac = (offset.x / size.width).coerceIn(0f, 1f)
                    onSeek(frac)
                }
            }
            .pointerInput(samples) {
                detectDragGestures(onDrag = { change, _ ->
                    val frac = (change.position.x / size.width).coerceIn(0f, 1f)
                    onSeek(frac)
                })
            }
    ) {
        if (samples.isEmpty()) return@Canvas
        val barWidth = size.width / samples.size
        val centerY = size.height / 2f
        val activeUntil = (progress.coerceIn(0f, 1f) * samples.size).toInt()
        for (i in samples.indices) {
            val mag = samples[i].coerceIn(0f, 1f)
            val barHalfHeight = (mag * size.height / 2f).coerceAtLeast(1f)
            drawLine(
                color = if (i <= activeUntil) activeColor else inactiveColor,
                start = Offset(i * barWidth + barWidth / 2f, centerY - barHalfHeight),
                end = Offset(i * barWidth + barWidth / 2f, centerY + barHalfHeight),
                strokeWidth = (barWidth * 0.6f).coerceAtLeast(1f),
            )
        }
    }
}
```

- [ ] **Step 4: Run to verify pass**

Expected: PASS.

- [ ] **Step 5: Commit**

```
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/recordings/WaveformBar.kt app/src/test/java/org/walktalkmeditate/pilgrim/ui/recordings/WaveformBarTest.kt
git commit -m "feat(recordings): WaveformBar Canvas composable with tap+drag seek"
```

---

## Task 9: VoiceCard composable

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/voice/VoiceCard.kt`
- Test: `app/src/test/java/org/walktalkmeditate/pilgrim/ui/settings/voice/VoiceCardTest.kt`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Add new strings**

In `strings.xml`, add (without removing existing):

```xml
<string name="settings_voice_card_header_title">Voice</string>
<string name="settings_voice_card_header_subtitle">Speaking and listening</string>
<string name="settings_voice_guide_label">Voice Guide</string>
<string name="settings_voice_guide_description">Spoken prompts during walks and meditation</string>
<string name="settings_voice_guide_packs_row">Guide Packs</string>
<string name="settings_auto_transcribe_label">Auto-transcribe</string>
<string name="settings_auto_transcribe_description">Convert recordings to text after each walk</string>
<string name="settings_recordings_row">Recordings</string>
<string name="settings_recordings_detail_zero">0 recordings • 0.0 MB</string>
<string name="settings_recordings_detail_one">1 recording • %1$s MB</string>
<string name="settings_recordings_detail_many">%1$d recordings • %2$s MB</string>
```

(Note: `•` in the strings is the literal U+2022 character with surrounding spaces. iOS-faithful.)

- [ ] **Step 2: Write failing VoiceCard test**

`app/src/test/java/org/walktalkmeditate/pilgrim/ui/settings/voice/VoiceCardTest.kt`:

Cases:
1. Renders header, voice-guide toggle, auto-transcribe toggle, recordings row.
2. Voice-guide toggle off → Guide Packs row not visible.
3. Voice-guide toggle on → Guide Packs row visible, tappable, fires `onOpenVoiceGuides`.
4. Recordings detail formatting: 0/1/N count + MB display.
5. Toggling switches fires the corresponding setter callback.

- [ ] **Step 3: Run to verify fail**

Expected: FAIL.

- [ ] **Step 4: Create VoiceCard composable**

`app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/voice/VoiceCard.kt`:

Structure (pseudocode — match PracticeCard / AtmosphereCard styling exactly):

```kotlin
@Composable
fun VoiceCard(
    state: VoiceCardState,
    onSetVoiceGuideEnabled: (Boolean) -> Unit,
    onSetAutoTranscribe: (Boolean) -> Unit,
    onOpenVoiceGuides: () -> Unit,
    onOpenRecordings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pilgrimColors = LocalPilgrimColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(pilgrimColors.parchmentSecondary)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CardHeader(
            title = stringResource(R.string.settings_voice_card_header_title),
            subtitle = stringResource(R.string.settings_voice_card_header_subtitle),
        )
        SettingToggleRow(
            label = stringResource(R.string.settings_voice_guide_label),
            description = stringResource(R.string.settings_voice_guide_description),
            checked = state.voiceGuideEnabled,
            onCheckedChange = onSetVoiceGuideEnabled,
        )
        AnimatedVisibility(
            visible = state.voiceGuideEnabled,
            enter = fadeIn(animationSpec = tween(200, easing = EaseInOut)) +
                expandVertically(animationSpec = tween(200, easing = EaseInOut)),
            exit = fadeOut(animationSpec = tween(200, easing = EaseInOut)) +
                shrinkVertically(animationSpec = tween(200, easing = EaseInOut)),
        ) {
            SettingNavRow(
                label = stringResource(R.string.settings_voice_guide_packs_row),
                onClick = onOpenVoiceGuides,
            )
        }
        Divider(color = pilgrimColors.fog.copy(alpha = 0.2f))
        SettingToggleRow(
            label = stringResource(R.string.settings_auto_transcribe_label),
            description = stringResource(R.string.settings_auto_transcribe_description),
            checked = state.autoTranscribe,
            onCheckedChange = onSetAutoTranscribe,
        )
        Divider(color = pilgrimColors.fog.copy(alpha = 0.2f))
        SettingNavRow(
            label = stringResource(R.string.settings_recordings_row),
            detail = formatRecordingsDetail(state.recordingsCount, state.recordingsSizeBytes),
            onClick = onOpenRecordings,
        )
    }
}

@Stable
data class VoiceCardState(
    val voiceGuideEnabled: Boolean,
    val autoTranscribe: Boolean,
    val recordingsCount: Int,
    val recordingsSizeBytes: Long,
)

@Composable
private fun formatRecordingsDetail(count: Int, bytes: Long): String {
    val mb = String.format(Locale.US, "%.1f", bytes / 1_000_000.0)
    return when {
        count == 0 -> stringResource(R.string.settings_recordings_detail_zero)
        count == 1 -> stringResource(R.string.settings_recordings_detail_one, mb)
        else -> stringResource(R.string.settings_recordings_detail_many, count, mb)
    }
}
```

(Reuse existing `SettingToggleRow` / `SettingNavRow` / `CardHeader` from PracticeCard / AtmosphereCard.)

- [ ] **Step 5: Run tests**

Expected: PASS.

- [ ] **Step 6: Commit**

```
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/voice/ app/src/test/java/org/walktalkmeditate/pilgrim/ui/settings/voice/ app/src/main/res/values/strings.xml
git commit -m "feat(settings): VoiceCard composable (iOS-faithful)"
```

---

## Task 10: SettingsScreen integration

**Files:**
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/SettingsScreen.kt`
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/SettingsViewModel.kt`
- Modify: `app/src/test/java/org/walktalkmeditate/pilgrim/ui/settings/SettingsViewModelTest.kt` (if affected)

- [ ] **Step 1: Update SettingsViewModel**

Add:
- `private val voicePreferences: VoicePreferencesRepository` constructor param.
- `private val walkRepository: WalkRepository` (if not already injected).
- `private val fileSystem: VoiceRecordingFileSystem` constructor param.
- `val voiceCardState: StateFlow<VoiceCardState>` — combines voiceGuideEnabled + autoTranscribe + recordingsAggregate.
- `fun setVoiceGuideEnabled(enabled: Boolean)` — `viewModelScope.launch { voicePreferences.setVoiceGuideEnabled(enabled) }`.
- `fun setAutoTranscribe(enabled: Boolean)`.
- `recordingsAggregate: StateFlow<RecordingsAggregate>` per spec.

- [ ] **Step 2: Update SettingsScreen**

- Drop the transitional voice-guides nav row (lines ~140-146 of SettingsScreen).
- Insert `VoiceCard(state = voiceCardState, ...)` above the AtmosphereCard. Wire callbacks:
  - `onSetVoiceGuideEnabled = viewModel::setVoiceGuideEnabled`
  - `onSetAutoTranscribe = viewModel::setAutoTranscribe`
  - `onOpenVoiceGuides = { onAction(SettingsAction.OpenVoiceGuides) }`
  - `onOpenRecordings = { onAction(SettingsAction.OpenRecordings) }` (new action — added in Task 13).

- [ ] **Step 3: Update existing SettingsViewModelTest constructors**

Add `FakeVoicePreferencesRepository` + a fake `WalkRepository` returning empty recording list to every `SettingsViewModel(...)` instantiation in tests.

- [ ] **Step 4: Run tests**

```
./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.ui.settings.*" --no-configuration-cache
```

Expected: PASS.

- [ ] **Step 5: Commit**

```
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/SettingsScreen.kt app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/SettingsViewModel.kt app/src/test/java/org/walktalkmeditate/pilgrim/ui/settings/
git commit -m "feat(settings): integrate VoiceCard into SettingsScreen above Atmosphere"
```

---

## Task 11: RecordingsListViewModel + RecordingsSection

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/recordings/RecordingsSection.kt`
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/recordings/RecordingsListViewModel.kt`
- Test: `app/src/test/java/org/walktalkmeditate/pilgrim/ui/recordings/RecordingsListViewModelTest.kt`

- [ ] **Step 1: Write failing VM test**

Cases (~12):
- empty list → `RecordingsListUiState.Loaded(visibleSections=[], hasAnyRecordings=false)`.
- 2 walks each with 1 recording → grouped into 2 sections, newest first.
- search filter — case-insensitive, transcription text only.
- `onPlay(recordingId)` → looks up entity, calls `controller.play(entity)`.
- `onPause()` calls `controller.pause()`.
- `onSpeedCycle()` cycles 1.0 → 1.5 → 2.0 → 1.0.
- `onSeek(recordingId, fraction)` calls `controller.seek(fraction)` only when this is the currently-playing recording.
- `onTranscriptionEdit` calls `walkRepository.updateVoiceRecording` with the new text.
- `onDeleteFile` calls `fileSystem.deleteFile`, marks file unavailable, keeps row visible.
- `onDeleteAllFiles` deletes all files, all rows become unavailable, transcriptions stay.
- `onRetranscribe` calls `transcriptionScheduler.scheduleForWalk(walkId)` (or a single-recording variant if added).
- search no-match → `visibleSections.isEmpty() && hasAnyRecordings = true`.

- [ ] **Step 2: Run to verify fail**

Expected: FAIL.

- [ ] **Step 3: Create RecordingsSection**

```kotlin
data class RecordingsSection(
    val walk: Walk,
    val recordings: List<VoiceRecording>,
)
```

- [ ] **Step 4: Create RecordingsListViewModel**

Match the spec's API. Source flow:
```kotlin
val state: StateFlow<RecordingsListUiState> = combine(
    walkRepository.observeAllVoiceRecordings(),
    walkRepository.observeAllWalks(),
    searchQuery,
    playbackController.state,
    playbackController.playbackSpeed,
    playbackController.playbackPositionMillis,
    editingRecordingId,
) { recordings, walks, query, playbackState, speed, posMs, editingId ->
    val sections = walks
        .filter { walk -> recordings.any { it.walkId == walk.id } }
        .sortedByDescending { it.startTimestamp }
        .map { walk -> RecordingsSection(walk, recordings.filter { it.walkId == walk.id }) }
    val visible = if (query.isBlank()) sections else sections.mapNotNull { sec ->
        val q = query.lowercase()
        val filtered = sec.recordings.filter { (it.transcription ?: "").lowercase().contains(q) }
        if (filtered.isEmpty()) null else sec.copy(recordings = filtered)
    }
    val playingId = (playbackState as? PlaybackState.Playing)?.recordingId
    val playingRec = playingId?.let { id -> recordings.firstOrNull { it.id == id } }
    val frac = if (playingRec != null && playingRec.durationMillis > 0) {
        (posMs.toFloat() / playingRec.durationMillis.toFloat()).coerceIn(0f, 1f)
    } else 0f
    RecordingsListUiState.Loaded(
        visibleSections = visible,
        hasAnyRecordings = sections.isNotEmpty(),
        searchQuery = query,
        playingRecordingId = playingId,
        playbackPositionFraction = frac,
        playbackSpeed = speed,
        editingRecordingId = editingId,
    )
}.flowOn(Dispatchers.Default)
 .stateIn(viewModelScope, SharingStarted.Eagerly, RecordingsListUiState.Loading)
```

(`combine` of 7 flows — Kotlin supports up to 5 with the typed overload; for >5, use the `vararg` overload that returns `Array<Any?>`. Type erasure makes the lambda body more verbose; consider grouping into intermediate `Pair`/`Triple`/`data class` to keep it readable.)

- [ ] **Step 5: Run tests**

Expected: PASS.

- [ ] **Step 6: Commit**

```
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/recordings/RecordingsSection.kt app/src/main/java/org/walktalkmeditate/pilgrim/ui/recordings/RecordingsListViewModel.kt app/src/test/java/org/walktalkmeditate/pilgrim/ui/recordings/RecordingsListViewModelTest.kt
git commit -m "feat(recordings): RecordingsListViewModel + state machine"
```

---

## Task 12: RecordingRow composable

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/recordings/RecordingRow.kt`
- Test: `app/src/test/java/org/walktalkmeditate/pilgrim/ui/recordings/RecordingRowTest.kt`

Cases (~5):
- File-available state: play button + metadata + speed pill + waveform.
- File-unavailable state: `waveform.slash` icon + "File unavailable".
- Speed pill at 1.0: stone-on-tinted-background; at 1.5+: parchment-on-solid-stone.
- Edit mode: TextField visible, Done button commits.
- Tap on transcription block enters edit mode.

Implementation matches spec section "RecordingsListScreen architecture — Recording row".

- [ ] **Step 1-5:** Standard TDD cycle (write test, fail, implement, pass, commit).

```
git commit -m "feat(recordings): RecordingRow composable with edit mode + speed pill"
```

---

## Task 13: RecordingsListScreen + Routes

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/recordings/RecordingsListScreen.kt`
- Test: `app/src/test/java/org/walktalkmeditate/pilgrim/ui/recordings/RecordingsListScreenTest.kt`
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/PilgrimNavHost.kt` (add Routes.RECORDINGS_LIST + SettingsAction.OpenRecordings)
- Modify: `app/src/main/res/values/strings.xml` (add screen-level strings)

Cases (~6):
- Empty state (no recordings) → empty title + GraphicEq icon.
- Populated state → sections render.
- Search no-match → "No recordings match".
- Swipe right → confirmation dialog → delete file.
- Swipe left → onRetranscribe callback fires.
- Delete-all button at bottom → confirmation → all file paths deleted.

Add new strings (Task 13 batch):

```xml
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

Add route:

```kotlin
// In Routes:
const val RECORDINGS_LIST = "recordings"

// In SettingsAction sealed class:
data object OpenRecordings : SettingsAction

// In PilgrimNavHost composable, when handling SettingsAction:
SettingsAction.OpenRecordings -> navController.navigate(Routes.RECORDINGS_LIST)

// New nav graph entry:
composable(Routes.RECORDINGS_LIST) {
    RecordingsListScreen(
        onBack = { navController.popBackStack() },
        onWalkClick = { walkId -> navController.navigate(Routes.walkSummary(walkId)) },
    )
}
```

- [ ] **Step 1-5:** TDD cycle.

```
git commit -m "feat(recordings): RecordingsListScreen + nav + strings"
```

---

## Task 14: End-to-end smoke + polish

- [ ] **Step 1: Run full test suite**

```
./gradlew :app:testDebugUnitTest --no-configuration-cache
```

Expected: PASS — all existing + 28+ new tests green.

- [ ] **Step 2: Run lint + assemble**

```
./gradlew :app:lintDebug :app:assembleDebug --no-configuration-cache
```

Expected: PASS.

- [ ] **Step 3: Manual device QA on OnePlus 13 (or emulator)**

Walk through every iOS test plan item from the spec:
- VoiceCard renders correctly above AtmosphereCard.
- Voice Guide toggle off → Guide Packs row hidden (animated 200ms).
- Voice Guide toggle on → Guide Packs row visible, navigates to picker.
- Auto-transcribe toggle off → finish a walk → no auto-transcription.
- Auto-transcribe toggle on → finish a walk → transcription kicks in.
- Recordings nav → RecordingsListScreen.
- Walk-grouped sections, newest first.
- Tap section header → WalkSummaryScreen.
- Play / pause / scrub / speed cycle on a row.
- Inline transcription edit → Done → persists.
- Search → filters by transcription text only.
- Swipe right → confirmation → delete file (transcription kept).
- Swipe left → retranscribe.
- Delete All → confirmation → all files removed.
- Dark mode flip — every surface flips.
- TalkBack — every row + toggle has a sensible spoken label.

- [ ] **Step 4: Polish loop**

```
/polish
```

Run review-fix cycles until clean.

- [ ] **Step 5: Final adversarial review**

Dispatch a code-reviewer agent (subagent type: `feature-dev:code-reviewer`) for an end-to-end final review of the branch diff. Address any HIGH-confidence findings.

- [ ] **Step 6: Open PR**

```
gh pr create --title "Stage 10-D: VoiceCard + RecordingsListScreen (iOS-parity)" --body "..."
```

Include in the PR body:
- Summary, scope, key migration semantics
- Test plan checklist (manual QA items)
- Known iOS divergences (from spec)
- Quality confidence score

---

## Verification gates

Per CLAUDE.md "Definition of Done":

- [ ] All tests pass — full suite green.
- [ ] No lint warnings (release config).
- [ ] No `// TODO` without an issue/stage reference.
- [ ] Every new platform-builder path has at least one Robolectric `.build()` test (per CLAUDE.md voice-guide trap rule). Specifically: ExoPlayer `PlaybackParameters`, AudioFocusRequest path through play-flow.
- [ ] iOS-faithful storage keys verified by reading the actual DataStore file after a write (in `VoicePreferencesRepositoryTest`).
- [ ] Manual device QA done with screenshots committed to the PR review thread.
- [ ] Quality confidence ≥ 4/5 going into final review.
