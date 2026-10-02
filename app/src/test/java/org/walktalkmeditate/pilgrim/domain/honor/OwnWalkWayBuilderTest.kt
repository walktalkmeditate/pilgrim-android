// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain.honor

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.entity.WalkEvent
import org.walktalkmeditate.pilgrim.data.entity.WalkPhoto
import org.walktalkmeditate.pilgrim.data.entity.Waypoint
import org.walktalkmeditate.pilgrim.domain.WalkEventType
import org.walktalkmeditate.pilgrim.domain.seek.SeekPersistence

/**
 * Ports iOS `UnitTests/Honor/OwnWalkWayBuilderTests.swift@7c200bf` test for
 * test (the first eight, on the same walk and numbers), then pins each
 * builder rule of parity spec A §9–§11 and the Android-only data gaps
 * (the spec's decision 4). The last test feeds the walk iOS's own builder
 * built `honor/own-walk-way.json` from (see [WayCodecTest]) into the
 * Android builder and compares the two Ways.
 */
class OwnWalkWayBuilderTest {

    private val start = 1_777_622_400_000L // 2026-05-01T08:00:00Z
    private val walkUuid = "3f2b8c1e-5d4a-4e6f-9a7b-1c2d3e4f5a6b"
    private val recordingPath = "Recordings/a/b.m4a"

    private fun at(seconds: Long): Long = start + seconds * 1_000

    private fun sample(i: Int, lon: Double, lat: Double = 42.88, alt: Double? = 300.0): RouteDataSample =
        RouteDataSample(
            walkId = WALK_ID,
            timestamp = at(i * 60L),
            latitude = lat,
            longitude = lon,
            altitudeMeters = alt,
            horizontalAccuracyMeters = 5f,
            verticalAccuracyMeters = 5f,
            speedMetersPerSecond = 1.4f,
            directionDegrees = 90f,
        )

    private fun recording(
        fromSeconds: Long,
        toSeconds: Long,
        path: String = recordingPath,
        transcription: String? = "a thought that runs on for more than eight words in total",
    ): VoiceRecording = VoiceRecording(
        walkId = WALK_ID,
        startTimestamp = at(fromSeconds),
        endTimestamp = at(toSeconds),
        durationMillis = (toSeconds - fromSeconds) * 1_000,
        fileRelativePath = path,
        transcription = transcription,
    )

    private fun waypoint(seconds: Long, lon: Double, label: String?, icon: String?, lat: Double = 42.88): Waypoint =
        Waypoint(walkId = WALK_ID, timestamp = at(seconds), latitude = lat, longitude = lon, label = label, icon = icon)

    private fun photo(seconds: Long?, lat: Double?, lng: Double?, uri: String = PHOTO_URI): WalkPhoto =
        WalkPhoto(
            walkId = WALK_ID,
            photoUri = uri,
            pinnedAt = at(3_600),
            takenAt = seconds?.let(::at),
            capturedLat = lat,
            capturedLng = lng,
        )

    private fun span(fromSeconds: Long, toSeconds: Long) = OwnWalkWayBuilder.TimeSpan(at(fromSeconds), at(toSeconds))

    /** Ten samples 100 m apart, one per minute. */
    private fun walk(uuid: String? = walkUuid): OwnWalkWayBuilder.Input = OwnWalkWayBuilder.Input(
        uuid = uuid,
        startedAtMillis = start,
        intention = null,
        activeSeconds = 540.0 - 200.0,
        weatherCondition = null,
        weatherTemperatureC = null,
        samples = (0 until 10).map { sample(it, lon = -8.54 + it * 0.00122) },
        recordings = listOf(recording(fromSeconds = 4 * 60 + 10, toSeconds = 5 * 60)),
        photos = emptyList(),
        waypoints = listOf(
            waypoint(seconds = 120, lon = -8.54 + 2 * 0.00122, label = "Oak", icon = "leaf"),
            waypoint(seconds = 0, lon = -8.54, label = "x", icon = SeekPersistence.ARRIVAL_WAYPOINT_ICON),
        ),
        pauses = listOf(span(3 * 60, 3 * 60 + 200)),
        sittings = listOf(span(7 * 60, 19 * 60)),
    )

