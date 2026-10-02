// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.honor.pilgrimage

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.Locale
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.honor.WayStore
import org.walktalkmeditate.pilgrim.domain.honor.WayCoordinate
import org.walktalkmeditate.pilgrim.domain.honor.WayStage
import org.walktalkmeditate.pilgrim.domain.honor.WayStageHours
import org.walktalkmeditate.pilgrim.domain.honor.WayStagePlace

/**
 * Port of iOS `PilgrimageLedgerTests.swift@7c200bf` (11, names kept), then
 * the spec's additions (P2 §8, §10, C-7, C-8, C-15): the order-safe replay,
 * a nil outcome writing nothing, a non-finite fraction, the 5% edge on both
 * sides, the NFC name match, a failed write thrown, the file's shape, and
 * the store's other writes. The two lock layers are
 * [PilgrimageLedgerLockTest]'s. Robolectric for the progress line's strings.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PilgrimageLedgerTest {

    @get:Rule val folder = TemporaryFolder()

    private val day = Instant.ofEpochSecond(1_800_000_000)
    private val resources = ApplicationProvider.getApplicationContext<Context>().resources
    private lateinit var dir: File
    private lateinit var wayStore: WayStore
    private lateinit var store: PilgrimageLedgerStore

    @Before
    fun setUp() {
        dir = File(folder.root, "Ways")
        wayStore = WayStore({ dir }, syncDirectory = { true })
        store = PilgrimageLedgerStore(wayStore)
    }

    private fun ledger() = PilgrimageLedger(routeId = "camino-frances")

    private fun stage(index: Int, name: String, km: Double) = WayStage(
        routeId = "camino-frances",
        index = index,
        count = 33,
        name = name,
        theme = "t",
        narrative = "n",
        closing = "c",
        warnings = emptyList(),
        distanceKm = km,
        gainMeters = 100.0,
        hours = WayStageHours(min = 5.0, max = 7.0),
        difficulty = "moderate",
        start = WayStagePlace(name = "a", at = WayCoordinate(lat = 0.0, lon = 0.0)),
        end = WayStagePlace(name = "b", at = WayCoordinate(lat = 0.0, lon = 0.01)),
    )

    private fun routeStage(index: Int, name: String, km: Double) = PilgrimageRouteStage(
        index = index,
        name = name,
        distanceKm = km,
        gainMeters = 100.0,
        hours = WayStageHours(min = 5.0, max = 7.0),
        difficulty = "moderate",
    )

    private fun identity(index: Int, name: String = "s$index", km: Double = 20.0, routeId: String = "camino-frances") =
        PilgrimageStageIdentity(routeId = routeId, index = index, name = name, distanceKm = km)

    private val arrived = HonorStageOutcome(progressFrac = 1.0, arrived = true)

    private val ledgerFile get() = File(dir, "pilgrimage/camino-frances/ledger.json")

    /** The stage surfaces' formatter is U37's (owner decision 7); the line spells whatever it is given. */
    private val kilometres = { meters: Double -> String.format(Locale.US, "%.2f km", meters / 1000) }

    // ---- iOS PilgrimageLedgerTests: recording ----

    @Test
    fun `a completed stage and a partial one are both remembered`() {
        val led = ledger()
            .recorded(0, "SJPP to Roncesvalles", 24.2, HonorStageOutcome(progressFrac = 1.0, arrived = true), day)
            .recorded(1, "Roncesvalles to Zubiri", 21.9, HonorStageOutcome(progressFrac = 0.58, arrived = false), day)

        assertEquals(true, led.stages["0"]?.completed)
        assertEquals(24.2, led.stages["0"]?.kmWalked ?: 0.0, 0.01)
        assertNull(led.stages["0"]?.stoppedAtFrac)
        assertEquals(false, led.stages["1"]?.completed)
        assertEquals(0.58, led.stages["1"]?.stoppedAtFrac ?: 0.0, 0.001)
        assertEquals(21.9 * 0.58, led.stages["1"]?.kmWalked ?: 0.0, 0.01)
        assertEquals(24.2 + 21.9 * 0.58, led.totalKmWalked, 0.01)
        assertEquals(1, led.completedCount)
    }

    @Test
    fun `a second walk of the same stage never loses ground`() {
        val led = ledger()
            .recorded(0, "s", 24.2, HonorStageOutcome(progressFrac = 1.0, arrived = true), day)
            .recorded(0, "s", 24.2, HonorStageOutcome(progressFrac = 0.2, arrived = false), day.plusSeconds(86_400))

        assertEquals("walking it again half-way does not un-walk it", true, led.stages["0"]?.completed)
        assertEquals(24.2, led.stages["0"]?.kmWalked ?: 0.0, 0.01)
    }

    // ---- iOS PilgrimageLedgerTests: the next row ----

    @Test
    fun `next offers the first unwalked stage and resumes a partial one`() {
        var led = ledger().recorded(0, "a", 24.2, HonorStageOutcome(progressFrac = 1.0, arrived = true), day)
        assertEquals(PilgrimageLedger.Next(index = 1, resumeFrac = null), led.next(stageCount = 3))

        led = led.recorded(1, "b", 21.9, HonorStageOutcome(progressFrac = 0.58, arrived = false), day)
        assertEquals(PilgrimageLedger.Next(index = 1, resumeFrac = 0.58), led.next(stageCount = 3))

        led = led.recorded(1, "b", 21.9, HonorStageOutcome(progressFrac = 1.0, arrived = true), day)
            .recorded(2, "c", 20.0, HonorStageOutcome(progressFrac = 1.0, arrived = true), day)
        assertNull("every stage walked", led.next(stageCount = 3))
    }

    @Test
    fun `an empty ledger offers the first stage`() {
        assertEquals(
            PilgrimageLedger.Next(index = 0, resumeFrac = null),
            PilgrimageLedger(routeId = "x").next(stageCount = 33),
        )
    }

    @Test
    fun `progress line reads the stage you are on`() {
        var led = ledger()
        assertEquals("33 stages", PilgrimageLedger.progressLine(resources, ledger = null, stageCount = 33, kilometres))
        for (index in 0 until 4) {
            led = led.recorded(index, "s$index", 28.0, HonorStageOutcome(progressFrac = 1.0, arrived = true), day)
        }

        assertEquals(
            "stage 5 of 33 · ${kilometres(112_000.0)} walked",
            PilgrimageLedger.progressLine(resources, led, stageCount = 33, kilometres),
        )

        for (index in 4 until 33) {
            led = led.recorded(index, "s$index", 10.0, HonorStageOutcome(progressFrac = 1.0, arrived = true), day)
        }
        assertTrue(
            PilgrimageLedger.progressLine(resources, led, stageCount = 33, kilometres).startsWith("you have walked the whole way"),
        )
    }

    // ---- iOS PilgrimageLedgerTests: identity across an update ----

    @Test
    fun `reconcile keeps stages whose identity held and carries the rest's kilometres`() {
        val led = ledger()
            .recorded(0, "SJPP to Roncesvalles", 24.2, arrived, day)
            .recorded(1, "Roncesvalles to Zubiri", 21.9, arrived, day)
            .recorded(2, "Zubiri to Pamplona", 20.4, arrived, day)

        val reconciled = led.reconciled(
            listOf(
                routeStage(0, name = "SJPP to Roncesvalles", km = 24.2),
                routeStage(1, name = "Roncesvalles to Zubiri", km = 22.7),
                routeStage(2, name = "Zubiri to Larrasoaña", km = 20.4),
            ),
        )

        assertEquals(setOf("0", "1"), reconciled.stages.keys)
        assertEquals("the dropped stage's kilometres are kept", 20.4, reconciled.carriedKm ?: 0.0, 0.01)
        assertEquals(24.2 + 21.9 + 20.4, reconciled.totalKmWalked, 0.01)
        assertEquals(true, reconciled.redrawNoticePending)
    }

    @Test
    fun `a stage whose distance moved more than five percent is dropped`() {
        val led = ledger().recorded(0, "a", 20.0, arrived, day)

        val reconciled = led.reconciled(listOf(routeStage(0, name = "a", km = 21.5)))

        assertTrue(reconciled.stages.isEmpty())
        assertEquals(20.0, reconciled.carriedKm ?: 0.0, 0.01)
    }

    @Test
    fun `an unchanged route raises no notice`() {
        val led = ledger().recorded(0, "a", 20.0, arrived, day)

        val reconciled = led.reconciled(listOf(routeStage(0, name = "a", km = 20.0)))

        assertEquals(1, reconciled.stages.size)
        assertNull(reconciled.redrawNoticePending)
        assertNull(reconciled.carriedKm)
    }

    // ---- iOS PilgrimageLedgerTests: the writer ----

    @Test
    fun `nothing is written without an anchor`() {
        assertNull(
            "the engine never anchored on the Way: no stage was walked",
            PilgrimageLedgerWriter.entry(PilgrimageStageIdentity(stage(0, name = "a", km = 24.2)), outcome = null),
        )

        val written = PilgrimageLedgerWriter.entry(
            PilgrimageStageIdentity(stage(3, name = "a", km = 24.2)),
            HonorStageOutcome(progressFrac = 0.4, arrived = false),
        )

        assertEquals(3, written?.index)
        assertEquals(24.2, written?.distanceKm)
    }

    // ---- iOS PilgrimageLedgerTests: the file ----

    @Test
    fun `the ledger outlives the stages`() {
        store.save(ledger().recorded(0, "a", 24.2, arrived, day))
        assertEquals(1, store.load("camino-frances")?.completedCount)

        wayStore.delete(WayStore.stageWayId(routeId = "camino-frances", stageIndex = 0))

        assertEquals(1, store.load("camino-frances")?.completedCount)
        assertNull(store.load("../etc"))
    }

    /*
     * open-pilgrimages has renamed route ids twice: `kumano-kodo` became
     * `kumano-kodo-nakahechi` in v1.8.0, and `shikoku-88` became the four
     * dojo in v1.9.0. A ledger kept under the old id stays readable and is
     * never half-listed: the catalog draws the rows.
     */
    @Test
    fun `a renamed route leaves its ledger readable and unlisted`() {
        store.save(PilgrimageLedger(routeId = "shikoku-88").recorded(0, "Temples 1-10", 40.0, arrived, day))

        val catalog = PilgrimageCatalogService.parse(fixture("index-pilgrimages.json"))

        assertFalse(
            "shikoku-88 is a pilgrimage id now, and pilgrimages have no package",
            catalog.routes.any { it.id == "shikoku-88" },
        )
        assertFalse(catalog.groups.flatMap { it.entries }.any { it.id == "shikoku-88" })
        val kept = store.load("shikoku-88")!!
        assertEquals(1, kept.completedCount)
        assertEquals(40.0, kept.totalKmWalked, 0.01)
    }

    // ---- the order-safe merge (P2 A-5, C-8) ----

    @Test
    fun `an older walk replayed after a newer one keeps the newer date, stop, name and distance, and still completes and takes the max`() {
        val newer = day.plusSeconds(86_400)
        val afterUpdate = ledger().recorded(1, "Roncesvalles to Zubiri", 22.7, HonorStageOutcome(0.3, arrived = false), newer)

        val furtherBefore = afterUpdate.recorded(1, "Roncesvalles a Zubiri", 21.9, HonorStageOutcome(0.9, arrived = false), day)
        val arrivedBefore = afterUpdate.recorded(1, "Roncesvalles a Zubiri", 21.9, HonorStageOutcome(0.5, arrived = true), day)

        assertEquals(
            PilgrimageLedger.Entry(
                name = "Roncesvalles to Zubiri",
                distanceKm = 22.7,
                walkedAt = newer,
                kmWalked = 21.9 * 0.9,
                completed = false,
                stoppedAtFrac = 0.3,
            ),
            furtherBefore.stages["1"],
        )
        assertEquals(
            PilgrimageLedger.Entry(
                name = "Roncesvalles to Zubiri",
                distanceKm = 22.7,
                walkedAt = newer,
                kmWalked = 21.9,
                completed = true,
                stoppedAtFrac = null,
            ),
            arrivedBefore.stages["1"],
        )
    }

    @Test
    fun `a record is older only by a whole second, as the file stores its date`() {
        val walked = ledger().recorded(0, "a", 20.0, HonorStageOutcome(0.5, arrived = false), day)

        val sameSecond = walked.recorded(0, "b", 21.0, HonorStageOutcome(0.2, arrived = false), day.plusMillis(900))
        val secondBefore = walked.recorded(0, "b", 21.0, HonorStageOutcome(0.2, arrived = false), day.minusMillis(100))

        assertEquals("iOS's rule: the last record's identity and stop", "b", sameSecond.stages["0"]?.name)
        assertEquals(0.2, sameSecond.stages["0"]?.stoppedAtFrac ?: 0.0, 0.0)
        assertEquals("an older walk's: the entry's own", "a", secondBefore.stages["0"]?.name)
        assertEquals(0.5, secondBefore.stages["0"]?.stoppedAtFrac ?: 0.0, 0.0)
    }

    @Test
    fun `the launch retry's older walk, through the file, leaves the newer walk's entry its identity`() {
        val newer = day.plusSeconds(86_400)
        store.record(identity(4, name = "after the update", km = 22.7), HonorStageOutcome(0.3, arrived = false), newer)

        store.record(identity(4, name = "before the update", km = 21.9), arrived, day)

        val entry = store.load("camino-frances")!!.stages.getValue("4")
        assertEquals("after the update", entry.name)
        assertEquals(22.7, entry.distanceKm, 0.0)
        assertEquals(newer, entry.walkedAt)
        assertTrue(entry.completed)
        assertEquals(21.9, entry.kmWalked, 0.0)
    }

    // ---- record's rules in full (P2 C-15) ----

    @Test
    fun `a nil outcome writes nothing, not even an empty file or its folder`() {
        store.record(identity(0), outcome = null, at = day)

        assertFalse(File(dir, "pilgrimage").exists())
        assertNull(store.load("camino-frances"))
    }

    @Test
    fun `a non-finite fraction counts as 0, and any other is held to between 0 and 1`() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.5).forEach { frac ->
            val entry = ledger().recorded(0, "a", 24.2, HonorStageOutcome(frac, arrived = false), day).stages.getValue("0")
            assertEquals("$frac", 0.0, entry.stoppedAtFrac!!, 0.0)
            assertEquals("$frac", 0.0, entry.kmWalked, 0.0)
        }

        val past = ledger().recorded(0, "a", 24.2, HonorStageOutcome(1.4, arrived = false), day).stages.getValue("0")

        assertEquals(1.0, past.stoppedAtFrac!!, 0.0)
        assertEquals(24.2, past.kmWalked, 0.0)
    }

    @Test
    fun `a stage's distance that isn't finite or is negative is credited as 0`() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, -3.0).forEach { km ->
            val entry = ledger().recorded(0, "a", km, arrived, day).stages.getValue("0")
            assertEquals("$km", 0.0, entry.distanceKm, 0.0)
            assertEquals("$km", 0.0, entry.kmWalked, 0.0)
        }
    }

    @Test
    fun `a later partial walk takes over the stop, the name and the distance, as iOS ships it`() {
        val led = ledger()
            .recorded(0, "a", 20.0, HonorStageOutcome(0.8, arrived = false), day)
            .recorded(0, "a, redrawn", 20.5, HonorStageOutcome(0.1, arrived = false), day.plusSeconds(60))

        val entry = led.stages.getValue("0")
        assertEquals("a, redrawn", entry.name)
        assertEquals(20.5, entry.distanceKm, 0.0)
        assertEquals("the last stop, not the farthest", 0.1, entry.stoppedAtFrac!!, 0.0)
        assertEquals("the kilometres keep the best", 20.0 * 0.8, entry.kmWalked, 0.0)
    }

    // ---- reconcile's edges ----

    @Test
    fun `five percent of the old distance keeps an entry on either side, and a hair more drops it`() {
        val led = listOf("a", "b", "c", "d").foldIndexed(ledger()) { index, acc, name -> acc.recorded(index, name, 20.0, arrived, day) }

        val reconciled = led.reconciled(
            listOf(
                routeStage(0, name = "a", km = 21.0),
                routeStage(1, name = "b", km = 19.0),
                routeStage(2, name = "c", km = 21.01),
                routeStage(3, name = "d", km = 18.99),
            ),
        )

        assertEquals("19 is 5% of the old 20, though 5.3% of the new 19", setOf("0", "1"), reconciled.stages.keys)
        assertEquals(40.0, reconciled.carriedKm ?: 0.0, 1e-9)
    }

    @Test
    fun `a name is matched as Swift's == matches it, in any normalization, with case and accents counting`() {
        val composed = "Camino Franc\u00e9s"
        val decomposed = "Camino France\u0301s"
        val led = listOf(0, 1, 2).fold(ledger()) { acc, index -> acc.recorded(index, composed, 20.0, arrived, day) }

        val reconciled = led.reconciled(
            listOf(
                routeStage(0, name = decomposed, km = 20.0),
                routeStage(1, name = "camino franc\u00e9s", km = 20.0),
                routeStage(2, name = "Camino Frances", km = 20.0),
            ),
        )

        assertEquals(setOf("0"), reconciled.stages.keys)
    }

    @Test
    fun `a kept entry keeps its old name and distance, and a drop with no kilometres raises no notice`() {
        val led = ledger()
            .recorded(0, "a", 20.0, arrived, day)
            .recorded(1, "b", 20.0, HonorStageOutcome(0.0, arrived = false), day)

        val reconciled = led.reconciled(listOf(routeStage(0, name = "a", km = 20.9), routeStage(1, name = "renamed", km = 20.0)))

        assertEquals(20.0, reconciled.stages.getValue("0").distanceKm, 0.0)
        assertEquals(setOf("0"), reconciled.stages.keys)
        assertNull(reconciled.redrawNoticePending)
        assertNull(reconciled.carriedKm)
    }

    @Test
    fun `the progress line counts one stage in the singular, and carried kilometres alone are progress`() {
        assertEquals("1 stage", PilgrimageLedger.progressLine(resources, ledger = null, stageCount = 1, kilometres))
        assertEquals("3 stages", PilgrimageLedger.progressLine(resources, ledger(), stageCount = 3, kilometres))

        val carried = ledger().copy(carriedKm = 20.4)

        assertEquals(
            "stage 1 of 3 · ${kilometres(20_400.0)} walked",
            PilgrimageLedger.progressLine(resources, carried, stageCount = 3, kilometres),
        )
    }

    // ---- the store (P2 §8, C-7) ----

    @Test
    fun `a failed write throws, leaving no temp behind`() {
        File(ledgerFile, "in-the-way").apply { parentFile!!.mkdirs() }.writeText("x")

        assertThrows(IOException::class.java) { store.record(identity(0), arrived, day) }
        assertThrows(IOException::class.java) { store.save(ledger()) }
        assertEquals(emptyList<String>(), ledgerFile.parentFile!!.list()!!.filter { it.endsWith(".tmp") })
    }

    @Test
    fun `a route id the store refuses writes nothing and counts as done`() {
        store.record(identity(0, routeId = "../etc"), arrived, day)
        store.save(PilgrimageLedger(routeId = "Camino"))

        assertFalse(dir.exists())
    }

    @Test
    fun `an undecodable ledger reads as absent and the next record replaces it, as iOS ships it`() {
        ledgerFile.apply { parentFile!!.mkdirs() }.writeText("""{"routeId":"camino-frances"}""")

        assertNull(store.load("camino-frances"))
        store.record(identity(1), arrived, day)

        assertEquals("pilgrim-ios #120 item 5", setOf("1"), store.load("camino-frances")!!.stages.keys)
    }

    @Test
    fun `the file is the store's format, keys sorted, nulls left out, dates in whole seconds`() {
        val at = Instant.parse("2027-01-15T08:00:00.75Z")
        val led = ledger()
            .recorded(0, "Saint-Jean / Roncesvalles", 24.2, arrived, at)
            .recorded(10, "b", 21.9, HonorStageOutcome(0.58, arrived = false), at)
            .recorded(2, "c", 20.0, HonorStageOutcome(0.0, arrived = false), at)
            .copy(carriedKm = 20.4, redrawNoticePending = true)

        store.save(led)

        assertEquals(
            """{"carriedKm":20.4,"redrawNoticePending":true,"routeId":"camino-frances","stages":{""" +
                """"0":{"completed":true,"distanceKm":24.2,"kmWalked":24.2,"name":"Saint-Jean / Roncesvalles","walkedAt":"2027-01-15T08:00:00Z"},""" +
                """"10":{"completed":false,"distanceKm":21.9,"kmWalked":12.701999999999998,"name":"b","stoppedAtFrac":0.58,"walkedAt":"2027-01-15T08:00:00Z"},""" +
                """"2":{"completed":false,"distanceKm":20.0,"kmWalked":0.0,"name":"c","stoppedAtFrac":0.0,"walkedAt":"2027-01-15T08:00:00Z"}}}""",
            ledgerFile.readText(),
        )
        assertEquals(led.copy(stages = led.stages.mapValues { (_, entry) -> entry.copy(walkedAt = Instant.parse("2027-01-15T08:00:00Z")) }), store.load("camino-frances"))
    }

    @Test
    fun `clearing the notice takes its key out, and writes nothing when none is pending`() {
        store.save(ledger().recorded(0, "a", 20.0, arrived, day).copy(redrawNoticePending = true))

        store.clearRedrawNotice("camino-frances")

        assertNull(store.load("camino-frances")!!.redrawNoticePending)
        assertFalse(ledgerFile.readText().contains("redrawNoticePending"))
        ledgerFile.setLastModified(1_000_000L)
        store.clearRedrawNotice("camino-frances")
        assertEquals("no second write", 1_000_000L, ledgerFile.lastModified())
        store.clearRedrawNotice("camino-norte")
        assertFalse(File(dir, "pilgrimage/camino-norte/ledger.json").exists())
    }

    @Test
    fun `an update's reconcile rewrites a ledger on disk, and makes none where there was none`() {
        store.reconcile("camino-frances", listOf(routeStage(0, name = "a", km = 20.0)))
        assertFalse(ledgerFile.exists())

        store.save(ledger().recorded(0, "a", 20.0, arrived, day))
        store.reconcile("camino-frances", listOf(routeStage(0, name = "a, redrawn", km = 20.0)))

        val reconciled = store.load("camino-frances")!!
        assertTrue(reconciled.stages.isEmpty())
        assertEquals(20.0, reconciled.carriedKm ?: 0.0, 0.0)
        assertEquals(true, reconciled.redrawNoticePending)
    }

    @Test
    fun `the ledger sits in its route's package folder, beside its lock`() {
        store.record(identity(0), arrived, day)

        assertNotNull(store.load("camino-frances"))
        assertEquals(setOf("ledger.json", "ledger.json.lock"), ledgerFile.parentFile!!.list()!!.toSet())
    }

    private fun fixture(name: String): ByteArray =
        requireNotNull(javaClass.classLoader?.getResourceAsStream("honor/pilgrimage/$name")) {
            "missing fixture $name"
        }.use { it.readBytes() }
}
