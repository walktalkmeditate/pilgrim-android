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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.voiceguide.PromptDensity
import org.walktalkmeditate.pilgrim.data.voiceguide.VoiceGuideFileStore
import org.walktalkmeditate.pilgrim.data.voiceguide.VoiceGuideManifest
import org.walktalkmeditate.pilgrim.data.voiceguide.VoiceGuideManifestService
import org.walktalkmeditate.pilgrim.data.voiceguide.VoiceGuidePack
import org.walktalkmeditate.pilgrim.data.voiceguide.VoiceGuidePrompt
import org.walktalkmeditate.pilgrim.domain.Clock
import org.walktalkmeditate.pilgrim.domain.WalkAccumulator
import org.walktalkmeditate.pilgrim.domain.WalkState

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VoiceGuideOrchestratorTest {

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

    private fun prompt(id: String, r2Key: String = "p/$id.aac", phase: String? = null) =
        VoiceGuidePrompt(
            id = id, seq = 0, durationSec = 1.0,
            fileSizeBytes = 100L, r2Key = r2Key, phase = phase,
        )

    private fun pack(
        id: String = "p",
        prompts: List<VoiceGuidePrompt> = listOf(prompt("pw1", "p/w1.aac")),
        meditationPrompts: List<VoiceGuidePrompt>? = null,
        meditationScheduling: PromptDensity? = null,
    ) = VoiceGuidePack(
        id = id, version = "1", name = id, tagline = "", description = "",
        theme = "", iconName = "", type = "walk", walkTypes = emptyList(),
        scheduling = PromptDensity(
            densityMinSec = 10, densityMaxSec = 20,
            minSpacingSec = 0, initialDelaySec = 0, walkEndBufferSec = 0,
        ),
        totalDurationSec = 0.0, totalSizeBytes = 0L,
        prompts = prompts,
        meditationPrompts = meditationPrompts,
        meditationScheduling = meditationScheduling,
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
        (pack.prompts + (pack.meditationPrompts ?: emptyList())).forEach {
            fileStore.fileForPrompt(it.r2Key).writeBytes(ByteArray(it.fileSizeBytes.toInt()))
        }
    }

    @Test fun `Active with no selection does not spawn scheduler`() = runTest {
        seedManifest(listOf(pack()))
        val walkState = MutableStateFlow<WalkState>(WalkState.Active(acc))
        val selectedPackId = MutableStateFlow<String?>(null)
        val s = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        VoiceGuideOrchestrator(
            walkState, selectedPackId, manifestService, fileStore,
            capturingPlayer, FixedClock(), s,
        ).start()
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(0, capturingPlayer.playCount)
        s.cancel()
    }

    @Test fun `Active with selection but not-downloaded does not spawn`() = runTest {
        seedManifest(listOf(pack()))
        // No prompt files written.
        val walkState = MutableStateFlow<WalkState>(WalkState.Active(acc))
        val selectedPackId = MutableStateFlow<String?>("p")
        val s = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        VoiceGuideOrchestrator(
            walkState, selectedPackId, manifestService, fileStore,
            capturingPlayer, FixedClock(), s,
        ).start()
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(0, capturingPlayer.playCount)
        s.cancel()
    }

    @Test fun `Active with eligible pack eventually plays a prompt`() = runTest {
        val pk = pack()
        seedManifest(listOf(pk))
        writePromptFiles(pk)
        val walkState = MutableStateFlow<WalkState>(WalkState.Active(acc))
        val selectedPackId = MutableStateFlow<String?>("p")
        val s = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        VoiceGuideOrchestrator(
            walkState, selectedPackId, manifestService, fileStore,
            capturingPlayer, FixedClock(), s,
        ).start()
        runCurrent()
        assertTrue(
            "expected at least 1 play, got ${capturingPlayer.playCount}",
            capturingPlayer.playCount >= 1,
        )
        s.cancel()
    }

    @Test fun `Finished cancels scheduler and stops player`() = runTest {
        val pk = pack()
        seedManifest(listOf(pk))
        writePromptFiles(pk)
        val walkState = MutableStateFlow<WalkState>(WalkState.Active(acc))
        val selectedPackId = MutableStateFlow<String?>("p")
        val s = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        VoiceGuideOrchestrator(
            walkState, selectedPackId, manifestService, fileStore,
            capturingPlayer, FixedClock(), s,
        ).start()
        runCurrent()
        val baseline = capturingPlayer.playCount

        walkState.value = WalkState.Finished(acc, endedAt = 5_000L)
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(baseline, capturingPlayer.playCount)
        assertTrue(capturingPlayer.stopCount >= 1)
        s.cancel()
    }

    @Test fun `Paused cancels scheduler and stops player`() = runTest {
        val pk = pack()
        seedManifest(listOf(pk))
        writePromptFiles(pk)
        val walkState = MutableStateFlow<WalkState>(WalkState.Active(acc))
        val selectedPackId = MutableStateFlow<String?>("p")
        val s = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        VoiceGuideOrchestrator(
            walkState, selectedPackId, manifestService, fileStore,
            capturingPlayer, FixedClock(), s,
        ).start()
        runCurrent()

        walkState.value = WalkState.Paused(acc, pausedAt = 5_000L)
        runCurrent()

        assertTrue(capturingPlayer.stopCount >= 1)
        s.cancel()
    }

    @Test fun `Meditating with pack having no meditation prompts — no meditation scheduler`() = runTest {
        val pk = pack(meditationPrompts = null)
        seedManifest(listOf(pk))
        writePromptFiles(pk)
        val walkState = MutableStateFlow<WalkState>(
            WalkState.Meditating(acc, meditationStartedAt = 1_000L),
        )
        val selectedPackId = MutableStateFlow<String?>("p")
        val s = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        VoiceGuideOrchestrator(
            walkState, selectedPackId, manifestService, fileStore,
            capturingPlayer, FixedClock(), s,
        ).start()
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(0, capturingPlayer.playCount)
        s.cancel()
    }

    @Test fun `Meditating with meditation pack plays a meditation prompt`() = runTest {
        val medDensity = PromptDensity(
            densityMinSec = 10, densityMaxSec = 20,
            minSpacingSec = 0, initialDelaySec = 0, walkEndBufferSec = 0,
        )
        val medPrompts = listOf(prompt("pm1", "p/m1.aac"))
        val pk = pack(
            prompts = listOf(prompt("pw1", "p/w1.aac")),
            meditationPrompts = medPrompts,
            meditationScheduling = medDensity,
        )
        seedManifest(listOf(pk))
        writePromptFiles(pk)
        val walkState = MutableStateFlow<WalkState>(
            WalkState.Meditating(acc, meditationStartedAt = 1_000L),
        )
        val selectedPackId = MutableStateFlow<String?>("p")
        val s = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        VoiceGuideOrchestrator(
            walkState, selectedPackId, manifestService, fileStore,
            capturingPlayer, FixedClock(), s,
        ).start()
        runCurrent()

        assertTrue(
            "expected at least 1 meditation play, got ${capturingPlayer.playCount}",
            capturingPlayer.playCount >= 1,
        )
        val lastFile = capturingPlayer.lastFile
        assertTrue(
            "expected meditation r2Key, got ${lastFile?.path}",
            lastFile?.path?.endsWith("p/m1.aac") == true,
        )
        s.cancel()
    }

    // --- fakes ---

    private class FixedClock(private var millis: Long = 1_700_000_000_000L) : Clock {
        override fun now(): Long = millis
    }

    private class CapturingVoiceGuidePlayer : VoiceGuidePlayer {
        private val _state = MutableStateFlow<VoiceGuidePlayer.State>(VoiceGuidePlayer.State.Idle)
        override val state: StateFlow<VoiceGuidePlayer.State> = _state.asStateFlow()
        private val played = CopyOnWriteArrayList<File>()
        @Volatile var stopCount: Int = 0
        @Volatile var lastFile: File? = null
        val playCount: Int get() = played.size

        override fun play(file: File, onFinished: () -> Unit) {
            played += file
            lastFile = file
            _state.value = VoiceGuidePlayer.State.Playing
            // Fire completion immediately so the scheduler's play
            // history advances without needing natural media completion.
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