    private fun make(
        input: OwnWalkWayBuilder.Input,
        recordingIsPresent: (VoiceRecording) -> Boolean = { true },
        zone: ZoneId = ZoneOffset.UTC,
        locale: Locale = Locale.US,
    ): Way? = OwnWalkWayBuilder.make(input, recordingIsPresent, zone, locale)

    private fun build(input: OwnWalkWayBuilder.Input = walk()): Way = checkNotNull(make(input)) { "no Way built" }

    private fun Way.moment(id: String): WayMoment = moments.single { it.id == id }

    @Test
    fun `builds moments at the right fracs with coordinates`() {
        val way = build()
        assertEquals(10, way.route.size)
        assertEquals(900.0, way.totalDistanceMeters, 10.0)
        assertEquals(340.0, way.theirActiveSeconds, 0.0)

        val voice = way.moment("voice-1")
        assertEquals(4.0 / 9.0, voice.frac, 0.03)
        assertEquals(-8.54 + 4 * 0.00122, voice.at?.lon ?: 0.0, 0.0001)
        val kind = voice.kind as WayMomentKind.Voice
        assertEquals(50.0, kind.duration, 0.0)
        assertEquals(VoiceKind.SPOKEN, kind.kind)
        assertEquals(WayMedia.Recording(relativePath = recordingPath), kind.media)

        val sit = way.moment("sit-1").kind as WayMomentKind.Meditation
        assertEquals(12, sit.minutes)
        assertFalse(sit.isEstimate)

        val rest = way.moment("rest-1").kind as WayMomentKind.Rest
        assertEquals(3, rest.minutes)

        assertTrue(way.moments.any { it.id == "waypoint-1" })
        assertEquals(
            "reserved-icon waypoints are excluded",
            1,
            way.moments.count { it.kind is WayMomentKind.Waypoint },
        )
        assertEquals(way.moments.map { it.frac }.sorted(), way.moments.map { it.frac })
    }

    @Test
    fun `nil without a route`() {
        assertNull(make(walk().copy(samples = emptyList())))
    }

    /**
     * A walk that never left one spot is jitter, not a Way: every frac would
     * collapse onto the same place and the companion could not move.
     */
    @Test
    fun `nil when the route is shorter than the floor`() {
        val jitter = (0 until 10).map { sample(it, lon = -8.54 + it * 0.00001) } // ~8 m end to end
        assertNull(make(walk().copy(samples = jitter)))
    }

    @Test
    fun `a way just over the floor is still built`() {
        val short = (0 until 10).map { sample(it, lon = -8.54 + it * 0.00005) } // ~40 m end to end
        val way = build(walk().copy(samples = short))
        assertTrue(way.totalDistanceMeters >= OwnWalkWayBuilder.MIN_LENGTH_METERS)
    }

    @Test
    fun `skips recordings whose file is gone`() {
        val way = checkNotNull(make(walk(), recordingIsPresent = { false }))
        assertFalse(way.moments.any { it.isVoice })
        assertEquals(0, way.voiceCount)
    }

    @Test
    fun `identity and title`() {
        val way = build()
        assertEquals("walk:$walkUuid", way.id)
        assertEquals(WaySource.OwnWalk(uuid = walkUuid), way.source)
        assertNull(way.expires)
    }

    @Test
    fun `spans follow the recording and the sitting`() {
        val spans = checkNotNull(build().spans)
        assertEquals(listOf(WaySpanKind.TALKING, WaySpanKind.MEDITATING), spans.map { it.kind })
        for (span in spans) {
            assertTrue(span.endFrac > span.startFrac)
            assertTrue(span.startFrac >= 0.0)
            assertTrue(span.endFrac <= 1.0)
        }
    }

