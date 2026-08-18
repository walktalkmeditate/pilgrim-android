// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.walk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.walktalkmeditate.pilgrim.data.entity.ActivityInterval
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.domain.ActivityType

class RouteSegmentsTest {

    private fun sample(t: Long, lat: Double = 0.0, lng: Double = 0.0) =
        RouteDataSample(walkId = 1L, timestamp = t, latitude = lat, longitude = lng, altitudeMeters = 0.0)

    private fun meditation(start: Long, end: Long) = ActivityInterval(
        walkId = 1L,
        startTimestamp = start,
        endTimestamp = end,
        activityType = ActivityType.MEDITATING,
    )

    private fun recording(start: Long, durationMs: Long) = VoiceRecording(
        walkId = 1L,
        startTimestamp = start,
        endTimestamp = start + durationMs,
        durationMillis = durationMs,
        fileRelativePath = "recordings/x.wav",
        transcription = null,
    )

    @Test fun emptySamples_returnsEmptyList() {
        val result = computeRouteSegments(emptyList(), emptyList(), emptyList())
        assertTrue(result.isEmpty())
    }

    @Test fun singleSample_returnsEmptyList() {
        val result = computeRouteSegments(listOf(sample(0L)), emptyList(), emptyList())
        assertTrue(result.isEmpty())
    }

    @Test fun allWalking_returnsOneSegment() {
        val samples = listOf(sample(0L, 1.0, 1.0), sample(10L, 2.0, 2.0), sample(20L, 3.0, 3.0))
        val result = computeRouteSegments(samples, emptyList(), emptyList())
        assertEquals(1, result.size)
        assertEquals(RouteActivity.Walking, result[0].activity)
        assertEquals(3, result[0].points.size)
    }

    @Test fun talkInMiddle_splitsIntoWalkTalkWalk() {
        val samples = (0L..40L step 10L).map { sample(it, it.toDouble(), it.toDouble()) }
        val recordings = listOf(recording(start = 15L, durationMs = 10L)) // covers t=15..25
        val result = computeRouteSegments(samples, emptyList(), recordings)
        assertEquals(3, result.size)
        assertEquals(RouteActivity.Walking, result[0].activity)
        assertEquals(RouteActivity.Talking, result[1].activity)
        assertEquals(RouteActivity.Walking, result[2].activity)
    }

    @Test fun meditationOverlapsTalking_meditationWins() {
        val samples = listOf(sample(10L), sample(20L), sample(30L))
        val intervals = listOf(meditation(start = 5L, end = 35L))
        val recordings = listOf(recording(start = 10L, durationMs = 15L))
        val result = computeRouteSegments(samples, intervals, recordings)
        assertEquals(1, result.size)
        assertEquals(RouteActivity.Meditating, result[0].activity)
    }

    @Test fun boundaryPointSharedBetweenSegments() {
        val samples = (0L..30L step 10L).map { sample(it, it.toDouble(), it.toDouble()) }
        val intervals = listOf(meditation(start = 15L, end = 25L))
        val result = computeRouteSegments(samples, intervals, emptyList())
        assertEquals(3, result.size)
        // Last point of segment 0 == first point of segment 1
        assertEquals(result[0].points.last(), result[1].points.first())
        assertEquals(result[1].points.last(), result[2].points.first())
    }

    @Test fun pureMeditation_returnsOneMeditatingSegment() {
        val samples = listOf(sample(10L), sample(20L), sample(30L))
        val intervals = listOf(meditation(start = 5L, end = 35L))
        val result = computeRouteSegments(samples, intervals, emptyList())
        assertEquals(1, result.size)
        assertEquals(RouteActivity.Meditating, result[0].activity)
    }

    private fun walk3Recording1() = recording(start = 1787014864689L, durationMs = 43212L)
    private fun walk3Recording2() = recording(start = 1787015044574L, durationMs = 120108L)

