// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.share

import java.time.ZoneId
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.domain.LocationPoint

/**
 * Mirrors `UnitTests/SharePayloadTourTests.swift` (pin `3f9f9e8`) for
 * the tour/pauses wire encoding, plus Android-specific coverage the
 * iOS suite doesn't need (its ViewModel builds the payload directly,
 * with no separate pure-builder seam): `SharePayloadBuilder`
 * integration for `interactive`/`trimEnabled`/`excludedRecordingUuids`,
 * pauses assembly, trim outcome reporting, and the classic-path
 * byte-identical regression guard.
 *
 * [wireJson] is shaped exactly like
 * [org.walktalkmeditate.pilgrim.di.NetworkModule.provideJson]
 * (`explicitNulls = false`) — the U1 production probe proved the
 * worker 400s on literal nulls, so every omission assertion here goes
 * through this real shape, not kotlinx's permissive default.
 */
class SharePayloadTourTest {

    private val wireJson = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private fun encode(payload: SharePayload): String = wireJson.encodeToString(SharePayload.serializer(), payload)

    private fun String.parsedObject(): JsonObject = wireJson.parseToJsonElement(this).jsonObject

    private fun minimalPayload(
        tour: SharePayload.Tour? = null,
        pauses: List<SharePayload.Pause>? = null,
        photos: List<SharePayload.Photo>? = null,
    ) = SharePayload(
        stats = SharePayload.Stats(
            distance = 1000.0, activeDuration = 600.0, elevationAscent = null, elevationDescent = null,
            steps = null, meditateDuration = 0.0, talkDuration = 0.0, weatherCondition = null, weatherTemperature = null,
        ),
        route = listOf(
            SharePayload.RoutePoint(lat = 35.68, lon = -105.94, alt = 2100.0, ts = 1000L),
            SharePayload.RoutePoint(lat = 35.69, lon = -105.93, alt = 2110.0, ts = 1600L),
        ),
        activityIntervals = emptyList(),
        journal = null,
        expiryDays = 90,
        units = "metric",
        startDate = "2026-08-11T08:00:00Z",
        tzIdentifier = "America/Denver",
        toggledStats = listOf("distance"),
        placeStart = null,
        placeEnd = null,
        mark = null,
        waypoints = null,
        photos = photos,
        tour = tour,
        pauses = pauses,
    )

    // MARK: - wire encoding (mirrors SharePayloadTourTests.swift 1:1)

    @Test
    fun `tour encodes snake_case with ordered recordings`() {
        val tour = SharePayload.Tour(
            recordings = listOf(
                SharePayload.TourRecording(
                    n = 1, startTs = 1100L, endTs = 1400L, duration = 300.0, kind = "spoken",
                    transcription = null, wpm = 120.0, sizeBytes = 2_400_000L,
                ),
                SharePayload.TourRecording(
                    n = 2, startTs = 1450L, endTs = 1500L, duration = 50.0, kind = "ambient",
                    transcription = null, wpm = null, sizeBytes = 800_000L,
                ),
            ),
            trimM = 150,
        )
        val json = encode(minimalPayload(tour = tour))
        val tourJson = json.parsedObject()["tour"]!!.jsonObject
        assertEquals(150, tourJson["trim_m"]!!.jsonPrimitive.int)
        val recs = tourJson["recordings"]!!.jsonArray
        assertEquals(2, recs.size)
        assertEquals(1, recs[0].jsonObject["n"]!!.jsonPrimitive.int)
        assertEquals(1100L, recs[0].jsonObject["start_ts"]!!.jsonPrimitive.long)
        assertEquals(1400L, recs[0].jsonObject["end_ts"]!!.jsonPrimitive.long)
        assertEquals("spoken", recs[0].jsonObject["kind"]!!.jsonPrimitive.content)
        assertEquals(2_400_000L, recs[0].jsonObject["size_bytes"]!!.jsonPrimitive.long)
        assertEquals(120.0, recs[0].jsonObject["wpm"]!!.jsonPrimitive.double, 0.0)
        assertFalse("wpm key must be omitted (not null) for a recording with no wpm", recs[1].jsonObject.containsKey("wpm"))
    }