    @Test
    fun `the walker's own transcription rides onto the voice moment`() {
        val voice = build().moments.first { it.isVoice }
        assertEquals("a thought that runs on for more than eight words in total", voice.transcript)
    }

    // Android additions: each rule of parity spec A §9–§11.

    @Test
    fun `the uuid is used verbatim, whatever its case`() {
        val imported = "E621E1F8-C36C-495A-93FC-0C247A3E6E5F"
        val way = build(walk(uuid = imported))
        assertEquals("walk:$imported", way.id)
        assertEquals(WaySource.OwnWalk(uuid = imported), way.source)
    }

    @Test
    fun `no uuid gives no way`() {
        assertNull(make(walk(uuid = null)))
        assertNull(make(walk(uuid = "")))
    }

    @Test
    fun `fewer than two samples gives no way`() {
        assertNull(make(walk().copy(samples = listOf(sample(0, lon = -8.54)))))
    }

    @Test
    fun `route times count from the first sample, in time order, with altitude when known`() {
        val samples = (0 until 10).map { sample(it, lon = -8.54 + it * 0.00122) }
            .map { it.copy(timestamp = it.timestamp + 5_000) }
            .mapIndexed { i, s -> if (i == 3) s.copy(altitudeMeters = null) else s }
            .reversed()
        val way = build(walk().copy(samples = samples))
        assertEquals(Instant.ofEpochMilli(start), way.departedAt)
        assertEquals((0 until 10).map { it * 60.0 }, way.route.map { it.t })
        assertEquals(-8.54, way.route.first().lon, 0.0)
        assertNull(way.route[3].alt)
        assertEquals(300.0, way.route[4].alt ?: 0.0, 0.0)
    }

    @Test
    fun `a long route is stride-sampled to 4000 points and keeps its full-resolution length`() {
        val samples = (0..4000).map { i ->
            sample(0, lon = -8.54 + i * 0.00001).copy(timestamp = start + i * 1_000L)
        }
        val input = walk().copy(
            samples = samples,
            recordings = emptyList(),
            waypoints = emptyList(),
            sittings = emptyList(),
            pauses = listOf(OwnWalkWayBuilder.TimeSpan(start + 2_001_000L, start + 2_300_000L)),
        )
        val way = build(input)
        val full = samples.map {
            val t = (it.timestamp - start) / 1_000.0
            WayPoint(lat = it.latitude, lon = it.longitude, alt = it.altitudeMeters, t = t)
        }
        val fullGeometry = WayGeometry(full)

        assertEquals(OwnWalkWayBuilder.MAX_ROUTE_POINTS, way.route.size)
        assertEquals(full.first(), way.route.first())
        assertEquals("index round(1999 × 4000/3999) = 1999", full[1999], way.route[1999])
        assertEquals("index round(2000 × 4000/3999) = 2001", full[2001], way.route[2000])
        assertEquals(full.last(), way.route.last())
        assertEquals(fullGeometry.totalMeters, way.totalDistanceMeters, 0.0)
        assertEquals(
            "fracs come from the full-resolution geometry",
            fullGeometry.frac(atElapsed = 2_001.0),
            way.moment("rest-1").frac,
            0.0,
        )
        assertEquals(4000, build(input.copy(samples = samples.take(4000))).route.size)
    }

    @Test
    fun `a date before or after the walk lands on the first or last sample, and a tie takes the earlier sample`() {
        val input = walk().copy(
            recordings = emptyList(),
            sittings = emptyList(),
            waypoints = listOf(
                waypoint(seconds = -60, lon = -8.0, label = "before", icon = "leaf"),
                waypoint(seconds = 3_600, lon = -8.0, label = "after", icon = "leaf"),
            ),
            pauses = listOf(span(90, 90 + 200)),
        )
        val way = build(input)
        assertEquals(0.0, way.moment("waypoint-1").frac, 0.0)
        assertEquals(1.0, way.moment("waypoint-2").frac, 0.0)
        val rest = way.moment("rest-1")
        assertEquals(
            "90 s sits halfway between the samples at 60 s and 120 s",
            WayCoordinate(42.88, -8.54 + 0.00122),
            rest.at,
        )
        assertEquals(WayGeometry(way.route).frac(atElapsed = 60.0), rest.frac, 0.0)
    }