    /**
     * Exact `route_data_samples.timestamp` values for device DB walk
     * id=3 (Round-2 QA, 2026-08-18), thinned in the two inter-recording
     * "walking" stretches (every ~20th sample) since classification
     * there is not in question — see [walk3Recording1]/[walk3Recording2]
     * for the paired `voice_recordings` rows.
     */
    private fun walk3SampleTimestamps(): List<Long> {
        val timestamps = listOf(
            // 6 samples immediately before recording 1
            1787014858704L, 1787014859704L, 1787014860704L, 1787014861704L,
            1787014862704L, 1787014863616L,
            // 44 samples inside recording 1's window [864689..907901]
            1787014864703L, 1787014865704L, 1787014866704L, 1787014867704L,
            1787014868704L, 1787014869704L, 1787014870704L, 1787014871704L,
            1787014872704L, 1787014873704L, 1787014874704L, 1787014875704L,
            1787014876704L, 1787014877704L, 1787014878704L, 1787014879704L,
            1787014880704L, 1787014881704L, 1787014882705L, 1787014883705L,
            1787014884705L, 1787014885704L, 1787014886704L, 1787014887704L,
            1787014888704L, 1787014889615L, 1787014890704L, 1787014891704L,
            1787014892717L, 1787014893704L, 1787014894704L, 1787014895705L,
            1787014896704L, 1787014897704L, 1787014898704L, 1787014899704L,
            1787014900659L, 1787014901705L, 1787014902620L, 1787014903705L,
            1787014904620L, 1787014905704L, 1787014906704L, 1787014907704L,
            // thinned samples in the ~137s gap between the two recordings
            1787014908704L, 1787014928705L, 1787014948704L, 1787014972704L,
            1787014992704L, 1787015012704L, 1787015032704L,
            // 120 samples inside recording 2's window [1044574..1164682]
            1787015044704L, 1787015045705L, 1787015046608L, 1787015047704L,
            1787015048704L, 1787015049704L, 1787015050704L, 1787015051704L,
            1787015052704L, 1787015053704L, 1787015054704L, 1787015055704L,
            1787015056643L, 1787015057704L, 1787015058705L, 1787015059664L,
            1787015060705L, 1787015061704L, 1787015062626L, 1787015063704L,
            1787015064704L, 1787015065667L, 1787015066705L, 1787015067705L,
            1787015068608L, 1787015069704L, 1787015070704L, 1787015071639L,
            1787015072705L, 1787015073704L, 1787015074642L, 1787015075704L,
            1787015076704L, 1787015077704L, 1787015078704L, 1787015079704L,
            1787015080704L, 1787015081704L, 1787015082704L, 1787015083704L,
            1787015084704L, 1787015085704L, 1787015086704L, 1787015087667L,
            1787015088705L, 1787015089704L, 1787015090668L, 1787015091705L,
            1787015092704L, 1787015093704L, 1787015094704L, 1787015095704L,
            1787015096704L, 1787015097704L, 1787015098704L, 1787015099704L,
            1787015100704L, 1787015101704L, 1787015102705L, 1787015103704L,
            1787015104704L, 1787015105665L, 1787015106704L, 1787015107704L,
            1787015108686L, 1787015109704L, 1787015110704L, 1787015111627L,
            1787015112704L, 1787015113704L, 1787015114608L, 1787015115704L,
            1787015116704L, 1787015117704L, 1787015118681L, 1787015119704L,
            1787015120704L, 1787015121704L, 1787015122704L, 1787015123704L,
            1787015124704L, 1787015125704L, 1787015126604L, 1787015127704L,
            1787015128704L, 1787015129666L, 1787015130704L, 1787015131626L,
            1787015132704L, 1787015133704L, 1787015134628L, 1787015135702L,
            1787015136704L, 1787015137704L, 1787015138704L, 1787015139620L,
            1787015140661L, 1787015141704L, 1787015142704L, 1787015143704L,
            1787015144704L, 1787015145704L, 1787015146704L, 1787015147704L,
            1787015148704L, 1787015149704L, 1787015150704L, 1787015151666L,
            1787015152705L, 1787015153704L, 1787015154704L, 1787015155705L,
            1787015156704L, 1787015157705L, 1787015158704L, 1787015159704L,
            1787015160704L, 1787015161705L, 1787015162703L, 1787015163682L,
            // 6 samples immediately after recording 2
            1787015164702L, 1787015165704L, 1787015166704L, 1787015167704L,
            1787015168705L, 1787015169684L,
        )
        return timestamps
    }

    private fun walk3RealSegments(): List<RouteSegment> {
        val samples = walk3SampleTimestamps().mapIndexed { i, t -> sample(t, i.toDouble(), i.toDouble()) }
        return computeRouteSegments(
            samples,
            emptyList(),
            listOf(walk3Recording1(), walk3Recording2()),
        )
    }

    /**
     * Round-2 QA (2026-08-18): device DB for walk id=3 pulled from a
     * real walk showed the summary map rendering only the SECOND of two
     * voice recordings as a rust "Talking" segment; the first recording's
     * stretch rendered as plain "Walking". The share worker's compiled
     * gradient — built independently from the same walk export — shows
     * TWO rust spans, confirming both talks cover genuine route
     * displacement.
     *
     * This test proves [classify] is NOT the bug: fed the exact real
     * timestamps, it correctly produces two Talking segments. See
     * [paintOrder_walk3RealFixture_talkingSegmentsPaintAfterAllWalking]
     * below for the actual root cause (a render-order occlusion, not a
     * classification error) and its fix.
     */
    @Test fun walk3RealFixture_bothVoiceRecordingsProduceTalkingSegments() {
        val result = walk3RealSegments()

        val talkingSegments = result.filter { it.activity == RouteActivity.Talking }
        assertEquals(
            "expected one Talking segment per real voice recording " +
                "(walk 3 has 2) — the worker's two-span gradient is the oracle",
            2,
            talkingSegments.size,
        )
    }

