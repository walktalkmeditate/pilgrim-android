// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import android.app.Application
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import java.time.Instant
import java.time.ZoneId
import kotlin.math.nextUp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.data.entity.WalkEvent
import org.walktalkmeditate.pilgrim.data.entity.WalkPhoto
import org.walktalkmeditate.pilgrim.data.entity.Waypoint
import org.walktalkmeditate.pilgrim.data.walk.WalkMapAnnotationKind
import org.walktalkmeditate.pilgrim.data.walk.computeWalkMapAnnotations
import org.walktalkmeditate.pilgrim.domain.WalkEventType
import org.walktalkmeditate.pilgrim.domain.seek.SeekChainGenerator
import org.walktalkmeditate.pilgrim.domain.seek.SeekPersistence
import org.walktalkmeditate.pilgrim.domain.seek.SeekPoint
import org.walktalkmeditate.pilgrim.domain.seek.SeekSkyLight

/**
 * Port of iOS `SeekSummaryTests.swift@c1745e8`. Robolectric because the
 * unknowns-found / signs-line / provenance strings resolve through real
 * resources — the asserted English values are the cross-platform contract
 * shared with iOS's `NSLocalizedString` defaults. Port spec
 * `docs/parity/2026-07-14-port-seek-summary-u11.md` § 12.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SeekSummaryModelTest {

    private val resources: Resources =
        ApplicationProvider.getApplicationContext<Application>().resources

    private val home = SeekPoint(48.8566, 2.3522)
    private val walkStart = Instant.parse("2024-06-15T09:00:00Z").toEpochMilli()
    private val equator = SeekPoint(0.0, 0.0)
    private val noonUtcAtEquinox = Instant.parse("2024-03-20T12:00:00Z").toEpochMilli()
    private val midnightUtcAtEquinox = Instant.parse("2024-03-20T00:00:00Z").toEpochMilli()
    private val dawnUtcAtEquinox = Instant.parse("2024-03-20T06:00:00Z").toEpochMilli()

    private fun point(bearing: Double, meters: Double, from: SeekPoint = home): SeekPoint =
        SeekChainGenerator.destination(from, bearingDegrees = bearing, distanceMeters = meters)

    private fun minutesIn(minutes: Int): Long = walkStart + minutes * 60_000L

    private fun arrival(ordinal: Int, center: SeekPoint, minutesIn: Int) =
        SeekSummaryModel.Arrival(
            label = SeekPersistence.arrivalWaypointLabel(resources, ordinal),
            center = center,
            arrivedAtEpochMs = minutesIn(minutesIn),
        )

    private fun photoSign(id: String, at: SeekPoint?, minutesIn: Int) =
        SeekSummaryModel.Sign(
            kind = SeekSummaryModel.Sign.Kind.PHOTO,
            id = id,
            coordinate = at,
            timestampEpochMs = minutesIn(minutesIn),
        )

    private fun voiceSign(id: String, at: SeekPoint?, minutesIn: Int) =
        SeekSummaryModel.Sign(
            kind = SeekSummaryModel.Sign.Kind.VOICE_RECORDING,
            id = id,
            coordinate = at,
            timestampEpochMs = minutesIn(minutesIn),
        )

    // --- Seek detection + nil paths -----------------------------------

    @Test
    fun `isSeekWalk detects the SEEK_MODE event`() {
        assertTrue(SeekSummaryModel.isSeekWalk(listOf(WalkEventType.SEEK_MODE)))
        assertTrue(
            SeekSummaryModel.isSeekWalk(
                listOf(WalkEventType.UNKNOWN, WalkEventType.SEEK_MODE, WalkEventType.SEEK_ARRIVAL),
            ),
        )
        assertFalse(SeekSummaryModel.isSeekWalk(emptyList()))
        assertFalse(
            SeekSummaryModel.isSeekWalk(
                listOf(WalkEventType.PAUSED, WalkEventType.SEEK_ARRIVAL),
            ),
        )
    }

    @Test
    fun `wander walk yields no model`() {
        val data = SeekSummaryModel.summaryData(
            events = emptyList(),
            arrivals = listOf(arrival(1, home, 10)),
            signs = emptyList(),
        )
        assertNull(data)
    }

    @Test
    fun `zero arrivals yields no model`() {
        val data = SeekSummaryModel.summaryData(
            events = listOf(WalkEventType.SEEK_MODE),
            arrivals = emptyList(),
            signs = listOf(photoSign("p1", home, 5)),
        )
        assertNull(data)
    }

    // --- Grouping ------------------------------------------------------

    @Test
    fun `two clearings group signs and strays`() {
        val clearing1 = point(bearing = 90.0, meters = 500.0)
        val clearing2 = point(bearing = 90.0, meters = 1500.0)
        val data = SeekSummaryModel.summaryData(
            events = listOf(WalkEventType.SEEK_MODE, WalkEventType.SEEK_ARRIVAL, WalkEventType.SEEK_ARRIVAL),
            arrivals = listOf(
                arrival(1, clearing1, 15),
                arrival(2, clearing2, 35),
            ),
            signs = listOf(
                photoSign("at-first", clearing1, 16),
                photoSign("at-second", point(bearing = 0.0, meters = 20.0, from = clearing2), 36),
                photoSign("mid-route", point(bearing = 90.0, meters = 1000.0), 25),
            ),
        )

        assertNotNull(data)
        assertEquals(2, data!!.groups.size)
        assertEquals(listOf("at-first"), data.groups[0].photoIds)
        assertEquals(listOf("at-second"), data.groups[1].photoIds)
        assertEquals(listOf("mid-route"), data.alongTheWay.photoIds)
    }

    @Test
    fun `grouping boundary is inclusive at exactly 80m`() {
        assertTrue(SeekSummaryModel.belongsToClearing(SeekSummaryModel.GROUPING_RADIUS_METERS))
        assertFalse(
            SeekSummaryModel.belongsToClearing(SeekSummaryModel.GROUPING_RADIUS_METERS.nextUp()),
        )
    }

    @Test
    fun `79m photo groups to the clearing, 81m photo strays`() {
        val clearing = point(bearing = 90.0, meters = 500.0)
        val data = SeekSummaryModel.summaryData(
            events = listOf(WalkEventType.SEEK_MODE),
            arrivals = listOf(arrival(1, clearing, 15)),
            signs = listOf(
                photoSign("just-inside", point(bearing = 0.0, meters = 79.0, from = clearing), 16),
                photoSign("just-outside", point(bearing = 0.0, meters = 81.0, from = clearing), 17),
            ),
        )

        assertEquals(listOf("just-inside"), data!!.groups[0].photoIds)
        assertEquals(listOf("just-outside"), data.alongTheWay.photoIds)
    }

    @Test
    fun `sign groups to the nearest clearing`() {
        val clearing1 = point(bearing = 90.0, meters = 500.0)
        val clearing2 = point(bearing = 90.0, meters = 620.0)
        val nearSecond = point(bearing = 90.0, meters = 590.0)
        val data = SeekSummaryModel.summaryData(
            events = listOf(WalkEventType.SEEK_MODE),
            arrivals = listOf(
                arrival(1, clearing1, 15),
                arrival(2, clearing2, 30),
            ),
            signs = listOf(photoSign("between", nearSecond, 31)),
        )

        assertEquals(emptyList<String>(), data!!.groups[0].photoIds)
        assertEquals(listOf("between"), data.groups[1].photoIds)
    }

    // --- Timestamp fallback (coordinate-less signs) ---------------------

    @Test
    fun `coordinate-less voice within 5min groups to preceding arrival`() {
        val data = SeekSummaryModel.summaryData(
            events = listOf(WalkEventType.SEEK_MODE),
            arrivals = listOf(arrival(1, point(bearing = 90.0, meters = 500.0), 15)),
            signs = listOf(voiceSign("v1", null, 17)),
        )

        assertEquals(listOf("v1"), data!!.groups[0].voiceRecordingIds)
        assertTrue(data.alongTheWay.isEmpty)
    }

    @Test
    fun `coordinate-less voice outside 5min strays`() {
        val data = SeekSummaryModel.summaryData(
            events = listOf(WalkEventType.SEEK_MODE),
            arrivals = listOf(arrival(1, point(bearing = 90.0, meters = 500.0), 15)),
            signs = listOf(voiceSign("v-late", null, 25)),
        )

        assertEquals(emptyList<String>(), data!!.groups[0].voiceRecordingIds)
        assertEquals(listOf("v-late"), data.alongTheWay.voiceRecordingIds)
    }

    @Test
    fun `coordinate-less voice before first arrival strays`() {
        val data = SeekSummaryModel.summaryData(
            events = listOf(WalkEventType.SEEK_MODE),
            arrivals = listOf(arrival(1, point(bearing = 90.0, meters = 500.0), 15)),
            signs = listOf(voiceSign("v-early", null, 5)),
        )

        assertEquals(listOf("v-early"), data!!.alongTheWay.voiceRecordingIds)
    }

    // --- Ordering --------------------------------------------------------

    @Test
    fun `groups sort by arrival time with ordinals reassigned`() {
        val laterCenter = point(bearing = 90.0, meters = 1500.0)
        val earlierCenter = point(bearing = 90.0, meters = 500.0)
        val data = SeekSummaryModel.summaryData(
            events = listOf(WalkEventType.SEEK_MODE),
            arrivals = listOf(
                arrival(2, laterCenter, 35),
                arrival(1, earlierCenter, 15),
            ),
            signs = emptyList(),
        )

        assertEquals(listOf(1, 2), data!!.groups.map { it.ordinal })
        assertEquals(earlierCenter, data.groups.first().center)
        assertEquals("First clearing", data.groups.first().label)
    }

    // --- Found under (the hour's light) ---------------------------------

    @Test
    fun `foundUnderDaypart reads the sky at the place and moment`() {
        assertEquals(
            SeekSkyLight.Daypart.MIDDAY,
            SeekSummaryModel.foundUnderDaypart(equator, noonUtcAtEquinox),
        )
        assertEquals(
            SeekSkyLight.Daypart.NIGHT,
            SeekSummaryModel.foundUnderDaypart(equator, midnightUtcAtEquinox),
        )
        assertEquals(
            SeekSkyLight.Daypart.GOLDEN,
            SeekSummaryModel.foundUnderDaypart(equator, dawnUtcAtEquinox),
        )
    }

    @Test
    fun `clearing groups carry their found-under light`() {
        val data = SeekSummaryModel.summaryData(
            events = listOf(WalkEventType.SEEK_MODE, WalkEventType.SEEK_ARRIVAL),
            arrivals = listOf(
                SeekSummaryModel.Arrival(
                    label = "First clearing",
                    center = equator,
                    arrivedAtEpochMs = noonUtcAtEquinox,
                ),
            ),
            signs = emptyList(),
        )
        assertEquals(SeekSkyLight.Daypart.MIDDAY, data!!.groups.first().foundUnder)
    }

    @Test
    fun `found-under captions match iOS copy`() {
        assertEquals(
            "Found in the golden hour",
            foundUnderText(resources, SeekSkyLight.Daypart.GOLDEN),
        )
        assertEquals(
            "Found in broad daylight",
            foundUnderText(resources, SeekSkyLight.Daypart.MIDDAY),
        )
        assertEquals(
            "Found under the night sky",
            foundUnderText(resources, SeekSkyLight.Daypart.NIGHT),
        )
    }

    // --- Unknowns found (R19: never totals, never "X of Y") --------------

    @Test
    fun `unknowns text spells one two three exactly`() {
        assertEquals("One unknown found", SeekSummaryModel.unknownsFoundText(resources, 1))
        assertEquals("Two unknowns found", SeekSummaryModel.unknownsFoundText(resources, 2))
        assertEquals("Three unknowns found", SeekSummaryModel.unknownsFoundText(resources, 3))
    }

    @Test
    fun `unknowns text falls back to the count format beyond three`() {
        assertEquals("4 unknowns found", SeekSummaryModel.unknownsFoundText(resources, 4))
        assertEquals("12 unknowns found", SeekSummaryModel.unknownsFoundText(resources, 12))
    }

    @Test
    fun `unknowns text never phrases a total`() {
        for (count in 1..4) {
            val text = SeekSummaryModel.unknownsFoundText(resources, count)
            assertFalse("R19 forbids 'X of Y' phrasing, got: $text", text.contains("of "))
        }
    }

    // --- Signs line -------------------------------------------------------

    @Test
    fun `signs line joins fragments with a middle dot in photos-voices-marks order`() {
        assertEquals(
            "a photo · 2 voice notes · a mark",
            signsLine(resources, photos = 1, voices = 2, marks = 1),
        )
        assertEquals(
            "3 photos · a voice note",
            signsLine(resources, photos = 3, voices = 1, marks = 0),
        )
        assertEquals("2 marks", signsLine(resources, photos = 0, voices = 0, marks = 2))
        assertNull(signsLine(resources, photos = 0, voices = 0, marks = 0))
    }

    // --- Seed keepsake (provenance) ---------------------------------------

    @Test
    fun `summaryData carries the gateway moment and intention presence`() {
        val seededAt = walkStart - 30_000L
        val data = SeekSummaryModel.summaryData(
            events = listOf(WalkEventType.SEEK_MODE, WalkEventType.SEEK_ARRIVAL),
            arrivals = listOf(arrival(1, home, 10)),
            signs = emptyList(),
            seededAtEpochMs = seededAt,
            intentionWasVoiced = true,
        )
        assertEquals(seededAt, data!!.seededAtEpochMs)
        assertTrue(data.intentionWasVoiced)
    }

    @Test
    fun `defaults leave the keepsake silent`() {
        val data = SeekSummaryModel.summaryData(
            events = listOf(WalkEventType.SEEK_MODE),
            arrivals = listOf(arrival(1, home, 10)),
            signs = emptyList(),
        )
        assertNull("no gateway moment, no keepsake line", data!!.seededAtEpochMs)
    }

    @Test
    fun `seeded line phrases intention voiced vs quiet`() {
        val utc = ZoneId.of("UTC")
        assertEquals(
            "The way was shaped by your intention and the moment you set out — 9:00 AM.",
            seededLine(resources, walkStart, intentionWasVoiced = true, zoneId = utc),
        )
        assertEquals(
            "The way was shaped by the moment you set out — 9:00 AM.",
            seededLine(resources, walkStart, intentionWasVoiced = false, zoneId = utc),
        )
    }

    // --- Entity adapter -----------------------------------------------------

    private fun seekEvents(arrivalAtEpochMs: Long) = listOf(
        WalkEvent(walkId = 1L, timestamp = walkStart, eventType = WalkEventType.SEEK_MODE),
        WalkEvent(walkId = 1L, timestamp = arrivalAtEpochMs, eventType = WalkEventType.SEEK_ARRIVAL),
    )

    private fun arrivalWaypoint(
        center: SeekPoint,
        timestamp: Long,
        ordinal: Int = 1,
    ) = Waypoint(
        walkId = 1L,
        timestamp = timestamp,
        latitude = center.latitude,
        longitude = center.longitude,
        label = SeekPersistence.arrivalWaypointLabel(resources, ordinal),
        icon = SeekPersistence.ARRIVAL_WAYPOINT_ICON,
    )

    @Test
    fun `adapter wander walk yields no model`() {
        val data = SeekSummaryModel.summaryData(
            events = emptyList(),
            waypoints = listOf(arrivalWaypoint(home, walkStart)),
            photos = emptyList(),
            voiceRecordings = emptyList(),
            routeSamples = emptyList(),
            intention = null,
        )
        assertNull(data)
    }

    @Test
    fun `adapter seek walk without arrivals yields no model`() {
        val data = SeekSummaryModel.summaryData(
            events = listOf(
                WalkEvent(walkId = 1L, timestamp = walkStart, eventType = WalkEventType.SEEK_MODE),
            ),
            waypoints = emptyList(),
            photos = emptyList(),
            voiceRecordings = emptyList(),
            routeSamples = emptyList(),
            intention = null,
        )
        assertNull(data)
    }

    @Test
    fun `adapter groups photos recordings and user waypoints`() {
        val clearing = point(bearing = 90.0, meters = 500.0)
        val arrivalAt = minutesIn(15)
        val farAway = point(bearing = 270.0, meters = 400.0)
        val recording = VoiceRecording(
            walkId = 1L,
            startTimestamp = arrivalAt + 60_000L,
            endTimestamp = arrivalAt + 120_000L,
            durationMillis = 60_000L,
            fileRelativePath = "recordings/in-clearing.wav",
        )
        val userWaypoint = Waypoint(
            walkId = 1L,
            timestamp = minutesIn(5),
            latitude = farAway.latitude,
            longitude = farAway.longitude,
            label = "Bench",
            icon = "leaf",
        )
        val photo = WalkPhoto(
            walkId = 1L,
            photoUri = "content://media/1",
            pinnedAt = arrivalAt + 90_000L,
            takenAt = arrivalAt + 90_000L,
            capturedLat = clearing.latitude,
            capturedLng = clearing.longitude,
        )

        val data = SeekSummaryModel.summaryData(
            events = seekEvents(arrivalAt),
            waypoints = listOf(arrivalWaypoint(clearing, arrivalAt), userWaypoint),
            photos = listOf(photo),
            voiceRecordings = listOf(recording),
            routeSamples = listOf(
                RouteDataSample(
                    walkId = 1L,
                    timestamp = walkStart,
                    latitude = home.latitude,
                    longitude = home.longitude,
                ),
                RouteDataSample(
                    walkId = 1L,
                    timestamp = arrivalAt + 60_000L,
                    latitude = clearing.latitude,
                    longitude = clearing.longitude,
                ),
            ),
            intention = null,
        )

        assertNotNull(data)
        assertEquals(1, data!!.groups.size)
        assertEquals("First clearing", data.groups[0].label)
        assertEquals(listOf(photo.uuid), data.groups[0].photoIds)
        assertEquals(listOf(recording.uuid), data.groups[0].voiceRecordingIds)
        assertEquals(emptyList<String>(), data.groups[0].waypointIds)
        assertEquals(listOf(userWaypoint.uuid), data.alongTheWay.waypointIds)
        assertEquals(walkStart, data.seededAtEpochMs)
        assertFalse(data.intentionWasVoiced)
    }

    @Test
    fun `adapter recording without route data uses timestamp fallback`() {
        val clearing = point(bearing = 90.0, meters = 500.0)
        val arrivalAt = minutesIn(15)
        val inWindow = VoiceRecording(
            walkId = 1L,
            startTimestamp = arrivalAt + 2 * 60_000L,
            endTimestamp = arrivalAt + 3 * 60_000L,
            durationMillis = 60_000L,
            fileRelativePath = "recordings/in-window.wav",
        )
        val late = VoiceRecording(
            walkId = 1L,
            startTimestamp = arrivalAt + 20 * 60_000L,
            endTimestamp = arrivalAt + 21 * 60_000L,
            durationMillis = 60_000L,
            fileRelativePath = "recordings/late.wav",
        )

        val data = SeekSummaryModel.summaryData(
            events = seekEvents(arrivalAt),
            waypoints = listOf(arrivalWaypoint(clearing, arrivalAt)),
            photos = emptyList(),
            voiceRecordings = listOf(inWindow, late),
            routeSamples = emptyList(),
            intention = null,
        )

        assertEquals(listOf(inWindow.uuid), data!!.groups[0].voiceRecordingIds)
        assertEquals(listOf(late.uuid), data.alongTheWay.voiceRecordingIds)
    }

    @Test
    fun `adapter uses ordinal labels from persisted waypoints and reports intention voiced`() {
        val clearing1 = point(bearing = 90.0, meters = 500.0)
        val clearing2 = point(bearing = 90.0, meters = 1500.0)
        val data = SeekSummaryModel.summaryData(
            events = seekEvents(minutesIn(15)),
            waypoints = listOf(
                arrivalWaypoint(clearing2, minutesIn(35), ordinal = 2),
                arrivalWaypoint(clearing1, minutesIn(15), ordinal = 1),
            ),
            photos = emptyList(),
            voiceRecordings = emptyList(),
            routeSamples = emptyList(),
            intention = "find calm",
        )

        assertEquals(listOf("First clearing", "Second clearing"), data!!.groups.map { it.label })
        assertTrue(data.intentionWasVoiced)
    }

    // --- Summary map annotations ------------------------------------------

    @Test
    fun `computeWalkMapAnnotations renders arrivals as hour-lit halos`() {
        val pins = computeWalkMapAnnotations(
            routeSamples = listOf(
                RouteDataSample(walkId = 1L, timestamp = walkStart, latitude = 0.0, longitude = 0.0),
            ),
            meditationIntervals = emptyList(),
            voiceRecordings = emptyList(),
            waypoints = listOf(
                Waypoint(
                    walkId = 1L,
                    timestamp = noonUtcAtEquinox,
                    latitude = 0.0,
                    longitude = 0.0,
                    label = "First clearing",
                    icon = SeekPersistence.ARRIVAL_WAYPOINT_ICON,
                ),
                Waypoint(
                    walkId = 1L,
                    timestamp = noonUtcAtEquinox,
                    latitude = 0.001,
                    longitude = 0.0,
                    label = "Grateful",
                    icon = "heart",
                ),
            ),
        )

        val arrivalKind = pins
            .mapNotNull { it.kind as? WalkMapAnnotationKind.SeekArrival }
            .singleOrNull()
        assertNotNull("an arrival waypoint must render as a SeekArrival halo", arrivalKind)
        assertEquals("First clearing", arrivalKind!!.label)
        assertEquals(
            "noon at the equator is broad daylight — and the record keeps the sky palette",
            SeekSkyLight.hex(SeekSkyLight.Daypart.MIDDAY, starlight = false),
            arrivalKind.lightHex,
        )
        assertTrue(
            "ordinary waypoints keep their pin",
            pins.any { (it.kind as? WalkMapAnnotationKind.Waypoint)?.iconKey == "heart" },
        )
    }
}
