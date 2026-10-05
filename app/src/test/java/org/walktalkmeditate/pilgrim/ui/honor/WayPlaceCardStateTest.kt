// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.honor

import android.app.Application
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.honor.HonorCardStateEntity
import org.walktalkmeditate.pilgrim.data.honor.HonorMomentStateEntity
import org.walktalkmeditate.pilgrim.data.honor.HonorSessionEntity
import org.walktalkmeditate.pilgrim.data.honor.HonorSourceKind
import org.walktalkmeditate.pilgrim.data.honor.HonorVoiceEnd
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.domain.honor.VoiceKind
import org.walktalkmeditate.pilgrim.domain.honor.Way
import org.walktalkmeditate.pilgrim.domain.honor.WayCoordinate
import org.walktalkmeditate.pilgrim.domain.honor.WayGeometry
import org.walktalkmeditate.pilgrim.domain.honor.WayMedia
import org.walktalkmeditate.pilgrim.domain.honor.WayMoment
import org.walktalkmeditate.pilgrim.domain.honor.WayMomentKind
import org.walktalkmeditate.pilgrim.domain.honor.WayPoint
import org.walktalkmeditate.pilgrim.domain.honor.WaySource
import org.walktalkmeditate.pilgrim.ui.walk.map.WayGlyph
import org.walktalkmeditate.pilgrim.ui.walk.map.WayPinTint
import org.walktalkmeditate.pilgrim.ui.walk.map.wayPins

