// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.Manifest
import android.app.Application
import android.content.Context
import android.media.AudioManager
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.audio.AudioFocusCoordinator
import org.walktalkmeditate.pilgrim.audio.FakeAudioCapture
import org.walktalkmeditate.pilgrim.audio.VoiceRecorder
import org.walktalkmeditate.pilgrim.core.flags.FixedReleaseFlags
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.domain.WalkMode
import org.walktalkmeditate.pilgrim.domain.WalkState
import org.walktalkmeditate.pilgrim.location.FakeLocationSource
import org.walktalkmeditate.pilgrim.walk.BellTrigger
import org.walktalkmeditate.pilgrim.walk.WalkController
import org.walktalkmeditate.pilgrim.walk.WalkStartRequest
import org.walktalkmeditate.pilgrim.walk.WalkStartTimeoutException
import org.walktalkmeditate.pilgrim.walk.seek.SeekHandOff
import org.walktalkmeditate.pilgrim.walk.seek.SeekSonarSettings
import org.walktalkmeditate.pilgrim.walk.seek.SeekStart
import org.walktalkmeditate.pilgrim.walk.seek.SeekTrackerHarness

/**
 * The walk screen's seek Start with Seek in `:tracker` (plan U25): a start
 * the tracker refuses hands the session back to the ready screen, and one
 * that only timed out keeps it quiet, since its walk may still land.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkViewModelSeekStartTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dispatcher = UnconfinedTestDispatcher()
    private val controller = RefusingController()
    private val handOff = RecordingHandOff()
    private lateinit var db: PilgrimDatabase
    private lateinit var voiceRecorder: VoiceRecorder
    private lateinit var viewModel: WalkViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        shadowOf(context as Application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        db = Room.inMemoryDatabaseBuilder(context, PilgrimDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(dispatcher.asExecutor())
            .setTransactionExecutor(dispatcher.asExecutor())
            .build()
        val repository = WalkRepository(
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
        val clock = org.walktalkmeditate.pilgrim.domain.Clock { 1_000L }
        val audioFocus = AudioFocusCoordinator(context.getSystemService(AudioManager::class.java))
        voiceRecorder = VoiceRecorder(context, FakeAudioCapture(), audioFocus, clock)
        viewModel = WalkViewModel(
            context, controller, repository, clock, voiceRecorder, FakeLocationSource(),
            org.walktalkmeditate.pilgrim.data.units.FakeUnitsPreferencesRepository(),
            org.walktalkmeditate.pilgrim.data.practice.FakePracticePreferencesRepository(),
            org.walktalkmeditate.pilgrim.data.weather.FakeWeatherFetching(),
            collectiveStats = org.walktalkmeditate.pilgrim.data.collective.CollectiveStatsSource.of(),
            soundsPreferences = org.walktalkmeditate.pilgrim.data.sounds.FakeSoundsPreferencesRepository(),
            whisperService = org.walktalkmeditate.pilgrim.data.whisper.FakeWhisperService(),
            cairnService = org.walktalkmeditate.pilgrim.data.cairn.FakeCairnService(),
            whisperManifestService = org.walktalkmeditate.pilgrim.data.whisper.FakeWhisperManifestService(),
            geoCacheService = org.walktalkmeditate.pilgrim.data.proximity.FakeGeoCacheService(),
            proximityService = org.walktalkmeditate.pilgrim.data.proximity.FakeProximityDetectionService(),
            whisperPlayer = org.walktalkmeditate.pilgrim.data.whisper.FakeWhisperPlayer(),
            stonePlayer = org.walktalkmeditate.pilgrim.data.cairn.FakeStonePlayer(),
            intentionHistory = org.walktalkmeditate.pilgrim.data.intention.FakeIntentionHistoryRepository(),
            voiceGuidePauseController = org.walktalkmeditate.pilgrim.audio.voiceguide.FakeVoiceGuidePauseController(),
            soundscapeUiController = FakeWalkSoundscapeUiController(),
            releaseFlags = FixedReleaseFlags(honor = true),
            beginHonorWalk = { error("no honor start in this test") },
            honorPreferences = { error("no honor start in this test") },
            seekHandOff = handOff,
        )
    }

    @After
    fun tearDown() {
        runBlocking { withTimeout(WAIT_MILLIS) { viewModel.viewModelScope.coroutineContext[Job]?.cancelAndJoin() } }
        voiceRecorder.stop()
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `a seek start the tracker refuses gives the session back to the ready screen`() = runTest(dispatcher) {
        controller.failure = IllegalStateException("the tracker could not start in the background")

        viewModel.startWalk(mode = WalkMode.Seek)
        assertStartAttempted()

        assertEquals(1, handOff.begun)
        assertEquals(1, handOff.cancelled)
    }

    @Test
    fun `a seek start that only timed out keeps the ready screen quiet, its walk may still land`() = runTest(dispatcher) {
        controller.failure = WalkStartTimeoutException("tracker did not start walk within 5000 ms", RuntimeException("timed out"))

        viewModel.startWalk(mode = WalkMode.Seek)
        assertStartAttempted()

        assertEquals(1, handOff.begun)
        assertEquals(0, handOff.cancelled)
    }

    private fun assertStartAttempted() {
        assertEquals("the start reached the controller with the hand-off", 1, controller.attempts)
        assertEquals(WalkMode.Seek, controller.lastRequest?.mode)
    }

    private class RecordingHandOff : SeekHandOff {
        var begun = 0
        var cancelled = 0

        override suspend fun begin(): SeekStart {
            begun += 1
            return SeekStart(
                chain = SeekTrackerHarness.chain(clearingCount = 1),
                activeIndex = 0,
                durationMinutes = 30,
                tintHex = null,
                seed = SeekTrackerHarness.SEED,
                seededAtEpochMillis = SeekTrackerHarness.BASE_MILLIS,
                intention = null,
                distanceToActiveMeters = null,
                fogBucket = null,
                walker = null,
                nextPulseDueAtMillis = null,
                sonar = SeekSonarSettings(sonarEnabled = true, sonarVolume = 0.5f, soundsEnabled = true),
            )
        }

        override fun cancel() {
            cancelled += 1
        }
    }

    private class RefusingController : WalkController {
        var failure: Throwable = IllegalStateException("not set")
        var lastRequest: WalkStartRequest? = null
        var attempts = 0
        override val state = MutableStateFlow<WalkState>(WalkState.Idle)
        override val bellTriggers: SharedFlow<BellTrigger> = MutableSharedFlow()
        override val liveSteps: StateFlow<Int?> = MutableStateFlow(null)
        override suspend fun startWalk(intention: String?, mode: WalkMode): Walk = error("not used")
        override suspend fun startWalk(request: WalkStartRequest): Walk {
            lastRequest = request
            attempts += 1
            throw failure
        }
        override suspend fun pauseWalk() = Unit
        override suspend fun resumeWalk() = Unit
        override suspend fun startMeditation() = Unit
        override suspend fun endMeditation(endMillis: Long?) = Unit
        override suspend fun finishWalk() = Unit
        override suspend fun discardWalk() = Unit
        override suspend fun recordLocation(point: LocationPoint) = Unit
        override suspend fun setIntention(text: String) = Unit
        override suspend fun recordWaypoint(label: String?, icon: String?) = Unit
        override suspend fun recoverStaleWalks(): Long? = null
        override suspend fun restoreActiveWalk(): Walk? = null
    }

    private companion object {
        const val WAIT_MILLIS = 10_000L
    }
}
