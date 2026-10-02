// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain.honor

import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording

/**
 * The `way.json` wire format (parity spec A §2) against a file iOS wrote.
 *
 * How `honor/own-walk-way.json` was made: iOS's own code wrote it, not a
 * hand (spec A, open question 3). A throwaway harness outside both repos
 * compiled `Pilgrim/Models/Honor/Way.swift`, `WayGeometry.swift`, and
 * `OwnWalkWayBuilder.swift` verbatim from `git show 7c200bf:…` with
 * `swiftc` 6.3.3 on macOS, beside stubs of the seven data interfaces the
 * builder reads (only the members it touches, at the pin's types) and
 * verbatim copies of `TourBuilder.classify`, `SeekPersistence
 * .isArrivalWaypoint`, and `HonorPersistence.isArrivalWaypoint`. Its
 * `main.swift` built one walk (mirrored field for field by
 * `OwnWalkWayBuilderTest.iosFixtureWalk`), ran the real
 * `OwnWalkWayBuilder.make(from:)`, and wrote the Way with the encoder
 * `WayStore.swift:26-31@7c200bf` uses: `dateEncodingStrategy = .iso8601`,
 * `outputFormatting = [.sortedKeys]`. It ran as
 * `CFFIXED_USER_HOME=<scratch> TZ=Europe/Madrid ./harness own-walk-way.json`,
 * so the builder's Documents file probe and `TimeZone.current` were the
 * harness's own. The bytes are unedited.
 *
 * Android cannot write those exact bytes, and the gap is spelling only:
 * Swift writes a whole-number `Double` as `60` where kotlinx writes
 * `60.0`, and Swift escapes `/` as `\/`. So the re-encode is checked as a
 * JSON tree (same keys in the same sorted order, same strings, numbers
 * equal by value), and then as text once those two spellings are
 * normalized.
 */
class WayCodecTest {

    private val fixture: String by lazy {
        checkNotNull(javaClass.classLoader?.getResourceAsStream(OWN_WALK_FIXTURE)) {
            "missing test resource $OWN_WALK_FIXTURE"
        }.bufferedReader().readText()
    }

    @Test
    fun `the iOS fixture decodes`() {
        val way = WayJson.decode(fixture)
        assertEquals("walk:E621E1F8-C36C-495A-93FC-0C247A3E6E5F", way.id)
        assertEquals(WaySource.OwnWalk(uuid = "E621E1F8-C36C-495A-93FC-0C247A3E6E5F"), way.source)
        assertEquals("the long way round", way.title)
        assertEquals(Instant.parse("2026-05-01T08:00:00Z"), way.departedAt)
        assertEquals("Europe/Madrid", way.tzIdentifier)
        assertNull(way.expires)
        assertEquals(20, way.route.size)
        assertEquals(WayPoint(lat = 42.88, lon = -8.54, alt = 300.0, t = 0.0), way.route.first())
        assertEquals(795.0, way.theirActiveSeconds, 0.0)
        assertEquals(WayWeather(condition = "clear", temperatureC = 14.5), way.weather)
        assertEquals(
            listOf("voice-1", "waypoint-1", "photo-1", "photo-2", "voice-2", "rest-1", "sit-1", "voice-3"),
            way.moments.map { it.id },
        )
        val voice = way.moments[0]
        assertEquals(WayCoordinate(lat = 42.8806, lon = -8.53756), voice.at)
        val voiceKind = voice.kind as WayMomentKind.Voice
        assertEquals(60.0, voiceKind.duration, 0.0)
        assertEquals(VoiceKind.SPOKEN, voiceKind.kind)
        assertEquals(WayMedia.Recording("Recordings/E621E1F8-C36C-495A-93FC-0C247A3E6E5F/r1.m4a"), voiceKind.media)
        assertEquals(
            "I keep coming back to the river. It sounds different in the morning, slower somehow.",
            voice.transcript,
        )
        assertEquals("I keep coming back to the river.", voice.transcriptLine)
        assertEquals(VoiceKind.AMBIENT, (way.moments[4].kind as WayMomentKind.Voice).kind)
        assertNull(way.moments[7].transcript)
        assertEquals(WayMomentKind.Waypoint(label = "Oak", icon = "leaf"), way.moments[1].kind)
        assertEquals(
            WayMomentKind.Photo(WayMedia.PhotoAsset("9F1C2B7A-3D4E-4F50-8A6B-7C8D9E0F1A2B/L0/001")),
            way.moments[2].kind,
        )
        assertEquals(WayMomentKind.Rest(minutes = 5), way.moments[5].kind)
        assertEquals(WayMomentKind.Meditation(minutes = 3, isEstimate = false), way.moments[6].kind)
        assertEquals(
            listOf(WaySpanKind.TALKING, WaySpanKind.MEDITATING, WaySpanKind.TALKING),
            way.spans?.map { it.kind },
        )
        assertNull(way.marks)
        assertNull(way.stage)
    }

