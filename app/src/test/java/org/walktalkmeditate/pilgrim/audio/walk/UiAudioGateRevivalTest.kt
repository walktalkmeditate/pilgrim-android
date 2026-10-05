// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio.walk

import android.app.Application
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.core.flags.FixedReleaseFlags
import org.walktalkmeditate.pilgrim.data.TestRealTimeDispatcher
import org.walktalkmeditate.pilgrim.service.WalkTrackingService
import org.walktalkmeditate.pilgrim.walk.WalkActionPublisher
import org.walktalkmeditate.pilgrim.walk.honor.HonorHapticsPort
import org.walktalkmeditate.pilgrim.walk.honor.HonorHarness
import org.walktalkmeditate.pilgrim.walk.honor.HonorHarness.Companion.fix
import org.walktalkmeditate.pilgrim.walk.honor.HonorMediaFiles
import org.walktalkmeditate.pilgrim.walk.honor.HonorSession
import org.walktalkmeditate.pilgrim.walk.honor.HonorSessionStart

/**
 * A `:tracker` revival against the real Honor session (plan U18): a fresh
 * process holds the UI's gates until the UI, reading the bumped gate
 * generation from Room, re-sends them, or until the fixed wait passes with
 * no UI to answer. The trailhead voice is reached, and waits in the
 * engine's queue, while the walker records.
 *
 * The gate's wait runs on a scheduler only the test advances, so a hold
 * can only end by the UI's answer or the test's word.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UiAudioGateRevivalTest {

    @get:Rule val folder = TemporaryFolder()

    private lateinit var h: HonorHarness
    private lateinit var arbiterScope: CoroutineScope
    private lateinit var uiScope: CoroutineScope
    /** One voice, 222 m in: reached once the gates apply, unlike a trailhead voice, which Begin's replayed fix meets on open gates. */
    private val way = HonorHarness.way(moments = listOf(HonorHarness.voice(1, VOICE_LON)))
    private val walkActions = WalkActionPublisher(ApplicationProvider.getApplicationContext<Application>())
    private val recording = MutableStateFlow(true)
    private val sent = MutableStateFlow<List<Intent>>(emptyList())
    private val waits = TestCoroutineScheduler()

    /** One `:tracker` process: its own gate, arbiter, player, and session. */
    private inner class Tracker {
        val gate = UiAudioGate(CoroutineScope(StandardTestDispatcher(waits)), UiAudioGate.REFRESH_WAIT_MILLIS)
        val player = FakeWayVoicePlayer(audioLog())
        val arbiter = WalkAudioArbiter(player, FakeWayVoiceSoundscape(audioLog()), AudibleWhisperPlayer(audioLog()), gate, arbiterScope)
        val session = HonorSession(
            database = h.db,
            wayStore = h.store,
            arrivalRecorder = h.controller,
            voice = arbiter,
            duck = arbiter,
            haptics = NoHaptics,
            gatePort = arbiter,
            media = HonorMediaFiles({ h.filesRoot }, h.store),
            releaseFlags = FixedReleaseFlags(honor = true),
            clock = h.clock,
            arrivalLabel = { title -> "Walked their way: $title" },
            sessionDispatcher = Dispatchers.IO,
            tickMillis = 0,
        )
        private var delivered = 0

        /** The service's handler, for each intent the UI sent since this process began listening. */
        fun deliver(upTo: Int) {
            val intents = sent.value
            for (intent in intents.subList(delivered, upTo)) {
                gate.apply(WalkTrackingService.uiAudioGateSignalFromExtras(intent)!!)
            }
            delivered = upTo
            shadowOf(Looper.getMainLooper()).idle()
        }

        fun skipTo(count: Int) {
            delivered = count
        }

        suspend fun drain() {
            repeat(3) {
                session.awaitIdle()
                shadowOf(Looper.getMainLooper()).idle()
            }
        }
    }

    @Before
    fun setUp() {
        h = HonorHarness(folder.root)
        h.writeRecordings(way)
        arbiterScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        uiScope = CoroutineScope(SupervisorJob() + TestRealTimeDispatcher.instance)
    }

    @After
    fun tearDown() {
        runBlocking { uiScope.coroutineContext[Job]!!.cancelAndJoin() }
        arbiterScope.cancel()
        h.close()
    }

    private fun startUi() {
        UiAudioGatePublisher(
            releaseFlags = FixedReleaseFlags(honor = true),
            recording = recording,
            refreshes = h.db.honorDao().uiAudioGateRefreshes(),
            send = { kind, held, seq, token ->
                sent.update { it + walkActions.uiAudioGateIntent(kind, held, seq, token) }
            },
            bootNanos = { 0L },
            scope = uiScope,
        ).start()
    }

    private suspend fun awaitSent(count: Int) {
        withTimeout(WAIT_MS) { sent.first { it.size >= count } }
    }

    /** The first process: the walk starts with a take under way, and the trailhead voice queues behind it. */
    private suspend fun firstProcessThenKill(withUi: Boolean): Long {
        val walk = h.startHonorWalk(way)
        if (withUi) {
            startUi()
            // Its recording, and the re-send for the walk's first generation: one gate each way.
            awaitSent(FIRST_WALK_SENDS)
        }
        val first = Tracker()
        if (withUi) first.deliver(FIRST_WALK_SENDS)
        first.session.start(h.serviceScope, walk.id, h.controller.state, fix(0.0, h.clock.millis))
        first.drain()
        if (withUi) {
            awaitSent(FIRST_WALK_SENDS + RESEND)
            first.deliver(FIRST_WALK_SENDS + RESEND)
        }
        for (lon in listOf(VOICE_LON / 2, VOICE_LON)) {
            h.clock.millis += 1_000
            first.session.onFix(fix(lon, h.clock.millis))
        }
        first.drain()
        assertEquals("the voice waits in the engine behind the take", 0, first.player.plays.size)
        first.session.stop()
        return walk.id
    }

    @Test
    fun `a revived session with a take under way starts no voice until the take ends`() = runBlocking {
        val walkId = firstProcessThenKill(withUi = true)

        val revived = Tracker()
        revived.skipTo(FIRST_WALK_SENDS + RESEND)
        val result = revived.session.start(h.serviceScope, walkId, h.controller.state)
        assertEquals(HonorSessionStart.Started(revived = true), result)
        revived.drain()
        assertEquals("a fresh process holds the gates it hasn't heard", 0, revived.player.plays.size)

        awaitSent(FIRST_WALK_SENDS + 2 * RESEND)
        revived.deliver(FIRST_WALK_SENDS + 2 * RESEND)
        revived.drain()
        assertEquals(UiAudioGates(prompt = false, recording = true), revived.gate.gates.value)
        assertEquals("the UI's answer keeps the take's gate closed", 0, revived.player.plays.size)

        recording.value = false
        awaitSent(FIRST_WALK_SENDS + 2 * RESEND + 1)
        revived.deliver(FIRST_WALK_SENDS + 2 * RESEND + 1)
        revived.drain()
        assertEquals(listOf("voice-1"), revived.player.plays.map { it.file.nameWithoutExtension })
    }

    @Test
    fun `with no UI alive, a revived session's gates open after the fixed wait`() = runBlocking {
        val walkId = firstProcessThenKill(withUi = false)

        val revived = Tracker()
        revived.session.start(h.serviceScope, walkId, h.controller.state)
        revived.drain()
        waits.advanceTimeBy(UiAudioGate.REFRESH_WAIT_MILLIS - 1)
        waits.runCurrent()
        revived.drain()
        assertEquals(0, revived.player.plays.size)

        waits.advanceTimeBy(2)
        waits.runCurrent()
        revived.drain()
        assertEquals(listOf("voice-1"), revived.player.plays.map { it.file.nameWithoutExtension })
    }

    private object NoHaptics : HonorHapticsPort {
        override fun momentReached() = Unit

        override fun softTap() = Unit

        override fun waterAhead() = Unit

        override fun arrival() = Unit

        override val arrivalMillis = 0L
    }

    private companion object {
        const val VOICE_LON = 0.002
        const val RESEND = 3
        const val FIRST_WALK_SENDS = 1 + RESEND

        /** A failsafe, not a grace window: each wait returns the moment its intent is sent. */
        const val WAIT_MS = 30_000L
    }
}
