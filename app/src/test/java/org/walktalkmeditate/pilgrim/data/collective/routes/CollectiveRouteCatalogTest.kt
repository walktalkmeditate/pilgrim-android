// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.collective.routes

import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.walktalkmeditate.pilgrim.data.units.UnitSystem

/**
 * Ports iOS `UnitTests/CollectiveRouteCatalogTests.swift@9a418e4` one test
 * for one test (parity spec `docs/parity/2026-07-23-port-route-catalog-u2.md`).
 *
 * Two fixtures, and the pairing is load-bearing: the two-route fixture owns
 * the web's published pick (7 Oct 2026 → kumano-kodo) and the 26/30 October
 * distribution; the production parity fixture owns the 62 webPicks/webLines
 * vectors, 7 Oct 2026 → camino-primitivo, and the 21/31 distribution.
 * Asserting a vector against the wrong fixture fails in a way that looks
 * exactly like a broken port.
 */
class CollectiveRouteCatalogTest {

    private val collectiveKm = 696.98

    private val twoRouteFixture: CollectiveRouteCatalog by lazy {
        CollectiveRouteCatalog.decode(loadFixture("collective/collective-routes-two-route-fixture.json"))
    }

    private val production: CollectiveRouteCatalog by lazy { loadParityCatalog() }