    @Test
    fun `re-encoding the iOS fixture gives the same JSON tree, keys in the same order`() {
        val reencoded = WayJson.encode(WayJson.decode(fixture))
        assertSameJsonTree(Json.parseToJsonElement(fixture), Json.parseToJsonElement(reencoded))
    }

    @Test
    fun `the re-encoded text differs from iOS's only in how it spells whole numbers and slashes`() {
        val reencoded = WayJson.encode(WayJson.decode(fixture))
        assertEquals(
            fixture.replace("\\/", "/"),
            reencoded.replace(WHOLE_NUMBER_DOUBLE, "$1"),
        )
    }

    @Test
    fun `an Android-built way round-trips`() {
        val way = checkNotNull(
            OwnWalkWayBuilder.make(builtWalk(), { true }, zone = ZoneOffset.UTC, locale = Locale.US),
        )
        assertEquals(way, WayJson.decode(WayJson.encode(way)))
    }

    @Test
    fun `every source and media case round-trips`() {
        val shared = way(
            source = WaySource.Share(id = "Qoi4YmPHLN", pageUrl = "https://walk.pilgrimapp.org/Qoi4YmPHLN"),
            moments = listOf(
                WayMoment(
                    id = "voice-1",
                    frac = 0.1,
                    at = WayCoordinate(42.1, -8.2),
                    kind = WayMomentKind.Voice(
                        endFrac = 0.2,
                        duration = 5.5,
                        kind = VoiceKind.AMBIENT,
                        media = WayMedia.File("audio/1.m4a"),
                    ),
                    place = "Rúa do Franco",
                    transcript = "the worker's words",
                ),
                WayMoment(
                    id = "photo-1",
                    frac = 0.2,
                    at = null,
                    kind = WayMomentKind.Photo(WayMedia.File("photos/1.jpg")),
                ),
            ),
        )
        val own = way(source = WaySource.OwnWalk("e621e1f8-c36c-495a-93fc-0c247a3e6e5f"))
        val stage = way(source = WaySource.Pilgrimage(routeId = "camino-frances", stageIndex = 7))
        for (original in listOf(shared, own, stage)) {
            assertEquals(original, WayJson.decode(WayJson.encode(original)))
        }
    }

    /** Ports iOS `WayStoreTests.testAWayWrittenBeforeSpansExistedStillDecodes`. */
    @Test
    fun `nil optionals stay off the wire`() {
        val rest = WayMoment(id = "rest-1", frac = 0.5, at = null, kind = WayMomentKind.Rest(minutes = 3))
        val way = way(source = WaySource.OwnWalk("e621e1f8-c36c-495a-93fc-0c247a3e6e5f"), moments = listOf(rest))
        val json = WayJson.encode(way)
        val omitted = listOf("spans", "marks", "stage", "tzIdentifier", "expires", "weather")
        for (key in omitted + listOf("alt", "at", "transcript", "place")) {
            assertFalse("nil $key stays off the wire", json.contains("\"$key\""))
        }
        assertFalse(json.contains("null"))
        assertNull(WayJson.decode(json).spans)
    }

