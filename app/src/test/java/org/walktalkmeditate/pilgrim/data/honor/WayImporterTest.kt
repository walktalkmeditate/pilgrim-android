// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.honor

import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.walktalkmeditate.pilgrim.domain.Clock
import org.walktalkmeditate.pilgrim.domain.honor.VoiceKind
import org.walktalkmeditate.pilgrim.domain.honor.Way
import org.walktalkmeditate.pilgrim.domain.honor.WayCoordinate
import org.walktalkmeditate.pilgrim.domain.honor.WayMedia
import org.walktalkmeditate.pilgrim.domain.honor.WayMoment
import org.walktalkmeditate.pilgrim.domain.honor.WayMomentKind
import org.walktalkmeditate.pilgrim.domain.honor.WaySource
import org.walktalkmeditate.pilgrim.domain.honor.WaySpanKind
import org.walktalkmeditate.pilgrim.domain.honor.WayWeather

/**
 * Port of iOS `WayImporterTests.swift@7c200bf`, with its `tour.json`
 * fixture verbatim, plus one rejecting test for each of the 29 bounds in
 * shared-walk spec S1 §4.3 in iOS's check order, the text rules of §7,
 * the HTTP rules of §3 over a MockWebServer (the cross-host redirect
 * refusal and the early length refusal are R6 additions), and pins for
 * the decode differences iOS's decoder doesn't share (S1 open questions
 * 2–5).
 */
class WayImporterTest {

    @get:Rule val folder = TemporaryFolder()

    private val server = MockWebServer()
    private val acceptedAt = Instant.parse("2026-09-30T12:00:00Z")
    private var clockMillis = acceptedAt.toEpochMilli()
    private lateinit var store: WayStore

