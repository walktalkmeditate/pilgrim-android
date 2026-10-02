// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.walk.seek

import android.app.Application
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.audio.walk.UiAudioGateRefresh
import org.walktalkmeditate.pilgrim.audio.walk.uiAudioGateRefreshes
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.honor.HonorSessionEntity
import org.walktalkmeditate.pilgrim.data.honor.HonorSourceKind
import org.walktalkmeditate.pilgrim.data.seek.SeekDisplayState
import org.walktalkmeditate.pilgrim.data.seek.SeekLiveSessionKey
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.domain.WalkEventType
import org.walktalkmeditate.pilgrim.domain.WalkMode
import org.walktalkmeditate.pilgrim.domain.seek.SeekChain
import org.walktalkmeditate.pilgrim.domain.seek.SeekChainCodec
import org.walktalkmeditate.pilgrim.domain.seek.SeekClearing
import org.walktalkmeditate.pilgrim.domain.seek.SeekEnginePhase
import org.walktalkmeditate.pilgrim.domain.seek.SeekPersistence
import org.walktalkmeditate.pilgrim.domain.seek.SeekPoint
import org.walktalkmeditate.pilgrim.walk.WalkStartRequest
import org.walktalkmeditate.pilgrim.walk.seek.SeekTrackerHarness.Companion.chain