    /** Ports iOS `PilgrimageWayImporterTests.testAWayWrittenBeforeStagesStillDecodes`. */
    @Test
    fun `a way written before stages still decodes`() {
        val json = """
            {"id":"share:Qoi4YmPHLN",
             "source":{"share":{"id":"Qoi4YmPHLN","pageURL":"https://walk.pilgrimapp.org/Qoi4YmPHLN"}},
             "title":"Rúa do Franco → Obradoiro","departedAt":"2026-08-01T07:00:00Z",
             "expires":"2099-01-01T00:00:00Z",
             "route":[{"lat":42.88,"lon":-8.545,"t":0},{"lat":42.88,"lon":-8.540,"t":400}],
             "totalDistanceMeters":420,"theirActiveSeconds":400,
             "moments":[{"id":"waypoint-1","frac":0.5,"kind":{"waypoint":{"label":"Oak","icon":"leaf"}}}]}
        """.trimIndent()
        val way = WayJson.decode(json)
        assertNull(way.marks)
        assertNull(way.stage)
        assertNull(way.moments[0].text)
        assertNull(way.moments[0].pin)
        assertEquals(WaySource.Share(id = "Qoi4YmPHLN", pageUrl = "https://walk.pilgrimapp.org/Qoi4YmPHLN"), way.source)
        assertEquals(Instant.parse("2099-01-01T00:00:00Z"), way.expires)
        assertNull(way.route[0].alt)
    }

    /** Ports iOS `PilgrimageWayImporterTests.testAStageWayCarriesMarksAndAStageBlock`. */
    @Test
    fun `a stage way carries marks and a stage block`() {
        val stage = WayStage(
            routeId = "camino-frances",
            index = 0,
            count = 33,
            name = "Saint-Jean-Pied-de-Port to Roncesvalles",
            theme = "Initiation",
            narrative = "The Pyrenees are …",
            closing = "You crossed a border on foot.",
            warnings = listOf("The Napoleon Route closes in winter."),
            distanceKm = 24.2,
            gainMeters = 1419.0,
            hours = WayStageHours(min = 7.0, max = 9.0),
            difficulty = "hard",
            start = WayStagePlace(name = "Saint-Jean-Pied-de-Port", at = WayCoordinate(lat = 43.163, lon = -1.236)),
            end = WayStagePlace(name = "Roncesvalles", at = WayCoordinate(lat = 43.01, lon = -1.319)),
        )
        val mark = WayMark(
            id = "wp-fuente-roldan",
            kind = WayMarkKind.WATER,
            name = "Fuente de Roldán",
            at = WayCoordinate(lat = 43.1, lon = -1.3),
            frac = 0.42,
            offLineMeters = 40.0,
        )
        val moment = WayMoment(
            id = "wp-orisson",
            frac = 0.3,
            at = WayCoordinate(lat = 0.0, lon = 0.0027),
            kind = WayMomentKind.Waypoint(label = "Vierge d'Orisson", icon = "building.columns"),
            text = "A shepherd carried this Madonna up from Lourdes.",
            names = mapOf("fr" to "Vierge d'Orisson"),
            sitMinutes = 5,
            pin = WayCoordinate(lat = 0.0, lon = 0.0028),
        )
        val way = Way(
            id = "pilgrimage:camino-frances:0",
            source = WaySource.Pilgrimage(routeId = "camino-frances", stageIndex = 0),
            title = stage.name,
            departedAt = Instant.ofEpochSecond(1_700_000_000),
            tzIdentifier = "Europe/Madrid",
            expires = null,
            route = listOf(
                WayPoint(lat = 0.0, lon = 0.0, alt = null, t = 0.0),
                WayPoint(lat = 0.0, lon = 0.00898, alt = null, t = 28_800.0),
            ),
            totalDistanceMeters = 1000.0,
            theirActiveSeconds = 28_800.0,
            moments = listOf(moment),
            weather = null,
            marks = listOf(mark),
            stage = stage,
        )

        val round = WayJson.decode(WayJson.encode(way))
        assertEquals(way, round)
        assertEquals(9.0, round.stage?.hours?.max ?: 0.0, 0.0)
        assertEquals(WayMarkKind.WATER, round.marks?.first()?.kind)
        assertEquals(5, round.moments.first().sitMinutes)
        assertEquals(WayCoordinate(lat = 0.0, lon = 0.0028), round.moments.first().pin)
        assertTrue(round.isPilgrimageStage)
    }

