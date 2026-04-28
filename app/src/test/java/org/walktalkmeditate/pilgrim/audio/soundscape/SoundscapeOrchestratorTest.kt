// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio.soundscape

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.audio.AudioAsset
import org.walktalkmeditate.pilgrim.data.audio.AudioAssetType
import org.walktalkmeditate.pilgrim.data.audio.AudioManifest
import org.walktalkmeditate.pilgrim.data.audio.AudioManifestService
import org.walktalkmeditate.pilgrim.data.soundscape.SoundscapeFileStore
import org.walktalkmeditate.pilgrim.data.sounds.FakeSoundsPreferencesRepository
import org.walktalkmeditate.pilgrim.data.sounds.SoundsPreferencesRepository
import org.walktalkmeditate.pilgrim.domain.WalkAccumulator
import org.walktalkmeditate.pilgrim.domain.WalkState

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SoundscapeOrchestratorTest {

    private val acc = WalkAccumulator(walkId = 1L, startedAt = 1_000L)

    private lateinit var context: Application
    private lateinit var server: MockWebServer
    private lateinit var httpClient: OkHttpClient
    private lateinit var json: Json
    private lateinit var fileStore: SoundscapeFileStore
    private lateinit var manifestService: AudioManifestService
    private lateinit var manifestScope: CoroutineScope
    private val capturingPlayer = CapturingSoundscapePlayer()

    private val manifestCache: File get() = File(context.filesDir, "audio_manifest.json")
    private val soundscapeRoot: File get() = File(context.filesDir, "audio/soundscape")

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manifestCache.delete()
        soundscapeRoot.deleteRecursively()

        server = MockWebServer().also { it.start() }
        httpClient = OkHttpClient()
        json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        fileStore = SoundscapeFileStore(context)
        manifestScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After fun tearDown() {
        manifestScope.cancel()
        server.shutdown()
        manifestCache.delete()
        soundscapeRoot.deleteRecursively()
    }

    private fun asset(id: String, type: String = AudioAssetType.SOUNDSCAPE, size: Long = 128L) =
        AudioAsset(
            id = id, type = type, name = id, displayName = id,
            durationSec = 120.0, r2Key = "$type/$id.aac", fileSizeBytes = size,
        )

    private fun seedManifest(assets: List<AudioAsset>) {
        manifestCache.writeText(
            json.encodeToString(AudioManifest(version = "v1", assets = assets)),
        )
        manifestService = AudioManifestService(
            context = context,
            httpClient = httpClient,
            json = json,
            scope = manifestScope,
            manifestUrl = server.url("/manifest.json").toString(),
        )
        runBlocking {
            manifestScope.coroutineContext[Job]?.children?.forEach { it.join() }
        }
    }

    private fun writeAssetFile(a: AudioAsset) {
        fileStore.fileFor(a).writeBytes(ByteArray(a.fileSizeBytes.toInt()))
    }

    @Test fun `Meditating with no selection does not play`() = runTest {
        seedManifest(listOf(asset("rain")))
        val walkState = MutableStateFlow<WalkState>(
            WalkState.Meditating(acc, meditationStartedAt = 1_000L),
        )
        val selectedAssetId = MutableStateFlow<String?>(null)
        val s = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        SoundscapeOrchestrator(
            walkState, selectedAssetId, manifestService, fileStore,
            capturingPlayer, FakeSoundsPreferencesRepository(initial = true), s,
        ).start()
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(0, capturingPlayer.playCount)
        s.cancel()
    }

    @Test fun `Meditating with selection but file missing does not play`() = runTest {
        seedManifest(listOf(asset("rain")))
        // No file written.
        val walkState = MutableStateFlow<WalkState>(
            WalkState.Meditating(acc, meditationStartedAt = 1_000L),
        )
        val selectedAssetId = MutableStateFlow<String?>("rain")
        val s = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        SoundscapeOrchestrator(
            walkState, selectedAssetId, manifestService, fileStore,
            capturingPlayer, FakeSoundsPreferencesRepository(initial = true), s,
        ).start()
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(0, capturingPlayer.playCount)
        s.cancel()
    }

    @Test fun `Meditating with eligible soundscape plays after start delay`() = runTest {
        val a = asset("rain")
        seedManifest(listOf(a))
        writeAssetFile(a)
        val walkState = MutableStateFlow<WalkState>(
            WalkState.Meditating(acc, meditationStartedAt = 1_000L),
        )
        val selectedAssetId = MutableStateFlow<String?>("rain")
        val s = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        SoundscapeOrchestrator(
            walkState, selectedAssetId, manifestService, fileStore,
            capturingPlayer, FakeSoundsPreferencesRepository(initial = true), s,
        ).start()
        runCurrent()
        // Before the 800ms delay elapses, no play yet.
        advanceTimeBy(799)
        runCurrent()
        assertEquals(0, capturingPlayer.playCount)
        // After the delay completes, play fires once.
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, capturingPlayer.playCount)
        s.cancel()
    }

    @Test fun `Meditating to Active stops the player and cancels pending play`() = runTest {
        val a = asset("rain")
        seedManifest(listOf(a))
        writeAssetFile(a)
        val walkState = MutableStateFlow<WalkState>(
            WalkState.Meditating(acc, meditationStartedAt = 1_000L),
        )
        val selectedAssetId = MutableStateFlow<String?>("rain")
        val s = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        SoundscapeOrchestrator(
            walkState, selectedAssetId, manifestService, fileStore,
            capturingPlayer, FakeSoundsPreferencesRepository(initial = true), s,
        ).start()
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        val playsDuringMed = capturingPlayer.playCount

        walkState.value = WalkState.Active(acc)
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()

        // No additional plays after transition.
        assertEquals(playsDuringMed, capturingPlayer.playCount)
        assertTrue(
            "expected at least one stop on exit, got ${capturingPlayer.stopCount}",
            capturingPlayer.stopCount >= 1,
        )
        s.cancel()
    }

    @Test fun `exit during start delay cancels without playing`() = runTest {
        val a = asset("rain")
        seedManifest(listOf(a))
        writeAssetFile(a)
        val walkState = MutableStateFlow<WalkState>(
            WalkState.Meditating(acc, meditationStartedAt = 1_000L),
        )
        val selectedAssetId = MutableStateFlow<String?>("rain")
        val s = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        SoundscapeOrchestrator(
            walkState, selectedAssetId, manifestService, fileStore,
            capturingPlayer, FakeSoundsPreferencesRepository(initial = true), s,
        ).start()
        runCurrent()
        // User bails mid-delay (tap Done at ~400ms).
        advanceTimeBy(400)
        runCurrent()
        walkState.value = WalkState.Active(acc)
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        // The delay got cancelled before play fired.
        assertEquals(0, capturingPlayer.playCount)
        s.cancel()
    }

    @Test fun `Finished stops the player`() = runTest {
        val a = asset("rain")
        seedManifest(listOf(a))
        writeAssetFile(a)
        val walkState = MutableStateFlow<WalkState>(
            WalkState.Meditating(acc, meditationStartedAt = 1_000L),
        )
        val selectedAssetId = MutableStateFlow<String?>("rain")
        val s = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        SoundscapeOrchestrator(
            walkState, selectedAssetId, manifestService, fileStore,
            capturingPlayer, FakeSoundsPreferencesRepository(initial = true), s,
        ).start()
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()

        walkState.value = WalkState.Finished(acc, endedAt = 5_000L)
        runCurrent()
        assertTrue(capturingPlayer.stopCount >= 1)
        s.cancel()
    }

    @Test fun `re-entering Meditating after Active replays`() = runTest {
        val a = asset("rain")
        seedManifest(listOf(a))
        writeAssetFile(a)
        val walkState = MutableStateFlow<WalkState>(
            WalkState.Meditating(acc, meditationStartedAt = 1_000L),
        )
        val selectedAssetId = MutableStateFlow<String?>("rain")
        val s = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        SoundscapeOrchestrator(
            walkState, selectedAssetId, manifestService, fileStore,
            capturingPlayer, FakeSoundsPreferencesRepository(initial = true), s,
        ).start()
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        val firstCount = capturingPlayer.playCount

        walkState.value = WalkState.Active(acc)
        runCurrent()
        walkState.value = WalkState.Meditating(acc, meditationStartedAt = 6_000L)
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()

        assertTrue(
            "expected a second play after re-entering Meditating, got ${capturingPlayer.playCount}",
            capturingPlayer.playCount > firstCount,
        )
        s.cancel()
    }

    @Test fun `player Error mid-session triggers one retry, then stops`() = runTest {
        val a = asset("rain")
        seedManifest(listOf(a))
        writeAssetFile(a)
        val walkState = MutableStateFlow<WalkState>(
            WalkState.Meditating(acc, meditationStartedAt = 1_000L),
        )
        val selectedAssetId = MutableStateFlow<String?>("rain")
        val s = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        SoundscapeOrchestrator(
            walkState, selectedAssetId, manifestService, fileStore,
            capturingPlayer, FakeSoundsPreferencesRepository(initial = true), s,
        ).start()
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, capturingPlayer.playCount)

        // Simulate a mid-session codec error: player transitions to
        // Error while walkState is still Meditating. Orchestrator
        // should retry ONE time.
        capturingPlayer.simulateError("decode failure")
        advanceTimeBy(500)
        runCurrent()
        assertEquals(
            "expected one retry after first Error, got ${capturingPlayer.playCount}",
            2, capturingPlayer.playCount,
        )

        // Second consecutive Error: retry budget exhausted, no more plays.
        capturingPlayer.simulateError("decode failure again")
        advanceTimeBy(500)
        runCurrent()
        assertEquals(
            "expected no further plays after budget exhaustion, got ${capturingPlayer.playCount}",
            2, capturingPlayer.playCount,
        )
        s.cancel()
    }

    @Test fun `retry budget resets when re-entering Meditating after Active`() = runTest {
        val a = asset("rain")
        seedManifest(listOf(a))
        writeAssetFile(a)
        val walkState = MutableStateFlow<WalkState>(
            WalkState.Meditating(acc, meditationStartedAt = 1_000L),
        )
        val selectedAssetId = MutableStateFlow<String?>("rain")
        val s = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        SoundscapeOrchestrator(
            walkState, selectedAssetId, manifestService, fileStore,
            capturingPlayer, FakeSoundsPreferencesRepository(initial = true), s,
        ).start()
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()

        // Burn the retry budget in session 1.
        capturingPlayer.simulateError("e1")
        advanceTimeBy(500)
        runCurrent()
        capturingPlayer.simulateError("e2")
        advanceTimeBy(500)
        runCurrent()
        val session1Plays = capturingPlayer.playCount

        // Exit to Active then re-enter Meditating — new session.
        walkState.value = WalkState.Active(acc)
        runCurrent()
        walkState.value = WalkState.Meditating(acc, meditationStartedAt = 6_000L)
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(
            "expected fresh play on new session, got ${capturingPlayer.playCount - session1Plays}",
            1, capturingPlayer.playCount - session1Plays,
        )

        // Session 2 should have its own retry budget.
        capturingPlayer.simulateError("session 2 glitch")
        advanceTimeBy(500)
        runCurrent()
        assertEquals(
            "expected a session-2 retry, got ${capturingPlayer.playCount - session1Plays}",
            2, capturingPlayer.playCount - session1Plays,
        )
        s.cancel()
    }

    @Test fun `type-mismatched asset id is ineligible`() = runTest {
        // Seed a BELL-typed asset with the same id — the manifest has
        // the id but the filter must reject non-soundscape types.
        val bell = asset("bell1", type = AudioAssetType.BELL)
        seedManifest(listOf(bell))
        writeAssetFile(bell) // Written into soundscape/ dir — for this test it's irrelevant; the type check runs first.
        val walkState = MutableStateFlow<WalkState>(
            WalkState.Meditating(acc, meditationStartedAt = 1_000L),
        )
        val selectedAssetId = MutableStateFlow<String?>("bell1")
        val s = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        SoundscapeOrchestrator(
            walkState, selectedAssetId, manifestService, fileStore,
            capturingPlayer, FakeSoundsPreferencesRepository(initial = true), s,
        ).start()
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(0, capturingPlayer.playCount)
        s.cancel()
    }

    @Test fun `master toggle off prevents soundscape spawn on Meditating`() = runTest {
        val a = asset("rain")
        seedManifest(listOf(a))
        writeAssetFile(a)
        val walkState = MutableStateFlow<WalkState>(
            WalkState.Meditating(acc, meditationStartedAt = 1_000L),
        )
        val selectedAssetId = MutableStateFlow<String?>("rain")
        val s = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        SoundscapeOrchestrator(
            walkState, selectedAssetId, manifestService, fileStore,
            capturingPlayer,
            FakeSoundsPreferencesRepository(initial = false),
            s,
        ).start()
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        // Master sounds toggle is OFF — even though the soundscape is
        // eligible (selected, manifest hit, file on disk), no spawn.
        assertEquals(0, capturingPlayer.playCount)
        s.cancel()
    }

    @Test fun `flipping master toggle off mid-session cancels playback`() = runTest {
        val a = asset("rain")
        seedManifest(listOf(a))
        writeAssetFile(a)
        val walkState = MutableStateFlow<WalkState>(
            WalkState.Meditating(acc, meditationStartedAt = 1_000L),
        )
        val selectedAssetId = MutableStateFlow<String?>("rain")
        val prefs = FakeSoundsPreferencesRepository(initial = true)
        val s = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        SoundscapeOrchestrator(
            walkState, selectedAssetId, manifestService, fileStore,
            capturingPlayer, prefs, s,
        ).start()
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, capturingPlayer.playCount)

        // User flips the master toggle OFF mid-meditation.
        prefs.setSoundsEnabled(false)
        runCurrent()

        assertTrue(
            "expected at least one stop after master toggle off, got ${capturingPlayer.stopCount}",
            capturingPlayer.stopCount >= 1,
        )
        s.cancel()
    }

    @Test fun `master toggle flipped off during start delay does not play`() = runTest {
        // Covers the combine-driven cancellation path: when the master
        // toggle flips OFF while a start-delay coroutine is suspended,
        // `combine(walkState, soundsEnabled)` re-emits with enabled=false
        // and the orchestrator cancels the in-flight delay coroutine
        // before `player.play()` runs. Under StandardTestDispatcher the
        // dispatcher serializes coroutines, so the cancel reliably runs
        // before the delay resumption — that's what this test asserts.
        //
        // The defensive `soundsEnabled.value` check inside `attemptPlay()`
        // closes the sub-frame race window that can exist on real
        // dispatchers (where the delay continuation may already be on
        // the run queue when the cancel arrives). That defensive check
        // is verified by code review only, not by this test.
        val a = asset("rain")
        seedManifest(listOf(a))
        writeAssetFile(a)
        val walkState = MutableStateFlow<WalkState>(
            WalkState.Meditating(acc, meditationStartedAt = 1_000L),
        )
        val selectedAssetId = MutableStateFlow<String?>("rain")
        val prefs = FakeSoundsPreferencesRepository(initial = true)
        val s = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        SoundscapeOrchestrator(
            walkState, selectedAssetId, manifestService, fileStore,
            capturingPlayer, prefs, s,
        ).start()
        runCurrent()
        // Sit inside the start delay (800ms in production). Don't
        // advance past it yet.
        advanceTimeBy(500)
        runCurrent()
        assertEquals(0, capturingPlayer.playCount)

        // Flip OFF mid-delay; the combine-driven cancel runs.
        prefs.setSoundsEnabled(false)
        runCurrent()
        // Advance past the original delay window. attemptPlay must
        // NOT have fired player.play() — the defensive gate-check
        // catches any sub-frame race.
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(
            "expected zero plays after master toggle off during start delay, got ${capturingPlayer.playCount}",
            0, capturingPlayer.playCount,
        )
        s.cancel()
    }

    @Test fun `flipping master toggle on mid-session spawns soundscape`() = runTest {
        val a = asset("rain")
        seedManifest(listOf(a))
        writeAssetFile(a)
        val walkState = MutableStateFlow<WalkState>(
            WalkState.Meditating(acc, meditationStartedAt = 1_000L),
        )
        val selectedAssetId = MutableStateFlow<String?>("rain")
        val prefs = FakeSoundsPreferencesRepository(initial = false)
        val s = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        SoundscapeOrchestrator(
            walkState, selectedAssetId, manifestService, fileStore,
            capturingPlayer, prefs, s,
        ).start()
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(0, capturingPlayer.playCount)

        // User flips master toggle ON mid-meditation. After the start
        // delay, the soundscape should fire.
        prefs.setSoundsEnabled(true)
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(
            "expected soundscape to spawn after master toggle on, got ${capturingPlayer.playCount}",
            1, capturingPlayer.playCount,
        )
        s.cancel()
    }

    // --- fakes ---

    private class CapturingSoundscapePlayer : SoundscapePlayer {
        private val _state = MutableStateFlow<SoundscapePlayer.State>(SoundscapePlayer.State.Idle)
        override val state: StateFlow<SoundscapePlayer.State> = _state.asStateFlow()
        private val played = CopyOnWriteArrayList<File>()
        @Volatile var stopCount: Int = 0
        val playCount: Int get() = played.size

        override fun play(file: File) {
            played += file
            _state.value = SoundscapePlayer.State.Playing
        }

        override fun stop() {
            stopCount += 1
            _state.value = SoundscapePlayer.State.Idle
        }

        override fun release() {
            _state.value = SoundscapePlayer.State.Idle
        }

        /** Test hook: transition the player to Error as ExoPlayer would. */
        fun simulateError(reason: String) {
            _state.value = SoundscapePlayer.State.Error(reason)
        }
    }
}
