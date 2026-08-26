// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.audio.model.FakeWhisperModelDownloadScheduler
import org.walktalkmeditate.pilgrim.audio.model.ModelDownloadWork
import org.walktalkmeditate.pilgrim.audio.model.WhisperModelState
import org.walktalkmeditate.pilgrim.audio.model.WhisperModelStore
import org.walktalkmeditate.pilgrim.core.threads.AutoTranscriptionSkipReason
import org.walktalkmeditate.pilgrim.core.threads.FakeAutoTranscriptionSkipState
import org.walktalkmeditate.pilgrim.data.voice.FakeVoicePreferencesRepository

/**
 * Pins the U11 sheet-facing VM plumbing (spec section 4): override
 * toggle and retry delegate to the scheduler (which owns the REPLACE
 * semantics), the sticky override reads back, and the pending substate
 * composes pref x store state. The store is real over the Robolectric
 * filesDir (TranscriptionRunnerTest pattern); the scheduler fake
 * doubles as the work source.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ModelDownloadViewModelTest {

    private lateinit var context: Context
    private lateinit var scheduler: FakeWhisperModelDownloadScheduler
    private lateinit var storeScope: CoroutineScope
    private lateinit var store: WhisperModelStore
    private val dispatcher = UnconfinedTestDispatcher()

    private var dataSaverRestricted = false

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        context = ApplicationProvider.getApplicationContext()
        java.io.File(context.filesDir, "whisper-model").deleteRecursively()
        scheduler = FakeWhisperModelDownloadScheduler()
        storeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        store = WhisperModelStore(
            context = context,
            workSource = scheduler,
            unmeteredProbe = { true },
            scope = storeScope,
        )
    }

    @After
    fun tearDown() {
        storeScope.cancel()
        java.io.File(context.filesDir, "whisper-model").deleteRecursively()
        Dispatchers.resetMain()
    }

    private fun newViewModel(
        autoTranscribe: Boolean = false,
        autoTranscriptionSkipState: FakeAutoTranscriptionSkipState = FakeAutoTranscriptionSkipState(),
    ) = ModelDownloadViewModel(
        modelStore = store,
        voicePreferences = FakeVoicePreferencesRepository(
            initialAutoTranscribe = autoTranscribe,
        ),
        downloadScheduler = scheduler,
        backgroundDataProbe = { dataSaverRestricted },
        autoTranscriptionSkipState = autoTranscriptionSkipState,
    )

    @Test
    fun `setCellularOverride delegates to the scheduler`() = runTest(dispatcher) {
        val vm = newViewModel()

        vm.setCellularOverride(true)
        vm.setCellularOverride(false)

        assertEquals(listOf(true, false), scheduler.cellularOverrideCalls)
    }

    @Test
    fun `retryDownload delegates to the scheduler REPLACE path`() = runTest(dispatcher) {
        val vm = newViewModel()

        vm.retryDownload()

        assertEquals(1, scheduler.retryCalls)
    }

    @Test
    fun `cellularOverride reflects the scheduler's sticky flag`() = runTest(dispatcher) {
        val vm = newViewModel()

        vm.cellularOverride.test(timeout = 10.seconds) {
            var item = awaitItem()
            scheduler.setCellularOverride(true)
            while (!item) item = awaitItem()
            assertTrue(item)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `pendingSubstate is manual preparing while the pref is off with no usable model`() =
        runTest(dispatcher) {
            val vm = newViewModel(autoTranscribe = false)

            assertEquals(
                PendingTranscriptionSubstate.ManualPreparing(WhisperModelState.Absent),
                vm.pendingSubstate.value,
            )
        }

    @Test
    fun `pendingSubstate follows the store through a download`() = runTest(dispatcher) {
        val vm = newViewModel(autoTranscribe = true)

        vm.pendingSubstate.test(timeout = 10.seconds) {
            scheduler.work.value = ModelDownloadWork.Downloading(
                bytesDownloaded = 42_000_000L,
                totalBytes = 148_000_000L,
            )
            var item = awaitItem()
            while (
                item != PendingTranscriptionSubstate.WaitingOnDownload(
                    WhisperModelState.Downloading(
                        bytesDownloaded = 42_000_000L,
                        totalBytes = 148_000_000L,
                    ),
                )
            ) {
                item = awaitItem()
            }
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- U6: battery-skip flag joins the pendingSubstate combine -------

    @Test
    fun `skip flag does not override a non-Ready (delivery-phase) substate`() = runTest(dispatcher) {
        val skipState = FakeAutoTranscriptionSkipState()
        val vm = newViewModel(autoTranscribe = true, autoTranscriptionSkipState = skipState)

        vm.pendingSubstate.test(timeout = 10.seconds) {
            assertEquals(PendingTranscriptionSubstate.WaitingOnDownload(WhisperModelState.Absent), awaitItem())
            skipState.setSkipped(AutoTranscriptionSkipReason.LowBattery)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `pendingSubstate seeds from the skip state's CURRENT value at construction`() = runTest(dispatcher) {
        val skipState = FakeAutoTranscriptionSkipState()
        skipState.setSkipped(AutoTranscriptionSkipReason.LowBattery)

        val vm = newViewModel(autoTranscribe = false, autoTranscriptionSkipState = skipState)

        // Pref-off cell is unaffected by the skip flag either way — this
        // pins that the VM reads skipReason.value (not a stale default)
        // for its initialValue seed, without depending on a Ready model.
        assertEquals(
            PendingTranscriptionSubstate.ManualPreparing(WhisperModelState.Absent),
            vm.pendingSubstate.value,
        )
    }

    @Test
    fun `dataSaverRestricted reads the probe fresh on every access`() = runTest(dispatcher) {
        val vm = newViewModel()

        dataSaverRestricted = false
        assertFalse(vm.dataSaverRestricted)
        dataSaverRestricted = true
        assertTrue(vm.dataSaverRestricted)
    }
}