    @Test
    fun `unknown keys are ignored at every level`() {
        val extended = fixture
            .replaceFirst("{\"departedAt\"", "{\"futureTopLevel\":{\"a\":[1,2]},\"departedAt\"")
            .replaceFirst("{\"at\":", "{\"futureMomentField\":true,\"at\":")
            .replaceFirst("{\"voice\":{", "{\"voice\":{\"futureVoiceField\":\"x\",")
            .replaceFirst("{\"alt\":", "{\"futurePointField\":1,\"alt\":")
        assertTrue(extended.contains("futureVoiceField"))
        assertEquals(WayJson.decode(fixture), WayJson.decode(extended))
    }

    @Test
    fun `an unknown case or raw value fails the whole way`() {
        val badSpan = fixture.replaceFirst("\"kind\":\"meditating\"", "\"kind\":\"running\"")
        val badKind = fixture.replaceFirst("{\"rest\":{\"minutes\":5}}", "{\"dance\":{\"minutes\":5}}")
        val twoCases = fixture.replaceFirst(
            "{\"rest\":{\"minutes\":5}}",
            "{\"rest\":{\"minutes\":5},\"meditation\":{\"minutes\":1,\"isEstimate\":false}}",
        )
        val badSource = fixture.replaceFirst("{\"ownWalk\":", "{\"somebody\":")
        val badVoiceKind = fixture.replaceFirst("\"kind\":\"ambient\"", "\"kind\":\"music\"")
        for (json in listOf(badSpan, badKind, twoCases, badSource, badVoiceKind)) {
            assertTrue("the edit found its text", json != fixture)
            assertThrows(SerializationException::class.java) { WayJson.decode(json) }
        }
    }

    @Test
    fun `dates are whole-second UTC on the wire`() {
        val way = way(source = WaySource.OwnWalk("x")).copy(
            departedAt = Instant.ofEpochMilli(1_777_622_400_987),
            expires = Instant.parse("2099-01-01T00:00:00Z"),
        )
        val json = WayJson.encode(way)
        assertTrue(json.contains("\"departedAt\":\"2026-05-01T08:00:00Z\""))
        assertTrue(json.contains("\"expires\":\"2099-01-01T00:00:00Z\""))
        assertEquals(Instant.parse("2026-05-01T08:00:00Z"), WayJson.decode(json).departedAt)

        val offset = fixture.replace("\"2026-05-01T08:00:00Z\"", "\"2026-05-01T10:00:00+02:00\"")
        assertEquals(Instant.parse("2026-05-01T08:00:00Z"), WayJson.decode(offset).departedAt)
        val fractional = fixture.replace("\"2026-05-01T08:00:00Z\"", "\"2026-05-01T08:00:00.5Z\"")
        assertThrows("iOS's .iso8601 refuses fractional seconds", SerializationException::class.java) {
            WayJson.decode(fractional)
        }
        val dateOnly = fixture.replace("\"2026-05-01T08:00:00Z\"", "\"2026-05-01\"")
        assertThrows(SerializationException::class.java) { WayJson.decode(dateOnly) }
    }

    @Test
    fun `associated values are one-key objects, an unlabeled value is _0, and counts are integers`() {
        val file = WayMedia.File("a/1.m4a")
        val json = WayJson.encode(
            way(
                source = WaySource.OwnWalk("E621E1F8-C36C-495A-93FC-0C247A3E6E5F"),
                moments = listOf(
                    WayMoment("voice-1", 0.1, null, WayMomentKind.Voice(0.2, 50.0, VoiceKind.SPOKEN, file)),
                    WayMoment("rest-1", 0.2, null, WayMomentKind.Rest(minutes = 3)),
                    WayMoment("sit-1", 0.3, null, WayMomentKind.Meditation(minutes = 12, isEstimate = true)),
                ),
            ),
        )
        assertTrue(json.contains("\"source\":{\"ownWalk\":{\"_0\":\"E621E1F8-C36C-495A-93FC-0C247A3E6E5F\"}}"))
        val voice = """{"voice":{"duration":50.0,"endFrac":0.2,"kind":"spoken","media":{"file":{"_0":"a/1.m4a"}}}}"""
        assertTrue(json.contains(voice))
        assertTrue(json.contains("{\"rest\":{\"minutes\":3}}"))
        assertTrue(json.contains("{\"meditation\":{\"isEstimate\":true,\"minutes\":12}}"))
    }