    @Test
    fun `recordings with no path are skipped before the file probe`() {
        val probed = mutableListOf<String>()
        val input = walk().copy(recordings = listOf(recording(fromSeconds = 250, toSeconds = 300, path = "")))
        val way = checkNotNull(make(input, recordingIsPresent = { probed += it.fileRelativePath; true }))
        assertEquals(0, way.voiceCount)
        assertEquals(emptyList<String>(), probed)
    }

    @Test
    fun `voices number by start time over the present recordings only`() {
        val input = walk().copy(
            recordings = listOf(
                recording(fromSeconds = 400, toSeconds = 420, path = "c.m4a"),
                recording(fromSeconds = 100, toSeconds = 110, path = "a.m4a"),
                recording(fromSeconds = 250, toSeconds = 260, path = "gone.m4a"),
            ),
        )
        val way = checkNotNull(make(input, recordingIsPresent = { it.fileRelativePath != "gone.m4a" }))
        val voices = way.moments.filter { it.isVoice }.sortedBy { it.id }
        assertEquals(listOf("voice-1", "voice-2"), voices.map { it.id })
        assertEquals(listOf("a.m4a", "c.m4a"), voices.map { (it.media as WayMedia.Recording).relativePath })
    }

    @Test
    fun `a short transcription is ambient, none is spoken, and transcripts are trimmed and capped`() {
        val long = List(140) { "words" }.joinToString(" ")
        val input = walk().copy(
            recordings = listOf(
                recording(fromSeconds = 60, toSeconds = 70, transcription = "  birds, mostly \n"),
                recording(fromSeconds = 180, toSeconds = 190, transcription = null),
                recording(fromSeconds = 300, toSeconds = 310, transcription = "   "),
                recording(fromSeconds = 420, toSeconds = 430, transcription = long),
            ),
        )
        val way = build(input)
        fun voiceKind(id: String) = (way.moment(id).kind as WayMomentKind.Voice).kind
        assertEquals(VoiceKind.AMBIENT, voiceKind("voice-1"))
        assertEquals("birds, mostly", way.moment("voice-1").transcript)
        assertEquals(VoiceKind.SPOKEN, voiceKind("voice-2"))
        assertNull(way.moment("voice-2").transcript)
        assertEquals("an empty transcription counts no words", VoiceKind.AMBIENT, voiceKind("voice-3"))
        assertNull(way.moment("voice-3").transcript)
        assertEquals(VoiceKind.SPOKEN, voiceKind("voice-4"))
        assertEquals(long.take(WayMoment.MAX_TRANSCRIPT_CHARACTERS), way.moment("voice-4").transcript)
    }

    @Test
    fun `a voice that starts and ends at one sample keeps its place and has no talking span`() {
        val input = walk().copy(recordings = listOf(recording(fromSeconds = 250, toSeconds = 260)))
        val way = build(input)
        val voice = way.moment("voice-1")
        assertEquals(voice.frac, (voice.kind as WayMomentKind.Voice).endFrac, 0.0)
        assertEquals(listOf(WaySpanKind.MEDITATING), way.spans?.map { it.kind })
    }

    @Test
    fun `a photo keeps its own coordinate and takes its frac by time`() {
        val input = walk().copy(photos = listOf(photo(seconds = 365, lat = 43.0, lng = -8.0)))
        val way = build(input)
        val moment = way.moment("photo-1")
        assertEquals(WayCoordinate(lat = 43.0, lon = -8.0), moment.at)
        assertEquals(WayGeometry(way.route).frac(atElapsed = 360.0), moment.frac, 0.0)
        assertEquals(WayMomentKind.Photo(media = WayMedia.PhotoAsset(localIdentifier = PHOTO_URI)), moment.kind)
    }

