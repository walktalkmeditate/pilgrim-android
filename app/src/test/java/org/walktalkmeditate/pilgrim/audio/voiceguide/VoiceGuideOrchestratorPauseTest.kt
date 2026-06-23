// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio.voiceguide

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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.sounds.FakeSoundsPreferencesRepository
import org.walktalkmeditate.pilgrim.data.voice.FakeVoicePreferencesRepository
import org.walktalkmeditate.pilgrim.data.voiceguide.PromptDensity
import org.walktalkmeditate.pilgrim.data.voiceguide.VoiceGuideFileStore
import org.walktalkmeditate.pilgrim.data.voiceguide.VoiceGuideManifest
import org.walktalkmeditate.pilgrim.data.voiceguide.VoiceGuideManifestService
import org.walktalkmeditate.pilgrim.data.voiceguide.VoiceGuidePack
import org.walktalkmeditate.pilgrim.data.voiceguide.VoiceGuidePrompt
import org.walktalkmeditate.pilgrim.domain.Clock
import org.walktalkmeditate.pilgrim.domain.WalkAccumulator
import org.walktalkmeditate.pilgrim.domain.WalkState

/**
 * Manual-QA batch 2, BUG B3: there was no in-walk voice-guide
 * play/pause control. Covers the orchestrator side: [pause] stops new
 * prompts + flips [isPaused] + stops the in-flight player, [resume]
 * lets prompts flow again, and [activePackName] reflects the running
 * pack so the ActiveWalk control knows when to show.
 * iOS parity `VoiceGuideManagement.pauseGuide/resumeGuide`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VoiceGuideOrchestratorPauseTest {

    private val acc = WalkAccumulator(walkId = 1L, startedAt = 1_000L)

    private lateinit var context: Application
    private lateinit var server: MockWebServer
    private lateinit var httpClient: OkHttpClient
    private lateinit var json: Json
    private lateinit var fileStore: VoiceGuideFileStore
    private lateinit var manifestService: VoiceGuideManifestService
    private lateinit var manifestScope: CoroutineScope
    private val capturingPlayer = CapturingVoiceGuidePlayer()

    private val manifestCache: File get() = File(context.filesDir, "voice_guide_manifest.json")
    private val promptsRoot: File get() = File(context.filesDir, "voice_guide_prompts")

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manifestCache.delete()
        promptsRoot.deleteRecursively()
        server = MockWebServer().also { it.start() }
        httpClient = OkHttpClient()
        json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        fileStore = VoiceGuideFileStore(context)
        manifestScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After fun tearDown() {
        manifestScope.cancel()
        server.shutdown()
        manifestCache.delete()
        promptsRoot.deleteRecursively()
    }

    private fun prompt(id: String, r2Key: String = "p/$id.aac") =
        VoiceGuidePrompt(
            id = id, seq = 0, durationSec = 1.0,
            fileSizeBytes = 100L, r2Key = r2Key, phase = null,
        )

    private fun pack(
        id: String = "p",
        name: String = "Forest Walk",
        prompts: List<VoiceGuidePrompt> = listOf(prompt("pw1", "p/w1.aac")),
    ) = VoiceGuidePack(
        id = id, version = "1", name = name, tagline = "", description = "",
        theme = "", iconName = "", type = "walk", walkTypes = emptyList(),
        scheduling = PromptDensity(
            densityMinSec = 10, densityMaxSec = 20,
            minSpacingSec = 0, initialDelaySec = 0, walkEndBufferSec = 0,
        ),
        totalDurationSec = 0.0, totalSizeBytes = 0L,
        prompts = prompts,
        meditationPrompts = null,
        meditationScheduling = null,
    )

    private fun seedManifest(packs: List<VoiceGuidePack>) {
        manifestCache.writeText(
            json.encodeToString(VoiceGuideManifest(version = "v1", packs = packs)),
        )
        manifestService = VoiceGuideManifestService(
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

    private fun writePromptFiles(pack: VoiceGuidePack) {
        pack.prompts.forEach {
            fileStore.fileForPrompt(it.r2Key).writeBytes(ByteArray(it.fileSizeBytes.toInt()))
        }
    }

    private fun orchestrator(
        walkState: MutableStateFlow<WalkState>,
        selectedPackId: MutableStateFlow<String?>,
        scope: CoroutineScope,
    ) = VoiceGuideOrchestrator(
        walkState, selectedPackId, manifestService, fileStore,
        capturingPlayer, FixedClock(),
        FakeSoundsPreferencesRepository(initialSoundsEnabled = true),
        FakeVoicePreferencesRepository(initialVoiceGuideEnabled = true),
                VoiceGuideProgressRepository.NoOp,
        scope,
    )

    // try/finally so an assertion failure still cancels `s` —
    // otherwise `runTest`'s cleanup spins forever draining the
    // orchestrator's perpetual `while(isActive){delay}` scheduler loop
    // (documented trap on the @Ignore'd test in
    // VoiceGuideOrchestratorTest; Stage 5-F memory).
    @Test fun `activePackName reflects the running walk pack and clears on Finish`() = runTest {
        val pk = pack(name = "Forest Walk")
        seedManifest(listOf(pk))
        writePromptFiles(pk)
        val walkState = MutableStateFlow<WalkState>(WalkState.Active(acc))
        val selectedPackId = MutableStateFlow<String?>("p")
        val s = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val orch = orchestrator(walkState, selectedPackId, s)
        try {
            orch.start()
            runCurrent()

            assertEquals("Forest Walk", orch.activePackName.value)
            assertFalse(orch.isPaused.value)

            walkState.value = WalkState.Finished(acc, endedAt = 5_000L)
            runCurrent()
            assertEquals(null, orch.activePackName.value)
        } finally {
            s.cancel()
        }
    }

    @Test fun `pause stops new prompts and flips isPaused, resume clears the flag`() = runTest {
        val pk = pack()
        seedManifest(listOf(pk))
        writePromptFiles(pk)
        val walkState = MutableStateFlow<WalkState>(WalkState.Active(acc))
        val selectedPackId = MutableStateFlow<String?>("p")
        val s = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val orch = orchestrator(walkState, selectedPackId, s)
        try {
            orch.start()
            runCurrent()
            assertTrue(
                "expected a play before pause, got ${capturingPlayer.playCount}",
                capturingPlayer.playCount >= 1,
            )

            orch.pause()
            runCurrent()
            assertTrue(orch.isPaused.value)
            assertTrue(
                "pause must stop the in-flight player",
                capturingPlayer.stopCount >= 1,
            )
            val baseline = capturingPlayer.playCount

            // Several scheduler ticks elapse while paused — the
            // scheduler's own `decide(isPaused=true)` returns null and
            // playOrSkip's pause gate short-circuits, so nothing plays.
            advanceTimeBy(120_000)
            runCurrent()
            assertEquals(
                "no new prompts while paused",
                baseline, capturingPlayer.playCount,
            )

            // resume() clears the flag so the scheduler loop's
            // `decide(isPaused=...)` is no longer force-suppressed.
            // (Deeper re-scheduling timing under a real clock is
            // VoiceGuideSchedulerTest's domain; FixedClock here keeps
            // the perpetual loop test-stable.)
            orch.resume()
            runCurrent()
            assertFalse(orch.isPaused.value)
        } finally {
            s.cancel()
        }
    }

    // AF24 (iOS PR #45): ending a meditation must not force-resume a voice
    // guide the user had manually paused. The Meditating→Active resume used
    // to reset isPaused on the fresh walk-scheduler spawn; walk-end already
    // clears it for fresh-walk parity, so the spawn-time reset only clobbered
    // a live user pause.
    @Test fun `ending meditation preserves a user-initiated pause`() = runTest {
        val pk = pack()
        seedManifest(listOf(pk))
        writePromptFiles(pk)
        val walkState = MutableStateFlow<WalkState>(WalkState.Active(acc))
        val selectedPackId = MutableStateFlow<String?>("p")
        val s = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val orch = orchestrator(walkState, selectedPackId, s)
        try {
            orch.start()
            runCurrent()
            assertFalse(orch.isPaused.value)

            // User manually pauses the guide mid-walk.
            orch.pause()
            runCurrent()
            assertTrue(orch.isPaused.value)

            // User starts a meditation, then returns to the walk.
            walkState.value = WalkState.Meditating(acc, meditationStartedAt = 2_000L)
            runCurrent()
            walkState.value = WalkState.Active(acc)
            runCurrent()

            // The pause must survive the meditation round-trip.
            assertTrue(
                "ending meditation force-resumed a user-paused guide",
                orch.isPaused.value,
            )
        } finally {
            s.cancel()
        }
    }

    private class FixedClock(private var millis: Long = 1_700_000_000_000L) : Clock {
        override fun now(): Long = millis
    }

    private class CapturingVoiceGuidePlayer : VoiceGuidePlayer {
        private val _state = MutableStateFlow<VoiceGuidePlayer.State>(VoiceGuidePlayer.State.Idle)
        override val state: StateFlow<VoiceGuidePlayer.State> = _state.asStateFlow()
        private val played = CopyOnWriteArrayList<File>()
        @Volatile var stopCount: Int = 0
        val playCount: Int get() = played.size

        override fun play(file: File, onFinished: () -> Unit) {
            played += file
            _state.value = VoiceGuidePlayer.State.Playing
            _state.value = VoiceGuidePlayer.State.Idle
            onFinished()
        }

        override fun stop() {
            stopCount += 1
            _state.value = VoiceGuidePlayer.State.Idle
        }

        override fun release() {
            _state.value = VoiceGuidePlayer.State.Idle
        }
    }
}
