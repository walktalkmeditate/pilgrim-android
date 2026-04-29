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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VoiceGuideOrchestratorVoiceEnabledTest {

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