/**
 * The place-card queue and its cards as pure state (parity spec E §7–§11,
 * D §5): what enters, in what order, what leaves and when, and what a
 * restarted UI rebuilds from Room alone (AE1), with the shared walk's
 * branches (shared spec S4 §10).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WayPlaceCardStateTest {

    private val resources: Resources = ApplicationProvider.getApplicationContext<Application>().resources

    // ---- What enters, and in what order ----------------------------------

    @Test
    fun `two reached places give one card and one more waiting`() {
        val queue = queue(rows = listOf(reached("rest-1", at = T), reached("sit-1", at = T + 1)))

        assertEquals("rest-1" to 1, queue.top to queue.pendingCount)
    }

    @Test
    fun `the indicator reads the full count however many dots it draws`() {
        assertEquals("6 more waiting", resources.getString(R.string.honor_card_more_waiting, "6"))
    }

    @Test
    fun `reached places wait in the order they were reached`() {
        val queue = queue(rows = listOf(reached("sit-1", at = T + 1), reached("rest-1", at = T)))

        assertEquals(listOf("rest-1", "sit-1"), queue.momentIds)
    }

    @Test
    fun `a starting voice goes to the front`() {
        val queue = queue(rows = listOf(reached("rest-1", at = T), started("voice-1", at = T + 5)))

        assertEquals(listOf("voice-1", "rest-1"), queue.momentIds)
    }

    @Test
    fun `a place reached after a voice started waits behind the voice`() {
        val queue = queue(rows = listOf(started("voice-1", at = T), reached("rest-1", at = T + 5)))

        assertEquals(listOf("voice-1", "rest-1"), queue.momentIds)
    }

    @Test
    fun `a queued voice the engine hasn't started has no card`() {
        val queue = queue(rows = listOf(HonorMomentStateEntity(walkId = 1, momentId = "voice-1", reachedAt = T, queuePosition = 0)))

        assertEquals(emptyList<String>(), queue.momentIds)
    }

    @Test
    fun `a tapped pin jumps the queue`() {
        val queue = queue(
            rows = listOf(reached("rest-1", at = T), reached("sit-1", at = T + 1)),
            local = CardTouches(taps = mapOf("sit-1" to T + 3)),
        )

        assertEquals(listOf("sit-1", "rest-1"), queue.momentIds)
    }

    @Test
    fun `a tapped pin opens a card for a moment never reached`() {
        val queue = queue(local = CardTouches(taps = mapOf("voice-2" to T)))

        assertEquals(listOf("voice-2"), queue.momentIds)
    }

    // ---- What leaves ------------------------------------------------------

    @Test
    fun `dismissing the top card advances to the next`() {
        val queue = queue(
            rows = listOf(reached("rest-1", at = T), reached("sit-1", at = T + 1)),
            local = CardTouches(dismissals = mapOf("rest-1" to T + 2)),
        )

        assertEquals(listOf("sit-1"), queue.momentIds)
    }

    @Test
    fun `a pin tapped after a dismissal brings the card back`() {
        val queue = queue(
            rows = listOf(reached("rest-1", at = T)),
            local = CardTouches(dismissals = mapOf("rest-1" to T + 2), taps = mapOf("rest-1" to T + 3)),
        )

        assertEquals(listOf("rest-1"), queue.momentIds)
    }

    @Test
    fun `a voice starting after this UI dismissed its pin-opened card brings it back`() {
        val queue = queue(
            rows = listOf(started("voice-1", at = T + 5)),
            local = CardTouches(taps = mapOf("voice-1" to T), dismissals = mapOf("voice-1" to T + 1)),
        )

        assertEquals(listOf("voice-1"), queue.momentIds)
    }

    @Test
    fun `a dismissal an earlier UI process made stays`() {
        val queue = queue(rows = listOf(reached("rest-1", at = T)), cards = listOf(card("rest-1", dismissedAt = T + 1)))

        assertEquals(emptyList<String>(), queue.momentIds)
    }

    @Test
    fun `an untouched voice card retires 20 s after its voice ended`() {
        val queue = queue(rows = listOf(started("voice-1", at = T, end = HonorVoiceEnd.FINISHED)), now = T + CARD_RETIRE_MILLIS)

        assertEquals(emptyList<String>(), queue.momentIds)
    }

    @Test
    fun `until then it shows, and says when it will go`() {
        val queue = queue(rows = listOf(started("voice-1", at = T, end = HonorVoiceEnd.FINISHED)), now = T + 19_999)

        assertEquals(listOf("voice-1") to T + CARD_RETIRE_MILLIS, queue.momentIds to queue.nextChangeAtMillis)
    }

    @Test
    fun `a failed voice's card retires like a finished one`() {
        val queue = queue(rows = listOf(started("voice-1", at = T, end = HonorVoiceEnd.FAILED)), now = T + CARD_RETIRE_MILLIS)

        assertEquals(emptyList<String>(), queue.momentIds)
    }

    @Test
    fun `a touched voice card never retires`() {
        val queue = queue(
            rows = listOf(started("voice-1", at = T, end = HonorVoiceEnd.FINISHED)),
            cards = listOf(card("voice-1", touched = true)),
            now = T + CARD_RETIRE_MILLIS * 10,
        )

        assertEquals(listOf("voice-1"), queue.momentIds)
    }

    @Test
    fun `a touch made in this UI before its row lands keeps the card too`() {
        val queue = queue(
            rows = listOf(started("voice-1", at = T, end = HonorVoiceEnd.FINISHED)),
            local = CardTouches(touched = setOf("voice-1")),
            now = T + CARD_RETIRE_MILLIS,
        )

        assertEquals(listOf("voice-1"), queue.momentIds)
    }

    @Test
    fun `a skipped voice's card never retires (pilgrim-ios #106, matched)`() {
        val queue = queue(rows = listOf(started("voice-1", at = T, end = HonorVoiceEnd.SKIPPED)), now = T + CARD_RETIRE_MILLIS * 10)

        assertEquals(listOf("voice-1"), queue.momentIds)
    }

    @Test
    fun `dropped, replaced, and reply-interrupted voices' cards never retire either`() {
        val ends = listOf(HonorVoiceEnd.DROPPED, HonorVoiceEnd.REPLACED, HonorVoiceEnd.INTERRUPTED)

        val kept = ends.map { end ->
            queue(rows = listOf(started("voice-1", at = T, end = end)), now = T + CARD_RETIRE_MILLIS * 10).momentIds
        }

        assertEquals(List(ends.size) { listOf("voice-1") }, kept)
    }

    @Test
    fun `a place card never retires on its own`() {
        val queue = queue(rows = listOf(reached("rest-1", at = T)), now = T + CARD_RETIRE_MILLIS * 10)

        assertEquals(listOf("rest-1") to null, queue.momentIds to queue.nextChangeAtMillis)
    }

    @Test
    fun `a voice playing again doesn't retire`() {
        val queue = queue(
            rows = listOf(started("voice-1", at = T, end = HonorVoiceEnd.FINISHED)),
            playing = "voice-1",
            now = T + CARD_RETIRE_MILLIS,
        )

        assertEquals(listOf("voice-1"), queue.momentIds)
    }

    @Test
    fun `a pin tapped after the card retired brings it back for good`() {
        val queue = queue(
            rows = listOf(started("voice-1", at = T, end = HonorVoiceEnd.FINISHED)),
            local = CardTouches(taps = mapOf("voice-1" to T + 25_000)),
            now = T + 60_000,
        )

        assertEquals(listOf("voice-1"), queue.momentIds)
    }

    @Test
    fun `a pin tapped before the card retired doesn't save it`() {
        val queue = queue(
            rows = listOf(started("voice-1", at = T, end = HonorVoiceEnd.FINISHED)),
            local = CardTouches(taps = mapOf("voice-1" to T + 5_000)),
            now = T + CARD_RETIRE_MILLIS,
        )

        assertEquals(emptyList<String>(), queue.momentIds)
    }

    @Test
    fun `a voice that failed to play sits above the voice it handed its turn to (pilgrim-ios #106, matched)`() {
        val queue = queue(
            rows = listOf(
                started("voice-1", at = T, end = HonorVoiceEnd.FAILED_AT_START, endedAt = T + 3),
                started("voice-2", at = T + 3),
            ),
            now = T + 4,
        )

        assertEquals(listOf("voice-1", "voice-2"), queue.momentIds)
    }

    @Test
    fun `a voice that broke off midway stays under the voice that follows it, as a natural end does`() {
        val queue = queue(
            rows = listOf(
                started("voice-1", at = T, end = HonorVoiceEnd.FAILED, endedAt = T + 3),
                started("voice-2", at = T + 3),
            ),
            now = T + 4,
        )

        assertEquals(listOf("voice-2", "voice-1"), queue.momentIds)
    }

    @Test
    fun `a dismissed voice card stays gone when its voice breaks off`() {
        val queue = queue(
            rows = listOf(started("voice-1", at = T, end = HonorVoiceEnd.FAILED, endedAt = T + 5)),
            local = CardTouches(dismissals = mapOf("voice-1" to T + 2)),
            now = T + 6,
        )

        assertEquals(emptyList<String>(), queue.momentIds)
    }

    @Test
    fun `a failed start never undoes a dismissal made after its voice started`() {
        val queue = queue(
            rows = listOf(started("voice-1", at = T, end = HonorVoiceEnd.FAILED_AT_START, endedAt = T + 5)),
            local = CardTouches(dismissals = mapOf("voice-1" to T + 2)),
            now = T + 6,
        )

        assertEquals(emptyList<String>(), queue.momentIds)
    }

    // ---- Dismissed, then raised again (iOS appends a reached place) --------

    @Test
    fun `a place tapped ahead and dismissed still rises when it is reached, behind the cards before it`() {
        val queue = queue(
            rows = listOf(reached("sit-1", at = T + 1), reached("rest-1", at = T + 5)),
            local = CardTouches(taps = mapOf("rest-1" to T), dismissals = mapOf("rest-1" to T + 2)),
        )

        assertEquals(listOf("sit-1", "rest-1"), queue.momentIds)
    }

    @Test
    fun `a pin tapped before a dismissal is spent by it`() {
        val queue = queue(local = CardTouches(taps = mapOf("rest-1" to T), dismissals = mapOf("rest-1" to T + 2)))

        assertEquals(emptyList<String>(), queue.momentIds)
    }

    @Test
    fun `after a restart, a place dismissed ahead of its reach rises when it is reached`() {
        val queue = queue(rows = listOf(reached("rest-1", at = T + 5)), cards = listOf(card("rest-1", dismissedAt = T + 2)))

        assertEquals(listOf("rest-1"), queue.momentIds)
    }

    @Test
    fun `after a restart, a voice played again after its card was dismissed shows again`() {
        val queue = queue(rows = listOf(started("voice-1", at = T + 5)), cards = listOf(card("voice-1", dismissedAt = T + 2)))

        assertEquals(listOf("voice-1"), queue.momentIds)
    }

    @Test
    fun `after a restart, a pin tapped since the dismissal brings the card back`() {
        val queue = queue(
            rows = listOf(reached("rest-1", at = T)),
            cards = listOf(card("rest-1", dismissedAt = T + 2)),
            local = CardTouches(taps = mapOf("rest-1" to T + 4)),
        )

        assertEquals(listOf("rest-1"), queue.momentIds)
    }

    // ---- Rebuilt after a UI restart, from Room alone (AE1) -----------------

    @Test
    fun `rebuilt after a restart, the cards still in their window are the ones left`() {
        val queue = queue(
            rows = listOf(
                started("voice-1", at = T - 60_000, end = HonorVoiceEnd.FINISHED, endedAt = T - 30_000),
                reached("rest-1", at = T - 40_000),
                started("voice-2", at = T - 10_000, end = HonorVoiceEnd.FINISHED, endedAt = T - 5_000),
                reached("sit-1", at = T - 1_000),
            ),
            cards = listOf(card("rest-1", dismissedAt = T - 35_000)),
            now = T,
        )

        assertEquals(listOf("voice-2", "sit-1"), queue.momentIds)
    }

    @Test
    fun `rebuilt after a restart, heard voices' pins are stone`() {
        val rows = listOf(started("voice-1", at = T))

        val pins = wayPins(way(), heardVoiceIds = rows.filter { it.heard }.mapTo(mutableSetOf()) { it.momentId })

        assertEquals(
            listOf(WayPinTint.STONE, WayPinTint.FOG),
            pins.filter { it.momentId.startsWith("voice-") }.map { it.tint },
        )
    }

    @Test
    fun `rebuilt after a restart, the chip reads the persisted voice and its offsets`() {
        val session = session().copy(
            playingMomentId = "voice-1",
            voiceStartedAt = T,
            voiceStartOffsetMillis = 2_000,
            voiceRate = 1.5,
        )

        val view = HonorVoiceView.of(session)

        assertEquals("voice-1" to 5_000L, view.playingMomentId to view.positionMillis(T + 2_000))
    }

    @Test
    fun `rebuilt after a restart, a paused voice stands at its pause offset`() {
        val session = session().copy(playingMomentId = "voice-1", voicePaused = true, voicePauseOffsetMillis = 7_000)

        assertEquals(7_000L, HonorVoiceView.of(session).positionMillis(T + 60_000))
    }

    @Test
    fun `rebuilt after a restart, a landed arrival card counts only what came before it`() {
        val summary = HonorArrival.summary(
            way = way(),
            rows = listOf(
                started("voice-1", at = T - 9_000),
                reached("rest-1", at = T - 1_000),
                reached("sit-1", at = T + 1_000),
                started("voice-2", at = T + 2_000),
            ),
            arrivedAtMillis = T,
        )

        assertEquals(HonorArrivalSummary("the long way", voicesHeard = 1, placesPassed = 1), summary)
    }

    // ---- A command's result, until Room answers ----------------------------

    @Test
    fun `a command's result holds while the session row reads as it did`() {
        val persisted = HonorVoiceView.of(session().copy(playingMomentId = "voice-1", voiceStartedAt = T))
        val pending = PendingVoiceCommand(persisted.pausedAt(T + 1_000), baseline = persisted, sentAtMillis = T + 1_000)

        assertEquals(pending, pending.heldOver(persisted, nowMillis = T + 2_000))
    }

    @Test
    fun `Room's answer wins over a command's result`() {
        val persisted = HonorVoiceView.of(session().copy(playingMomentId = "voice-1", voiceStartedAt = T))
        val pending = PendingVoiceCommand(persisted.pausedAt(T + 1_000), baseline = persisted, sentAtMillis = T + 1_000)

        assertNull(pending.heldOver(persisted.released(), nowMillis = T + 1_500))
    }

    @Test
    fun `an unanswered command gives way after the window`() {
        val persisted = HonorVoiceView.of(session().copy(playingMomentId = "voice-1", voiceStartedAt = T))
        val pending = PendingVoiceCommand(persisted.pausedAt(T), baseline = persisted, sentAtMillis = T)

        assertNull(pending.heldOver(persisted, nowMillis = T + COMMAND_CONFIRM_WINDOW_MILLIS))
    }

    // ---- The card's words and numbers (E §8–§11) ---------------------------

    @Test
    fun `under 30 m the subline reads here`() {
        assertEquals("here", subline(distance = 29.9, place = null))
    }

    @Test
    fun `further off it reads the distance away, in metres up to a kilometre`() {
        assertEquals("120 m away", subline(distance = 120.4, place = null))
    }

    @Test
    fun `past a kilometre it reads one decimal`() {
        assertEquals("1.2 km away", subline(distance = 1_234.0, place = null))
    }

    @Test
    fun `in miles it reads feet up to a tenth of a mile`() {
        assertEquals("328 ft away" to "0.5 mi away", subline(100.0, null, UnitSystem.Imperial) to subline(805.0, null, UnitSystem.Imperial))
    }

    @Test
    fun `with no fix and no place there is no subline`() {
        assertNull(subline(distance = null, place = null))
    }

    @Test
    fun `the tick is clockwise from the heading, kept positive`() {
        val here = WayCoordinate(0.0, 0.0)
        val there = WayCoordinate(0.0, 0.01)

        assertEquals(330.0, WayRelation.tick(here, headingDegrees = 120.0, there = there)!!, 1e-6)
    }

    @Test
    fun `the arrival line counts voices then places`() {
        val line = HonorArrivalCopy.line(resources, HonorArrivalSummary("a way", voicesHeard = 1, placesPassed = 3))

        assertEquals("one voice heard · 3 places passed", line)
    }

    @Test
    fun `with nothing heard or passed the arrival line is the whole way`() {
        val line = HonorArrivalCopy.line(resources, HonorArrivalSummary("a way", voicesHeard = 0, placesPassed = 0))

        assertEquals("the whole way, in their steps", line)
    }

    @Test
    fun `the soft-tap caption truncates to whole metres and caps at 999,999`() {
        assertEquals(listOf(250L, 999_999L, 0L), listOf(250.9, 2e9, Double.POSITIVE_INFINITY).map(::softTapCaptionMeters))
    }

    @Test
    fun `the soft-tap caption is always metres (pilgrim-ios #109, matched)`() {
        assertEquals("off the way · 250 m", resources.getString(R.string.honor_soft_tap_caption, "250"))
    }

    @Test
    fun `the meditation caption has its singular, unlike the card's kicker`() {
        val one = resources.getString(R.string.honor_meditation_they_sat_one, "1")
        val zero = resources.getString(R.string.honor_meditation_they_sat, "0")

        assertEquals("they sat here 1 minute" to "they sat here 0 minutes", one to zero)
    }

    @Test
    fun `the compass reports a new heading only once it has turned 3 degrees`() {
        val filter = HeadingFilter()

        val published = listOf(10.0, 12.0, 13.0, 15.5, 359.0, 1.0).map(filter::next)

        assertEquals(listOf(10.0, null, 13.0, null, 359.0, null), published)
    }

    // ---- Shared walks (shared spec S4 §10) ---------------------------------

    @Test
    fun `a shared voice's subline appends the street name`() {
        assertEquals(
            listOf("here · Rúa do Franco", "120 m away · Rúa do Franco", "Rúa do Franco"),
            listOf(10.0, 120.0, null).map { subline(it, place = "Rúa do Franco") },
        )
    }

    @Test
    fun `a shared estimated sitting reads about on the card`() {
        assertEquals("they sat here about 12 minutes", kicker(WayMomentKind.Meditation(minutes = 12, isEstimate = true)))
    }

    @Test
    fun `a shared rest with no length reads 0 minutes`() {
        assertEquals("they rested here 0 minutes", kicker(WayMomentKind.Rest(minutes = 0)))
    }

    @Test
    fun `an unlabelled shared waypoint keeps iOS's empty kicker line`() {
        val waypoint = WayMoment("m", 0.5, null, WayMomentKind.Waypoint(label = "", icon = ""))

        assertEquals("", WayMomentCopy.kicker(resources, waypoint, keepsEmpty = true))
    }

    @Test
    fun `the walker's own unlabelled waypoint has no kicker (owner decision 4)`() {
        assertNull(kicker(WayMomentKind.Waypoint(label = "", icon = "")))
    }

    @Test
    fun `a shared waypoint's unknown icon draws mappin`() {
        val moment = WayMoment("waypoint-1", 0.3, null, WayMomentKind.Waypoint(label = "gate", icon = "sun.haze.fill"))

        assertEquals(WayGlyph.Waypoint("mappin"), WayGlyph.header(moment))
    }

    @Test
    fun `a shared waypoint's body is A place they marked`() {
        val moment = WayMoment("waypoint-1", 0.3, null, WayMomentKind.Waypoint(label = "", icon = "mappin"))

        assertEquals("A place they marked.", WayMomentCopy.placeCopy(resources, moment, isStage = false))
    }

    @Test
    fun `a shared voice with no length reads 0 00 over 0 00`() {
        val voice = WayMoment("voice-1", 0.2, null, WayMomentKind.Voice(0.3, 0.0, VoiceKind.SPOKEN, WayMedia.File("audio/0.m4a")))

        assertEquals(
            "0:00 / 0:00",
            resources.getString(R.string.honor_card_clock, WayMomentCopy.clock(0.0), WayMomentCopy.clock(voice.voiceDurationSeconds)),
        )
    }

    // ---- Harness ----------------------------------------------------------

    private fun queue(
        rows: List<HonorMomentStateEntity> = emptyList(),
        cards: List<HonorCardStateEntity> = emptyList(),
        local: CardTouches = CardTouches(),
        playing: String? = null,
        now: Long = T + 10,
    ) = HonorCards.queue(way(), rows, cards, local, playing, now)

    private fun subline(distance: Double?, place: String?, units: UnitSystem = UnitSystem.Metric): String? =
        WayRelation.subline(
            distanceMeters = distance,
            place = place,
            units = units,
            here = resources.getString(R.string.honor_card_here),
            away = { resources.getString(R.string.honor_card_away, it) },
        )

    private fun kicker(kind: WayMomentKind): String? = WayMomentCopy.kicker(resources, WayMoment("m", 0.5, null, kind))

    private fun reached(momentId: String, at: Long) = HonorMomentStateEntity(walkId = 1, momentId = momentId, reachedAt = at)

    private fun started(momentId: String, at: Long, end: HonorVoiceEnd? = null, endedAt: Long? = null) =
        HonorMomentStateEntity(
            walkId = 1,
            momentId = momentId,
            reachedAt = at,
            voiceStartedAt = at,
            voiceEndedAt = endedAt ?: at.takeIf { end != null },
            voiceEnd = end,
            heard = true,
        )

    private fun card(momentId: String, dismissedAt: Long? = null, touched: Boolean = false) =
        HonorCardStateEntity(walkId = 1, momentId = momentId, dismissedAtMillis = dismissedAt ?: 0, touched = touched)

    private fun session() = HonorSessionEntity(
        walkId = 1,
        wayId = "walk:0e8d6f8a-5b1c-4f1e-9a53-2f1d8c7b6a50",
        sourceKind = HonorSourceKind.OWN_WALK,
        voicesEnabled = true,
        softTapEnabled = false,
    )

    private val route = (0..10).map { i -> WayPoint(lat = 0.0, lon = i * 0.001, alt = null, t = i * 60.0) }

    private fun way() = Way(
        id = "walk:0e8d6f8a-5b1c-4f1e-9a53-2f1d8c7b6a50",
        source = WaySource.OwnWalk("0e8d6f8a-5b1c-4f1e-9a53-2f1d8c7b6a50"),
        title = "the long way",
        departedAt = Instant.ofEpochSecond(1_700_000_000),
        tzIdentifier = "UTC",
        expires = null,
        route = route,
        totalDistanceMeters = WayGeometry(route).totalMeters,
        theirActiveSeconds = 600.0,
        moments = listOf(
            WayMoment(
                id = "voice-1", frac = 0.2, at = null,
                kind = WayMomentKind.Voice(0.25, 20.0, VoiceKind.SPOKEN, WayMedia.Recording("recordings/v1.wav")),
            ),
            WayMoment(id = "rest-1", frac = 0.4, at = null, kind = WayMomentKind.Rest(minutes = 4)),
            WayMoment(
                id = "voice-2", frac = 0.6, at = null,
                kind = WayMomentKind.Voice(0.65, 20.0, VoiceKind.SPOKEN, WayMedia.Recording("recordings/v2.wav")),
            ),
            WayMoment(id = "sit-1", frac = 0.8, at = null, kind = WayMomentKind.Meditation(minutes = 6, isEstimate = false)),
        ),
        weather = null,
    )

    private companion object {
        const val T = 1_700_100_000_000L
    }
}