    @Test
    fun `pauses encode snake_case`() {
        val json = encode(minimalPayload(tour = null, pauses = listOf(SharePayload.Pause(startTs = 1150L, endTs = 1450L))))
        val pauses = json.parsedObject()["pauses"]!!.jsonArray
        assertEquals(1150L, pauses[0].jsonObject["start_ts"]!!.jsonPrimitive.long)
        assertEquals(1450L, pauses[0].jsonObject["end_ts"]!!.jsonPrimitive.long)
    }

    @Test
    fun `absent tour and pauses are omitted from JSON`() {
        val json = encode(minimalPayload(tour = null))
        val root = json.parsedObject()
        assertFalse(root.containsKey("tour"))
        assertFalse(root.containsKey("pauses"))
    }

    @Test
    fun `photo without data omits the data key`() {
        val photo = SharePayload.Photo(lat = 35.69, lon = -105.94, ts = 1200L, data = null)
        val json = encode(minimalPayload(tour = null, photos = listOf(photo)))
        val photos = json.parsedObject()["photos"]!!.jsonArray
        assertFalse(photos[0].jsonObject.containsKey("data"))
        assertEquals(1200L, photos[0].jsonObject["ts"]!!.jsonPrimitive.long)
    }

    // MARK: - full-pipeline wire proof (critical: worker 400s on literal nulls, U1 finding)

    private fun recording(
        uuid: String = UUID.randomUUID().toString(),
        walkId: Long = 1L,
        startTimestamp: Long = 1_000_000L,
        endTimestamp: Long = 1_060_000L,
        fileRelativePath: String = "recordings/test.wav",
        transcription: String? = "Test transcription",
        wordsPerMinute: Double? = null,
    ): VoiceRecording = VoiceRecording(
        uuid = uuid,
        walkId = walkId,
        startTimestamp = startTimestamp,
        endTimestamp = endTimestamp,
        durationMillis = endTimestamp - startTimestamp,
        fileRelativePath = fileRelativePath,
        transcription = transcription,
        wordsPerMinute = wordsPerMinute,
    )

    private fun walk(startMs: Long = 1_700_000_000_000L) = Walk(
        id = 1L,
        startTimestamp = startMs,
        endTimestamp = startMs + 60 * 60 * 1000L,
    )

    private fun baseInputs(
        routePoints: List<LocationPoint> = listOf(
            LocationPoint(timestamp = 1_700_000_000_000L, latitude = 45.0, longitude = -70.0),
            LocationPoint(timestamp = 1_700_000_060_000L, latitude = 45.001, longitude = -70.001),
        ),
        recordings: List<VoiceRecording> = emptyList(),
        pauseSpans: List<PauseSpan> = emptyList(),
        recordingArtifacts: Map<String, RecordingArtifact> = emptyMap(),
    ) = ShareInputs(
        walk = walk(),
        routePoints = routePoints,
        altitudeSamples = emptyList(),
        activityIntervals = emptyList(),
        voiceRecordings = recordings,
        waypoints = emptyList(),
        distanceMeters = 1_234.0,
        activeDurationSeconds = 600.0,
        meditateDurationSeconds = 0.0,
        talkDurationSeconds = 0.0,
        elevationAscentMeters = 42.0,
        elevationDescentMeters = 40.0,
        steps = 1_500,
        pauseSpans = pauseSpans,
        recordingArtifacts = recordingArtifacts,
    )

    private fun allOn() = WalkShareOptions(
        expiry = ExpiryOption.Season,
        journal = "",
        includeDistance = true,
        includeDuration = true,
        includeElevation = true,
        includeActivityBreakdown = true,
        includeSteps = true,
        includeWaypoints = false,
    )