    private fun loadFixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "missing test resource $name" }
            .bufferedReader()
            .readText()

    private fun utcMillis(
        year: Int,
        month: Int,
        day: Int,
        hour: Int = 0,
        minute: Int = 0,
        second: Int = 0,
    ): Long = ZonedDateTime.of(year, month, day, hour, minute, second, 0, ZoneOffset.UTC)
        .toInstant()
        .toEpochMilli()

    private fun catalogJson(routes: String = "", horizons: String = ""): String =
        """{ "version": "v", "pilgrimages": [$routes], "horizons": [$horizons] }"""

    private fun makeRoute(
        nameEn: String,
        km: Double,
        best: List<Int> = emptyList(),
        peak: List<Int> = emptyList(),
        id: String = "route-id",
    ): CollectiveRoute = CollectiveRoute(
        id = id,
        kind = CollectiveRoute.Kind.Route(nameEn),
        km = km,
        companyLine = "Some walked it.",
        bestMonths = best,
        peakMonths = peak,
    )

    private fun makeHorizon(
        preposition: String,
        body: String,
        km: Double,
        id: String = "horizon-id",
    ): CollectiveRoute = CollectiveRoute(
        id = id,
        kind = CollectiveRoute.Kind.Cosmic(preposition, body),
        km = km,
        companyLine = "No one has.",
    )

    // Decoding

    @Test
    fun `decode reads both arrays into one entry list`() {
        assertEquals("fixture", twoRouteFixture.version)
        assertEquals(5, twoRouteFixture.entries.size)
    }

    @Test
    fun `decode orders routes by id then appends horizons in artifact order`() {
        assertEquals(
            listOf("camino-frances", "kumano-kodo", "around-earth", "to-the-moon", "to-the-sun"),
            twoRouteFixture.entries.map { it.id },
        )
    }

    @Test
    fun `decode binds route and cosmic payloads`() {
        assertEquals(CollectiveRoute.Kind.Route("Camino Francés"), twoRouteFixture.entries.first().kind)
        assertEquals(CollectiveRoute.Kind.Cosmic("to", "the Sun"), twoRouteFixture.entries.last().kind)
    }

    @Test
    fun `decode drops entry with unrecognised kind and survivors still select`() {
        val catalog = CollectiveRouteCatalog.decode(
            catalogJson(
                routes = """
                { "id": "good", "kind": "route", "nameEn": "Good", "companyLine": "c", "km": 10 },
                { "id": "weird", "kind": "wormhole", "nameEn": "Weird", "companyLine": "c", "km": 20 }
                """.trimIndent(),
                horizons = """
                { "id": "around-earth", "kind": "cosmic", "preposition": "around", "body": "the Earth", "companyLine": "c", "km": 40075 }
                """.trimIndent(),
            ),
        )

        assertEquals(listOf("good", "around-earth"), catalog.entries.map { it.id })
        assertNotNull(
            "Surviving entries must still select after a sibling is dropped",
            catalog.entry(utcMillis(2026, 10, 7)),
        )
    }

    @Test
    fun `decode drops entry missing its distance`() {
        val catalog = CollectiveRouteCatalog.decode(
            catalogJson(
                routes = """
                { "id": "good", "kind": "route", "nameEn": "Good", "companyLine": "c", "km": 10 },
                { "id": "no-km", "kind": "route", "nameEn": "No Distance", "companyLine": "c" }
                """.trimIndent(),
            ),
        )
        assertEquals(listOf("good"), catalog.entries.map { it.id })
    }

    @Test
    fun `decode drops entry with non positive distance`() {
        val catalog = CollectiveRouteCatalog.decode(
            catalogJson(
                routes = """
                { "id": "good", "kind": "route", "nameEn": "Good", "companyLine": "c", "km": 10 },
                { "id": "zero", "kind": "route", "nameEn": "Zero", "companyLine": "c", "km": 0 },
                { "id": "negative", "kind": "route", "nameEn": "Negative", "companyLine": "c", "km": -5 }
                """.trimIndent(),
            ),
        )
        assertEquals(listOf("good"), catalog.entries.map { it.id })
    }

    @Test
    fun `decode drops route missing its name`() {
        val catalog = CollectiveRouteCatalog.decode(
            catalogJson(
                routes = """
                { "id": "good", "kind": "route", "nameEn": "Good", "companyLine": "c", "km": 10 },
                { "id": "nameless", "kind": "route", "companyLine": "c", "km": 20 }
                """.trimIndent(),
            ),
        )
        assertEquals(listOf("good"), catalog.entries.map { it.id })
    }

    @Test
    fun `decode drops entry missing its company line`() {
        val catalog = CollectiveRouteCatalog.decode(
            catalogJson(
                routes = """
                { "id": "good", "kind": "route", "nameEn": "Good", "companyLine": "c", "km": 10 },
                { "id": "silent", "kind": "route", "nameEn": "Silent", "km": 20 }
                """.trimIndent(),
            ),
        )
        assertEquals(
            "An entry with nobody to name cannot satisfy the walk-summary line",
            listOf("good"),
            catalog.entries.map { it.id },
        )
    }

    @Test
    fun `decode treats absent season arrays as no seasonality`() {
        val catalog = CollectiveRouteCatalog.decode(
            catalogJson(
                routes = """{ "id": "sparse", "kind": "route", "nameEn": "Sparse", "companyLine": "c", "km": 100 }""",
            ),
        )
        val entry = catalog.entries.single()
        assertEquals(emptyList<Int>(), entry.bestMonths)
        assertEquals(emptyList<Int>(), entry.peakMonths)
    }

    @Test
    fun `decode survives a missing horizons array`() {
        val catalog = CollectiveRouteCatalog.decode(
            """{ "version": "v", "pilgrimages": [ { "id": "a", "kind": "route", "nameEn": "A", "companyLine": "c", "km": 10 } ] }""",
        )
        assertEquals(1, catalog.entries.size)
    }

    @Test
    fun `empty catalog has no entries`() {
        assertTrue(CollectiveRouteCatalog.EMPTY.entries.isEmpty())
    }

    // Seeding

    @Test
    fun `utc seed packs the utc calendar date`() {
        assertEquals(20_261_007u, CollectiveRouteSeed.utcDay(utcMillis(2026, 10, 7)).seed)
    }

    @Test
    fun `utc seed ignores the time of day`() {
        assertEquals(20_261_007u, CollectiveRouteSeed.utcDay(utcMillis(2026, 10, 7, 23, 59, 59)).seed)
    }

    @Test
    fun `hash matches the web scramble`() {
        assertEquals(3_837_869_072u, CollectiveRouteSeed.hash(20_261_007u))
        assertEquals(1_575_279_303u, CollectiveRouteSeed.hash(20_260_101u))
        assertEquals(824_515_495u, CollectiveRouteSeed.hash(1u))
        assertEquals(0u, CollectiveRouteSeed.hash(0u))
        assertEquals(539_527_247u, CollectiveRouteSeed.hash(4_294_967_295u))
    }

    // Seasonal weighting

    private val weightedKumano = makeRoute("Kumano Kodo", km = 39.0, best = listOf(3, 4, 5, 10, 11), peak = listOf(4, 5, 10, 11))
    private val weightedCamino = makeRoute("Camino", km = 700.0, best = listOf(5, 6, 9), peak = listOf(7, 8))

    @Test
    fun `weight best and peak month takes both bonuses`() {
        assertEquals(6, weightedKumano.weight(inMonth = 10))
    }

    @Test
    fun `weight off season month takes neither bonus`() {
        assertEquals(1, weightedKumano.weight(inMonth = 7))
    }

    @Test
    fun `weight best but not peak month takes only the season bonus`() {
        assertEquals(3, weightedCamino.weight(inMonth = 5))
    }

    @Test
    fun `weight peak but not best month takes no bonus at all`() {
        assertEquals(1, weightedCamino.weight(inMonth = 7))
    }

    @Test
    fun `weight entry with no seasonality stays at base`() {
        assertEquals(1, makeRoute("Sparse", km = 100.0).weight(inMonth = 7))
    }

    @Test
    fun `weight cosmic horizon is constant across the year`() {
        val earth = makeHorizon("around", "the Earth", km = 40_075.0)
        assertEquals(1, earth.weight(inMonth = 10))
        assertEquals(1, earth.weight(inMonth = 1))
    }

    // Selection — two-route fixture

    @Test
    fun `two route fixture reproduces the webs pinned fixture vector`() {
        assertEquals("kumano-kodo", twoRouteFixture.entry(utcMillis(2026, 10, 7))?.id)
    }

    @Test
    fun `two route fixture october favours in season routes`() {
        val inSeason = (1..30).filter { day ->
            twoRouteFixture.entry(utcMillis(2026, 10, day))?.bestMonths?.contains(10) ?: false
        }
        assertEquals(26, inSeason.size)
    }

    @Test
    fun `entry is stable across repeated calls`() {
        val date = utcMillis(2026, 10, 7)
        val first = twoRouteFixture.entry(date)?.id
        assertEquals(first, twoRouteFixture.entry(date)?.id)
        assertEquals(first, twoRouteFixture.entry(date)?.id)
    }

    @Test
    fun `entry agrees across the whole utc day`() {
        assertEquals(
            twoRouteFixture.entry(utcMillis(2026, 10, 7, 0, 0, 0))?.id,
            twoRouteFixture.entry(utcMillis(2026, 10, 7, 23, 59, 59))?.id,
        )
    }

    @Test
    fun `entry agrees across zones on the same utc day`() {
        val tokyoMorning = ZonedDateTime.of(2026, 10, 7, 9, 0, 0, 0, ZoneId.of("Asia/Tokyo"))
            .toInstant()
            .toEpochMilli()
        val losAngelesAfternoon = ZonedDateTime.of(2026, 10, 7, 16, 59, 59, 0, ZoneId.of("America/Los_Angeles"))
            .toInstant()
            .toEpochMilli()

        val utcPick = twoRouteFixture.entry(utcMillis(2026, 10, 7))?.id
        assertEquals(utcPick, twoRouteFixture.entry(tokyoMorning)?.id)
        assertEquals(utcPick, twoRouteFixture.entry(losAngelesAfternoon)?.id)
    }

    @Test
    fun `reordering the routes does not change the selection`() {
        val routes = twoRouteFixture.entries.filterNot { it.isCosmic }
        val horizons = twoRouteFixture.entries.filter { it.isCosmic }
        val reordered = CollectiveRouteCatalog("fixture", routes.reversed() + horizons)

        for (day in 1..31) {
            val date = utcMillis(2026, 10, day)
            assertEquals("on day $day", twoRouteFixture.entry(date)?.id, reordered.entry(date)?.id)
        }
    }

    @Test
    fun `canonically ordered keeps horizons in the order given`() {
        val moon = makeHorizon("to", "the Moon", km = 384_400.0, id = "to-the-moon")
        val earth = makeHorizon("around", "the Earth", km = 40_075.0, id = "around-earth")
        assertEquals(
            listOf("to-the-moon", "around-earth"),
            CollectiveRouteCatalog.canonicallyOrdered(listOf(moon, earth)).map { it.id },
        )
    }

    @Test
    fun `empty catalog selects nothing`() {
        assertNull(CollectiveRouteCatalog.EMPTY.entry(utcMillis(2026, 10, 7)))
    }

    @Test
    fun `horizons only catalog still selects`() {
        val catalog = CollectiveRouteCatalog(
            "v",
            listOf(
                makeHorizon("around", "the Earth", km = 40_075.0, id = "around-earth"),
                makeHorizon("to", "the Moon", km = 384_400.0, id = "to-the-moon"),
            ),
        )
        val picked = (1..31).mapNotNull { catalog.entry(utcMillis(2026, 10, it))?.id }
        assertEquals(31, picked.size)
        assertTrue(picked.toSet().all { it in setOf("around-earth", "to-the-moon") })
    }

    // Daily-line phrasing

    private val kumano = makeRoute("Kumano Kodo", km = 39.0)
    private val frances = makeRoute("Camino Francés", km = 764.0)
    private val earth = makeHorizon("around", "the Earth", km = 40_075.0)
    private val moon = makeHorizon("to", "the Moon", km = 384_400.0)
    private val sun = makeHorizon("to", "the Sun", km = 149_600_000.0)

    @Test
    fun `daily line unknown total says nothing`() {
        assertNull(kumano.dailyLine(collectiveKm = null, units = UnitSystem.Metric))
    }

    @Test
    fun `daily line zero total says the path is beginning`() {
        assertEquals("The path is beginning.", kumano.dailyLine(collectiveKm = 0.0, units = UnitSystem.Metric))
    }

    @Test
    fun `daily line route walked many times counts the completions`() {
        assertEquals(
            "Together, we've walked the Kumano Kodo 17 times.",
            kumano.dailyLine(collectiveKm = 694.5, units = UnitSystem.Metric),
        )
    }

    @Test
    fun `daily line route walked once says one complete`() {
        assertEquals(
            "Together, one Test Route complete.",
            makeRoute("Test Route", km = 500.0).dailyLine(collectiveKm = 694.5, units = UnitSystem.Metric),
        )
    }

    @Test
    fun `daily line route not yet reached states a percentage`() {
        assertEquals(
            "We are 91% of the way to one Camino Francés.",
            frances.dailyLine(collectiveKm = 694.5, units = UnitSystem.Metric),
        )
    }

    @Test
    fun `daily line route almost reached clamps below one hundred percent`() {
        assertEquals(
            "We are 99% of the way to one Near Route.",
            makeRoute("Near Route", km = 700.0).dailyLine(collectiveKm = 699.0, units = UnitSystem.Metric),
        )
    }

    @Test
    fun `daily line horizon reached twice counts the circuits`() {
        assertEquals(
            "Together, 2 times around the Earth.",
            earth.dailyLine(collectiveKm = 90_000.0, units = UnitSystem.Metric),
        )
    }

    @Test
    fun `daily line horizon reached exactly once says once`() {
        assertEquals(
            "Together, once around the Earth.",
            earth.dailyLine(collectiveKm = 40_075.0, units = UnitSystem.Metric),
        )
    }

    @Test
    fun `daily line horizon at or above one percent states one decimal`() {
        assertEquals(
            "We are 1.7% of the way around the Earth.",
            earth.dailyLine(collectiveKm = 694.5, units = UnitSystem.Metric),
        )
    }

    @Test
    fun `daily line horizon below one percent states the remaining distance`() {
        assertEquals("383,706 km to the Moon.", moon.dailyLine(collectiveKm = 694.5, units = UnitSystem.Metric))
        assertEquals("149,599,306 km to the Sun.", sun.dailyLine(collectiveKm = 694.5, units = UnitSystem.Metric))
    }

    @Test
    fun `daily line horizon below one percent renders in miles when preferred`() {
        assertEquals("238,424 mi to the Moon.", moon.dailyLine(collectiveKm = 694.5, units = UnitSystem.Imperial))
    }

    @Test
    fun `daily line nonsense total clamps completions to the ceiling`() {
        // "Misprint, not crash": an absurd total from a bad API response
        // must clamp at COMPLETIONS_CEILING instead of overflowing the
        // Long conversion.
        assertEquals(
            "Together, we've walked the Kumano Kodo 1000000000000 times.",
            kumano.dailyLine(collectiveKm = Double.MAX_VALUE, units = UnitSystem.Metric),
        )
        assertEquals(
            "Together, 1000000000000 times around the Earth.",
            earth.dailyLine(collectiveKm = Double.MAX_VALUE, units = UnitSystem.Metric),
        )
    }

    @Test
    fun `daily line non finite total says the path is beginning`() {
        assertEquals(
            "The path is beginning.",
            kumano.dailyLine(collectiveKm = Double.POSITIVE_INFINITY, units = UnitSystem.Metric),
        )
        assertEquals(
            "The path is beginning.",
            kumano.dailyLine(collectiveKm = Double.NaN, units = UnitSystem.Metric),
        )
    }

    // Contribution phrasing

    private val norte = CollectiveRoute(
        id = "camino-norte",
        kind = CollectiveRoute.Kind.Route("Camino del Norte"),
        km = 784.0,
        companyLine = "21,521 pilgrims completed it in 2025.",
    )
    private val contributionEarth = CollectiveRoute(
        id = "around-earth",
        kind = CollectiveRoute.Kind.Cosmic("around", "the Earth"),
        km = 40_075.0,
        companyLine = "A handful have ever walked it; the first finished in 1974.",
    )

    @Test
    fun `contribution line route places the walk against it and names its company`() {
        assertEquals(
            "Your 4.2 km against the Camino del Norte. 21,521 pilgrims completed it in 2025.",
            norte.contributionLine(walkKm = 4.2, units = UnitSystem.Metric),
        )
    }

    @Test
    fun `contribution line horizon states the horizons magnitude`() {
        assertEquals(
            "Your 4.2 km against 40,075 km around the Earth. A handful have ever walked it; the first finished in 1974.",
            contributionEarth.contributionLine(walkKm = 4.2, units = UnitSystem.Metric),
        )
    }

    @Test
    fun `contribution line horizon day is never skipped`() {
        val horizonDay = utcMillis(2026, 10, 12)
        assertEquals("around-earth", production.entry(horizonDay)?.id)
        assertNotNull(production.contributionLine(horizonDay, walkKm = 4.2, units = UnitSystem.Metric))
    }

    @Test
    fun `contribution line respects the pilgrims unit`() {
        assertEquals(
            "Your 2.6 mi against the Camino del Norte. 21,521 pilgrims completed it in 2025.",
            norte.contributionLine(walkKm = 4.2, units = UnitSystem.Imperial),
        )
    }

    @Test
    fun `contribution line empty catalog says nothing`() {
        assertNull(
            CollectiveRouteCatalog.EMPTY.contributionLine(utcMillis(2026, 10, 7), walkKm = 4.2, units = UnitSystem.Metric),
        )
    }

    @Test
    fun `zero distance walk contribution renders whole kilometres`() {
        assertEquals(
            "Your 0 km against the Camino del Norte. 21,521 pilgrims completed it in 2025.",
            norte.contributionLine(walkKm = 0.0, units = UnitSystem.Metric),
        )
    }

    // Parity with the web module — production-artifact fixture only

    private val webPicks = listOf(
        Triple(
            2026,
            10,
            "kumano-kodo,camino-primitivo,kumano-kodo,camino-primitivo,camino-ingles,shikoku-88," +
                "camino-primitivo,shikoku-88,kumano-kodo,shikoku-88,camino-primitivo,around-earth," +
                "shikoku-88,camino-portugues,kumano-kodo,shikoku-88,shikoku-88,camino-portugues," +
                "kumano-kodo,camino-primitivo,camino-ingles,camino-frances,shikoku-88,camino-primitivo," +
                "camino-portugues,camino-ingles,camino-portugues,camino-primitivo,around-earth," +
                "kumano-kodo,kumano-kodo",
        ),
        Triple(
            2027,
            1,
            "camino-primitivo,around-earth,camino-primitivo,kumano-kodo,camino-norte,to-the-sun," +
                "kumano-kodo,kumano-kodo,shikoku-88,to-the-sun,camino-norte,camino-portugues," +
                "shikoku-88,camino-norte,camino-frances,around-earth,camino-norte,camino-portugues," +
                "kumano-kodo,shikoku-88,camino-portugues,camino-ingles,camino-ingles,shikoku-88," +
                "camino-frances,to-the-moon,camino-ingles,camino-ingles,to-the-sun,camino-primitivo," +
                "to-the-moon",
        ),
    )

    private val webLines = mapOf(
        "around-earth" to "We are 1.7% of the way around the Earth.",
        "camino-frances" to "We are 91% of the way to one Camino de Santiago.",
        "camino-ingles" to "Together, we've walked the Camino Inglés 6 times.",
        "camino-norte" to "We are 89% of the way to one Camino del Norte.",
        "camino-portugues" to "Together, we've walked the Camino Portugués 2 times.",
        "camino-primitivo" to "Together, we've walked the Camino Primitivo 2 times.",
        "kumano-kodo" to "Together, we've walked the Kumano Kodo 17 times.",
        "shikoku-88" to "We are 58% of the way to one Shikoku 88 Temple Pilgrimage.",
        "to-the-moon" to "383,703 km to the Moon.",
        "to-the-sun" to "149,599,303 km to the Sun.",
    )

    private fun eachWebPick(check: (Long, String, String) -> Unit) {
        for ((year, month, ids) in webPicks) {
            ids.split(",").forEachIndexed { index, expectedId ->
                val day = index + 1
                check(utcMillis(year, month, day), expectedId, "on $year-$month-$day")
            }
        }
    }

    @Test
    fun `entry agrees with the web every day of two sample months`() {
        eachWebPick { date, expectedId, label ->
            assertEquals(label, expectedId, production.entry(date)?.id)
        }
    }

    @Test
    fun `daily line agrees with the web every day of two sample months`() {
        eachWebPick { date, expectedId, label ->
            assertEquals(
                label,
                webLines.getValue(expectedId),
                production.dailyLine(date, collectiveKm = collectiveKm, units = UnitSystem.Metric),
            )
        }
    }

    @Test
    fun `production catalog diverges from the webs fixture vector`() {
        assertEquals(
            "The published 'kumano-kodo' vector belongs to the two-route test fixture, not this artifact",
            "camino-primitivo",
            production.entry(utcMillis(2026, 10, 7))?.id,
        )
    }

    @Test
    fun `production october favours in season routes`() {
        val inSeason = (1..31).filter { day ->
            production.entry(utcMillis(2026, 10, day))?.bestMonths?.contains(10) ?: false
        }
        assertEquals(21, inSeason.size)
    }

    @Test
    fun `consecutive days scatter`() {
        val month = (1..31).mapNotNull { production.entry(utcMillis(2026, 10, it))?.id }
        val changes = month.zipWithNext().count { (a, b) -> a != b }
        assertTrue("Consecutive days should rarely repeat (changes: $changes)", changes >= 20)
        assertTrue("A month should surface several different entries", month.toSet().size >= 5)
    }
}