    @Test
    fun `a photo with no capture time is skipped`() {
        val input = walk().copy(photos = listOf(photo(seconds = null, lat = 43.0, lng = -8.0)))
        assertEquals(0, build(input).photoCount)
    }

    @Test
    fun `a photo with no coordinates sits at the route sample nearest its time`() {
        val input = walk().copy(
            photos = listOf(
                photo(seconds = 365, lat = null, lng = null, uri = "content://p/1"),
                photo(seconds = 485, lat = 43.0, lng = null, uri = "content://p/2"),
            ),
        )
        val way = build(input)
        assertEquals(WayCoordinate(lat = 42.88, lon = -8.54 + 6 * 0.00122), way.moment("photo-1").at)
        assertEquals(WayCoordinate(lat = 42.88, lon = -8.54 + 8 * 0.00122), way.moment("photo-2").at)
    }

    @Test
    fun `photos number by capture time`() {
        val input = walk().copy(
            photos = listOf(
                photo(seconds = 485, lat = 43.0, lng = -8.0, uri = "content://p/late"),
                photo(seconds = 125, lat = 43.0, lng = -8.0, uri = "content://p/early"),
            ),
        )
        val way = build(input)
        assertEquals(WayMomentKind.Photo(WayMedia.PhotoAsset("content://p/early")), way.moment("photo-1").kind)
        assertEquals(WayMomentKind.Photo(WayMedia.PhotoAsset("content://p/late")), way.moment("photo-2").kind)
    }

    @Test
    fun `both reserved arrival icons are excluded and a user waypoint keeps its own coordinate`() {
        val input = walk().copy(
            waypoints = listOf(
                waypoint(seconds = 500, lon = -8.6, label = "Walked", icon = HonorPersistence.ARRIVAL_WAYPOINT_ICON),
                waypoint(seconds = 400, lon = -8.6, label = "Found", icon = SeekPersistence.ARRIVAL_WAYPOINT_ICON),
                waypoint(seconds = 300, lon = -8.61, label = "Oak", icon = "leaf", lat = 42.9),
            ),
        )
        val way = build(input)
        val kept = way.moments.filter { it.kind is WayMomentKind.Waypoint }
        assertEquals(listOf("waypoint-1"), kept.map { it.id })
        assertEquals(WayMomentKind.Waypoint(label = "Oak", icon = "leaf"), kept.single().kind)
        assertEquals(WayCoordinate(lat = 42.9, lon = -8.61), kept.single().at)
        assertEquals(WayGeometry(way.route).frac(atElapsed = 300.0), kept.single().frac, 0.0)
    }

    @Test
    fun `a waypoint with no icon draws mappin and one with no label has no kicker`() {
        val input = walk().copy(waypoints = listOf(waypoint(seconds = 300, lon = -8.6, label = null, icon = null)))
        assertEquals(WayMomentKind.Waypoint(label = "", icon = "mappin"), build(input).moment("waypoint-1").kind)
    }

    @Test
    fun `pauses of 180 s or more become rests, rounded half away from zero`() {
        val input = walk().copy(pauses = listOf(span(60, 60 + 179), span(300, 300 + 180), span(420, 420 + 270)))
        val way = build(input)
        val rests = way.moments.filter { it.kind is WayMomentKind.Rest }.sortedBy { it.id }
        assertEquals(listOf("rest-1", "rest-2"), rests.map { it.id })
        assertEquals(WayMomentKind.Rest(minutes = 3), rests[0].kind)
        assertEquals("4.5 minutes rounds up, not to even", WayMomentKind.Rest(minutes = 5), rests[1].kind)
        assertEquals("placed at the pause's start", WayCoordinate(42.88, -8.54 + 7 * 0.00122), rests[1].at)
    }

