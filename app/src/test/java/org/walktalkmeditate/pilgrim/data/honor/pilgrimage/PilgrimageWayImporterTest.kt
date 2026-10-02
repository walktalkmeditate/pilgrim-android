// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.honor.pilgrimage

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.honor.WayError
import org.walktalkmeditate.pilgrim.data.honor.WayImporter
import org.walktalkmeditate.pilgrim.domain.honor.Way
import org.walktalkmeditate.pilgrim.domain.honor.WayCoordinate
import org.walktalkmeditate.pilgrim.domain.honor.WayGeometry
import org.walktalkmeditate.pilgrim.domain.honor.WayJson
import org.walktalkmeditate.pilgrim.domain.honor.WayMarkKind
import org.walktalkmeditate.pilgrim.domain.honor.WayMomentKind
import org.walktalkmeditate.pilgrim.domain.honor.WayPoint
import org.walktalkmeditate.pilgrim.domain.honor.WaySource
import org.walktalkmeditate.pilgrim.domain.honor.WayStageHours
import org.walktalkmeditate.pilgrim.honor.HonorImportCopy
import org.walktalkmeditate.pilgrim.honor.HonorImportState

/**
 * Port of iOS `PilgrimageWayImporterTests.swift@7c200bf`, with its five
 * fixtures byte for byte (pilgrimage-stage spec P1, Test inventory). Sixteen
 * of its twenty tests are here. `testAStageWayCarriesMarksAndAStageBlock`
 * and `testAWayWrittenBeforeStagesStillDecodes` were ported into
 * `WayCodecTest`, `testTheStoreAcceptsStageIdsAndRefusesEverythingElse`
 * into `WayStoreTest`, and `testAStageWayRoundTripsThroughTheStore` waits
 * for U33's store tree (P1 C16).
 *
 * The plan's "a stage whose count or name disagrees with the route row" is
 * not here: on iOS that check is the package manager's (`stageOneStage`),
 * so its test moves to U34 (P1 C3).
 *
 * After iOS's tests come the spec's additions: each bound and cap at its
 * limit and one past it, local names in iOS's order (C4), the Way built
 * field by field (§5.4), the decoding rules (§4) and kotlinx's differences
 * from Foundation pinned (A7, A9), and the copy against `strings.xml`.
 * Robolectric only for the strings.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PilgrimageWayImporterTest {

    private val resources = ApplicationProvider.getApplicationContext<Context>().resources

    // ---- iOS's tests ----------------------------------------------------

    @Test
    fun `fixture package is readable`() {
        listOf("stage-00.json", "stage-01.json", "route.json", "index.json").forEach { name ->
            assertTrue(name, fixture(name).isNotEmpty())
        }
    }

    /**
     * The build marks a route sparse when fewer than half its stages carry
     * a curated place beyond the start and end towns. The fixture must carry
     * both fields, and one route must omit them, so the parse is exercised
     * against an older index too.
     */
    @Test
    fun `the fixture index carries the sparse flag`() {
        val routes = Json.parseToJsonElement(fixtureText("index.json")).jsonObject.getValue("routes").jsonArray
        val ways = routes.first().jsonObject.getValue("ways").jsonObject
        assertEquals(0.4, ways.getValue("placesPerStage").jsonPrimitive.double, 0.0)
        assertEquals(true, ways.getValue("sparse").jsonPrimitive.boolean)
        val older = routes.last().jsonObject.getValue("ways").jsonObject
        assertNull("an index written before the flag existed", older["sparse"])
        assertNull(older["placesPerStage"])
    }

    @Test
    fun `decodes the fixture stage`() {
        val way = stage00()

        assertEquals("pilgrimage:camino-frances:0", way.id)
        assertEquals(WaySource.Pilgrimage(routeId = "camino-frances", stageIndex = 0), way.source)
        assertEquals("Saint-Jean-Pied-de-Port to Roncesvalles", way.title)
        assertNull("a route never returns to the trail on its own", way.expires)
        assertNull(way.weather)
        assertEquals(11, way.route.size)
        assertEquals(1000.0, way.totalDistanceMeters, 5.0)
        assertEquals(28_800.0, way.theirActiveSeconds, 0.0)
        assertEquals("Europe/Madrid", way.tzIdentifier)
    }

    @Test
    fun `moments carry text, local names, sit minutes and a pin`() {
        val way = stage00()

        assertEquals(listOf("wp-saint-jean", "wp-orisson", "wp-roncesvalles"), way.moments.map { it.id })
        val orisson = way.moments.first { it.id == "wp-orisson" }
        val kind = orisson.kind as WayMomentKind.Waypoint
        assertEquals("Vierge d'Orisson", kind.label)
        assertEquals("building.columns", kind.icon)
        assertEquals("A shepherd carried this Madonna up from Lourdes.", orisson.text)
        assertEquals("Orissongo Ama Birjina", orisson.names?.get("eu"))
        assertEquals(5, orisson.sitMinutes)
        assertEquals("triggers fire on the line", WayCoordinate(lat = 0.0, lon = 0.002694), orisson.at)
        assertEquals("the pin draws off it", WayCoordinate(lat = 0.0, lon = 0.0027), orisson.pin)
        assertNull(way.moments.first { it.id == "wp-saint-jean" }.text)
    }

    @Test
    fun `marks and the stage block survive`() {
        val way = stage00()

        val marks = requireNotNull(way.marks)
        assertEquals(listOf("wp-fuente-roldan", "wp-fuente-lejos", "wp-bar-orisson"), marks.map { it.id })
        assertEquals(WayMarkKind.WATER, marks[0].kind)
        assertEquals("Fuente de Roldán", marks[0].name)
        assertEquals(12.0, marks[0].offLineMeters, 0.0)
        assertEquals(WayMarkKind.FOOD, marks[2].kind)
        val stage = requireNotNull(way.stage)
        assertEquals("camino-frances", stage.routeId)
        assertEquals(0, stage.index)
        assertEquals(2, stage.count)
        assertEquals("Initiation", stage.theme)
        assertEquals("You crossed a border on foot. Few things are still done this way.", stage.closing)
        assertEquals(listOf("The Napoleon Route closes in winter."), stage.warnings)
        assertEquals(24.2, stage.distanceKm, 0.0)
        assertEquals(1419.0, stage.gainMeters, 0.0)
        assertEquals(WayStageHours(min = 7.0, max = 9.0), stage.hours)
        assertEquals("hard", stage.difficulty)
        assertEquals("Roncesvalles", stage.end.name)
    }

    @Test
    fun `the stage must match the route and index it was fetched for`() {
        val data = fixture("stage-00.json")

        assertNotWalkable { PilgrimageWayImporter.way(from = data, routeId = "camino-frances", stageIndex = 4) }
        assertNotWalkable { PilgrimageWayImporter.way(from = data, routeId = "camino-norte", stageIndex = 0) }
        assertNotWalkable { PilgrimageWayImporter.way(from = data, routeId = "../etc", stageIndex = 0) }
    }

    /**
     * One field out of range at a time; each `from` is anchored with enough
     * surrounding context to match exactly one place in the fixture, so a
     * case can never pass because a different field's guard fired first.
     * 1e400 fails kotlinx's decode, as it fails Foundation's (pinned below).
     */
    @Test
    fun `out of range stage fields are not walkable`() {
        val base = fixtureText("stage-00.json")
        listOf(
            Triple(
                "moment frac above 1",
                "\"frac\": 0.3,\n      \"kind\": \"waypoint\"",
                "\"frac\": 1.4,\n      \"kind\": \"waypoint\"",
            ),
            Triple(
                "route point latitude off Earth",
                "\"lat\": 0, \"lon\": 0.002694, \"alt\": 700",
                "\"lat\": 991, \"lon\": 0.002694, \"alt\": 700",
            ),
            Triple(
                "moment at latitude off Earth",
                "\"at\": { \"lat\": 0, \"lon\": 0.002694 },\n      \"pin\"",
                "\"at\": { \"lat\": 991, \"lon\": 0.002694 },\n      \"pin\"",
            ),
            Triple(
                "moment pin longitude off Earth",
                "\"pin\": { \"lat\": 0, \"lon\": 0.002700 }",
                "\"pin\": { \"lat\": 0, \"lon\": 999 }",
            ),
            Triple(
                "mark at latitude off Earth",
                "\"at\": { \"lat\": 0, \"lon\": 0.002694 }, \"frac\": 0.3, \"offLineMeters\": 20 }",
                "\"at\": { \"lat\": 991, \"lon\": 0.002694 }, \"frac\": 0.3, \"offLineMeters\": 20 }",
            ),
            Triple("mark frac above 1", "\"frac\": 0.7, \"offLineMeters\": 250", "\"frac\": 1.4, \"offLineMeters\": 250"),
            Triple("sitMinutes absurd", "\"sitMinutes\": 5", "\"sitMinutes\": 999999999"),
            Triple("distanceKm absurd", "\"distanceKm\": 24.2,", "\"distanceKm\": 1e300,"),
            Triple("stage count over 200", "\"count\": 2,", "\"count\": 900,"),
            Triple("mark frac negative", "\"frac\": 0.5, \"offLineMeters\": 12", "\"frac\": -0.5, \"offLineMeters\": 12"),
            Triple("hours not finite", "\"hours\": { \"min\": 7, \"max\": 9 },", "\"hours\": { \"min\": 7, \"max\": 1e400 },"),
        ).forEach { (name, from, to) ->
            val json = base.replace(from, to)
            assertNotEquals("$name: the fixture no longer contains that text", base, json)
            assertNotWalkable(name) { stage(json) }
        }
    }

    @Test
    fun `free text is capped at parse time`() {
        val long = "a".repeat(5000)
        val json = fixtureText("stage-00.json")
            .replace("\"theme\": \"Initiation\"", "\"theme\": \"$long\"")
            .replace("\"A shepherd carried this Madonna up from Lourdes.\"", "\"$long\"")

        val way = stage(json)

        assertEquals(80, way.stage?.theme?.length)
        assertEquals(600, way.moments.first { it.id == "wp-orisson" }.text?.length)
    }

    @Test
    fun `too many moments or marks is not walkable`() {
        val base = fixtureText("stage-00.json")
        val extraMark =
            ",{ \"id\": \"x\", \"kind\": \"water\", \"name\": \"x\", \"at\": { \"lat\": 0, \"lon\": 0 }, \"frac\": 0.1, \"offLineMeters\": 5 }"
        val many = extraMark.repeat(PilgrimageWayImporter.MAX_MARKS)
        val json = base.replace("\"offLineMeters\": 20 }\n  ],", "\"offLineMeters\": 20 }$many\n  ],")

        assertNotEquals("the fixture no longer contains that text", base, json)
        assertNotWalkable { stage(json) }
    }

    @Test
    fun `unknown mark kinds and moment kinds are skipped, not fatal`() {
        val base = fixtureText("stage-00.json")
        val json = base
            .replace("\"kind\": \"food\"", "\"kind\": \"helipad\"")
            .replace("\"frac\": 0.0,\n      \"kind\": \"waypoint\"", "\"frac\": 0.0,\n      \"kind\": \"shrine\"")
        assertNotEquals(base, json)

        val way = stage(json)

        assertEquals(listOf("wp-fuente-roldan", "wp-fuente-lejos"), way.marks?.map { it.id })
        assertEquals("an unknown moment kind is skipped, not fatal", listOf("wp-orisson", "wp-roncesvalles"), way.moments.map { it.id })
    }

    /**
     * The dataset's stage files omit `tzIdentifier` entirely and carry a
     * top-level `schemaVersion` the importer has no field for. The fixtures
     * both carry a `tzIdentifier`, so this is the only place either fact is
     * exercised.
     */
    @Test
    fun `decodes a stage file missing tzIdentifier with an unknown schemaVersion key`() {
        val base = fixtureText("stage-00.json")
        val json = base
            .replace("\"tzIdentifier\": \"Europe/Madrid\",\n", "")
            .replace("\"id\": \"pilgrimage:camino-frances:0\",", "\"schemaVersion\": 1,\n  \"id\": \"pilgrimage:camino-frances:0\",")
        assertNotEquals(base, json)

        assertNull(stage(json).tzIdentifier)
    }

    @Test
    fun `decodes the route file`() {
        val route = PilgrimageWayImporter.route(from = fixture("route.json"))

        assertEquals("camino-frances", route.id)
        assertEquals("Camino de Santiago (Francés)", route.name)
        assertEquals("Camiño de Santiago (Francés)", route.names["gl"])
        assertEquals("ES", route.country)
        assertEquals(2, route.stageCount)
        assertEquals(46.1, route.distanceKm, 0.0)
        assertEquals("The most walked of the caminos.", route.summary)
        assertEquals(listOf(0, 1), route.stages.map { it.index })
        assertEquals("hard", route.stages[0].difficulty)
        assertEquals(WayStageHours(min = 5.0, max = 7.0), route.stages[1].hours)
    }

    @Test
    fun `a route file whose numbers are out of range is not walkable`() {
        val base = fixtureText("route.json")
        listOf(
            "\"stageCount\": 2" to "\"stageCount\": 0",
            "\"distanceKm\": 46.1" to "\"distanceKm\": 99999",
            "\"id\": \"camino-frances\"" to "\"id\": \"../etc\"",
        ).forEach { (from, to) ->
            val json = base.replace(from, to)
            assertNotEquals(from, base, json)
            assertNotWalkable(to) { route(json) }
        }
    }

    /**
     * The manager downloads and saves each stage positionally, and the
     * route screen keys a tapped row on `route.json`'s own `index`. 1-based
     * indices would hand every tapped row the next stage's Way.
     */
    @Test
    fun `one-based stage indices are not walkable`() {
        val shifted = routeTree().jsonObject.getValue("stages").jsonArray.mapIndexed { i, row ->
            row.edited(listOf("index"), JsonPrimitive(row.jsonObject.getValue("index").jsonPrimitive.long + 1)).also {
                assertEquals(i + 1L, it.jsonObject.getValue("index").jsonPrimitive.long)
            }
        }

        assertNotWalkable { route(routeTree().edited(listOf("stages"), JsonArray(shifted)).toString()) }
    }

    /** A duplicate index leaves one saved stage unreachable and hands its row to the stage that does carry that index. */
    @Test
    fun `a duplicate stage index is not walkable`() {
        assertNotWalkable { route(routeWith("stages", 1, "index", value = JsonPrimitive(0))) }
    }

    @Test
    fun `every error has its own line`() {
        assertEquals("this route isn't walkable yet", line(PilgrimageError.NOT_WALKABLE))
        assertEquals("the download didn't finish", line(PilgrimageError.INCOMPLETE))
        assertEquals("finish your walk first", line(PilgrimageError.WALK_IN_PROGRESS))
        assertEquals("the routes are out of reach right now", line(PilgrimageError.CATALOG_UNREACHABLE))
        assertEquals("more map than can be saved at once", line(PilgrimageError.MAP_TOO_LARGE))
        assertEquals(
            "disk full keeps the copy the share importer already ships",
            HonorImportCopy.line(resources, HonorImportState.Failed(WayError.DISK_FULL)),
            line(PilgrimageError.DISK_FULL),
        )
    }

    // ---- The copy against strings.xml (P1 §1) ----------------------------

    // pilgrim-ios #122 item 5, matched: a route download that fills the disk speaks of voices.
    @Test
    fun `disk full borrows the share importer's line, voices and all, and every line is distinct`() {
        assertEquals("not enough space on this phone to save these voices", line(PilgrimageError.DISK_FULL))
        assertEquals(
            "one string, so the two can never drift",
            R.string.honor_import_disk_full,
            PilgrimageCopy.line(PilgrimageError.DISK_FULL),
        )
        assertEquals(PilgrimageError.entries.size, PilgrimageError.entries.map(::line).toSet().size)
    }

    // ---- The fixtures (P1 Test inventory) ----------------------------------

    @Test
    fun `the fixtures are iOS's, byte for byte`() {
        mapOf(
            "index.json" to "7c801679726b873adf8003f9aa0629f2796d144153b1d2d5ff39869fe80ed474",
            "index-pilgrimages.json" to "4cbb9b8925a148a0ed11cb62d0e1b64885c73ed847f999b26cbc9f5fa6ef0a0e",
            "route.json" to "a1dff943e66d4f8d2a584f9d71a5655fae10a54e39fd3f5adba8aa11b0f98695",
            "stage-00.json" to "df4b514670acbb67749cd6db0fcddedf131b38be89542c7e5fc7ac1aa6dc4932",
            "stage-01.json" to "0c6e3b575507aabf4286d19cb8f4f50feb8e4ec5e96361085b4f76793579e3a9",
        ).forEach { (name, expected) -> assertEquals(name, expected, sha256(fixture(name))) }
    }

    @Test
    fun `both fixture stages build their Ways`() {
        val second = PilgrimageWayImporter.way(from = fixture("stage-01.json"), routeId = "camino-frances", stageIndex = 1)

        assertEquals("pilgrimage:camino-frances:1", second.id)
        assertEquals(listOf("wp-zubiri"), second.moments.map { it.id })
        assertEquals("Roncesvalles to Zubiri", second.stage?.name)
        assertEquals(emptyList<String>(), second.stage?.warnings)
    }

    // ---- §5.1: the request, before the bytes ------------------------------

    @Test
    fun `a stage index outside 0 to 199 is refused before the file is read`() {
        listOf(-1, 200).forEach { index ->
            assertNotWalkable("$index") {
                PilgrimageWayImporter.way(from = fixture("stage-00.json"), routeId = "camino-frances", stageIndex = index)
            }
        }
    }

    @Test
    fun `a stage file is at most 2 MiB, exactly 2 MiB passing`() {
        val base = fixture("stage-00.json")
        val atCap = base + ByteArray(PilgrimageWayImporter.MAX_STAGE_BYTES - base.size) { ' '.code.toByte() }
        assertEquals(2_097_152, atCap.size)

        assertEquals("pilgrimage:camino-frances:0", PilgrimageWayImporter.way(atCap, "camino-frances", 0).id)
        assertNotWalkable { PilgrimageWayImporter.way(atCap + ' '.code.toByte(), "camino-frances", 0) }
    }

    @Test
    fun `an id that isn't the request's is refused, whatever its route and index say`() {
        assertNotWalkable { stage(stageWith("id", value = JsonPrimitive("pilgrimage:camino-frances:1"))) }
        assertNotWalkable { stage(stageWith("stage", "routeId", value = JsonPrimitive("camino-norte"))) }
        assertNotWalkable { stage(stageWith("stage", "index", value = JsonPrimitive(1))) }
    }

    @Test
    fun `a departure date that doesn't parse is not walkable`() {
        listOf("2026-08-19", "2026-08-19T15:31:51", "yesterday").forEach { date ->
            assertNotWalkable(date) { stage(stageWith("departedAt", value = JsonPrimitive(date))) }
        }
        assertEquals(
            "an offset and a fraction are read, as iOS's isoDate reads them",
            Instant.parse("2026-08-19T15:31:51.250Z"),
            stage(stageWith("departedAt", value = JsonPrimitive("2026-08-19T17:31:51.250+02:00"))).departedAt,
        )
    }

    @Test
    fun `a line under 20 m is not walkable, and one of 20 m is`() {
        // 0.00018 degrees of longitude on the equator is 20.02 m by haversine; 0.000179 is 19.90 m.
        val walkable = stageWith("route", value = JsonArray(listOf(point(lon = 0.0, t = 0), point(lon = 0.00018, t = 60))))
        val tooShort = stageWith("route", value = JsonArray(listOf(point(lon = 0.0, t = 0), point(lon = 0.000179, t = 60))))

        assertEquals(20.0, stage(walkable).totalDistanceMeters, 0.05)
        assertNotWalkable { stage(tooShort) }
    }

    // ---- §5.2: validate's rows, each at its limit and one past it ----------

    @Test
    fun `rows 1 to 3 - 2 to 2000 points, at most 200 moments and 400 marks, every kind counted`() {
        val pointsOf = { count: Int -> JsonArray(List(count) { i -> point(lon = i * 0.001, t = i) }) }
        assertEquals(2000, stage(stageWith("route", value = pointsOf(2000))).route.size)
        assertEquals(2, stage(stageWith("route", value = pointsOf(2))).route.size)
        assertNotWalkable("2001 points") { stage(stageWith("route", value = pointsOf(2001))) }
        assertNotWalkable("1 point") { stage(stageWith("route", value = pointsOf(1))) }

        val shrines = { count: Int -> JsonArray(List(count) { i -> moment(id = "m$i", kind = "shrine") }) }
        assertEquals("a quiet stage, not a broken one", 0, stage(stageWith("moments", value = shrines(200))).moments.size)
        assertNotWalkable("201 moments") { stage(stageWith("moments", value = shrines(201))) }

        val marks = { water: Int -> JsonArray(List(water) { i -> mark(id = "w$i") } + mark(id = "h", kind = "helipad")) }
        assertEquals("the stage at the cap, as camino-norte stage 7 is", 399, stage(stageWith("marks", value = marks(399))).marks?.size)
        assertNotWalkable("401 marks, one of a kind no one knows") { stage(stageWith("marks", value = marks(400))) }
    }

    @Test
    fun `row 4 - every point on Earth, its time within a week`() {
        assertBound(
            accepted = listOf(
                "lat 90" to stageWith("route", 3, "lat", value = JsonPrimitive(90)),
                "lat -90" to stageWith("route", 3, "lat", value = JsonPrimitive(-90)),
                "lon 180" to stageWith("route", 3, "lon", value = JsonPrimitive(180)),
                "lon -180" to stageWith("route", 0, "lon", value = JsonPrimitive(-180)),
                "t 604800" to stageWith("route", 10, "t", value = JsonPrimitive(604_800)),
            ),
            refused = listOf(
                "lat past 90" to stageWith("route", 3, "lat", value = JsonPrimitive(90.000001)),
                "lat past -90" to stageWith("route", 3, "lat", value = JsonPrimitive(-90.000001)),
                "lon past 180" to stageWith("route", 3, "lon", value = JsonPrimitive(180.000001)),
                "lon past -180" to stageWith("route", 0, "lon", value = JsonPrimitive(-180.000001)),
                "t below 0" to stageWith("route", 0, "t", value = JsonPrimitive(-0.001)),
                "t past 604800" to stageWith("route", 10, "t", value = JsonPrimitive(604_800.001)),
            ),
        )
    }

    @Test
    fun `row 5 - an altitude under 100 km in magnitude, strictly, and optional`() {
        assertBound(
            accepted = listOf(
                "99,999.9 m" to stageWith("route", 0, "alt", value = JsonPrimitive(99_999.9)),
                "-99,999.9 m" to stageWith("route", 0, "alt", value = JsonPrimitive(-99_999.9)),
                "absent" to stageWith("route", 0, "alt", value = null),
            ),
            refused = listOf(
                "100,000 m" to stageWith("route", 0, "alt", value = JsonPrimitive(100_000)),
                "-100,000 m" to stageWith("route", 0, "alt", value = JsonPrimitive(-100_000)),
            ),
        )
        assertNull(stage(stageWith("route", 0, "alt", value = null)).route[0].alt)
    }

    @Test
    fun `row 6 - a point's time never goes back, though it may repeat`() {
        assertBound(
            accepted = listOf("t repeats" to stageWith("route", 2, "t", value = JsonPrimitive(2880))),
            refused = listOf("t goes back" to stageWith("route", 2, "t", value = JsonPrimitive(2879))),
        )
    }

    @Test
    fun `rows 7 and 8 - the file's distance is at least 0 with no ceiling, and their time is within a week`() {
        assertBound(
            accepted = listOf(
                "distance 0" to stageWith("totalDistanceMeters", value = JsonPrimitive(0)),
                "distance 1e300" to stageWith("totalDistanceMeters", value = JsonPrimitive(1e300)),
                "time 0" to stageWith("theirActiveSeconds", value = JsonPrimitive(0)),
                "time 604800" to stageWith("theirActiveSeconds", value = JsonPrimitive(604_800)),
            ),
            refused = listOf(
                "distance below 0" to stageWith("totalDistanceMeters", value = JsonPrimitive(-0.001)),
                "time below 0" to stageWith("theirActiveSeconds", value = JsonPrimitive(-0.001)),
                "time past 604800" to stageWith("theirActiveSeconds", value = JsonPrimitive(604_800.001)),
            ),
        )
    }

    @Test
    fun `row 9 - each moment's frac, places, and sitting minutes, the skipped kinds included`() {
        assertBound(
            accepted = listOf(
                "frac 0" to stageWith("moments", 1, "frac", value = JsonPrimitive(0)),
                "frac 1" to stageWith("moments", 1, "frac", value = JsonPrimitive(1)),
                "at lat 90" to stageWith("moments", 1, "at", "lat", value = JsonPrimitive(90)),
                "pin lon -180" to stageWith("moments", 1, "pin", "lon", value = JsonPrimitive(-180)),
                "sitMinutes 0" to stageWith("moments", 1, "sitMinutes", value = JsonPrimitive(0)),
                "sitMinutes 1440" to stageWith("moments", 1, "sitMinutes", value = JsonPrimitive(1440)),
            ),
            refused = listOf(
                "frac below 0" to stageWith("moments", 1, "frac", value = JsonPrimitive(-0.000001)),
                "frac past 1" to stageWith("moments", 1, "frac", value = JsonPrimitive(1.000001)),
                "at lat past 90" to stageWith("moments", 1, "at", "lat", value = JsonPrimitive(90.000001)),
                "pin lon past -180" to stageWith("moments", 1, "pin", "lon", value = JsonPrimitive(-180.000001)),
                "sitMinutes below 0" to stageWith("moments", 1, "sitMinutes", value = JsonPrimitive(-1)),
                "sitMinutes past 1440" to stageWith("moments", 1, "sitMinutes", value = JsonPrimitive(1441)),
                "a skipped kind's frac" to stageTree()
                    .edited(listOf("moments", 0, "kind"), JsonPrimitive("shrine"))
                    .edited(listOf("moments", 0, "frac"), JsonPrimitive(1.4))
                    .toString(),
            ),
        )
        assertEquals("zero minutes is kept, not dropped", 0, stage(stageWith("moments", 1, "sitMinutes", value = JsonPrimitive(0))).moments[1].sitMinutes)
    }

    @Test
    fun `row 10 - each mark's frac, place, and distance off the line, the dropped kinds included`() {
        assertBound(
            accepted = listOf(
                "frac 0" to stageWith("marks", 0, "frac", value = JsonPrimitive(0)),
                "frac 1" to stageWith("marks", 0, "frac", value = JsonPrimitive(1)),
                "at lon 180" to stageWith("marks", 0, "at", "lon", value = JsonPrimitive(180)),
                "off the line 0 m" to stageWith("marks", 0, "offLineMeters", value = JsonPrimitive(0)),
                "off the line 100,000 m" to stageWith("marks", 0, "offLineMeters", value = JsonPrimitive(100_000)),
            ),
            refused = listOf(
                "frac below 0" to stageWith("marks", 0, "frac", value = JsonPrimitive(-0.000001)),
                "frac past 1" to stageWith("marks", 0, "frac", value = JsonPrimitive(1.000001)),
                "at lon past 180" to stageWith("marks", 0, "at", "lon", value = JsonPrimitive(180.000001)),
                "off the line below 0" to stageWith("marks", 0, "offLineMeters", value = JsonPrimitive(-0.001)),
                "off the line past 100,000 m" to stageWith("marks", 0, "offLineMeters", value = JsonPrimitive(100_000.001)),
                "a dropped kind's distance" to stageTree()
                    .edited(listOf("marks", 2, "kind"), JsonPrimitive("helipad"))
                    .edited(listOf("marks", 2, "offLineMeters"), JsonPrimitive(100_000.001))
                    .toString(),
            ),
        )
    }

    @Test
    fun `rows 11 to 13 - the stage's count is 1 to 200 and its index below it`() {
        assertEquals(200, stage(stageWith("stage", "count", value = JsonPrimitive(200))).stage?.count)
        assertEquals(1, stage(stageWith("stage", "count", value = JsonPrimitive(1))).stage?.count)
        assertNotWalkable("count 201") { stage(stageWith("stage", "count", value = JsonPrimitive(201))) }
        assertNotWalkable("count 0") { stage(stageWith("stage", "count", value = JsonPrimitive(0))) }

        val lastOfOne = Json.parseToJsonElement(fixtureText("stage-01.json")).edited(listOf("stage", "count"), JsonPrimitive(1))
        assertNotWalkable("index equal to count") {
            PilgrimageWayImporter.way(lastOfOne.toString().toByteArray(), routeId = "camino-frances", stageIndex = 1)
        }
    }

    @Test
    fun `rows 14 to 16 - the stage's distance, climb, and hours`() {
        assertBound(
            accepted = listOf(
                "0 km" to stageWith("stage", "distanceKm", value = JsonPrimitive(0)),
                "10,000 km" to stageWith("stage", "distanceKm", value = JsonPrimitive(10_000)),
                "0 m up" to stageWith("stage", "gainMeters", value = JsonPrimitive(0)),
                "30,000 m up" to stageWith("stage", "gainMeters", value = JsonPrimitive(30_000)),
                "0 to 0 hours" to stageWith("stage", "hours", value = hours(0.0, 0.0)),
                "100 to 100 hours" to stageWith("stage", "hours", value = hours(100.0, 100.0)),
            ),
            refused = listOf(
                "below 0 km" to stageWith("stage", "distanceKm", value = JsonPrimitive(-0.001)),
                "past 10,000 km" to stageWith("stage", "distanceKm", value = JsonPrimitive(10_000.001)),
                "below 0 m up" to stageWith("stage", "gainMeters", value = JsonPrimitive(-0.001)),
                "past 30,000 m up" to stageWith("stage", "gainMeters", value = JsonPrimitive(30_000.001)),
                "fewest hours below 0" to stageWith("stage", "hours", value = hours(-0.001, 9.0)),
                "most hours past 100" to stageWith("stage", "hours", value = hours(7.0, 100.001)),
                "most hours below the fewest" to stageWith("stage", "hours", value = hours(9.0, 7.0)),
            ),
        )
    }

    @Test
    fun `row 17 - at most 20 warnings, and nothing is cut to get there`() {
        val warnings = { count: Int -> JsonArray(List(count) { JsonPrimitive("w$it") }) }

        assertEquals(20, stage(stageWith("stage", "warnings", value = warnings(20))).stage?.warnings?.size)
        assertNotWalkable { stage(stageWith("stage", "warnings", value = warnings(21))) }
        assertEquals(listOf(""), stage(stageWith("stage", "warnings", value = warnings(1).edited(listOf(0), JsonPrimitive("")))).stage?.warnings)
    }

    @Test
    fun `row 18 - the stage's start and end on Earth`() {
        assertBound(
            accepted = listOf(
                "start lat 90" to stageWith("stage", "start", "at", "lat", value = JsonPrimitive(90)),
                "end lon -180" to stageWith("stage", "end", "at", "lon", value = JsonPrimitive(-180)),
            ),
            refused = listOf(
                "start lat past 90" to stageWith("stage", "start", "at", "lat", value = JsonPrimitive(90.000001)),
                "end lon past -180" to stageWith("stage", "end", "at", "lon", value = JsonPrimitive(-180.000001)),
            ),
        )
    }

    // ---- §5.3: every string cap, at the cap and one past it -----------------

    @Test
    fun `every string in a stage is cut to its cap, and one at the cap is kept whole`() {
        listOf<Triple<String, List<Any>, Pair<Int, (Way) -> String?>>>(
            Triple("title", listOf("title"), 120 to { it.title }),
            Triple("tzIdentifier", listOf("tzIdentifier"), 80 to { it.tzIdentifier }),
            Triple("moment id", listOf("moments", 2, "id"), 80 to { it.moments[2].id }),
            Triple("label", listOf("moments", 1, "label"), 80 to { (it.moments[1].kind as WayMomentKind.Waypoint).label }),
            Triple("icon", listOf("moments", 1, "icon"), 64 to { (it.moments[1].kind as WayMomentKind.Waypoint).icon }),
            Triple("text", listOf("moments", 1, "text"), 600 to { it.moments[1].text }),
            Triple("local name", listOf("moments", 1, "names", "eu"), 120 to { it.moments[1].names?.get("eu") }),
            Triple("mark id", listOf("marks", 0, "id"), 80 to { it.marks?.get(0)?.id }),
            Triple("mark name", listOf("marks", 0, "name"), 80 to { it.marks?.get(0)?.name }),
            Triple("stage name", listOf("stage", "name"), 120 to { it.stage?.name }),
            Triple("theme", listOf("stage", "theme"), 80 to { it.stage?.theme }),
            Triple("narrative", listOf("stage", "narrative"), 2000 to { it.stage?.narrative }),
            Triple("closing", listOf("stage", "closing"), 400 to { it.stage?.closing }),
            Triple("warning", listOf("stage", "warnings", 0), 300 to { it.stage?.warnings?.get(0) }),
            Triple("difficulty", listOf("stage", "difficulty"), 80 to { it.stage?.difficulty }),
            Triple("start", listOf("stage", "start", "name"), 120 to { it.stage?.start?.name }),
            Triple("end", listOf("stage", "end", "name"), 120 to { it.stage?.end?.name }),
        ).forEach { (name, path, capAndReader) ->
            val (cap, read) = capAndReader
            val atCap = "a".repeat(cap)
            assertEquals("$name at $cap", atCap, read(stage(stageTree().edited(path, JsonPrimitive(atCap)).toString())))
            assertEquals("$name past $cap", atCap, read(stage(stageTree().edited(path, JsonPrimitive(atCap + "a")).toString())))
        }
    }

    @Test
    fun `every string in a route file is cut to its cap, and one at the cap is kept whole`() {
        listOf<Triple<String, List<Any>, Pair<Int, (PilgrimageRoute) -> String?>>>(
            Triple("name", listOf("name"), 120 to { it.name }),
            Triple("country", listOf("country"), 80 to { it.country }),
            Triple("region", listOf("region"), 80 to { it.region }),
            Triple("tradition", listOf("tradition"), 80 to { it.tradition }),
            Triple("summary", listOf("summary"), 600 to { it.summary }),
            Triple("row name", listOf("stages", 0, "name"), 120 to { it.stages[0].name }),
            Triple("row difficulty", listOf("stages", 0, "difficulty"), 80 to { it.stages[0].difficulty }),
        ).forEach { (name, path, capAndReader) ->
            val (cap, read) = capAndReader
            val atCap = "a".repeat(cap)
            assertEquals("$name at $cap", atCap, read(route(routeTree().edited(path, JsonPrimitive(atCap)).toString())))
            assertEquals("$name past $cap", atCap, read(route(routeTree().edited(path, JsonPrimitive(atCap + "a")).toString())))
        }
    }

    // camino-norte's summary sits at exactly 600 characters (P1 §12).
    @Test
    fun `characters are counted as Swift counts them, so 600 accented letters written decomposed are kept whole`() {
        val decomposed = "e\u0301".repeat(600)

        assertEquals(decomposed, route(routeWith("summary", value = JsonPrimitive(decomposed))).summary)
        assertEquals(decomposed, route(routeWith("summary", value = JsonPrimitive(decomposed + "e\u0301"))).summary)
    }

    @Test
    fun `text, a summary, and local names are trimmed and dropped when blank, and the rest are cut but never trimmed`() {
        val padded = stage(stageWith("moments", 1, "text", value = JsonPrimitive(" \n A shepherd. \u200B")))
        assertEquals("A shepherd.", padded.moments[1].text)
        assertNull(stage(stageWith("moments", 1, "text", value = JsonPrimitive(" \n "))).moments[1].text)
        assertEquals("  Initiation ", stage(stageWith("stage", "theme", value = JsonPrimitive("  Initiation "))).stage?.theme)

        assertEquals("The most walked.", route(routeWith("summary", value = JsonPrimitive("  The most walked.\n"))).summary)
        assertNull(route(routeWith("summary", value = JsonPrimitive("   "))).summary)
        assertEquals(" ES ", route(routeWith("country", value = JsonPrimitive(" ES "))).country)
    }

    @Test
    fun `a moment's label falls back to empty and its icon to a pin only when absent`() {
        val bare = stage(
            stageTree()
                .edited(listOf("moments", 1, "label"), null)
                .edited(listOf("moments", 1, "icon"), null)
                .toString(),
        )
        assertEquals(WayMomentKind.Waypoint(label = "", icon = "mappin"), bare.moments[1].kind)

        val blank = stage(
            stageTree()
                .edited(listOf("moments", 1, "label"), JsonPrimitive(" "))
                .edited(listOf("moments", 1, "icon"), JsonPrimitive(""))
                .toString(),
        )
        assertEquals(WayMomentKind.Waypoint(label = " ", icon = ""), blank.moments[1].kind)
    }

    // ---- §5.3: local names, sorted, cut to 20, then filtered (C4) -----------

    /** Live Guernica (`camino-norte` stage 5, `wp-osm-town-node68651691`) carries 30 names (P1 §12). */
    private val guernica = linkedMapOf(
        "ar" to "غيرنايكا-لومو", "ast" to "Guernica y Luno", "ca" to "Guernica", "cs" to "Guernica",
        "cy" to "Gernika", "da" to "Guernica", "de" to "Gernika", "eo" to "Gerniko", "es" to "Guernica y Luno",
        "eu" to "Gernika", "fi" to "Guernica", "fr" to "Guernica", "ga" to "Gernika", "he" to "גרניקה",
        "it" to "Guernica", "ja" to "ゲルニカ", "ko" to "게르니카-루모", "lv" to "Gernika", "ms" to "Guernica",
        "nl" to "Guernica", "nn" to "Guernica", "no" to "Gernika", "pl" to "Guernica", "pt" to "Guernica e Luno",
        "ro" to "Guernica", "ru" to "Герника", "sr" to "Герника", "sv" to "Guernica", "uk" to "Герніка-Лумо",
        "zh" to "格爾尼卡",
    )

    private fun namesOf(names: Map<String, String>): Map<String, String>? {
        val tree = stageWith("moments", 1, "names", value = JsonObject(names.mapValues { JsonPrimitive(it.value) }))
        return stage(tree).moments[1].names
    }

    // Probed against iOS's `localNames` (Swift 6.3.3): the same twenty keys.
    @Test
    fun `Guernica's thirty local names keep the first twenty by key, and pt is not among them`() {
        val kept = requireNotNull(namesOf(guernica))

        assertEquals(
            "ar ast ca cs cy da de eo es eu fi fr ga he it ja ko lv ms nl".split(" "),
            kept.keys.sorted(),
        )
        assertNull(kept["pt"])
        assertEquals("Gernika", kept["eu"])
    }

    // Probed against iOS: with "EU" added, iOS keeps nineteen, nl gone.
    @Test
    fun `the cut comes before the filter, so a key that sorts first but isn't valid still takes a slot`() {
        val kept = requireNotNull(namesOf(linkedMapOf("EU" to "Gernika") + guernica))

        assertEquals("ar ast ca cs cy da de eo es eu fi fr ga he it ja ko lv ms".split(" "), kept.keys.sorted())
    }

    // Probed against iOS: "a" with a combining acute sorts after every ASCII key there, so all twenty stay.
    @Test
    fun `keys sort on their composed form, as Swift's less-than compares them`() {
        val kept = requireNotNull(namesOf(linkedMapOf("a\u0301" to "x") + guernica))

        assertEquals(20, kept.size)
        assertEquals("nl", kept.keys.sorted().last())
    }

    // Probed against iOS: only fr survives, trimmed.
    @Test
    fun `a key must be two or three lowercase letters and a value must not trim to nothing`() {
        val kept = namesOf(linkedMapOf("eu" to "  ", "fr" to " Vierge ", "zh-Hant" to "x", "e" to "x", "abcd" to "x"))

        assertEquals(mapOf("fr" to "Vierge"), kept)
        assertNull("nothing left is no names at all", namesOf(mapOf("EN" to "x")))
        assertNull("an empty map is no names at all", namesOf(emptyMap()))
    }

    @Test
    fun `a route's names are an empty map when none survive, never null`() {
        assertEquals(emptyMap<String, String>(), route(routeWith("names", value = null)).names)
        assertEquals(emptyMap<String, String>(), route(routeWith("names", value = JsonObject(mapOf("EN" to JsonPrimitive("x"))))).names)
        assertEquals(mapOf("es" to "Camino de Santiago (Francés)", "gl" to "Camiño de Santiago (Francés)"), route(fixtureText("route.json")).names)
    }

    // ---- §5.4: the Way, field by field -------------------------------------

    @Test
    fun `the Way's length is the line's own, not the file's, and its times are not rebased`() {
        val way = stage(
            stageTree()
                .edited(listOf("totalDistanceMeters"), JsonPrimitive(5))
                .edited(listOf("route", 0, "t"), JsonPrimitive(100))
                .toString(),
        )

        assertEquals(1000.0, way.totalDistanceMeters, 5.0)
        assertEquals(listOf(100.0, 2880.0, 5760.0), way.route.take(3).map { it.t })
        assertEquals(170.0, way.route[0].alt ?: 0.0, 0.0)
    }

    @Test
    fun `a stage with no marks carries an empty list, and way json says so`() {
        val second = PilgrimageWayImporter.way(from = fixture("stage-01.json"), routeId = "camino-frances", stageIndex = 1)

        assertEquals(emptyList<Any>(), second.marks)
        assertTrue(WayJson.encode(second).contains("\"marks\":[]"))
    }

    @Test
    fun `a stage Way has no expiry, weather, or spans, and is a pilgrimage stage`() {
        val way = stage00()

        assertNull(way.spans)
        assertTrue(way.isPilgrimageStage)
        assertTrue(way.source.isPackageOwned)
        assertTrue("no place or transcript on a waypoint", way.moments.all { it.place == null && it.transcript == null })
    }

    // 17 live stages share a frac between two moments (wp-sjpp before wp-sjpp-pilgrim-office at 0, P1 §12).
    @Test
    fun `moments sort by frac, a tie broken by id, and marks keep the file's order`() {
        val tied = stage(
            stageTree()
                .edited(listOf("moments", 0, "id"), JsonPrimitive("wp-sjpp-pilgrim-office"))
                .edited(listOf("moments", 2, "id"), JsonPrimitive("wp-sjpp"))
                .edited(listOf("moments", 2, "frac"), JsonPrimitive(0))
                .toString(),
        )

        assertEquals(listOf("wp-sjpp", "wp-sjpp-pilgrim-office", "wp-orisson"), tied.moments.map { it.id })
        assertEquals(listOf(0.5, 0.7, 0.3), stage00().marks?.map { it.frac })
    }

    @Test
    fun `the built Way round-trips through way json unchanged`() {
        val way = stage00()

        assertEquals(way, WayJson.decode(WayJson.encode(way)))
    }

    // ---- §6: route.json ----------------------------------------------------

    @Test
    fun `a route file is at most 512 KiB, exactly 512 KiB passing`() {
        val base = fixture("route.json")
        val atCap = base + ByteArray(PilgrimageWayImporter.MAX_ROUTE_BYTES - base.size) { ' '.code.toByte() }
        assertEquals(524_288, atCap.size)

        assertEquals("camino-frances", PilgrimageWayImporter.route(atCap).id)
        assertNotWalkable { PilgrimageWayImporter.route(atCap + ' '.code.toByte()) }
    }

    @Test
    fun `a route's numbers and rows, each at its limit and one past it`() {
        assertEquals(10_000.0, route(routeWith("distanceKm", value = JsonPrimitive(10_000))).distanceKm, 0.0)
        assertEquals(1, route(routeWith("stageCount", value = JsonPrimitive(1))).stageCount)
        assertEquals(200, route(routeWith("stageCount", value = JsonPrimitive(200))).stageCount)
        listOf(
            "past 10,000 km" to routeWith("distanceKm", value = JsonPrimitive(10_000.001)),
            "below 0 km" to routeWith("distanceKm", value = JsonPrimitive(-0.001)),
            "201 stages" to routeWith("stageCount", value = JsonPrimitive(201)),
            "a row past 10,000 km" to routeWith("stages", 1, "distanceKm", value = JsonPrimitive(10_000.001)),
            "a row past 30,000 m up" to routeWith("stages", 1, "gainMeters", value = JsonPrimitive(30_000.001)),
            "a row's hours backwards" to routeWith("stages", 1, "hours", value = hours(7.0, 5.0)),
            "a row's index of 200" to routeWith("stages", 1, "index", value = JsonPrimitive(200)),
            "a gap" to routeWith("stages", 1, "index", value = JsonPrimitive(2)),
            "a 65-character id" to routeWith("id", value = JsonPrimitive("a".repeat(65))),
        ).forEach { (name, json) -> assertNotWalkable(name) { route(json) } }

        val rows = { count: Int -> JsonArray(List(count) { routeRow(index = (count - 1 - it).toLong()) }) }
        val most = route(routeTree().edited(listOf("stageCount"), JsonPrimitive(200)).edited(listOf("stages"), rows(200)).toString())
        assertEquals("rows in any order come back in index order", (0 until 200).toList(), most.stages.map { it.index })
        assertNotWalkable("201 rows") { route(routeTree().edited(listOf("stages"), rows(201)).toString()) }
    }

    @Test
    fun `a route need not count its rows as its stageCount does, which the callers check`() {
        assertEquals(5, route(routeWith("stageCount", value = JsonPrimitive(5))).stageCount)
    }

    // ---- The live dataset's shapes (P1 §12, C14, C18) ---------------------

    @Test
    fun `a stage and a route carrying schemaVersion and stampHours decode, the keys ignored`() {
        val stampHours = buildJsonObject {
            put("opens", "08:00")
            put("closes", "17:00")
        }
        val stageFile = stageTree()
            .edited(listOf("schemaVersion"), JsonPrimitive("1.0.0"))
            .edited(listOf("stampHours"), stampHours)
        val routeFile = routeTree()
            .edited(listOf("schemaVersion"), JsonPrimitive("1.0.0"))
            .edited(listOf("stampHours"), stampHours)

        assertEquals(stage00(), stage(stageFile.toString()))
        assertEquals(route(fixtureText("route.json")), route(routeFile.toString()))
    }

    // All four Shikoku sections ship `"difficulty": ""`; skipping it is the facts line's job (C18, U37).
    @Test
    fun `an empty difficulty is kept, on a stage and on a route's rows`() {
        assertEquals("", stage(stageWith("stage", "difficulty", value = JsonPrimitive(""))).stage?.difficulty)
        assertEquals("", route(routeWith("stages", 0, "difficulty", value = JsonPrimitive(""))).stages[0].difficulty)
    }

    @Test
    fun `kinds compare exactly, and a stage whose moments and marks are all unknown is quiet, not broken`() {
        val capitalized = stage(
            stageTree()
                .edited(listOf("moments", 1, "kind"), JsonPrimitive("Waypoint"))
                .edited(listOf("marks", 0, "kind"), JsonPrimitive("Water"))
                .toString(),
        )
        assertEquals(listOf("wp-saint-jean", "wp-roncesvalles"), capitalized.moments.map { it.id })
        assertEquals(listOf("wp-fuente-lejos", "wp-bar-orisson"), capitalized.marks?.map { it.id })

        val quiet = stage(
            stageTree()
                .edited(listOf("moments"), JsonArray(listOf(moment(id = "a", kind = "temple"))))
                .edited(listOf("marks"), JsonArray(listOf(mark(id = "b", kind = "stamp"))))
                .toString(),
        )
        assertEquals(emptyList<Any>(), quiet.moments)
        assertEquals(emptyList<Any>(), quiet.marks)
    }

    // ---- §4: decoding strictness ------------------------------------------

    @Test
    fun `every required key of a stage file, missing or null, makes it not walkable`() {
        val required = listOf(
            listOf("id"), listOf("title"), listOf("departedAt"), listOf("route"), listOf("totalDistanceMeters"),
            listOf("theirActiveSeconds"), listOf("moments"), listOf("marks"), listOf("stage"),
            listOf("route", 0, "lat"), listOf("route", 0, "lon"), listOf("route", 0, "t"),
            listOf("moments", 0, "id"), listOf("moments", 0, "frac"), listOf("moments", 0, "kind"),
            listOf("moments", 0, "at", "lat"), listOf("moments", 0, "pin", "lon"),
            listOf("marks", 0, "id"), listOf("marks", 0, "kind"), listOf("marks", 0, "name"),
            listOf("marks", 0, "at"), listOf("marks", 0, "frac"), listOf("marks", 0, "offLineMeters"),
        ) + listOf(
            "routeId", "index", "count", "name", "theme", "narrative", "closing", "warnings",
            "distanceKm", "gainMeters", "hours", "difficulty", "start", "end",
        ).map { listOf("stage", it) } + listOf(
            listOf("stage", "hours", "min"), listOf("stage", "hours", "max"),
            listOf("stage", "start", "name"), listOf("stage", "start", "at"), listOf("stage", "end", "at", "lat"),
        )
        required.forEach { path ->
            listOf(null, JsonNull).forEach { gone ->
                val json = stageTree().edited(path, gone).toString()
                assertDecodeRefused("$path as $gone", StageFile.serializer(), json)
                assertNotWalkable("$path as $gone") { stage(json) }
            }
        }
    }

    @Test
    fun `every optional key of a stage file may be missing or null`() {
        val optional = listOf(
            listOf("tzIdentifier"), listOf("route", 0, "alt"),
        ) + listOf("label", "icon", "text", "names", "sitMinutes", "at", "pin").map { listOf("moments", 1, it) }
        optional.forEach { path ->
            assertEquals("$path missing", "pilgrimage:camino-frances:0", stage(stageTree().edited(path, null).toString()).id)
            assertEquals("$path null", "pilgrimage:camino-frances:0", stage(stageTree().edited(path, JsonNull).toString()).id)
        }
    }

    @Test
    fun `every required key of a route file, missing or null, makes it not walkable, and the optional ones may be`() {
        val required = listOf(
            listOf("id"), listOf("name"), listOf("distanceKm"), listOf("stageCount"), listOf("stages"),
        ) + listOf("index", "name", "distanceKm", "gainMeters", "hours", "difficulty").map { listOf("stages", 0, it) }
        required.forEach { path ->
            listOf(null, JsonNull).forEach { gone ->
                val json = routeTree().edited(path, gone).toString()
                assertDecodeRefused("$path as $gone", RouteFile.serializer(), json)
                assertNotWalkable("$path as $gone") { route(json) }
            }
        }
        listOf("names", "country", "region", "tradition", "summary").forEach { key ->
            assertEquals("$key missing", "camino-frances", route(routeTree().edited(listOf(key), null).toString()).id)
            assertEquals("$key null", "camino-frances", route(routeTree().edited(listOf(key), JsonNull).toString()).id)
        }
    }

    @Test
    fun `a value of the wrong type, a null in a name map, bytes that aren't UTF-8, or no JSON at all is not walkable`() {
        assertNotWalkable("a number for a string") { stage(stageWith("title", value = JsonPrimitive(5))) }
        assertNotWalkable("a null name") { stage(stageWith("moments", 1, "names", "eu", value = JsonNull)) }
        assertNotWalkable("a null warning") { stage(stageWith("stage", "warnings", 0, value = JsonNull)) }
        val bytes = fixture("stage-00.json").copyOf().also { it[it.indexOf('R'.code.toByte())] = 0xFF.toByte() }
        assertNotWalkable("malformed UTF-8") { PilgrimageWayImporter.way(bytes, "camino-frances", 0) }
        assertNotWalkable("a captive portal's page") { stage("<html>captive portal</html>") }
        assertNotWalkable("trailing text") { stage(fixtureText("stage-00.json") + "x") }
    }

    // ---- kotlinx's decoding differences from Foundation, pinned (A7, A9) ---

    @Test
    fun `A7 - integers decode 64 bits wide, so a huge one is refused by its bound, not by the decode`() {
        val huge = stageWith("moments", 1, "sitMinutes", value = JsonPrimitive(3_000_000_000))

        assertEquals(3_000_000_000, WayImporter.decodeWire(StageFile.serializer(), huge.toByteArray()).moments[1].sitMinutes)
        assertNotWalkable { stage(huge) }
    }

    @Test
    fun `A9 - kotlinx reads a quoted number, where iOS refuses one`() {
        val json = fixtureText("stage-00.json").replace("\"count\": 2,", "\"count\": \"3\",")

        assertNotEquals(fixtureText("stage-00.json"), json)
        assertEquals(3, stage(json).stage?.count)
    }

    @Test
    fun `A9 - kotlinx refuses an integer written with a fraction, where iOS reads one`() {
        val json = fixtureText("stage-00.json").replace("\"count\": 2,", "\"count\": 2.0,")

        assertNotEquals(fixtureText("stage-00.json"), json)
        assertNotWalkable { stage(json) }
    }

    @Test
    fun `A9 - an integer written with an exponent reads as iOS reads it`() {
        val json = fixtureText("stage-00.json").replace("\"count\": 2,", "\"count\": 3e0,")

        assertNotEquals(fixtureText("stage-00.json"), json)
        assertEquals(3, stage(json).stage?.count)
    }

    @Test
    fun `A9 - a repeated key keeps its last value, where iOS keeps its first`() {
        val twice = fixtureText("stage-00.json").replace(
            "\"title\": \"Saint-Jean-Pied-de-Port to Roncesvalles\",",
            "\"title\": \"Saint-Jean-Pied-de-Port to Roncesvalles\",\n  \"title\": \"Second\",",
        )

        assertEquals("Second", stage(twice).title)
    }

    @Test
    fun `A9 - a number too large for a double fails the decode, as on iOS`() {
        val json = fixtureText("stage-00.json").replace("\"theirActiveSeconds\": 28800", "\"theirActiveSeconds\": 1e400")

        assertNotEquals(fixtureText("stage-00.json"), json)
        assertThrows(IllegalArgumentException::class.java) { WayImporter.decodeWire(StageFile.serializer(), json.toByteArray()) }
    }

    @Test
    fun `A9 - a leading byte order mark fails the decode, where iOS accepts one`() {
        assertNotWalkable { stage("\uFEFF" + fixtureText("stage-00.json")) }
    }

    @Test
    fun `a line whose length comes out NaN is refused, as iOS's greater-or-equal guard refuses it`() {
        val antipodal = Json.parseToJsonElement("""[{"lat":2.5,"lon":0,"t":0},{"lat":-2.5,"lon":180,"t":60}]""")
        val premise = WayGeometry(listOf(WayPoint(lat = 2.5, lon = 0.0, alt = null, t = 0.0), WayPoint(lat = -2.5, lon = 180.0, alt = null, t = 60.0)))

        assertTrue("the pair must measure NaN for this test to mean anything", premise.totalMeters.isNaN())
        assertNotWalkable { stage(stageWith("route", value = antipodal)) }
    }

    @Test
    fun `moments tied on frac order their ids as Swift's less-than does`() {
        val decomposed = "e\u0301b"
        val composed = "\u00e9a"
        val json = stageTree().edited(listOf("moments", 0, "id"), JsonPrimitive(decomposed))
            .edited(listOf("moments", 1, "id"), JsonPrimitive(composed))
            .edited(listOf("moments", 1, "frac"), stageTree().jsonObject["moments"]!!.jsonArray[0].jsonObject["frac"]!!)
            .toString()

        assertEquals(listOf(composed, decomposed), stage(json).moments.map { it.id }.filter { it == composed || it == decomposed })
    }

    // ---- Nothing logged (P1 §3.1) -----------------------------------------

    @Test
    fun `nothing under data honor pilgrimage logs`() {
        val sources = File("src/main/java/org/walktalkmeditate/pilgrim/data/honor/pilgrimage").listFiles().orEmpty()
        assertTrue("the package's sources are where this test looks", sources.any { it.name == "PilgrimageWayImporter.kt" })
        val logging = Regex("""\bLog\.[a-z]+\(|android\.util\.Log\b|\bprintln\(|\bprint\(|\bTimber\b|printStackTrace\(""")
        sources.forEach { file ->
            assertTrue("${file.name} logs", logging.find(file.readText()) == null)
        }
    }

    // ---- Helpers -----------------------------------------------------------

    private fun fixture(name: String): ByteArray =
        requireNotNull(javaClass.classLoader?.getResourceAsStream("honor/pilgrimage/$name")) {
            "missing test resource honor/pilgrimage/$name"
        }.use { it.readBytes() }

    private fun fixtureText(name: String): String = fixture(name).toString(Charsets.UTF_8)

    private fun stage00(): Way = PilgrimageWayImporter.way(from = fixture("stage-00.json"), routeId = "camino-frances", stageIndex = 0)

    private fun stage(json: String): Way = PilgrimageWayImporter.way(from = json.toByteArray(), routeId = "camino-frances", stageIndex = 0)

    private fun route(json: String): PilgrimageRoute = PilgrimageWayImporter.route(from = json.toByteArray())

    private fun stageTree(): JsonElement = Json.parseToJsonElement(fixtureText("stage-00.json"))

    private fun routeTree(): JsonElement = Json.parseToJsonElement(fixtureText("route.json"))

    /** `stage-00.json` with the value at [path] set to [value], or removed when [value] is null. */
    private fun stageWith(vararg path: Any, value: JsonElement?): String = stageTree().edited(path.toList(), value).toString()

    /** `route.json` with the value at [path] set to [value], or removed when [value] is null. */
    private fun routeWith(vararg path: Any, value: JsonElement?): String = routeTree().edited(path.toList(), value).toString()

    /** The tree with the value at [path] (object keys and array indices) set to [value], or removed when it is null. */
    private fun JsonElement.edited(path: List<Any>, value: JsonElement?): JsonElement {
        val head = path.first()
        val rest = path.drop(1)
        return when (this) {
            is JsonObject -> {
                val key = head as String
                val next = if (rest.isEmpty()) value else getValue(key).edited(rest, value)
                JsonObject(if (next == null) this - key else this + (key to next))
            }
            is JsonArray -> {
                val index = head as Int
                val next = if (rest.isEmpty()) value else this[index].edited(rest, value)
                JsonArray(toMutableList().apply { if (next == null) removeAt(index) else set(index, next) })
            }
            else -> error("$head: a primitive has nothing inside it")
        }
    }

    private fun point(lon: Double, t: Int): JsonObject = buildJsonObject {
        put("lat", 0)
        put("lon", lon)
        put("t", t)
    }

    private fun moment(id: String, kind: String): JsonObject = buildJsonObject {
        put("id", id)
        put("frac", 0.5)
        put("kind", kind)
    }

    private fun mark(id: String, kind: String = "water"): JsonObject = buildJsonObject {
        put("id", id)
        put("kind", kind)
        put("name", "x")
        putJsonObject("at") {
            put("lat", 0)
            put("lon", 0)
        }
        put("frac", 0.1)
        put("offLineMeters", 5)
    }

    private fun hours(min: Double, max: Double): JsonObject = buildJsonObject {
        put("min", min)
        put("max", max)
    }

    private fun routeRow(index: Long): JsonObject = buildJsonObject {
        put("index", index)
        put("name", "s$index")
        put("distanceKm", 20)
        put("gainMeters", 300)
        put("hours", hours(5.0, 7.0))
        put("difficulty", "moderate")
    }

    private fun assertBound(accepted: List<Pair<String, String>>, refused: List<Pair<String, String>>) {
        accepted.forEach { (name, json) -> assertEquals(name, "pilgrimage:camino-frances:0", stage(json).id) }
        refused.forEach { (name, json) -> assertNotWalkable(name) { stage(json) } }
    }

    /** The wire model itself refuses [json]: no default stands in for a required key. */
    private fun <T> assertDecodeRefused(message: String, deserializer: DeserializationStrategy<T>, json: String) {
        assertThrows(message, IllegalArgumentException::class.java) { WayImporter.decodeWire(deserializer, json.toByteArray()) }
    }

    private fun assertNotWalkable(message: String? = null, block: () -> Unit) {
        val e = assertThrows(message, PilgrimageException::class.java) { block() }
        assertEquals(message, PilgrimageError.NOT_WALKABLE, e.error)
    }

    private fun line(error: PilgrimageError): String = resources.getString(PilgrimageCopy.line(error))

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
