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
import org.walktalkmeditate.pilgrim.audio.BellDurationResolver
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

    // Default to 800ms so the existing start-delay timing assertions
    // (799 → 0 plays, +1 → 1 play) keep their meaning: with the new
    // bell-aware delay, total = max(BELL_DELAY_FLOOR_MS=500, 800) = 800,
    // unchanged from the old fixed START_DELAY_MS. Individual tests
    // override `bellDurationMs` to exercise the iOS-parity
    // max(floor, bellDuration) behavior (BUG 4).
    @Volatile private var bellDurationMs: Long = 800L
    private val bellDurationResolver = BellDurationResolver { bellDurationMs }

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
            capturingPlayer, FakeSoundsPreferencesRepository(initialSoundsEnabled = true),
            bellDurationResolver, s,
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
            capturingPlayer, FakeSoundsPreferencesRepository(initialSoundsEnabled = true),
            bellDurationResolver, s,
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
            capturingPlayer, FakeSoundsPreferencesRepository(initialSoundsEnabled = true),
            bellDurationResolver, s,
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

    @Test fun `soundscape waits for the full bell duration before playing (BUG 4)`() = runTest {
        // iOS parity SoundManagement.swift:68-78 — a long
        // (user-downloaded) bell must not be cut short by the ambient
        // loop. With a 4.0s bell, play must NOT fire at 3.9s but MUST
        // fire just after 4.0s.
        bellDurationMs = 4_000L
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
            capturingPlayer, FakeSoundsPreferencesRepository(initialSoundsEnabled = true),
            bellDurationResolver, s,
        ).start()
        runCurrent()
        advanceTimeBy(3_999)
        runCurrent()
        assertEquals(
            "soundscape must not start before the 4.0s bell finishes",
            0, capturingPlayer.playCount,
        )
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, capturingPlayer.playCount)
        s.cancel()
    }

    @Test fun `bell shorter than the floor still waits BELL_DELAY_FLOOR_MS (BUG 4)`() = runTest {
        // iOS parity SoundManagement.swift:69 — max(0.5, bellDuration).
        // A 100ms bell (or a "None" selection resolving to ~0) must
        // still leave 500ms of silence before the ambient loop.
        bellDurationMs = 100L
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
            capturingPlayer, FakeSoundsPreferencesRepository(initialSoundsEnabled = true),
            bellDurationResolver, s,
        ).start()
        runCurrent()
        advanceTimeBy(499)
        runCurrent()
        assertEquals(
            "floor must hold playback until 500ms even for a 100ms bell",
            0, capturingPlayer.playCount,
        )
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
            capturingPlayer, FakeSoundsPreferencesRepository(initialSoundsEnabled = true),
            bellDurationResolver, s,
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
            capturingPlayer, FakeSoundsPreferencesRepository(initialSoundsEnabled = true),
            bellDurationResolver, s,
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
            capturingPlayer, FakeSoundsPreferencesRepository(initialSoundsEnabled = true),
            bellDurationResolver, s,
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
            capturingPlayer, FakeSoundsPreferencesRepository(initialSoundsEnabled = true),
            bellDurationResolver, s,
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
            capturingPlayer, FakeSoundsPreferencesRepository(initialSoundsEnabled = true),
            bellDurationResolver, s,
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
            capturingPlayer, FakeSoundsPreferencesRepository(initialSoundsEnabled = true),
            bellDurationResolver, s,
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
            capturingPlayer, FakeSoundsPreferencesRepository(initialSoundsEnabled = true),
            bellDurationResolver, s,
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
            FakeSoundsPreferencesRepository(initialSoundsEnabled = false),
            bellDurationResolver, s,
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
        val prefs = FakeSoundsPreferencesRepository(initialSoundsEnabled = true)
        val s = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        SoundscapeOrchestrator(
            walkState, selectedAssetId, manifestService, fileStore,
            capturingPlayer, prefs, bellDurationResolver, s,
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
        val prefs = FakeSoundsPreferencesRepository(initialSoundsEnabled = true)
        val s = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        SoundscapeOrchestrator(
            walkState, selectedAssetId, manifestService, fileStore,
            capturingPlayer, prefs, bellDurationResolver, s,
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
        val prefs = FakeSoundsPreferencesRepository(initialSoundsEnabled = false)
        val s = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        SoundscapeOrchestrator(
            walkState, selectedAssetId, manifestService, fileStore,
            capturingPlayer, prefs, bellDurationResolver, s,
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

    @Test fun `swapping selected soundscape mid-session restarts playback with new asset`() = runTest {
        // User opens Settings → Sound Settings → Soundscape row →
        // picks a different soundscape WHILE actively meditating.
        // Settings is a separate tab; meditation continues running.
        // The orchestrator must detect the asset swap and re-spawn
        // playback with the new file (cancel + stop the old, then
        // spawn with the new asset).
        val rain = asset("rain")
        val forest = asset("forest")
        seedManifest(listOf(rain, forest))
        writeAssetFile(rain)
        writeAssetFile(forest)
        val walkState = MutableStateFlow<WalkState>(
            WalkState.Meditating(acc, meditationStartedAt = 1_000L),
        )
        val selectedAssetId = MutableStateFlow<String?>("rain")
        val s = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        SoundscapeOrchestrator(
            walkState, selectedAssetId, manifestService, fileStore,
            capturingPlayer, FakeSoundsPreferencesRepository(initialSoundsEnabled = true),
            bellDurationResolver, s,
        ).start()
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals("expected initial spawn", 1, capturingPlayer.playCount)
        val firstPlayedFile = capturingPlayer.lastPlayedFile

        // User swaps soundscape selection mid-session.
        selectedAssetId.value = "forest"
        runCurrent()
        // Old job cancelled + player stopped.
        assertTrue(
            "expected stop on swap, got stopCount=${capturingPlayer.stopCount}",
            capturingPlayer.stopCount >= 1,
        )
        // New job plays the new file immediately — a swap is a
        // crossfade with no bell-duration start delay (BUG A1).
        runCurrent()
        assertEquals("expected re-spawn with new asset", 2, capturingPlayer.playCount)
        assertTrue(
            "expected new file path, was=${capturingPlayer.lastPlayedFile} (was=$firstPlayedFile)",
            capturingPlayer.lastPlayedFile != firstPlayedFile,
        )
        s.cancel()
    }

    @Test fun `first-start waits the bell duration but a mid-meditation swap plays immediately (BUG A1)`() = runTest {
        // iOS parity SoundManagement.swift:68-78 (bell delay scoped to
        // onMeditationStart only) + SoundscapePlayer.swift:30-33 (a
        // swap crossfades immediately, no start delay). The batch-1
        // regression applied the bell-duration delay on EVERY spawn
        // including swaps → multi-second silence on every track change.
        bellDurationMs = 4_000L
        val rain = asset("rain")
        val forest = asset("forest")
        seedManifest(listOf(rain, forest))
        writeAssetFile(rain)
        writeAssetFile(forest)
        val walkState = MutableStateFlow<WalkState>(
            WalkState.Meditating(acc, meditationStartedAt = 1_000L),
        )
        val selectedAssetId = MutableStateFlow<String?>("rain")
        val s = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        SoundscapeOrchestrator(
            walkState, selectedAssetId, manifestService, fileStore,
            capturingPlayer, FakeSoundsPreferencesRepository(initialSoundsEnabled = true),
            bellDurationResolver, s,
        ).start()
        runCurrent()

        // First start: the 4.0s bell-duration delay applies. No play
        // before the bell finishes.
        advanceTimeBy(3_999)
        runCurrent()
        assertEquals(
            "first start must wait the full bell duration",
            0, capturingPlayer.playCount,
        )
        advanceTimeBy(1)
        runCurrent()
        assertEquals("first start plays after bell", 1, capturingPlayer.playCount)

        // Mid-meditation swap: meditation is NOT exited. The swap must
        // play immediately — NOT wait another 4.0s. Use runCurrent
        // (not advanceUntilIdle — the player.state observer loop is
        // perpetual). Zero virtual time elapses between the swap and
        // this assertion.
        selectedAssetId.value = "forest"
        runCurrent()
        assertEquals(
            "a swap must NOT wait the bell duration — it plays immediately " +
                "(got playCount=${capturingPlayer.playCount} with no time elapsed)",
            2, capturingPlayer.playCount,
        )
        s.cancel()
    }

    @Test fun `flipping soundscape volume mid-session updates player without restarting`() = runTest {
        val a = asset("rain")
        seedManifest(listOf(a))
        writeAssetFile(a)
        val walkState = MutableStateFlow<WalkState>(
            WalkState.Meditating(acc, meditationStartedAt = 1_000L),
        )
        val selectedAssetId = MutableStateFlow<String?>("rain")
        val prefs = FakeSoundsPreferencesRepository(
            initialSoundsEnabled = true,
            initialSoundscapeVolume = 0.4f,
        )
        val s = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        SoundscapeOrchestrator(
            walkState, selectedAssetId, manifestService, fileStore,
            capturingPlayer, prefs, bellDurationResolver, s,
        ).start()
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        // Initial play has fired and the volume-collector seeded the
        // player with the initial pref value.
        assertEquals(1, capturingPlayer.playCount)
        assertEquals(0.4f, capturingPlayer.lastVolume)
        val playsBefore = capturingPlayer.playCount

        // User drags the slider to 0.2 mid-meditation.
        prefs.setSoundscapeVolume(0.2f)
        runCurrent()
        advanceTimeBy(50)
        runCurrent()

        // Volume change applied live — no restart, no extra play() call.
        assertEquals(
            "expected no restart when volume changes, got ${capturingPlayer.playCount}",
            playsBefore, capturingPlayer.playCount,
        )
        assertEquals(0.2f, capturingPlayer.lastVolume)
        s.cancel()
    }

    // --- fakes ---

    private class CapturingSoundscapePlayer : SoundscapePlayer {
        private val _state = MutableStateFlow<SoundscapePlayer.State>(SoundscapePlayer.State.Idle)
        override val state: StateFlow<SoundscapePlayer.State> = _state.asStateFlow()
        private val played = CopyOnWriteArrayList<File>()
        @Volatile var stopCount: Int = 0
        @Volatile var lastVolume: Float = Float.NaN
        @Volatile var setVolumeCount: Int = 0
        val playCount: Int get() = played.size
        val lastPlayedFile: File? get() = played.lastOrNull()

        override fun play(file: File) {
            played += file
            _state.value = SoundscapePlayer.State.Playing
        }

        override fun stop() {
            stopCount += 1
            _state.value = SoundscapePlayer.State.Idle
        }

        override fun setVolume(volume: Float) {
            lastVolume = volume
            setVolumeCount += 1
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