    @Test
    fun `every sitting becomes a meditation, whatever its length`() {
        val input = walk().copy(sittings = listOf(span(60, 80), span(300, 450)))
        val way = build(input)
        assertEquals(WayMomentKind.Meditation(minutes = 0, isEstimate = false), way.moment("sit-1").kind)
        assertEquals(
            "2.5 minutes rounds up",
            WayMomentKind.Meditation(minutes = 3, isEstimate = false),
            way.moment("sit-2").kind,
        )
        assertEquals(WayCoordinate(42.88, -8.54 + 5 * 0.00122), way.moment("sit-2").at)
    }

    @Test
    fun `moments sort by frac, then by id as plain strings`() {
        // Eleven recordings and a waypoint all nearest the sample at 120 s.
        val recordings = (0 until 11).map {
            recording(fromSeconds = 100L + it, toSeconds = 101L + it, path = "r$it.m4a")
        }
        val input = walk().copy(recordings = recordings, pauses = emptyList(), sittings = emptyList())
        val ids = build(input).moments.map { it.id }
        val expected = listOf("voice-1", "voice-10", "voice-11") + (2..9).map { "voice-$it" } + "waypoint-1"
        assertEquals(expected, ids)
    }

    @Test
    fun `the title is the trimmed intention, else the start date in medium style`() {
        assertEquals("the long way round", build(walk().copy(intention = "  the long way round \n")).title)
        assertEquals("May 1, 2026", build(walk().copy(intention = null)).title)
        assertEquals("May 1, 2026", build(walk().copy(intention = "   ")).title)
        val samoa = ZoneId.of("Pacific/Pago_Pago")
        val way = checkNotNull(make(walk(), zone = samoa))
        assertEquals("the date reads in the builder's zone", "Apr 30, 2026", way.title)
        assertEquals("Pacific/Pago_Pago", way.tzIdentifier)
    }

    @Test
    fun `weather rides only with a condition`() {
        assertNull(build(walk().copy(weatherCondition = null, weatherTemperatureC = 14.0)).weather)
        assertEquals(
            WayWeather(condition = "rain", temperatureC = null),
            build(walk().copy(weatherCondition = "rain")).weather,
        )
        assertEquals(
            WayWeather(condition = "clear", temperatureC = 14.5),
            build(walk().copy(weatherCondition = "clear", weatherTemperatureC = 14.5)).weather,
        )
    }

    @Test
    fun `a walk with a route and nothing else still builds, with empty spans`() {
        val bare = walk().copy(
            recordings = emptyList(),
            waypoints = emptyList(),
            pauses = emptyList(),
            sittings = emptyList(),
        )
        val way = build(bare)
        assertEquals(emptyList<WayMoment>(), way.moments)
        assertEquals(emptyList<WaySpan>(), way.spans)
        assertNull(way.marks)
        assertNull(way.stage)
    }

    @Test
    fun `input from rows derives pauses, sittings, and active time from the walk's events`() {
        val row = Walk(
            id = 7,
            uuid = walkUuid,
            startTimestamp = start,
            endTimestamp = at(600),
            intention = "slow",
            weatherCondition = "fog",
            weatherTemperature = 9.5,
        )
        fun event(seconds: Long, type: WalkEventType) = WalkEvent(walkId = 7, timestamp = at(seconds), eventType = type)
        val events = listOf(
            event(100, WalkEventType.PAUSED),
            event(400, WalkEventType.RESUMED),
            event(450, WalkEventType.MEDITATION_START),
            event(550, WalkEventType.MEDITATION_END),
            event(560, WalkEventType.PAUSED),
        )
        val samples = listOf(sample(0, lon = -8.54))
        val input = OwnWalkWayBuilder.Input.fromRows(row, samples, emptyList(), emptyList(), emptyList(), events)
        assertEquals(walkUuid, input.uuid)
        assertEquals(start, input.startedAtMillis)
        assertEquals("slow", input.intention)
        assertEquals("fog", input.weatherCondition)
        assertEquals(9.5, input.weatherTemperatureC ?: 0.0, 0.0)
        assertEquals(samples, input.samples)
        assertEquals("a trailing pause closes at the walk's end", listOf(span(100, 400), span(560, 600)), input.pauses)
        assertEquals(listOf(span(450, 550)), input.sittings)
        assertEquals("600 s less 340 s paused; the sitting counts as active", 260.0, input.activeSeconds, 0.0)
    }