    /**
     * Root cause of the missing-rust-segment bug (Round-2 QA,
     * 2026-08-18): walk 3's route is an out-and-back — the walker
     * re-crosses recording 1's exact coordinates (lat 30.4174-30.4178,
     * lon -97.6857..-97.6853) roughly 29 minutes later on the return
     * leg (confirmed against `route_data_samples`: e.g. outbound sample
     * at t=1787014907704 lands at (30.417469, -97.6856258); the return
     * leg passes (30.4174014, -97.685689) at t=1787016677808, a few
     * meters away). [computeRouteSegments] correctly tags both spans
     * (Talking for the outbound talk, Walking for the return leg) — see
     * [walk3RealFixture_bothVoiceRecordingsProduceTalkingSegments] — but
     * `PilgrimMap`'s renderer used to create one `PolylineAnnotation`
     * per segment IN CHRONOLOGICAL ORDER, and Mapbox paints
     * later-created annotations on top. The chronologically-LATER
     * return-leg Walking polyline therefore painted over the EARLIER
     * Talking polyline at their shared coordinates, burying the rust
     * tint under moss — even though the data was always correct.
     * Recording 2's talk survives because its own stretch (further
     * along the outbound leg) isn't retraced as closely.
     *
     * [routeSegmentsInPaintOrder] fixes this the same way [classify]
     * already resolves TIME overlap (Meditating > Talking > Walking):
     * applying that priority to render order guarantees a
     * higher-priority tint always ends up on top, regardless of which
     * segment is chronologically later.
     */
    @Test fun paintOrder_walk3RealFixture_talkingSegmentsPaintAfterAllWalking() {
        val segments = walk3RealSegments()
        // Sanity: this fixture actually reproduces the chronological
        // interleaving that causes the occlusion (Talking segment
        // NOT last among the segments preceding the walk's end).
        val talkingIndices = segments.indices.filter { segments[it].activity == RouteActivity.Talking }
        val walkingIndices = segments.indices.filter { segments[it].activity == RouteActivity.Walking }
        assertTrue(
            "fixture must contain a Walking segment chronologically AFTER " +
                "a Talking segment to exercise the occlusion",
            walkingIndices.max() > talkingIndices.min(),
        )

        val painted = routeSegmentsInPaintOrder(segments)

        val paintedTalkingIndices =
            painted.indices.filter { painted[it].activity == RouteActivity.Talking }
        val paintedWalkingIndices =
            painted.indices.filter { painted[it].activity == RouteActivity.Walking }
        assertTrue(
            "every Walking segment must paint BEFORE every Talking segment " +
                "so a geographically-overlapping later Walking stretch can " +
                "never bury an earlier talk's rust tint",
            paintedWalkingIndices.max() < paintedTalkingIndices.min(),
        )
    }

    @Test fun paintOrder_isStableAndPreservesAllSegments() {
        val walking1 = RouteSegment(RouteActivity.Walking, listOf(point(0L)))
        val talking = RouteSegment(RouteActivity.Talking, listOf(point(1L)))
        val walking2 = RouteSegment(RouteActivity.Walking, listOf(point(2L)))
        val meditating = RouteSegment(RouteActivity.Meditating, listOf(point(3L)))
        val walking3 = RouteSegment(RouteActivity.Walking, listOf(point(4L)))
        val chronological = listOf(walking1, talking, walking2, meditating, walking3)

        val painted = routeSegmentsInPaintOrder(chronological)

        assertEquals(
            "Walking (stable order preserved) then Talking then Meditating",
            listOf(walking1, walking2, walking3, talking, meditating),
            painted,
        )
    }

    private fun point(t: Long) =
        org.walktalkmeditate.pilgrim.domain.LocationPoint(timestamp = t, latitude = 0.0, longitude = 0.0)

    @Test fun nonMeditationActivityTypeIgnored() {
        // Only MEDITATING intervals classify as meditating; if the entity carried
        // a different activityType (future-proofing) the classifier ignores it.
        val samples = listOf(sample(10L), sample(20L))
        val intervals = listOf(
            ActivityInterval(
                walkId = 1L,
                startTimestamp = 5L,
                endTimestamp = 25L,
                activityType = ActivityType.WALKING,
            ),
        )
        val result = computeRouteSegments(samples, intervals, emptyList())
        assertEquals(1, result.size)
        assertEquals(RouteActivity.Walking, result[0].activity)
    }
}