/**
 * The seek session row (plan U25): what Begin's hand-off writes inside the
 * start, the compare-and-sets `:tracker` relies on (arrival once, each
 * command and setting once), what the UI reads back (the live session, the
 * gate refresh), how the row leaves with its walk, and the chain codec both
 * the intent and the row carry.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SeekSessionPersistenceTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var h: SeekTrackerHarness
    private val dao get() = h.db.seekDao()

    @Before
    fun setUp() {
        h = SeekTrackerHarness(dispatcher)
    }

    @After
    fun tearDown() {
        h.db.close()
    }

    private fun handOff() = h.handOff(chain(2), nextPulseDueAtMillis = 123_456L).copy(
        activeIndex = 1,
        tintHex = "#2377A4",
        sonar = SeekSonarSettings(sonarEnabled = false, sonarVolume = 0.3f, soundsEnabled = true),
    )

    private suspend fun seekWalk(start: SeekStart = handOff()): Walk = h.startSeekWalk(h.newController(), start)

    private fun at(point: SeekPoint) = LocationPoint(timestamp = 1L, latitude = point.latitude, longitude = point.longitude)

    // Begin's hand-off

    @Test
    fun `a seek start writes the marker and Begin's durable facts together`() = runTest(dispatcher) {
        val start = handOff()
        val walk = seekWalk(start)

        assertEquals(listOf(WalkEventType.SEEK_MODE), h.repository.eventsFor(walk.id).map { it.eventType })
        val row = h.row(walk.id)
        assertEquals(start.chain, SeekChainCodec.decode(row.chain))
        assertEquals(1, row.activeIndex)
        assertEquals(30, row.durationMinutes)
        assertEquals("#2377A4", row.tintHex)
        assertEquals(SeekTrackerHarness.SEED, row.seed)
        assertEquals(SeekTrackerHarness.BASE_MILLIS, row.seededAt)
        assertEquals("find the river", row.intention)
        assertEquals(123_456L, row.nextPulseDueAt)
        assertEquals(SeekEnginePhase.GUIDING, row.phase)
        assertFalse(row.sonarEnabled)
        assertEquals(0.3f, row.sonarVolume, 0f)
        assertTrue(row.soundsEnabled)
        assertEquals("a fresh row has never started a session", 0L, row.gateGeneration)
    }

    @Test
    fun `a seek start without a hand-off is the seek walk it has always been`() = runTest(dispatcher) {
        val walk = h.newController().startWalk(WalkStartRequest(mode = WalkMode.Seek))

        assertEquals(listOf(WalkEventType.SEEK_MODE), h.repository.eventsFor(walk.id).map { it.eventType })
        assertNull(dao.getSession(walk.id))
    }

    @Test
    fun `a late hand-off attaches only to the seek walk in progress, once`() = runTest(dispatcher) {
        val controller = h.newController()
        val walk = controller.startWalk(WalkStartRequest(mode = WalkMode.Seek))

        assertEquals(walk.id, controller.attachSeekSession(handOff()))
        assertEquals(null, controller.attachSeekSession(handOff()))
        assertEquals(1, h.row(walk.id).activeIndex)

        controller.finishWalk()
        val wander = h.newController()
        wander.startWalk(WalkStartRequest(mode = WalkMode.Wander))
        assertNull("a wander walk takes no seek session", wander.attachSeekSession(handOff()))
        assertNull("nor does any walk with the flag off", h.newController(honorEnabled = false).attachSeekSession(handOff()))
    }

    // Arrival

    @Test
    fun `a clearing records once, under the controller, with its ordinal`() = runTest(dispatcher) {
        val controller = h.newController()
        val walk = h.startSeekWalk(controller, h.handOff(chain(2)))
        val center = chain(2).clearings[0].center

        assertTrue(controller.recordSeekArrival(walk.id, clearingIndex = 0, at = at(center)) { "Clearing $it" })
        assertFalse("the clearing is already reached", controller.recordSeekArrival(walk.id, 0, at(center)) { "Clearing $it" })
        assertEquals(SeekEnginePhase.ARRIVED, h.row(walk.id).phase)
        assertEquals(h.clock.now(), h.row(walk.id).arrivedAt)

        // "Seek anew" from inside the clearing replaces it at the same index.
        dao.setPhase(walk.id, SeekEnginePhase.GUIDING)
        assertNull("leaving a clearing forgets when it was reached", h.row(walk.id).arrivedAt)
        assertTrue(controller.recordSeekArrival(walk.id, 0, at(center)) { "Clearing $it" })

        val arrivals = h.repository.waypointsFor(walk.id).filter { SeekPersistence.isArrivalWaypoint(it.icon) }
        assertEquals(listOf("Clearing 1", "Clearing 2"), arrivals.map { it.label })
        assertEquals(
            2,
            h.repository.eventsFor(walk.id).count { it.eventType == WalkEventType.SEEK_ARRIVAL },
        )
    }

    @Test
    fun `an arrival on another clearing, or after the finish, is refused`() = runTest(dispatcher) {
        val controller = h.newController()
        val walk = h.startSeekWalk(controller, h.handOff(chain(2)))
        val center = chain(2).clearings[0].center

        assertFalse("not the clearing the row guides to", controller.recordSeekArrival(walk.id, 1, at(center)) { "x" })
        controller.finishWalk()
        assertFalse(controller.recordSeekArrival(walk.id, 0, at(center)) { "x" })
        assertFalse(h.repository.recordSeekArrival(walk.id, 0, eventAt = 1L, place = null) { "x" })
        assertEquals(0, h.countSeekArrivalEvents())
    }

    @Test
    fun `an arrival with no fix keeps the event and skips the waypoint`() = runTest(dispatcher) {
        val controller = h.newController()
        val walk = h.startSeekWalk(controller, h.handOff(chain(1)))

        assertTrue(controller.recordSeekArrival(walk.id, 0, at = null) { "x" })

        assertEquals(1, h.countSeekArrivalEvents())
        assertEquals(0, h.countArrivalWaypoints())
    }

    @Test
    fun `a display write leaves the phase to arrival's compare-and-set`() = runTest(dispatcher) {
        val walk = seekWalk(h.handOff(chain(1)))
        dao.updateDisplay(
            SeekDisplayState(
                walkId = walk.id, chain = h.row(walk.id).chain, activeIndex = 0, distanceToActiveMeters = 42.0,
                fogBucket = 1, walkerLatitude = 1.0, walkerLongitude = 2.0, pulseToken = 3,
                pulseAligned = true, pulseCloseness = 0.9, nextPulseDueAt = 7L,
            ),
        )

        val row = h.row(walk.id)
        assertEquals(SeekEnginePhase.GUIDING, row.phase)
        assertEquals(3, row.pulseToken)
        assertEquals(42.0, row.distanceToActiveMeters!!, 0.0)
        assertEquals(1, dao.recordArrival(walk.id, activeIndex = 0, atMillis = 9L))
    }

    // Each command and setting once

    @Test
    fun `a command or a setting applies only past the last number applied`() = runTest(dispatcher) {
        val walk = seekWalk()

        assertEquals(1, dao.applyCommandSeq(walk.id, 10L))
        assertEquals(0, dao.applyCommandSeq(walk.id, 10L))
        assertEquals(0, dao.applyCommandSeq(walk.id, 9L))
        assertEquals(1, dao.applyPreferences(walk.id, 4L, sonarEnabled = true, sonarVolume = 0.8f, soundsEnabled = false))
        assertEquals(0, dao.applyPreferences(walk.id, 3L, sonarEnabled = false, sonarVolume = 0.1f, soundsEnabled = true))

        val row = h.row(walk.id)
        assertEquals(10L, row.lastCommandSeq)
        assertTrue(row.sonarEnabled)
        assertEquals(0.8f, row.sonarVolume, 0f)
        assertFalse(row.soundsEnabled)
    }

    // What the UI reads

    @Test
    fun `the live session is the unfinished seek walk's, with its gate generation`() = runTest(dispatcher) {
        val controller = h.newController()
        val walk = h.startSeekWalk(controller, handOff())
        dao.bumpGateGeneration(walk.id)

        assertEquals(SeekLiveSessionKey(walk.id, gateGeneration = 1L), dao.observeLiveSessionKey().first())
        controller.finishWalk()
        assertNull(dao.observeLiveSessionKey().first())
    }

    @Test
    fun `the UI re-sends its gates on a seek walk's generation, as on an honor walk's`() = runTest(dispatcher) {
        val refreshes = uiAudioGateRefreshes(h.db.honorDao(), dao)
        val controller = h.newController()
        val seek = h.startSeekWalk(controller, handOff())
        dao.bumpGateGeneration(seek.id)
        dao.bumpGateGeneration(seek.id)

        assertEquals(UiAudioGateRefresh(seek.id, gateGeneration = 2L), refreshes.first())

        controller.finishWalk()
        val wander = h.newController().apply { startWalk(WalkStartRequest()) }
        val plain = h.repository.getActiveWalk()!!
        assertEquals("a walk with neither session has no generation", UiAudioGateRefresh(plain.id, null), refreshes.first())
        wander.finishWalk()

        val honorWalk = h.repository.startWalk(startTimestamp = h.clock.now())
        h.db.honorDao().insertSession(
            HonorSessionEntity(
                walkId = honorWalk.id,
                wayId = "walk:0e8d6f8a-5b1c-4f1e-9a53-2f1d8c7b6a50",
                sourceKind = HonorSourceKind.OWN_WALK,
                voicesEnabled = true,
                softTapEnabled = false,
                gateGeneration = 5L,
            ),
        )
        assertEquals(UiAudioGateRefresh(honorWalk.id, gateGeneration = 5L), refreshes.first())
    }

    // The row leaves with its walk

    @Test
    fun `the row cascades with its walk and goes with the archive strip`() = runTest(dispatcher) {
        val deleted = seekWalk()
        h.repository.deleteWalkById(deleted.id)
        assertNull(dao.getSession(deleted.id))

        val controller = h.newController()
        val archived = h.startSeekWalk(controller, handOff())
        controller.finishWalk()
        h.repository.stripArchivedWalk(archived.id)
        assertNull(dao.getSession(archived.id))
        assertTrue("the walk row itself stays", h.repository.getWalk(archived.id) != null)
    }

    // What a session refuses

    @Test
    fun `a session refuses a walk with no row, a finished walk, and a chain that doesn't decode`() = runTest(dispatcher) {
        val controller = h.newController()
        val session = h.newSession(controller, SpySeekSound("tracker", h.clock, h.ops), MutableSharedFlow())
        val wander = controller.startWalk(WalkStartRequest())
        assertEquals(SeekSessionStart.NotSeek, session.start(backgroundScope, wander.id, controller.state))
        controller.finishWalk()

        val seek = h.startSeekWalk(controller, handOff())
        dao.updateDisplay(h.row(seek.id).let { row ->
            SeekDisplayState(
                walkId = seek.id, chain = "{\"clearings\":[]}", activeIndex = row.activeIndex,
                distanceToActiveMeters = null, fogBucket = null, walkerLatitude = null, walkerLongitude = null,
                pulseToken = 0, pulseAligned = false, pulseCloseness = 0.0, nextPulseDueAt = null,
            )
        })
        assertTrue(session.start(backgroundScope, seek.id, controller.state) is SeekSessionStart.Refused)
        controller.finishWalk()
        assertTrue(session.start(backgroundScope, seek.id, controller.state) is SeekSessionStart.Refused)
        assertEquals("a refused start bumps nothing", 0L, h.row(seek.id).gateGeneration)
    }

    // The chain codec

    @Test
    fun `a chain round-trips through its line of JSON bit for bit`() {
        val chain = SeekChain(
            clearings = listOf(
                SeekClearing(SeekPoint(42.878_212_345_678_9, -8.544_812_345_678_9), radiusMeters = 97.123_456_789),
                SeekClearing(SeekPoint(-89.999_999_9, 179.999_999_9), radiusMeters = 80.0),
            ),
            budgetMeters = 3_141.592_653_589_793,
        )

        assertEquals(chain, SeekChainCodec.decode(SeekChainCodec.encode(chain)))
        assertEquals(SeekChain(emptyList(), 0.0), SeekChainCodec.decode(SeekChainCodec.encode(SeekChain(emptyList(), 0.0))))
    }

    @Test
    fun `text that isn't a chain decodes to nothing`() {
        val clearing = """{"lat":1.0,"lon":2.0,"radius":90.0}"""
        listOf(
            "",
            "not json",
            "[]",
            """{"clearings":[$clearing]}""",
            """{"budget":100.0}""",
            """{"budget":-1.0,"clearings":[$clearing]}""",
            """{"budget":100.0,"clearings":[{"lat":91.0,"lon":2.0,"radius":90.0}]}""",
            """{"budget":100.0,"clearings":[{"lat":1.0,"lon":-181.0,"radius":90.0}]}""",
            """{"budget":100.0,"clearings":[{"lat":1.0,"lon":2.0,"radius":0.0}]}""",
            """{"budget":100.0,"clearings":[{"lat":1.0,"lon":2.0}]}""",
            """{"budget":100.0,"clearings":[{"lat":"north","lon":2.0,"radius":90.0}]}""",
            """{"budget":100.0,"clearings":[null]}""",
            """{"budget":100.0,"clearings":[${List(SeekChainCodec.MAX_CLEARINGS + 1) { clearing }.joinToString(",")}]}""",
        ).forEach { text -> assertNull(text.take(60), SeekChainCodec.decode(text)) }
    }
}