    @Before
    fun setUp() {
        server.start()
        store = WayStore({ File(folder.root, "Ways") }, Clock { clockMillis }, syncDirectory = { true })
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ---- iOS's tests ----------------------------------------------------

    /** `expires` carries milliseconds because the worker's `Date.toISOString()` does. */
    private fun fixture(expires: String = "2099-01-01T00:00:00.000Z", extraEncounter: String = "") = """
        {"v":1,"theme":"light","time_bucket":"morning","place_start":"Rúa do Franco","place_end":"Obradoiro",
         "weather_condition":"rain","weather_temperature":9,"units":"metric","start_date":"2026-08-01T07:00:00Z",
         "tz_identifier":"Europe/Madrid","expires":"$expires",
         "route":[{"lat":42.88,"lon":-8.545,"alt":250,"ts":1000},{"lat":42.88,"lon":-8.540,"alt":250,"ts":1400},{"lat":42.88,"lon":-8.535,"alt":250,"ts":1600}],
         "total_distance_m":820,
         "encounters":[{"type":"departure","frac":0},
           {"type":"voice","frac":0.5,"end_frac":0.6,"n":1,"duration":40,"dwell":30,"lat":42.8801,"lon":-8.5401},
           {"type":"ambience","frac":0.7,"end_frac":0.75,"n":2,"duration":20,"dwell":20},
           {"type":"photo","frac":0.8,"n":1,"dwell":5},
           {"type":"rest","frac":0.9,"minutes":4,"dwell":4}$extraEncounter,
           {"type":"arrival","frac":1}],
         "meditation":[{"start_frac":0.5,"end_frac":0.5,"duration":720},{"start_frac":0.75,"end_frac":0.75}],
         "activity_segments":[],"stats":{"active_duration":540}}
    """.trimIndent()

    @Test
    fun `builds a Way from the manifest`() {
        val way = build(fixture())

        assertEquals("share:$ID", way.id)
        assertEquals("Rúa do Franco → Obradoiro", way.title)
        assertEquals(listOf(0.0, 400.0, 600.0), way.route.map { it.t })
        assertEquals(540.0, way.theirActiveSeconds, 0.0)
        assertEquals(WayWeather(condition = "rain", temperatureC = 9.0), way.weather)
        val voice = way.moment("voice-1")
        assertEquals(WayCoordinate(lat = 42.8801, lon = -8.5401), voice.at)
        val spoken = voice.kind as WayMomentKind.Voice
        assertEquals(VoiceKind.SPOKEN, spoken.kind)
        assertEquals(WayMedia.File("audio/1.m4a"), spoken.media)
        val ambience = way.moment("voice-2")
        assertNull("older shares carry no coordinate", ambience.at)
        val ambient = ambience.kind as WayMomentKind.Voice
        assertEquals(VoiceKind.AMBIENT to WayMedia.File("audio/2.m4a"), ambient.kind to ambient.media)
        assertEquals(WayMomentKind.Photo(media = WayMedia.File("photos/1.jpg")), way.moment("photo-1").kind)
        assertEquals(
            "the second sitting has no duration: estimated from the 200 s gap of the segment holding frac 0.75",
            listOf(
                WayMomentKind.Meditation(minutes = 12, isEstimate = false),
                WayMomentKind.Meditation(minutes = 3, isEstimate = true),
            ),
            way.moments.map { it.kind }.filterIsInstance<WayMomentKind.Meditation>(),
        )
    }

    @Test
    fun `an expired manifest is returned to the trail`() {
        assertRefused(WayError.RETURNED_TO_TRAIL, fixture(expires = "2000-01-01T00:00:00Z"))
    }

    @Test
    fun `a malformed share id is not found before any network`() = runBlocking {
        val e = assertThrows(WayImportException::class.java) { runBlocking { importer().importShare("../etc") } }

        assertEquals(WayError.NOT_FOUND, e.error)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `an oversized manifest is unavailable`() {
        val many = List(300) { """,{"type":"waypoint","frac":0.5,"label":"x","icon":"leaf","dwell":3}""" }.joinToString("")
        assertRefused(WayError.UNAVAILABLE, fixture(extraEncounter = many))
    }

    @Test
    fun `departure, arrival, and unknown types are skipped`() {
        val way = build(fixture(extraEncounter = """,{"type":"beacon","frac":0.95}"""))

        assertEquals("departure, arrival, and the unknown beacon make nothing", 6, way.moments.size)
        assertTrue("the base fixture carries no waypoint", way.moments.none { it.kind is WayMomentKind.Waypoint })
    }

    /** Just the fields the build reads, each in range by default, so each case below moves exactly one. */
    private fun manifestJson(
        ts0: String = "1000",
        ts1: String = "1400",
        routeLat: String = "42.88",
        encounterFrac: String = "0.5",
        encounterN: String = "1",
        sittingDuration: String = "720",
        weatherTemperature: String = "9",
    ) = """
        {"v":1,"start_date":"2026-08-01T07:00:00Z","expires":"2099-01-01T00:00:00.000Z",
         "weather_temperature":$weatherTemperature,
         "route":[{"lat":$routeLat,"lon":-8.545,"alt":250,"ts":$ts0},{"lat":42.88,"lon":-8.540,"alt":250,"ts":$ts1}],
         "encounters":[{"type":"voice","frac":$encounterFrac,"end_frac":0.6,"n":$encounterN,"duration":40,"lat":42.8801,"lon":-8.5401}],
         "meditation":[{"start_frac":0.25,"end_frac":0.25,"duration":$sittingDuration}],
         "stats":{"active_duration":540}}
    """.trimIndent()

    @Test
    fun `out-of-range numbers are unavailable`() {
        listOf(
            // Long.MIN and Long.MAX decode; only the ts window keeps `ts - ts0` from wrapping.
            "ts overflow pair" to manifestJson(ts0 = "-9223372036854775808", ts1 = "9223372036854775807"),
            "duration" to manifestJson(sittingDuration = "1e300"),
            "weather_temperature" to manifestJson(weatherTemperature = "1e300"),
            "lat" to manifestJson(routeLat = "91"),
            "frac" to manifestJson(encounterFrac = "1.5"),
            "n" to manifestJson(encounterN = "0"),
        ).forEach { (name, json) -> assertRefused(WayError.UNAVAILABLE, json, name) }
        assertEquals("share:$ID", build(manifestJson()).id)
    }

    @Test
    fun `the meditation count is capped`() {
        val many = List(WayImporter.MAX_ENCOUNTERS + 1) { """{"start_frac":0.25,"end_frac":0.25,"duration":60}""" }.joinToString(",")
        assertRefused(WayError.UNAVAILABLE, minimal(meditation = "[$many]"))
    }

    @Test
    fun `a share id is ten safe characters`() {
        assertTrue(WayImporter.isShareId("Qoi4YmPHLN"))
        assertFalse("9 characters", WayImporter.isShareId("Qoi4YmPHL"))
        assertFalse("11 characters", WayImporter.isShareId("Qoi4YmPHLNx"))
        assertFalse("a slash in place of a safe character", WayImporter.isShareId("Qoi4YmPH/N"))
    }

    @Test
    fun `untrusted free text is bounded`() {
        val long = "x".repeat(500)
        val way = build(fixture(extraEncounter = """,{"type":"waypoint","frac":0.4,"label":"$long","icon":"$long"}"""))

        val waypoint = way.moment("waypoint-1").kind as WayMomentKind.Waypoint
        assertEquals("a manifest label reaches a map callout: bounded at the door", 80, waypoint.label.length)
        assertEquals(64, waypoint.icon.length)
    }

    @Test
    fun `the weather condition is bounded`() {
        val way = build(minimal(extra = ""","weather_condition":"${"y".repeat(500)}""""))
        assertEquals(64, way.weather?.condition?.length)
    }

    /** The floor own walks have: a route with no real length is not a Way anyone can follow. */
    @Test
    fun `a route shorter than the floor is unavailable`() {
        assertRefused(WayError.UNAVAILABLE, minimal(route = route(point(lon = -8.545, ts = 1000), point(lon = -8.54501, ts = 1400))))
    }

    @Test
    fun `activity segments become spans and unknown kinds are skipped`() {
        val segments = """[{"kind":"talk","start_frac":0.1,"end_frac":0.2},{"kind":"meditation","start_frac":0.5,"end_frac":0.6},{"kind":"dance","start_frac":0.7,"end_frac":0.8}]"""
        val way = build(minimal(route = ROUTE_3, segments = segments, extra = ""","place_start":"A","place_end":"B""""))

        assertEquals(listOf(WaySpanKind.TALKING, WaySpanKind.MEDITATING), way.spans?.map { it.kind })
        assertEquals(0.1, way.spans!!.first().startFrac, 1e-9)
    }

    @Test
    fun `an activity segment out of range is unavailable`() {
        assertRefused(WayError.UNAVAILABLE, minimal(segments = """[{"kind":"talk","start_frac":0.1,"end_frac":1.5}]"""))
    }

    @Test
    fun `a voice place is trimmed, capped, and dropped when blank`() {
        val extra = """
            ,{"type":"voice","frac":0.55,"end_frac":0.56,"n":3,"duration":5,"dwell":1,"place":"  Rúa do Franco  "},
            {"type":"voice","frac":0.56,"end_frac":0.57,"n":4,"duration":5,"dwell":1,"place":"${"x".repeat(81)}"},
            {"type":"voice","frac":0.57,"end_frac":0.58,"n":5,"duration":5,"dwell":1,"place":"   "}
        """.trimIndent()
        val way = build(fixture(extraEncounter = extra))

        assertNull("a voice without a place stays without one", way.moment("voice-1").place)
        assertEquals("Rúa do Franco", way.moment("voice-3").place)
        assertEquals(WayImporter.MAX_TITLE_PLACE_CHARACTERS, way.moment("voice-4").place?.length)
        assertNull(way.moment("voice-5").place)
    }

    @Test
    fun `a voice transcript is carried, trimmed, and capped`() {
        val extra = """
            ,{"type":"voice","frac":0.55,"end_frac":0.56,"n":3,"duration":5,"dwell":1,"transcript":"  The adobe walls hold the light. Then more.  "},
            {"type":"voice","frac":0.56,"end_frac":0.57,"n":4,"duration":5,"dwell":1,"transcript":"${"y".repeat(WayMoment.MAX_TRANSCRIPT_CHARACTERS + 40)}"},
            {"type":"voice","frac":0.57,"end_frac":0.58,"n":5,"duration":5,"dwell":1,"transcript":"   "}
        """.trimIndent()
        val way = build(fixture(extraEncounter = extra))

        assertNull("a voice the worker never transcribed carries nothing", way.moment("voice-1").transcript)
        assertEquals("The adobe walls hold the light. Then more.", way.moment("voice-3").transcript)
        assertEquals(WayMoment.MAX_TRANSCRIPT_CHARACTERS, way.moment("voice-4").transcript?.length)
        assertNull(way.moment("voice-5").transcript)
        assertEquals("The adobe walls hold the light.", way.moment("voice-3").transcriptLine)
    }

    // ---- The bounds, in iOS's order (S1 §4.3) ---------------------------
    // Rows 1–5 and 29 are the fetch's; they are under "the fetch" below.

    @Test
    fun `row 6 - every required key, missing or null, fails the decode`() {
        val complete = minimal(encounters = """[{"type":"rest","frac":0.5}]""", meditation = """[{"start_frac":0.5,"end_frac":0.5}]""")
        assertEquals("share:$ID", build(complete).id)
        listOf("\"v\":1,", "\"start_date\":\"2026-08-01T07:00:00Z\",", "\"expires\":\"2099-01-01T00:00:00.000Z\",")
            .forEach { assertRefused(WayError.UNAVAILABLE, complete.replace(it, ""), "without $it") }
        listOf("route", "encounters", "meditation").forEach { key ->
            assertRefused(WayError.UNAVAILABLE, complete.replace("\"$key\":", "\"_$key\":"), "without $key")
            assertRefused(WayError.UNAVAILABLE, complete.replace(Regex("\"$key\":\\[[^\\]]*\\]"), "\"$key\":null"), "null $key")
        }
        listOf("\"lat\":42.88,", "\"lon\":-8.545,", "\"alt\":250,", ",\"ts\":1000").forEach {
            assertRefused(WayError.UNAVAILABLE, complete.replaceFirst(it, ""), "a route point without $it")
        }
        assertRefused(WayError.UNAVAILABLE, complete.replace("\"type\":\"rest\",", ""), "an encounter without its type")
        assertRefused(WayError.UNAVAILABLE, complete.replace("\"type\":\"rest\",\"frac\":0.5", "\"type\":\"rest\""), "without its frac")
        assertRefused(WayError.UNAVAILABLE, complete.replace("\"start_frac\":0.5,", ""), "a sitting without its start")
        assertRefused(WayError.UNAVAILABLE, complete.replace(",\"end_frac\":0.5}]", "}]"), "a sitting without its end")
        listOf("""{"start_frac":0.1,"end_frac":0.2}""", """{"kind":"talk","end_frac":0.2}""", """{"kind":"talk","start_frac":0.1}""")
            .forEach { assertRefused(WayError.UNAVAILABLE, minimal(segments = "[$it]"), "a segment $it") }
        assertRefused(WayError.UNAVAILABLE, complete.replace("\"v\":1", "\"v\":\"one\""), "a string for a number")
        assertRefused(WayError.UNAVAILABLE, "not json at all")
    }

    @Test
    fun `row 6 - optional keys may be absent or null, and unknown keys are ignored`() {
        val way = build(
            minimal(
                stats = "null",
                extra = ""","place_start":null,"place_end":null,"weather_condition":null,"tz_identifier":null,"journal":"j","theme":"dark"""",
            ),
        )

        assertNull(way.weather)
        assertNull(way.tzIdentifier)
        assertEquals("the route's span, with no active duration", 400.0, way.theirActiveSeconds, 0.0)
    }

    @Test
    fun `row 6 - the version is required and never compared`() {
        assertEquals("share:$ID", build(minimal().replace("\"v\":1", "\"v\":2")).id)
    }

    @Test
    fun `row 7 - dates that don't parse are unavailable`() {
        assertRefused(WayError.UNAVAILABLE, minimal(startDate = "\"yesterday\""))
        assertRefused(WayError.UNAVAILABLE, minimal(expires = "\"soon\""))
    }

    @Test
    fun `row 8 - an expiry not later than now is returned to the trail, before every count and range`() {
        val now = "2026-10-01T00:00:00Z"
        assertRefused(WayError.RETURNED_TO_TRAIL, minimal(expires = "\"$now\""), "the boundary is strict")
        assertEquals("share:$ID", build(minimal(expires = "\"2026-10-01T00:00:01Z\"")).id)
        assertRefused(
            WayError.RETURNED_TO_TRAIL,
            minimal(expires = "\"2000-01-01T00:00:00Z\"", route = route(point(lat = 91.0, ts = 1000))),
            "an expired, malformed share still reads as returned",
        )
    }

    @Test
    fun `row 9 - the route has 2 to 2000 points`() {
        assertRefused(WayError.UNAVAILABLE, minimal(route = route(point(ts = 1000))))
        assertRefused(WayError.UNAVAILABLE, minimal(route = longRoute(2001)))
        assertEquals(2000, build(minimal(route = longRoute(2000))).route.size)
    }

    @Test
    fun `row 10 - at most 200 encounters, departure, arrival, and unknown kinds counted`() {
        val departure = """{"type":"departure","frac":0}"""
        val beacons = List(198) { """{"type":"beacon","frac":0.5}""" }
        val arrival = """{"type":"arrival","frac":1}"""
        assertEquals(0, build(minimal(encounters = (listOf(departure) + beacons + arrival).json())).moments.size)
        assertRefused(WayError.UNAVAILABLE, minimal(encounters = (listOf(departure) + beacons + beacons.first() + arrival).json()))
    }

    @Test
    fun `row 11 - at most 200 sittings`() {
        val sitting = """{"start_frac":0.25,"end_frac":0.25,"duration":60}"""
        assertEquals(200, build(minimal(meditation = List(200) { sitting }.json())).moments.size)
        assertRefused(WayError.UNAVAILABLE, minimal(meditation = List(201) { sitting }.json()))
    }

    @Test
    fun `rows 12 and 13 - route latitudes and longitudes`() {
        assertEquals("share:$ID", build(minimal(route = route(point(lat = 90.0, ts = 1000), point(lat = 89.99, ts = 1400)))).id)
        listOf(point(lat = 91.0), point(lat = -90.5), point(lon = 181.0), point(lon = -180.5)).forEach {
            assertRefused(WayError.UNAVAILABLE, minimal(route = route(it, point(lon = -8.540, ts = 1400))), it)
        }
    }

    @Test
    fun `row 14 - altitude under 100 km in magnitude, strictly`() {
        assertEquals("share:$ID", build(minimal(route = route(point(alt = 99_999.5), point(lon = -8.540, alt = -99_999.5, ts = 1400)))).id)
        listOf(100_000.0, -100_000.0, 1e300).forEach {
            assertRefused(WayError.UNAVAILABLE, minimal(route = route(point(alt = it), point(lon = -8.540, ts = 1400))), "alt $it")
        }
    }

    @Test
    fun `row 15 - timestamps between 1970 and 2100`() {
        assertEquals("share:$ID", build(minimal(route = route(point(ts = 0), point(lon = -8.540, ts = 4_102_444_800)))).id)
        assertRefused(WayError.UNAVAILABLE, minimal(route = route(point(ts = -1), point(lon = -8.540, ts = 1400))))
        assertRefused(WayError.UNAVAILABLE, minimal(route = route(point(ts = 1000), point(lon = -8.540, ts = 4_102_444_801))))
    }

    @Test
    fun `row 16 - timestamps never go back, though they may repeat`() {
        assertEquals(listOf(0.0, 0.0), build(minimal(route = route(point(ts = 1000), point(lon = -8.540, ts = 1000)))).route.map { it.t })
        assertRefused(WayError.UNAVAILABLE, minimal(route = route(point(ts = 1400), point(lon = -8.540, ts = 1000))))
    }

    @Test
    fun `row 17 - every encounter's frac is in 0 to 1, the skipped kinds included`() {
        listOf(
            """{"type":"rest","frac":1.5}""",
            """{"type":"rest","frac":-0.01}""",
            """{"type":"beacon","frac":1.5}""",
            """{"type":"departure","frac":-1}""",
            """{"type":"voice","frac":2}""",
        ).forEach { assertRefused(WayError.UNAVAILABLE, minimal(encounters = "[$it]"), it) }
    }

    @Test
    fun `rows 18 to 22 - each optional encounter number, when present`() {
        listOf(
            """"end_frac":1.01""",
            """"end_frac":-0.1""",
            """"duration":6480.5""",
            """"duration":-1""",
            """"minutes":1441""",
            """"minutes":-1""",
            """"n":0""",
            """"n":10001""",
            """"lat":91""",
            """"lon":181""",
            """"lat":-91,"lon":0""",
        ).forEach { field ->
            assertRefused(WayError.UNAVAILABLE, minimal(encounters = """[{"type":"beacon","frac":0.5,$field}]"""), field)
        }
        val edges = """[{"type":"voice","frac":0.5,"end_frac":1,"duration":6480,"n":10000},{"type":"rest","frac":0.5,"minutes":1440}]"""
        assertEquals(2, build(minimal(encounters = edges)).moments.size)
    }

    @Test
    fun `rows 23 and 24 - sitting fracs and declared durations`() {
        listOf(
            """{"start_frac":1.5,"end_frac":0.5}""",
            """{"start_frac":0.5,"end_frac":-0.5}""",
            """{"start_frac":0.5,"end_frac":0.5,"duration":6481}""",
            """{"start_frac":0.5,"end_frac":0.5,"duration":-1}""",
        ).forEach { assertRefused(WayError.UNAVAILABLE, minimal(meditation = "[$it]"), it) }
        val edge = build(minimal(meditation = """[{"start_frac":0.5,"end_frac":0.5,"duration":6480}]"""))
        assertEquals(WayMomentKind.Meditation(minutes = 108, isEstimate = false), edge.moments.single().kind)
    }

    @Test
    fun `row 25 - segment fracs`() {
        assertRefused(WayError.UNAVAILABLE, minimal(segments = """[{"kind":"talk","start_frac":-0.1,"end_frac":0.2}]"""))
        assertRefused(WayError.UNAVAILABLE, minimal(segments = """[{"kind":"dance","start_frac":0.1,"end_frac":1.2}]"""))
    }

    @Test
    fun `row 26 - their active time is at most a week`() {
        assertEquals(604_800.0, build(minimal(stats = """{"active_duration":604800}""")).theirActiveSeconds, 0.0)
        assertRefused(WayError.UNAVAILABLE, minimal(stats = """{"active_duration":604801}"""))
        assertRefused(WayError.UNAVAILABLE, minimal(stats = """{"active_duration":-1}"""))
    }

    @Test
    fun `row 27 - the temperature is between -100 and 100`() {
        assertEquals(100.0, build(minimal(extra = ""","weather_condition":"clear","weather_temperature":100""")).weather?.temperatureC)
        assertRefused(WayError.UNAVAILABLE, minimal(extra = ""","weather_temperature":100.5"""))
        assertRefused(WayError.UNAVAILABLE, minimal(extra = ""","weather_temperature":-100.5"""))
    }

    @Test
    fun `row 28 - a route of 20 m or more`() {
        assertRefused(WayError.UNAVAILABLE, minimal(route = route(point(lon = -8.545), point(lon = -8.5452, ts = 1400))))
        assertTrue(build(minimal()).totalDistanceMeters >= 20)
    }

    // ---- The build (S1 §5) ----------------------------------------------

    @Test
    fun `the Way's source names the share on the walk host, and its dates are the manifest's`() {
        val way = build(fixture())

        assertEquals(WaySource.Share(id = ID, pageUrl = "https://walk.pilgrimapp.org/$ID"), way.source)
        assertEquals(Instant.parse("2026-08-01T07:00:00Z"), way.departedAt)
        assertEquals(Instant.parse("2099-01-01T00:00:00Z"), way.expires)
        assertEquals("Europe/Madrid", way.tzIdentifier)
        assertEquals("their whole route, unsampled", 3, way.route.size)
    }

    @Test
    fun `one place titles the Way alone, and none falls back to the date, in the phone's zone and locale`() {
        assertEquals("Obradoiro", build(minimal(extra = ""","place_start":"  ","place_end":"Obradoiro"""")).title)
        assertEquals("Aug 1, 2026", build(minimal()).title)
        val lateUtc = minimal(startDate = "\"2026-08-01T02:00:00Z\"")
        assertEquals("Jul 31, 2026", build(lateUtc, zone = ZoneId.of("America/Los_Angeles")).title)
    }

    @Test
    fun `moments are ordered by frac, then by id as a plain string`() {
        val atOneFrac = """[
            {"type":"waypoint","frac":0.5},{"type":"voice","frac":0.5,"n":1},{"type":"rest","frac":0.5},{"type":"photo","frac":0.5,"n":1}
        ]"""
        val way = build(minimal(encounters = atOneFrac, meditation = """[{"start_frac":0.5,"end_frac":0.5,"duration":60}]"""))
        assertEquals(listOf("photo-1", "rest-1", "sit-1", "voice-1", "waypoint-1"), way.moments.map { it.id })

        val voices = List(10) { """{"type":"voice","frac":0.5,"n":${it + 1}}""" }.json()
        assertEquals("voice-10", build(minimal(encounters = voices)).moments[1].id)
    }

    @Test
    fun `ids count per kind for the moments made, and media paths take the manifest's n`() {
        val encounters = """[
            {"type":"voice","frac":0.1},{"type":"voice","frac":0.2,"n":3},{"type":"ambience","frac":0.3,"n":7},{"type":"photo","frac":0.4,"n":12}
        ]"""
        val way = build(minimal(encounters = encounters))

        assertEquals(listOf("voice-1", "voice-2", "photo-1"), way.moments.map { it.id })
        assertEquals(WayMedia.File("audio/3.m4a"), way.moment("voice-1").media)
        assertEquals(WayMedia.File("audio/7.m4a"), way.moment("voice-2").media)
        assertEquals(WayMedia.File("photos/12.jpg"), way.moment("photo-1").media)
    }

    @Test
    fun `absent numbers fall back as iOS's do, and a lone coordinate gives no place`() {
        val encounters = """[{"type":"voice","frac":0.2,"n":1,"lat":42.88},{"type":"rest","frac":0.4}]"""
        val way = build(minimal(encounters = encounters))

        val voice = way.moment("voice-1")
        assertEquals(WayMomentKind.Voice(endFrac = 0.2, duration = 0.0, kind = VoiceKind.SPOKEN, media = WayMedia.File("audio/1.m4a")), voice.kind)
        assertNull(voice.at)
        assertEquals(WayMomentKind.Rest(minutes = 0), way.moment("rest-1").kind)
    }

    @Test
    fun `a sitting estimate takes the first segment holding its frac, so a vertex takes the one ending there`() {
        // The walker moved for 100 s, then sat 600 s where they stopped; frac 1 lies on both segments.
        val route = route(point(lon = -8.545, ts = 1000), point(lon = -8.540, ts = 1100), point(lon = -8.540, ts = 1700))
        val way = build(minimal(route = route, meditation = """[{"start_frac":1,"end_frac":1}]"""))

        assertEquals(WayMomentKind.Meditation(minutes = 2, isEstimate = true), way.moments.single().kind)
    }

    @Test
    fun `spans keep the manifest's order at a shared start, and one of no length is dropped`() {
        val segments = """[{"kind":"meditation","start_frac":0.2,"end_frac":0.4},{"kind":"talk","start_frac":0.2,"end_frac":0.3},{"kind":"talk","start_frac":0.6,"end_frac":0.6}]"""
        val way = build(minimal(segments = segments))

        assertEquals(listOf(WaySpanKind.MEDITATING, WaySpanKind.TALKING), way.spans?.map { it.kind })
    }

    // ---- Sharer text (S1 §7) --------------------------------------------

    @Test
    fun `places trim Swift's whitespace set, which is not Kotlin's`() {
        val start = json(0x200B) + "Rúa do Franco" + json(0x0085)
        val end = json(0x001C) + "Obradoiro"
        val way = build(minimal(extra = ""","place_start":"$start","place_end":"$end""""))

        assertEquals("Rúa do Franco → " + Char(0x001C) + "Obradoiro", way.title)
    }

    @Test
    fun `labels and the weather are cut but not trimmed, and an icon falls back only when absent`() {
        val encounters = """[{"type":"waypoint","frac":0.1,"label":"  bench  ","icon":""},{"type":"waypoint","frac":0.2}]"""
        val way = build(minimal(encounters = encounters, extra = ""","weather_condition":" rain """"))

        assertEquals(WayMomentKind.Waypoint(label = "  bench  ", icon = ""), way.moment("waypoint-1").kind)
        assertEquals(WayMomentKind.Waypoint(label = "", icon = "mappin"), way.moment("waypoint-2").kind)
        assertEquals(WayWeather(condition = " rain ", temperatureC = null), way.weather)
    }

    @Test
    fun `an empty condition still makes weather, and a temperature without one makes none`() {
        assertEquals(WayWeather(condition = "", temperatureC = 9.0), build(minimal(extra = ""","weather_condition":"","weather_temperature":9""")).weather)
        assertNull(build(minimal(extra = ""","weather_temperature":9""")).weather)
    }

    @Test
    fun `text is cut by characters as Swift counts them, and the zone is kept whole`() {
        val accented = "e" + Char(0x0301)
        val zone = "Z".repeat(300)
        val way = build(
            minimal(
                encounters = """[{"type":"waypoint","frac":0.1,"label":"${accented.repeat(81)}"}]""",
                extra = ""","tz_identifier":"$zone"""",
            ),
        )

        assertEquals(accented.repeat(80), (way.moment("waypoint-1").kind as WayMomentKind.Waypoint).label)
        assertEquals(zone, way.tzIdentifier)
    }

    // ---- Dates (S1 §4.4) --------------------------------------------------

    @Test
    fun `every date either app or the worker writes parses, kept to the millisecond`() {
        listOf(
            "2026-08-01T07:00:00Z" to "2026-08-01T07:00:00Z",
            "2026-08-01T07:00:00.000Z" to "2026-08-01T07:00:00Z",
            "2026-08-01T07:00:00.1Z" to "2026-08-01T07:00:00.100Z",
            "2026-08-01T07:00:00.123456789Z" to "2026-08-01T07:00:00.123Z",
            "2026-08-01T09:00:00+02:00" to "2026-08-01T07:00:00Z",
            "2026-08-01T09:00:00.123+02:00" to "2026-08-01T07:00:00.123Z",
        ).forEach { (text, instant) -> assertEquals(text, Instant.parse(instant), WayImporter.isoDate(text)) }
    }

    @Test
    fun `strings neither app writes do not parse`() {
        // iOS reads `+0200` and rolls the 30th of February on; neither is reachable (S1 §4.4).
        listOf(
            "2026-08-01t07:00:00z",
            "2026-08-01T09:00:00+0200",
            "2026-02-30T07:00:00Z",
            "2026-08-01T07:00:60Z",
            "2026-08-01T07:00Z",
            "2026-08-01T07:00:00",
            "2026-08-01 07:00:00Z",
            "20260801T070000Z",
            "2026-08-01T07:00:00.Z",
        ).forEach { assertNull(it, WayImporter.isoDate(it)) }
    }

    // ---- Decode differences from Foundation's, pinned (S1 §2.4) ----------

    @Test
    fun `kotlinx reads a quoted number, where iOS refuses one`() {
        val quoted = minimal(route = route(point(ts = 1000), point(lon = -8.540, ts = 1400)).replace("\"ts\":1000", "\"ts\":\"1000\""))
        assertEquals(400.0, build(quoted).route.last().t, 0.0)
    }

    @Test
    fun `kotlinx refuses an integer written with a fraction, where iOS reads one`() {
        assertRefused(WayError.UNAVAILABLE, minimal().replace("\"ts\":1000", "\"ts\":1000.0"))
    }

    @Test
    fun `a repeated key keeps its last value, where iOS keeps its first`() {
        val twice = minimal().replace("\"v\":1,", "\"v\":1,\"start_date\":\"2026-08-02T07:00:00Z\",")
        assertEquals(Instant.parse("2026-08-01T07:00:00Z"), build(twice).departedAt)
    }

    @Test
    fun `a number too large for a double fails the decode, as on iOS`() {
        assertRefused(WayError.UNAVAILABLE, minimal(stats = """{"active_duration":1e400}"""))
    }

    // ---- The fetch (S1 §3; rows 1–5 and 29) ------------------------------

    @Test
    fun `the client is its own - 15 s to connect and read, 30 s in all, no retry, no cache`() {
        val client = WayImporter.httpClient(WayImporter.BASE_URL.toHttpUrl())

        assertEquals(15_000, client.connectTimeoutMillis)
        assertEquals(15_000, client.readTimeoutMillis)
        assertEquals(30_000, client.callTimeoutMillis)
        assertFalse(client.retryOnConnectionFailure)
        assertNull(client.cache)
    }

    @Test
    fun `a share is fetched from the walk host and listed, its first import its acceptance`() = runBlocking {
        server.enqueue(MockResponse().setBody(fixture()))

        val way = importer().importShare(ID)

        assertEquals("/$ID/tour.json", server.takeRequest().path)
        assertEquals(way.id, store.load("share:$ID")?.id)
        assertEquals(acceptedAt, store.acceptedAt("share:$ID"))
    }

    @Test
    fun `a share imported again rewrites its Way and keeps its first acceptance`() = runBlocking {
        server.enqueue(MockResponse().setBody(fixture()))
        server.enqueue(MockResponse().setBody(fixture().replace("Obradoiro", "Praza")))
        importer().importShare(ID)
        clockMillis += 86_400_000

        importer().importShare(ID)

        assertEquals("Rúa do Franco → Praza", store.load("share:$ID")?.title)
        assertEquals(acceptedAt, store.acceptedAt("share:$ID"))
    }

    @Test
    fun `row 2 - a 404 is not found`() {
        server.enqueue(MockResponse().setResponseCode(404))
        assertImportRefused(WayError.NOT_FOUND)
    }

    @Test
    fun `row 3 - any other status is unavailable`() {
        listOf(500, 410, 206, 204).forEach { code ->
            server.enqueue(MockResponse().setResponseCode(code).setBody(fixture()))
            assertImportRefused(WayError.UNAVAILABLE, "status $code")
        }
    }

    // An R6 addition: iOS cuts the stream at the same bound, later (correction 11).
    @Test
    fun `row 4 - a declared length over the cap is refused before the body is read`() = runBlocking {
        val oversized = Buffer().write(ByteArray(3 * 1024 * 1024) { ' '.code.toByte() })
        // At 64 KiB a second, reading this body would outlast the 30 s call.
        server.enqueue(MockResponse().setBody(oversized).throttleBody(64 * 1024L, 1, TimeUnit.SECONDS))

        val e = withTimeout(10_000) {
            runCatching { importer().importShare(ID) }.exceptionOrNull()
        }

        assertEquals(WayError.UNAVAILABLE, (e as WayImportException).error)
    }

    @Test
    fun `row 5 - a body past the cap is refused as it streams, and exactly the cap is read`() {
        // The cap counts bytes: the fixture's "ú" is two, so pad by characters to the byte count.
        val base = fixture()
        val wideBytes = base.toByteArray().size - base.length
        val exact = base.padEnd(WayImporter.MAX_MANIFEST_BYTES.toInt() - wideBytes, ' ')
        val atCap = Buffer().writeUtf8(exact)
        assertEquals(WayImporter.MAX_MANIFEST_BYTES, atCap.size)
        server.enqueue(MockResponse().setChunkedBody(atCap, 64 * 1024))
        assertEquals("share:$ID", runBlocking { importer().importShare(ID) }.id)

        server.enqueue(MockResponse().setChunkedBody(Buffer().writeUtf8(exact + " "), 64 * 1024))
        assertImportRefused(WayError.UNAVAILABLE)
    }

    @Test
    fun `offline, a dropped connection, and an undecodable body are all unavailable, and nothing is retried`() {
        server.enqueue(MockResponse().setBody("<html>captive portal</html>"))
        assertImportRefused(WayError.UNAVAILABLE, "a captive portal's page")

        // A retry would take the good response queued behind the drop.
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(MockResponse().setBody(fixture()))
        assertImportRefused(WayError.UNAVAILABLE, "a dropped connection")
        assertEquals("no retry on the same call", 2, server.requestCount)

        val gone = MockWebServer().apply { start() }
        val offline = gone.url("/")
        gone.shutdown()
        val e = assertThrows(WayImportException::class.java) { runBlocking { importer(base = offline).importShare(ID) } }
        assertEquals(WayError.UNAVAILABLE, e.error)
    }

    // An R6 addition: iOS follows an HTTPS redirect to any host (correction 11).
    @Test
    fun `a redirect off the walk host is refused, and one on it is followed`() {
        val elsewhere = MockWebServer().apply { start() }
        try {
            elsewhere.enqueue(MockResponse().setBody(fixture()))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", elsewhere.url("/$ID/tour.json")))
            assertImportRefused(WayError.UNAVAILABLE)
            assertEquals(0, elsewhere.requestCount)
        } finally {
            elsewhere.shutdown()
        }

        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/moved/tour.json"))
        server.enqueue(MockResponse().setBody(fixture()))
        assertEquals("share:$ID", runBlocking { importer().importShare(ID) }.id)
    }

    @Test
    fun `a build that refuses the share saves nothing`() {
        server.enqueue(MockResponse().setBody(fixture(expires = "2000-01-01T00:00:00Z")))

        assertImportRefused(WayError.RETURNED_TO_TRAIL)
        assertNull(store.load("share:$ID"))
    }

    @Test
    fun `row 29 - a save that fails throws the store's own error`() {
        val blocked = File(folder.root, "blocked").apply { writeText("a file where the store's folder should be") }
        server.enqueue(MockResponse().setBody(fixture()))

        assertThrows(IOException::class.java) {
            runBlocking { importer(store = WayStore({ File(blocked, "Ways") }, syncDirectory = { true })).importShare(ID) }
        }
    }

    // S1 §8.10, pilgrim-ios #114, matched: nothing checks for cancellation once the body is in.
    @Test
    fun `an import cancelled after its body arrived still lists its Way`() = runBlocking {
        server.enqueue(MockResponse().setBody(fixture()))
        lateinit var job: Job
        // The clock is read after the decode and before the save: the cancel lands right there.
        val importer = importer(clock = Clock {
            job.cancel()
            acceptedAt.toEpochMilli()
        })

        job = launch(Dispatchers.IO, start = CoroutineStart.LAZY) { importer.importShare(ID) }
        job.start()
        job.join()

        assertTrue(job.isCancelled)
        assertNotNull(store.load("share:$ID"))
    }

    // ---- Helpers ----------------------------------------------------------

    private fun importer(
        base: okhttp3.HttpUrl = server.url("/"),
        store: WayStore = this.store,
        clock: Clock = Clock { NOW.toEpochMilli() },
    ) = WayImporter(
        client = WayImporter.httpClient(base),
        baseUrl = base,
        store = store,
        clock = clock,
        zone = { UTC },
        locale = { Locale.US },
        ioDispatcher = Dispatchers.IO,
    )

    private fun build(json: String, zone: ZoneId = UTC): Way =
        WayImporter.way(WayImporter.manifest(json.toByteArray()), shareId = ID, now = NOW, zone = zone, locale = Locale.US)

    private fun assertRefused(expected: WayError, json: String, message: String = json) {
        val e = assertThrows(message, WayImportException::class.java) { build(json) }
        assertEquals(message, expected, e.error)
    }

    private fun assertImportRefused(expected: WayError, message: String = expected.name) {
        val e = assertThrows(message, WayImportException::class.java) { runBlocking { importer().importShare(ID) } }
        assertEquals(message, expected, e.error)
    }

    private fun Way.moment(id: String): WayMoment = moments.single { it.id == id }

    /** A JSON `\u` escape, so no source file carries the character itself. */
    private fun json(code: Int): String = "\\u" + String.format(Locale.ROOT, "%04x", code)

    private fun List<String>.json(): String = joinToString(",", "[", "]")

    private fun point(lat: Double = 42.88, lon: Double = -8.545, alt: Double = 250.0, ts: Long = 1000): String =
        """{"lat":$lat,"lon":$lon,"alt":$alt,"ts":$ts}"""

    private fun route(vararg points: String): String = points.joinToString(",", "[", "]")

    private fun longRoute(count: Int): String =
        route(*Array(count) { point(lon = -8.545 + it * 0.0001, ts = 1000L + it) })

    /** A valid manifest with only what the build reads; each argument is raw JSON. */
    private fun minimal(
        startDate: String = "\"2026-08-01T07:00:00Z\"",
        expires: String = "\"2099-01-01T00:00:00.000Z\"",
        route: String = ROUTE_2,
        encounters: String = "[]",
        meditation: String = "[]",
        segments: String = "[]",
        stats: String = """{"active_duration":540}""",
        extra: String = "",
    ) = """{"v":1,"start_date":$startDate,"expires":$expires,"route":$route,"encounters":$encounters,""" +
        """"meditation":$meditation,"activity_segments":$segments,"stats":$stats$extra}"""

    private companion object {
        const val ID = "Qoi4YmPHLN"
        val NOW: Instant = Instant.parse("2026-10-01T00:00:00Z")
        val UTC: ZoneId = ZoneId.of("UTC")
        const val ROUTE_2 = """[{"lat":42.88,"lon":-8.545,"alt":250,"ts":1000},{"lat":42.88,"lon":-8.540,"alt":250,"ts":1400}]"""
        const val ROUTE_3 =
            """[{"lat":42.88,"lon":-8.545,"alt":250,"ts":1000},{"lat":42.88,"lon":-8.540,"alt":250,"ts":1400},{"lat":42.88,"lon":-8.535,"alt":250,"ts":1600}]"""
    }
}