    @Test
    fun `interactive payload wire JSON never contains a transcription key and omits wpm when null`() {
        val withWpm = recording(
            startTimestamp = 1_000_000L, endTimestamp = 1_060_000L,
            transcription = "a real transcript with enough words to count as speech for sure",
            wordsPerMinute = 110.0,
        )
        val withoutWpm = recording(startTimestamp = 1_100_000L, endTimestamp = 1_160_000L, transcription = null, wordsPerMinute = null)
        val artifacts = mapOf(
            withWpm.uuid to RecordingArtifact(sizeBytes = 500_000L, fileExists = true),
            withoutWpm.uuid to RecordingArtifact(sizeBytes = 500_000L, fileExists = true),
        )

        val payload = SharePayloadBuilder.build(
            baseInputs(recordings = listOf(withWpm, withoutWpm), recordingArtifacts = artifacts),
            allOn().copy(interactive = true),
        )
        val json = encode(payload)

        // Raw-string proof: "transcription" must never appear anywhere
        // on the wire — the app's core privacy promise for this
        // feature, independent of how any one field happens to be nested.
        assertFalse("transcription key must never reach the wire", json.contains("\"transcription\""))

        val recs = json.parsedObject()["tour"]!!.jsonObject["recordings"]!!.jsonArray
        assertEquals(2, recs.size)
        assertTrue("wpm present when non-null", recs[0].jsonObject.containsKey("wpm"))
        assertFalse("wpm omitted when null", recs[1].jsonObject.containsKey("wpm"))
    }

    @Test
    fun `classic build wire JSON has no tour or pauses keys`() {
        val recordings = listOf(recording(transcription = "some talk", wordsPerMinute = 100.0))
        val payload = SharePayloadBuilder.build(baseInputs(recordings = recordings), allOn())
        val json = encode(payload)
        val root = json.parsedObject()
        assertFalse(root.containsKey("tour"))
        assertFalse(root.containsKey("pauses"))
    }

    // MARK: - classic-path regression (byte-identical to pre-U3 output)

    @Test
    fun `classic build is byte-identical to the pre-U3 output`() {
        val recordings = listOf(
            recording(
                uuid = "regression-uuid",
                startTimestamp = 1_700_000_040_000L,
                endTimestamp = 1_700_000_050_000L,
                transcription = "some talk",
                wordsPerMinute = 100.0,
            ),
        )
        // Deliberately NOT specifying interactive/trimEnabled/excludedRecordingUuids —
        // proves the new WalkShareOptions fields default to today's behavior.
        val payload = SharePayloadBuilder.build(
            baseInputs(recordings = recordings),
            allOn(),
            zoneId = ZoneId.of("UTC"),
        )
        val json = encode(payload)
        assertEquals(GOLDEN_CLASSIC_PAYLOAD, json)
        // Same fixture, decoded: no tour/pauses ever entered the model either.
        assertNull(payload.tour)
        assertNull(payload.pauses)
    }

    // MARK: - pauses assembly (SharePayloadBuilder.build integration)

    @Test
    fun `non-interactive build omits pauses even when pause spans are supplied`() {
        val payload = SharePayloadBuilder.build(
            baseInputs(pauseSpans = listOf(PauseSpan(startMs = 1_700_000_100_000L, durationMillis = 30_000L))),
            allOn(),
        )
        assertNull(payload.pauses)
    }

    @Test
    fun `interactive build drops a zero-length pause span`() {
        val payload = SharePayloadBuilder.build(
            baseInputs(pauseSpans = listOf(PauseSpan(startMs = 1_700_000_100_000L, durationMillis = 0L))),
            allOn().copy(interactive = true),
        )
        assertEquals(emptyList<SharePayload.Pause>(), payload.pauses)
    }

    @Test
    fun `interactive build caps pauses at 200`() {
        val spans = (0 until 201).map { i ->
            PauseSpan(startMs = 1_700_000_000_000L + i * 10_000L, durationMillis = 5_000L)
        }
        val payload = SharePayloadBuilder.build(baseInputs(pauseSpans = spans), allOn().copy(interactive = true))
        assertEquals(200, payload.pauses?.size)
    }

    @Test
    fun `buildPauses truncates to seconds and filters a span that truncates to zero length`() {
        val spans = listOf(
            // Crosses a second boundary despite a sub-second duration: 1_700_000_000.9 -> 1_700_000_001.3, kept.
            PauseSpan(startMs = 1_700_000_000_900L, durationMillis = 400L),
            // Truncates to the same second on both ends: dropped.
            PauseSpan(startMs = 1_700_000_100_000L, durationMillis = 0L),
        )
        val pauses = buildPauses(spans)
        assertEquals(1, pauses.size)
        assertEquals(1_700_000_000L, pauses[0].startTs)
        assertEquals(1_700_000_001L, pauses[0].endTs)
    }

