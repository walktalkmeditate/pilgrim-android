// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.RandomAccessFile
import kotlin.io.path.absolutePathString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.audio.model.FakeWhisperModelDownloadScheduler
import org.walktalkmeditate.pilgrim.audio.model.ModelDownloadWork
import org.walktalkmeditate.pilgrim.audio.model.ModelDownloadWorkSource
import org.walktalkmeditate.pilgrim.audio.model.WhisperModelConfig
import org.walktalkmeditate.pilgrim.audio.model.WhisperModelStore
import org.walktalkmeditate.pilgrim.core.prompt.LanguageGuess
import org.walktalkmeditate.pilgrim.core.prompt.LanguageIdentifierGateway
import org.walktalkmeditate.pilgrim.core.prompt.MlKitLanguageIdClient
import org.walktalkmeditate.pilgrim.core.threads.FakeThreadsPreferencesRepository
import org.walktalkmeditate.pilgrim.core.threads.ThreadsAnalysisEnvironment
import org.walktalkmeditate.pilgrim.core.threads.TranscriptContextAnalyzer
import org.walktalkmeditate.pilgrim.core.threads.TranscriptContextStore
import org.walktalkmeditate.pilgrim.core.threads.WordNetLexicon
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording

/**
 * Upgrade simulation (plan AE2, U10 spec L1): a v1.2.0 install has the
 * tiny at the flat path and — after this unit — NO bundled asset.
 * Transcription must work on the tiny throughout the download window,
 * survive the verified switch, and continue on base with the earlier
 * rows intact. Real Room + real [TranscriptionRunner] + real
 * [WhisperCppEngine] over a real store; only the JNI seam is faked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ModelUpgradePathTest {

    private lateinit var context: Context
    private lateinit var db: PilgrimDatabase
    private lateinit var repository: WalkRepository
    private lateinit var native: FakeWhisperNative
    private lateinit var scope: CoroutineScope
    private lateinit var store: WhisperModelStore
    private lateinit var scheduler: FakeWhisperModelDownloadScheduler
    private lateinit var runner: TranscriptionRunner

    private val modelRoot: File
        get() = File(context.filesDir, "whisper-model")

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, PilgrimDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = WalkRepository(
            database = db,
            walkDao = db.walkDao(),
            routeDao = db.routeDataSampleDao(),
            altitudeDao = db.altitudeSampleDao(),
            walkEventDao = db.walkEventDao(),
            activityIntervalDao = db.activityIntervalDao(),
            waypointDao = db.waypointDao(),
            voiceRecordingDao = db.voiceRecordingDao(),
            walkPhotoDao = db.walkPhotoDao(),
        )
        modelRoot.deleteRecursively()
        native = FakeWhisperNative()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        store = WhisperModelStore(
            context = context,
            workSource = object : ModelDownloadWorkSource {
                override fun observe(): Flow<ModelDownloadWork?> = flowOf(null)
            },
            unmeteredProbe = { true },
            scope = scope,
        )
        scheduler = FakeWhisperModelDownloadScheduler()
        // This suite doesn't exercise Threads at all — toggle off is the
        // production fast bail-out, so the rest of the wiring below never
        // actually runs.
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        runner = TranscriptionRunner(
            context,
            repository,
            WhisperCppEngine(store, native),
            store,
            scheduler,
            TranscriptContextAnalyzer(
                store = TranscriptContextStore(context, json),
                environment = ThreadsAnalysisEnvironment(context, WordNetLexicon(context, json)),
                languageIdClient = MlKitLanguageIdClient(
                    object : LanguageIdentifierGateway {
                        override suspend fun identifyPossibleLanguages(text: String): List<LanguageGuess> = emptyList()
                    },
                ),
                preferences = FakeThreadsPreferencesRepository(initialThreadsAfterWalks = false),
            ),
            FakeThreadsPreferencesRepository(initialThreadsAfterWalks = false),
        )
    }

    @After fun tearDown() {
        scope.cancel()
        modelRoot.deleteRecursively()
        db.close()
    }

    private fun writeSparse(file: File, length: Long) {
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { it.setLength(length) }
    }

    @Test fun `upgrader transcribes on tiny through the window, switches to base, loses nothing`() =
        runBlocking {
            writeSparse(
                File(modelRoot, "ggml-tiny.en.bin"),
                WhisperModelConfig.LEGACY_TINY_EXPECTED_BYTES,
            )
            val filesDir = context.filesDir.toPath()

            native.resultText = "recorded during the download window"
            val walkBefore = repository.startWalk(startTimestamp = 0L)
            val recordingBefore = insertRecording(walkBefore.id, start = 1_000_000L)
            assertEquals(Result.success(1), runner.transcribePending(walkBefore.id))
            assertEquals(
                WhisperModelConfig.legacyTinyPath(filesDir).absolutePathString(),
                native.initPaths.single(),
            )
            assertEquals(0, scheduler.ensureEnqueuedCalls)

            writeSparse(
                File(modelRoot, "base/ggml-base.bin"),
                WhisperModelConfig.EXPECTED_BYTES,
            )
            File(modelRoot, "base/ggml-base.bin.sha256")
                .writeText(WhisperModelConfig.EXPECTED_SHA256)
            store.onBaseVerified()

            assertFalse(
                "tiny must be gone after the verified switch",
                File(modelRoot, "ggml-tiny.en.bin").exists(),
            )

            native.resultText = "recorded after the switch"
            val walkAfter = repository.startWalk(startTimestamp = 10L)
            val recordingAfter = insertRecording(walkAfter.id, start = 2_000_000L)
            assertEquals(Result.success(1), runner.transcribePending(walkAfter.id))
            assertEquals(
                WhisperModelConfig.baseModelPath(filesDir).absolutePathString(),
                native.initPaths.last(),
            )

            assertEquals(
                "pre-switch transcription must survive the upgrade",
                "recorded during the download window",
                repository.getVoiceRecording(recordingBefore.id)!!.transcription,
            )
            assertEquals(
                "recorded after the switch",
                repository.getVoiceRecording(recordingAfter.id)!!.transcription,
            )
        }

    private suspend fun insertRecording(walkId: Long, start: Long): VoiceRecording {
        val recording = VoiceRecording(
            walkId = walkId,
            startTimestamp = start,
            endTimestamp = start + 5_000L,
            durationMillis = 5_000L,
            fileRelativePath = "recordings/upgrade-$start.wav",
            transcription = null,
        )
        val id = repository.recordVoice(recording)
        return recording.copy(id = id)
    }
}
