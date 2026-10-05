// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio.walk

import android.app.Application
import android.os.Looper
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
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
import org.walktalkmeditate.pilgrim.walk.honor.HonorHapticsPort
import org.walktalkmeditate.pilgrim.walk.honor.HonorHarness
import org.walktalkmeditate.pilgrim.walk.honor.HonorHarness.Companion.fix
import org.walktalkmeditate.pilgrim.walk.honor.HonorMediaFiles
import org.walktalkmeditate.pilgrim.walk.honor.HonorSession

/**
 * The arbiter as the real Honor session's ports: the session's own call
 * order (a voice's end, then its restore, then the next voice in the same
 * turn) keeps one run ducked and a whisper parked until the run ends.
 * The arbiter runs on the main looper as in production.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkAudioArbiterSessionTest {

    @get:Rule val folder = TemporaryFolder()

    private lateinit var h: HonorHarness
    private lateinit var arbiterScope: CoroutineScope
    private lateinit var arbiter: WalkAudioArbiter
    private lateinit var whispers: AudibleWhisperPlayer
    private val way = HonorHarness.way()
    private val log = audioLog()
    private val player = FakeWayVoicePlayer(log)
    private val uiGates = FakeUiAudioGates()

    @Before
    fun setUp() {
        h = HonorHarness(folder.root)
        h.writeRecordings(way)
        whispers = AudibleWhisperPlayer(log)
        arbiterScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        arbiter = WalkAudioArbiter(player, FakeWayVoiceSoundscape(log), whispers, uiGates, arbiterScope)
    }

    @After
    fun tearDown() {
        arbiterScope.cancel()
        h.close()
    }

    private fun session() = HonorSession(
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

    /** The session's actor and the arbiter's main-looper steps, until both are quiet. */
    private suspend fun HonorSession.drain() {
        repeat(3) {
            awaitIdle()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun idleFor(millis: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))

    @Test
    fun `a whisper waits through a run of the session's voices, one duck long, and plays when the run ends`() = runBlocking {
        val walk = h.startHonorWalk(way)
        val session = session()
        session.start(h.serviceScope, walk.id, h.controller.state, fix(0.0, h.clock.millis))
        session.drain()
        arbiter.requestWhisper(whisper("w1"))
        for (lon in listOf(0.001, 0.002, 0.003, 0.004, 0.005)) {
            h.clock.millis += 1_000
            session.onFix(fix(lon, h.clock.millis))
        }
        session.drain()
        assertEquals("voice-2 waits in the engine while voice-1 plays", 1, player.plays.size)

        player.plays.last().listener.onEnded()
        session.drain()
        idleFor(WalkAudioArbiter.RUN_SETTLE_MILLIS * 2)
        assertEquals(listOf("voice-1", "voice-2"), player.plays.map { it.file.nameWithoutExtension })
        assertEquals("the run goes on", emptyList<String>(), whispers.played)

        player.plays.last().listener.onEnded()
        session.drain()
        idleFor(WalkAudioArbiter.RUN_SETTLE_MILLIS * 2)

        assertEquals(listOf("w1"), whispers.played)
        assertEquals(listOf("hold 0.15"), log.filter { it.startsWith("hold") })
        assertEquals(listOf("release 0.4"), log.filter { it.startsWith("release") })
    }

    @Test
    fun `a prompt mid-voice stills the session's clock with the voice still listening, and its end runs it on`() =
        runBlocking {
            val walk = h.startHonorWalk(way)
            val session = session()
            session.start(h.serviceScope, walk.id, h.controller.state, fix(0.0, h.clock.millis))
            session.drain()
            h.clock.millis += 5_000

            uiGates.value.value = UiAudioGates(prompt = true)
            session.drain()
            val held = h.db.honorDao().getSession(walk.id)!!
            h.clock.millis += 30_000
            uiGates.value.value = UiAudioGates()
            session.drain()
            val released = h.db.honorDao().getSession(walk.id)!!

            assertEquals(
                listOf("voice-1", false, null, 5_000L),
                listOf(held.playingMomentId, held.voicePaused, held.voiceStartedAt, held.voiceStartOffsetMillis),
            )
            assertEquals(h.clock.millis to 5_000L, released.voiceStartedAt to released.voiceStartOffsetMillis)
        }

    private object NoHaptics : HonorHapticsPort {
        override fun momentReached() = Unit

        override fun softTap() = Unit

        override fun waterAhead() = Unit

        override fun arrival() = Unit

        override val arrivalMillis = 0L
    }
}