    // MARK: - excludedRecordingUuids (SharePayloadBuilder.build integration)

    @Test
    fun `interactive build excludes a recording listed in excludedRecordingUuids from the tour`() {
        val kept = recording(startTimestamp = 1_000_000L, endTimestamp = 1_060_000L)
        val excluded = recording(startTimestamp = 1_100_000L, endTimestamp = 1_160_000L)
        val artifacts = mapOf(
            kept.uuid to RecordingArtifact(sizeBytes = 1_000L, fileExists = true),
            excluded.uuid to RecordingArtifact(sizeBytes = 1_000L, fileExists = true),
        )
        val payload = SharePayloadBuilder.build(
            baseInputs(recordings = listOf(kept, excluded), recordingArtifacts = artifacts),
            allOn().copy(interactive = true, excludedRecordingUuids = setOf(excluded.uuid)),
        )
        assertEquals(1, payload.tour?.recordings?.size)
    }

    // MARK: - trim integration (SharePayloadBuilder.build)

    private fun longRoute(points: Int = 20) = (0 until points).map { i ->
        LocationPoint(timestamp = 1_700_000_000_000L + i * 30_000L, latitude = 35.0 + i * 0.001, longitude = -105.0)
    }

    // MARK: - prepareRoute (the once-per-load half of the route work)

    @Test
    fun `prepareRoute reports trim eligibility the same way canTrim does`() {
        // iOS `testCanTrimRouteReflectsRouteLength`
        // (`WalkShareInteractiveTests.swift:176-182@3f9f9e8`), read off
        // the downsampled points the trim would actually see.
        assertTrue(prepareRoute(baseInputs(routePoints = longRoute(points = 20))).canTrim)
        assertFalse(prepareRoute(baseInputs(routePoints = longRoute(points = 4))).canTrim)
    }

    @Test
    fun `a precomputed route yields exactly the route computing from inputs does`() {
        // The safety net for hoisting the downsample out of the
        // ViewModel's per-emission path: the two entry points must be
        // incapable of disagreeing about route, trim_m, or kept window.
        val inputs = baseInputs(routePoints = longRoute(points = 20))
        for (options in listOf(
            allOn().copy(interactive = true, trimEnabled = true),
            allOn().copy(interactive = true, trimEnabled = false),
            allOn().copy(interactive = false, trimEnabled = true),
        )) {
            assertEquals(
                computeInteractiveRoute(inputs, options),
                computeInteractiveRoute(prepareRoute(inputs).downsampled, options),
            )
        }
    }

    @Test
    fun `interactive plus trimEnabled trims the route and records trim_m on the tour`() {
        val route = longRoute()
        val payload = SharePayloadBuilder.build(baseInputs(routePoints = route), allOn().copy(interactive = true, trimEnabled = true))
        assertTrue("route should be shorter after trim", payload.route.size < route.size)
        assertEquals(150, payload.tour?.trimM)
    }

    @Test
    fun `trimEnabled without interactive leaves the route untouched and builds no tour`() {
        val route = longRoute()
        val payload = SharePayloadBuilder.build(baseInputs(routePoints = route), allOn().copy(interactive = false, trimEnabled = true))
        assertEquals(route.size, payload.route.size)
        assertNull(payload.tour)
    }

    @Test
    fun `interactive without trimEnabled leaves the route untouched but still builds a tour`() {
        val route = longRoute()
        val payload = SharePayloadBuilder.build(baseInputs(routePoints = route), allOn().copy(interactive = true, trimEnabled = false))
        assertEquals(route.size, payload.route.size)
        assertEquals(0, payload.tour?.trimM)
    }

    @Test
    fun `a route too short to trim reports trim_m as 0, not the requested 150, and stays untouched`() {
        // 4 points, ~66.6m per step = ~200m total, under the 4 * 150 = 600m floor.
        val route = (0 until 4).map { i ->
            LocationPoint(timestamp = 1_700_000_000_000L + i * 30_000L, latitude = 35.0 + i * 0.0006, longitude = -105.0)
        }
        val payload = SharePayloadBuilder.build(baseInputs(routePoints = route), allOn().copy(interactive = true, trimEnabled = true))
        assertEquals(route.size, payload.route.size)
        assertEquals(0, payload.tour?.trimM)
    }