    /**
     * The walk `honor/own-walk-way.json` was built from by iOS's own
     * `OwnWalkWayBuilder` (the harness `main.swift` described in
     * [WayCodecTest]), field for field.
     */
    @Test
    fun `the Android builder reproduces the iOS-built fixture`() {
        val iosFixture = checkNotNull(javaClass.classLoader?.getResourceAsStream(OWN_WALK_FIXTURE)) {
            "missing test resource $OWN_WALK_FIXTURE"
        }.bufferedReader().readText()
        val android = checkNotNull(
            make(
                iosFixtureWalk(),
                recordingIsPresent = { !it.fileRelativePath.endsWith("/gone.m4a") },
                zone = ZoneId.of("Europe/Madrid"),
            ),
        )
        assertSameJsonTree(
            expected = Json.parseToJsonElement(iosFixture),
            actual = Json.parseToJsonElement(WayJson.encode(android)),
            tolerance = 1e-9,
        )
    }

    private fun iosFixtureWalk(): OwnWalkWayBuilder.Input {
        val uuid = "E621E1F8-C36C-495A-93FC-0C247A3E6E5F"
        val folder = "Recordings/$uuid"
        val samples = (0 until 20).map { i ->
            val p = (if (i <= 6) i else i - 1).toDouble()
            RouteDataSample(
                walkId = WALK_ID,
                timestamp = at(5 + 60L * i),
                latitude = 42.88 + 0.0003 * p,
                longitude = -8.54 + 0.00122 * p,
                altitudeMeters = 300 + 1.5 * i,
            )
        }.reversed()
        val transcript = "  I keep coming back to the river. It sounds different in the morning, slower somehow.\n"
        return OwnWalkWayBuilder.Input(
            uuid = uuid,
            startedAtMillis = start,
            intention = "  the long way round  ",
            activeSeconds = 795.0,
            weatherCondition = "clear",
            weatherTemperatureC = 14.5,
            samples = samples,
            recordings = listOf(
                recording(fromSeconds = 130, toSeconds = 190, path = "$folder/r1.m4a", transcription = transcript),
                recording(
                    fromSeconds = 300,
                    toSeconds = 320,
                    path = "$folder/gone.m4a",
                    transcription = "this file was deleted in the app",
                ),
                recording(fromSeconds = 310, toSeconds = 315, path = "", transcription = null),
                recording(fromSeconds = 490, toSeconds = 510, path = "$folder/r2.m4a", transcription = "birds, mostly"),
                recording(fromSeconds = 1_040, toSeconds = 1_100, path = "$folder/r3.m4a", transcription = null),
            ),
            photos = listOf(
                photo(seconds = 275, lat = 42.8807, lng = -8.5351, uri = "9F1C2B7A-3D4E-4F50-8A6B-7C8D9E0F1A2B/L0/002"),
                photo(seconds = 274, lat = 42.8806, lng = -8.5352, uri = "9F1C2B7A-3D4E-4F50-8A6B-7C8D9E0F1A2B/L0/001"),
            ),
            waypoints = listOf(
                waypoint(seconds = 128, lon = -8.5330, label = "Oak", icon = "leaf", lat = 42.8815),
                waypoint(seconds = 700, lon = -8.5290, label = "Found it", icon = "sun.haze", lat = 42.8830),
                waypoint(
                    seconds = 1_140,
                    lon = -8.5181,
                    label = "Walked their way: x",
                    icon = "signpost.right.fill",
                    lat = 42.8854,
                ),
            ),
            pauses = listOf(span(320, 400), span(600, 870)),
            sittings = listOf(span(880, 1_030)),
        )
    }

    private companion object {
        const val WALK_ID = 1L
        const val PHOTO_URI = "content://media/external/images/1"
        const val OWN_WALK_FIXTURE = "honor/own-walk-way.json"
    }
}
