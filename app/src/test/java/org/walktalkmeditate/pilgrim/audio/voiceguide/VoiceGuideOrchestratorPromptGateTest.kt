// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio.voiceguide

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.Collections
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
import kotlinx.coroutines.test.TestScope
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
 * The guide's prompt level (plan U18, the placement table's "Voice guide"
 * row): the prompt gate hears it before the player is asked to start the
 * prompt, and again when the prompt ends by any path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VoiceGuideOrchestratorPromptGateTest {

    private val acc = WalkAccumulator(walkId = 1L, startedAt = 1_000L)
    private val log: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val player = HoldingPlayer(log)

    private lateinit var context: Application
    private lateinit var server: MockWebServer
    private lateinit var fileStore: VoiceGuideFileStore
    private lateinit var manifestService: VoiceGuideManifestService
    private lateinit var manifestScope: CoroutineScope

    private val manifestCache: File get() = File(context.filesDir, "voice_guide_manifest.json")
    private val promptsRoot: File get() = File(context.filesDir, "voice_guide_prompts")

    private val pack = VoiceGuidePack(
        id = "p", version = "1", name = "Forest Walk", tagline = "", description = "",
        theme = "", iconName = "", type = "walk", walkTypes = emptyList(),
        scheduling = PromptDensity(
            densityMinSec = 10, densityMaxSec = 20,
            minSpacingSec = 0, initialDelaySec = 0, walkEndBufferSec = 0,
        ),
        totalDurationSec = 0.0, totalSizeBytes = 0L,
        prompts = listOf(
            VoiceGuidePrompt(id = "pw1", seq = 0, durationSec = 1.0, fileSizeBytes = 100L, r2Key = "p/w1.aac", phase = null),
        ),
        meditationPrompts = null,
        meditationScheduling = null,
    )

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manifestCache.delete()
        promptsRoot.deleteRecursively()
        server = MockWebServer().also { it.start() }
        fileStore = VoiceGuideFileStore(context)
        manifestScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        manifestCache.writeText(json.encodeToString(VoiceGuideManifest(version = "v1", packs = listOf(pack))))
        manifestService = VoiceGuideManifestService(
            context = context,
            httpClient = OkHttpClient(),
            json = json,
            scope = manifestScope,
            manifestUrl = server.url("/manifest.json").toString(),
        )
        runBlocking { manifestScope.coroutineContext[Job]?.children?.forEach { it.join() } }
        pack.prompts.forEach { fileStore.fileForPrompt(it.r2Key).writeBytes(ByteArray(it.fileSizeBytes.toInt())) }
    }

    @After fun tearDown() {
        manifestScope.cancel()
        server.shutdown()
        manifestCache.delete()
        promptsRoot.deleteRecursively()
    }

    private fun TestScope.orchestrator(walkState: MutableStateFlow<WalkState>, scope: CoroutineScope) =
        VoiceGuideOrchestrator(
            walkState, MutableStateFlow("p"), manifestService, fileStore,
            player, FixedClock(),
            FakeSoundsPreferencesRepository(initialSoundsEnabled = true),
            FakeVoicePreferencesRepository(initialVoiceGuideEnabled = true),
            VoiceGuideProgressRepository.NoOp,
            scope,
            promptGate = { sounding -> log += "gate $sounding" },
        )

    @Test fun `the gate hears a prompt before the player is asked to start it`() = runTest {
        val walkState = MutableStateFlow<WalkState>(WalkState.Active(acc))
        val s = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val orchestrator = orchestrator(walkState, s).also { it.start() }
        runCurrent()

        assertEquals(listOf("gate true", "play"), log)
        assertTrue(orchestrator.promptSounding.value)

        player.finish()
        assertEquals(listOf("gate true", "play", "gate false"), log)
        assertFalse(orchestrator.promptSounding.value)
        s.cancel()
    }

    @Test fun `a prompt the walk's end stops lowers the gate`() = runTest {
        val walkState = MutableStateFlow<WalkState>(WalkState.Active(acc))
        val s = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val orchestrator = orchestrator(walkState, s).also { it.start() }
        runCurrent()

        walkState.value = WalkState.Finished(acc, endedAt = 60_000L)
        runCurrent()

        assertEquals("gate false", log.last())
        assertFalse(orchestrator.promptSounding.value)
        s.cancel()
    }

    @Test fun `the walker pausing the guide lowers the gate as the prompt stops`() = runTest {
        val walkState = MutableStateFlow<WalkState>(WalkState.Active(acc))
        val s = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val orchestrator = orchestrator(walkState, s).also { it.start() }
        runCurrent()

        orchestrator.pause()

        assertEquals(listOf("gate true", "play", "gate false"), log)
        s.cancel()
    }

    private class FixedClock(private val millis: Long = 1_700_000_000_000L) : Clock {
        override fun now(): Long = millis
    }

    /** Holds each prompt until [finish] or [stop], as the ExoPlayer player does; every end fires once. */
    private class HoldingPlayer(private val log: MutableList<String>) : VoiceGuidePlayer {
        private val _state = MutableStateFlow<VoiceGuidePlayer.State>(VoiceGuidePlayer.State.Idle)
        override val state: StateFlow<VoiceGuidePlayer.State> = _state.asStateFlow()
        @Volatile private var pending: (() -> Unit)? = null

        override fun play(file: File, onFinished: () -> Unit) {
            finish()
            log += "play"
            _state.value = VoiceGuidePlayer.State.Playing
            pending = onFinished
        }

        fun finish() {
            val end = pending ?: return
            pending = null
            _state.value = VoiceGuidePlayer.State.Idle
            end()
        }

        override fun stop() = finish()

        override fun release() = finish()
    }
}