    @Test
    fun `keys are sorted at every level, map keys included`() {
        val moment = WayMoment(
            id = "wp-1",
            frac = 0.5,
            at = WayCoordinate(lat = 1.0, lon = 2.0),
            kind = WayMomentKind.Waypoint(label = "Oak", icon = "leaf"),
            names = mapOf("ja" to "樫", "es" to "Roble", "fr" to "Chêne"),
        )
        val encoded = WayJson.encode(way(source = WaySource.OwnWalk("x"), moments = listOf(moment)))
        val top = Json.parseToJsonElement(encoded).jsonObject
        assertEquals(top.keys.sorted(), top.keys.toList())
        val encodedMoment = top.getValue("moments").jsonArray[0].jsonObject
        assertEquals(listOf("at", "frac", "id", "kind", "names"), encodedMoment.keys.toList())
        assertEquals(listOf("es", "fr", "ja"), encodedMoment.getValue("names").jsonObject.keys.toList())
    }

    @Test
    fun `a non-finite number fails to encode, as JSONEncoder does`() {
        val broken = way(source = WaySource.OwnWalk("x")).copy(totalDistanceMeters = Double.NaN)
        assertThrows(SerializationException::class.java) { WayJson.encode(broken) }
    }

    private fun way(source: WaySource, moments: List<WayMoment> = emptyList()): Way = Way(
        id = "walk:t",
        source = source,
        title = "t",
        departedAt = Instant.ofEpochSecond(2_000_000),
        tzIdentifier = null,
        expires = null,
        route = listOf(
            WayPoint(lat = 0.0, lon = 0.0, alt = null, t = 0.0),
            WayPoint(lat = 0.0, lon = 0.001, alt = null, t = 60.0),
        ),
        totalDistanceMeters = 111.0,
        theirActiveSeconds = 60.0,
        moments = moments,
        weather = null,
    )

    private fun builtWalk(): OwnWalkWayBuilder.Input {
        val start = 1_777_622_400_000L
        return OwnWalkWayBuilder.Input(
            uuid = "3f2b8c1e-5d4a-4e6f-9a7b-1c2d3e4f5a6b",
            startedAtMillis = start,
            intention = "to the river",
            activeSeconds = 540.5,
            weatherCondition = "rain",
            weatherTemperatureC = null,
            samples = (0 until 10).map { i ->
                RouteDataSample(
                    walkId = 1,
                    timestamp = start + i * 60_000L,
                    latitude = 42.88 + i * 0.0002,
                    longitude = -8.54 + i * 0.00122,
                    altitudeMeters = if (i % 2 == 0) 300.0 + i else null,
                )
            },
            recordings = listOf(
                VoiceRecording(
                    walkId = 1,
                    startTimestamp = start + 250_000,
                    endTimestamp = start + 300_000,
                    durationMillis = 50_000,
                    fileRelativePath = "recordings/w/r.wav",
                    transcription = "Past the mill. Then the long bridge over the river and up into the pines.",
                ),
            ),
            photos = emptyList(),
            waypoints = emptyList(),
            pauses = listOf(OwnWalkWayBuilder.TimeSpan(start + 180_000, start + 400_000)),
            sittings = listOf(OwnWalkWayBuilder.TimeSpan(start + 420_000, start + 480_000)),
        )
    }

    private companion object {
        const val OWN_WALK_FIXTURE = "honor/own-walk-way.json"

        /** A whole-number double as kotlinx spells it (`60.0`), in number position. */
        val WHOLE_NUMBER_DOUBLE = Regex("""(?<=[:,\[])(-?\d+)\.0(?=[,}\]])""")
    }
}
