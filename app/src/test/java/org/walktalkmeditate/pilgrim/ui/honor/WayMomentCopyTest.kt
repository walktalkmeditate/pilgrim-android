// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.honor

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.time.Instant
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.domain.honor.VoiceKind
import org.walktalkmeditate.pilgrim.domain.honor.Way
import org.walktalkmeditate.pilgrim.domain.honor.WayCoordinate
import org.walktalkmeditate.pilgrim.domain.honor.WayMedia
import org.walktalkmeditate.pilgrim.domain.honor.WayMoment
import org.walktalkmeditate.pilgrim.domain.honor.WayMomentKind
import org.walktalkmeditate.pilgrim.domain.honor.WayPoint
import org.walktalkmeditate.pilgrim.domain.honor.WaySource
import org.walktalkmeditate.pilgrim.domain.honor.WayStage
import org.walktalkmeditate.pilgrim.domain.honor.WayStageHours
import org.walktalkmeditate.pilgrim.domain.honor.WayStagePlace

/** A moment preview's words (parity spec F §14.2–§14.3), and a stage's (pilgrimage-stage spec P4 §8). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WayMomentCopyTest {

    private val resources = ApplicationProvider.getApplicationContext<Context>().resources

    private fun moment(kind: WayMomentKind, frac: Double = 0.0) =
        WayMoment(id = "m-1", frac = frac, at = null, kind = kind)

    private fun kicker(kind: WayMomentKind) = WayMomentCopy.kicker(resources, moment(kind))

    private fun way(tz: String?) = Way(
        id = "walk:0e8d6f8a-5b1c-4f1e-9a53-2f1d8c7b6a50",
        source = WaySource.OwnWalk("0e8d6f8a-5b1c-4f1e-9a53-2f1d8c7b6a50"),
        title = "t",
        // 2023-11-14 22:13:20 UTC
        departedAt = Instant.ofEpochMilli(1_700_000_000_000L),
        tzIdentifier = tz,
        expires = null,
        route = (0..10).map { i -> WayPoint(lat = 0.0, lon = i * 0.001, alt = null, t = i * 60.0) },
        totalDistanceMeters = 1_000.0,
        theirActiveSeconds = 600.0,
        moments = emptyList(),
        weather = null,
    )

    @Test
    fun `each kind's kicker is iOS's`() {
        val voice = { kind: VoiceKind -> WayMomentKind.Voice(0.2, 5.0, kind, WayMedia.Recording("r.wav")) }

        assertEquals("spoken here", kicker(voice(VoiceKind.SPOKEN)))
        assertEquals("the sound of this place", kicker(voice(VoiceKind.AMBIENT)))
        assertEquals("what they saw here", kicker(WayMomentKind.Photo(WayMedia.PhotoAsset("content://p"))))
        assertEquals("they sat here for 5 minutes", kicker(WayMomentKind.Meditation(5, isEstimate = false)))
        assertEquals("they sat here about 5 minutes", kicker(WayMomentKind.Meditation(5, isEstimate = true)))
        assertEquals("the bench", kicker(WayMomentKind.Waypoint("the bench", "leaf")))
        assertNull("owner decision 4: no label, no kicker", kicker(WayMomentKind.Waypoint("", "leaf")))
    }

    // pilgrim-ios #109, matched: "minutes" even for one.
    @Test
    fun `a one-minute rest still reads minutes`() {
        assertEquals("they rested here 1 minutes", kicker(WayMomentKind.Rest(1)))
    }

    // pilgrim-ios #110, matched: the hour is in the Way's tzIdentifier, the
    // zone the phone was in when the Way was built, not the walk's own.
    @Test
    fun `the subline reads the distance along their way and the hour in the Way's zone`() {
        val halfway = moment(WayMomentKind.Rest(4), frac = 0.5)

        fun subline(zone: String) =
            WayMomentCopy.subline(resources, way(zone), halfway, UnitSystem.Metric, Locale.US).replace('\u202F', ' ')

        assertEquals("0.50 km along their way · 7:18 AM", subline("Asia/Tokyo"))
        assertEquals("0.50 km along their way · 10:18 PM", subline("UTC"))
    }

    @Test
    fun `clocks are m colon ss and speeds read 1x, 1_5x, 2x`() {
        assertEquals("0:30", WayMomentCopy.clock(30.9))
        assertEquals("2:05", WayMomentCopy.clock(125.0))
        assertEquals("0:00", WayMomentCopy.clock(-3.0))
        assertEquals(listOf("1x", "1.5x", "2x"), listOf(1f, 1.5f, 2f).map(WayMomentCopy::speedLabel))
    }

    @Test
    fun `a waypoint with no text of its own is a place they marked`() {
        val unmarked = moment(WayMomentKind.Waypoint("", "leaf"))
        assertEquals("A place they marked.", WayMomentCopy.placeCopy(resources, unmarked, isStage = false))
        assertEquals(
            "the old gate",
            WayMomentCopy.placeCopy(
                resources,
                moment(WayMomentKind.Waypoint("", "leaf")).copy(text = "the old gate"),
                isStage = false,
            ),
        )
    }

    // ---- A pilgrimage stage (pilgrimage-stage spec P4 §8) ------------------
    //
    // The first two are iOS `PilgrimageStageWalkTests.swift@7c200bf`'s,
    // names kept, on its `waypoint(names:label:text:sitMinutes:)` builder.

    private fun waypoint(names: Map<String, String>?, label: String = "Vierge d'Orisson", text: String? = null) =
        WayMoment(
            id = "wp-x",
            frac = 0.3,
            at = WayCoordinate(lat = 0.0, lon = 0.0),
            kind = WayMomentKind.Waypoint(label = label, icon = "building.columns"),
            names = names,
            text = text,
        )

    @Test
    fun testTheLocalNameFollowsAFixedOrderAndNeverEchoesTheLabel() {
        fun localName(names: Map<String, String>?) = WayMomentCopy.localName(resources, waypoint(names))

        assertEquals(
            "eu comes first",
            "Orissongo Ama Birjina",
            localName(mapOf("es" to "Virgen de Orisson", "eu" to "Orissongo Ama Birjina", "fr" to "Vierge d'Orisson")),
        )
        assertEquals(
            "the French name is the label; es is next in order",
            "Virgen de Orisson",
            localName(mapOf("es" to "Virgen de Orisson", "fr" to "Vierge d'Orisson")),
        )
        assertNull("the only local name is the label itself", localName(mapOf("fr" to "Vierge d'Orisson")))
        assertNull(localName(null))
        assertNull("a language outside the order is not shown", localName(mapOf("ru" to "Орисон")))
        assertEquals(
            "English is never the local name",
            "Virgen de Orisson",
            localName(mapOf("en" to "Virgin of Orisson", "es" to "Virgen de Orisson")),
        )
        assertNull(localName(mapOf("en" to "Virgin of Orisson")))
    }

    @Test
    fun testThePlaceCopyChangesForAStage() {
        assertEquals("A place on the way.", WayMomentCopy.placeCopy(resources, waypoint(names = null), isStage = true))
        assertEquals("A place they marked.", WayMomentCopy.placeCopy(resources, waypoint(names = null), isStage = false))
        assertEquals(
            "A shepherd carried this Madonna.",
            WayMomentCopy.placeCopy(resources, waypoint(names = null, text = "A shepherd carried this Madonna."), isStage = true),
        )
    }

    // P4 §8.1, owner decision 7: no hour, the stage's own words, and iOS's numbers, never metres.
    @Test
    fun `a stage's subline reads the distance along the stage, with no hour`() {
        val stage = way(tz = "Europe/Madrid").copy(
            id = "pilgrimage:camino-frances:0",
            source = WaySource.Pilgrimage(routeId = "camino-frances", stageIndex = 0),
            totalDistanceMeters = 24_000.0,
            stage = WayStage(
                routeId = "camino-frances", index = 0, count = 33, name = "n", theme = "t", narrative = "n",
                closing = "c", warnings = emptyList(), distanceKm = 24.2, gainMeters = 1419.0,
                hours = WayStageHours(7.0, 9.0), difficulty = "hard",
                start = WayStagePlace("a", WayCoordinate(0.0, 0.0)), end = WayStagePlace("b", WayCoordinate(0.0, 0.01)),
            ),
        )

        fun subline(frac: Double, units: UnitSystem = UnitSystem.Metric) =
            WayMomentCopy.subline(resources, stage, moment(WayMomentKind.Waypoint("Orisson", "seal"), frac), units, Locale.US)

        assertEquals("1.2 km along the stage", subline(0.05))
        assertEquals("0.05 km along the stage", subline(50.0 / 24_000))
        assertEquals("0.75 mi along the stage", subline(0.05, UnitSystem.Imperial))
    }
}