    // MARK: - U8 consent parity (iOS WalkShareViewModel.buildPayload@3f9f9e8)

    @Test
    fun `excluded recording leaves no talk interval and no minutes on an interactive share`() {
        // Port of `testExcludedRecordingLeavesNoTalkInterval`
        // (`UnitTests/WalkShareInteractiveTests.swift:95-110@3f9f9e8`):
        // "Consent follows the checkbox: an excluded recording leaves no
        // trace — no talk interval, no rust on the route, no minutes in
        // the total." (`WalkShareViewModel.swift:343@3f9f9e8`).
        val kept = recording(startTimestamp = 1_000_000L, endTimestamp = 1_060_000L)
        val excluded = recording(startTimestamp = 2_000_000L, endTimestamp = 2_090_000L)
        val artifacts = mapOf(
            kept.uuid to RecordingArtifact(sizeBytes = 1_000_000L, fileExists = true),
            excluded.uuid to RecordingArtifact(sizeBytes = 1_500_000L, fileExists = true),
        )
        // 150s covers the kept candidate's 60s so the clamp can't mask exclusion filtering
        // (the Swift test's own note, `WalkShareInteractiveTests.swift:98-99@3f9f9e8`).
        val inputs = baseInputs(recordings = listOf(kept, excluded), recordingArtifacts = artifacts)
            .copy(talkDurationSeconds = 150.0)

        val payload = SharePayloadBuilder.build(
            inputs,
            allOn().copy(interactive = true, excludedRecordingUuids = setOf(excluded.uuid)),
        )

        val talk = payload.activityIntervals.filter { it.type == "talk" }
        assertEquals("the excluded candidate's talk interval must not appear", 1, talk.size)
        assertEquals(1_000L, talk.first().startTs)
        assertEquals(1_060L, talk.first().endTs)
        assertEquals("excluded duration must not count toward the total", 60.0, payload.stats.talkDuration!!, 0.0)
    }

    @Test
    fun `classic talk intervals ignore candidate exclusions entirely`() {
        // Port of `testClassicTalkIntervalsUnchangedByExclusions`
        // (`WalkShareInteractiveTests.swift:112-127@3f9f9e8`).
        val rec1 = recording(startTimestamp = 1_000_000L, endTimestamp = 1_060_000L)
        val rec2 = recording(startTimestamp = 2_000_000L, endTimestamp = 2_090_000L)
        val inputs = baseInputs(recordings = listOf(rec1, rec2)).copy(talkDurationSeconds = 999.0)

        val payload = SharePayloadBuilder.build(
            inputs,
            allOn().copy(interactive = false, excludedRecordingUuids = setOf(rec2.uuid)),
        )

        val talk = payload.activityIntervals.filter { it.type == "talk" }
        assertEquals("classic path reads voiceRecordings directly", 2, talk.size)
        assertEquals(999.0, payload.stats.talkDuration!!, 0.0)
    }

    @Test
    fun `interactive talk duration clamps to the walk's own talk duration`() {
        // Port of `testInteractiveTalkDurationClampedToWalkTalkDuration`
        // (`WalkShareInteractiveTests.swift:131-141@3f9f9e8`) — the
        // worker 400s on meditate+talk > active, so a pause-spanning
        // recording set must clamp (`WalkShareViewModel.swift:364-365@3f9f9e8`).
        val a = recording(startTimestamp = 1_000_000L, endTimestamp = 1_060_000L)
        val b = recording(startTimestamp = 2_000_000L, endTimestamp = 2_060_000L)
        val artifacts = mapOf(
            a.uuid to RecordingArtifact(sizeBytes = 1_000_000L, fileExists = true),
            b.uuid to RecordingArtifact(sizeBytes = 1_000_000L, fileExists = true),
        )
        val inputs = baseInputs(recordings = listOf(a, b), recordingArtifacts = artifacts)
            .copy(talkDurationSeconds = 100.0)

        val payload = SharePayloadBuilder.build(inputs, allOn().copy(interactive = true))

        assertEquals("included candidates sum to 120 — must clamp to 100", 100.0, payload.stats.talkDuration!!, 0.0)
    }

    @Test
    fun `trim's kept window excludes waypoints outside it and keeps the boundaries`() {
        // Port of `testInteractiveKeptWindowExcludesTrimmedWaypoints` +
        // `testInteractiveKeptWindowIncludesWaypointsAtExactBoundary`
        // (`WalkShareInteractiveTests.swift:189-223@3f9f9e8`): "Trim's
        // promise covers everything with a coordinate"
        // (`WalkShareViewModel.swift:477@3f9f9e8`).
        val base = 1_700_000_000_000L
        val route = (0 until 20).map { i ->
            LocationPoint(timestamp = base + i * 30_000L, latitude = 48.8566 + i * 0.001, longitude = 2.3522)
        }
        val kept = computeInteractiveRoute(
            baseInputs(routePoints = route),
            allOn().copy(interactive = true, trimEnabled = true),
        )
        val window = kept.keptWindow!!
        val inputs = baseInputs(routePoints = route).copy(
            waypoints = listOf(
                waypoint("Doorstep", base),
                waypoint("AtLowerBound", window.first * 1_000L),
                waypoint("Midpoint", base + 10 * 30_000L),
                waypoint("AtUpperBound", window.last * 1_000L),
            ),
        )

        val payload = SharePayloadBuilder.build(
            inputs,
            allOn().copy(interactive = true, trimEnabled = true, includeWaypoints = true),
        )

        val labels = payload.waypoints.orEmpty().map { it.label }
        assertFalse("trim excludes waypoints outside the kept route window", labels.contains("Doorstep"))
        assertTrue(labels.contains("Midpoint"))
        assertTrue("lower bound is inclusive (ClosedRange parity)", labels.contains("AtLowerBound"))
        assertTrue("upper bound is inclusive (ClosedRange parity)", labels.contains("AtUpperBound"))
    }

    @Test
    fun `a route too short to trim leaves waypoints unfiltered`() {
        // Port of `testShortRouteTrimIsHonestAndLeavesWaypointsUnfiltered`
        // (`WalkShareInteractiveTests.swift:225-247@3f9f9e8`).
        val base = 1_700_000_000_000L
        val route = (0 until 4).map { i ->
            LocationPoint(timestamp = base + i * 30_000L, latitude = 48.8566 + i * 0.0006, longitude = 2.3522)
        }
        val inputs = baseInputs(routePoints = route)
            .copy(waypoints = listOf(waypoint("Before the first fix", base - 3_600_000L)))

        val payload = SharePayloadBuilder.build(
            inputs,
            allOn().copy(interactive = true, trimEnabled = true, includeWaypoints = true),
        )

        assertEquals("a route too short to trim reports trimM 0", 0, payload.tour!!.trimM)
        assertTrue(payload.waypoints.orEmpty().map { it.label }.contains("Before the first fix"))
    }

    private fun waypoint(label: String, timestampMs: Long) = org.walktalkmeditate.pilgrim.data.entity.Waypoint(
        walkId = 1L,
        timestamp = timestampMs,
        latitude = 48.86,
        longitude = 2.3522,
        label = label,
        icon = "flag",
    )

    private companion object {
        // Captured from the U3 builder with the new WalkShareOptions/
        // ShareInputs fields left at their defaults — proves the
        // classic (non-interactive) path is byte-identical to the
        // pre-Phase-19 wire output for the same inputs. No tour,
        // pauses, journal, waypoints, or photos keys — same shape the
        // pre-U3 builder produced for an equivalent input set.
        const val GOLDEN_CLASSIC_PAYLOAD =
            """{"stats":{"distance":1234.0,"active_duration":600.0,"elevation_ascent":42.0,""" +
                """"elevation_descent":40.0,"steps":1500},"route":[{"lat":45.0,"lon":-70.0,"alt":0.0,""" +
                """"ts":1700000000},{"lat":45.001,"lon":-70.001,"alt":0.0,"ts":1700000060}],""" +
                """"activity_intervals":[{"type":"talk","start_ts":1700000040,"end_ts":1700000050}],""" +
                """"expiry_days":90,"units":"metric","start_date":"2023-11-14T22:13:20Z",""" +
                """"tz_identifier":"UTC","toggled_stats":["distance","duration","elevation",""" +
                """"activity_breakdown","steps"]}"""
    }
}
