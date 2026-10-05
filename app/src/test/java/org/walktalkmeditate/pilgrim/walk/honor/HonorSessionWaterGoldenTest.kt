// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.walk.honor

import android.app.Application
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.honor.HonorNoticeEntity
import org.walktalkmeditate.pilgrim.data.honor.HonorNoticeKind
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.domain.honor.Way
import org.walktalkmeditate.pilgrim.domain.honor.WayJson
import org.walktalkmeditate.pilgrim.walk.HonorSettings
import org.walktalkmeditate.pilgrim.walk.WalkControllerImpl

/**
 * Golden trace W10 through the real `:tracker` session (pilgrimage-stage
 * spec P3 §17.3): iOS's `water-skipped-then-spoken-43n` walked through
 * [HonorSession] on the harness's Room, store, and controller, its pause
 * and sitting through the controller as a walker's taps, its clock as the
 * walk's own. `:tracker` is killed inside the quiet hour and revived from
 * Room. iOS never revives a walk, so its uninterrupted trace is the answer:
 * the notice rows, their metres and firing times, the quiet clock, and one
 * haptic per notice, with none replayed by the revival.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HonorSessionWaterGoldenTest {

    @get:Rule val folder = TemporaryFolder()

    private lateinit var h: HonorHarness

    @Before
    fun setUp() {
        h = HonorHarness(folder.root)
    }

    @After
    fun tearDown() {
        h.close()
    }

    @Test
    fun `a tracker killed inside the quiet hour revives into exactly the water iOS spoke`() = runBlocking {
        val golden = Golden.load("water-skipped-then-spoken-43n")
        h.clock.millis = golden.t0Millis
        val walk = h.startHonorWalk(golden.way, HonorSettings(voicesEnabled = true, softTapEnabled = false))
        val firstProcess = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val firstPorts = FakePorts()
        var controller: WalkControllerImpl = h.controller
        var ports = firstPorts
        var session = h.newSession(ports)
        // Begin feeds inputs 0–3 itself: the fix, the clock's 0, the gates paused, then open.
        session.start(firstProcess, walk.id, controller.state, initialFix = golden.fix(0))
        session.awaitIdle()
        val cut = golden.trace.inputs.indexOfFirst { it.kind == "tick" && it.t > REVIVE_AFTER_SECONDS } + 1
        var spokenBeforeTheKill = emptyList<HonorNoticeEntity>()

        for (i in BEGIN_INPUTS until golden.trace.inputs.size) {
            if (i == cut) {
                spokenBeforeTheKill = h.db.honorDao().getNotices(walk.id)
                firstProcess.coroutineContext[Job]!!.cancelAndJoin()
                controller = h.newController().also { it.restoreActiveWalk() }
                ports = FakePorts()
                session = h.newSession(ports, arrivalRecorder = controller)
                session.start(h.serviceScope, walk.id, controller.state)
                session.awaitIdle()
                assertEquals("the revival speaks nothing", spokenBeforeTheKill, h.db.honorDao().getNotices(walk.id))
            }
            feed(golden, golden.trace.inputs[i], session, controller, ports)
        }

        val expected = golden.water().map { (input, event) ->
            HonorNoticeEntity(
                walkId = walk.id,
                kind = HonorNoticeKind.WATER,
                refId = event.id!!,
                meters = event.meters!!,
                firedAt = golden.t0Millis + (golden.trace.inputs[input].t * 1000).roundToLong(),
            )
        }
        val spoken = h.db.honorDao().getNotices(walk.id)
        assertEquals(expected.map { it.copy(meters = 0.0) }, spoken.map { it.copy(meters = 0.0) })
        expected.zip(spoken).forEach { (ios, android) ->
            val message = "${ios.refId}: iOS ${ios.meters} m, Android ${android.meters} m"
            assertTrue(message, abs(ios.meters - android.meters) <= METERS)
        }
        assertEquals("one notice before the kill, one after", 1, spokenBeforeTheKill.size)
        val iosClock = golden.records.last { it.state != null }.state!!.lastNoticeSeconds!!
        assertEquals(iosClock, h.db.honorDao().getSession(walk.id)!!.lastNoticeSeconds!!, SECONDS)
        assertEquals(1 to 1, firstPorts.waterHaptics() to ports.waterHaptics())
    }

    private suspend fun feed(
        golden: Golden,
        input: GoldenInput,
        session: HonorSession,
        controller: WalkControllerImpl,
        ports: FakePorts,
    ) {
        h.clock.millis = golden.t0Millis + (input.t * 1000).roundToLong()
        when (input.kind) {
            "tick" -> session.tickNow()
            "fix" -> session.onFix(golden.point(input))
            "gate" -> {
                val closed = input.value!!
                when (input.gate) {
                    "paused" -> if (closed) controller.pauseWalk() else controller.resumeWalk()
                    "meditating" -> if (closed) controller.startMeditation() else controller.endMeditation()
                    "recording" -> ports.gateFlow.value = ports.gateFlow.value.copy(recording = closed)
                    "externalAudio" -> ports.gateFlow.value = ports.gateFlow.value.copy(externalAudio = closed)
                    else -> error("unknown gate ${input.gate}")
                }
            }
            else -> error("a stage trace has no ${input.kind} input after Begin")
        }
        session.awaitIdle()
    }

    private fun FakePorts.waterHaptics(): Int = calls.count { it == "haptic water" }

    /** The corpus and iOS's records, read as `HonorGoldenTraceTest` reads them, with only what this test needs. */
    private class Golden(val way: Way, val trace: GoldenTraceFile, val records: List<GoldenRecordLine>) {
        val t0Millis: Long = (trace.t0 * 1000).roundToLong()

        fun point(input: GoldenInput) = LocationPoint(
            timestamp = t0Millis + (input.t * 1000).roundToLong(),
            latitude = input.lat!!,
            longitude = input.lon!!,
            horizontalAccuracyMeters = input.accuracy?.toFloat(),
            speedMetersPerSecond = input.speed?.toFloat(),
        )

        fun fix(index: Int): LocationPoint = point(trace.inputs[index].also { check(it.kind == "fix") })

        fun water(): List<Pair<Int, GoldenEventLine>> = records.flatMap { record ->
            record.events.orEmpty().filter { it.type == "markAhead" }.map { record.i to it }
        }

        companion object {
            private val json = Json { ignoreUnknownKeys = true }

            fun load(name: String) = Golden(
                way = WayJson.decode(resource("honor/golden/corpus/$name/way.json")),
                trace = json.decodeFromString(
                    GoldenTraceFile.serializer(),
                    resource("honor/golden/corpus/$name/trace.json"),
                ),
                records = resource("honor/golden/expected/$name.jsonl").lineSequence().filter { it.isNotBlank() }
                    .map { json.decodeFromString(GoldenRecordLine.serializer(), it) }.toList(),
            )

            private fun resource(path: String): String =
                checkNotNull(HonorSessionWaterGoldenTest::class.java.classLoader?.getResourceAsStream(path)) {
                    "missing test resource $path"
                }.bufferedReader().use { it.readText() }
        }
    }

    private companion object {
        /** Begin's own four inputs: the fix, the clock's 0, the gates paused, then open. */
        const val BEGIN_INPUTS = 4

        /** Killed on the tick after this many seconds: after the sitting, inside the quiet hour. */
        const val REVIVE_AFTER_SECONDS = 3000.0

        const val METERS = 1e-9
        const val SECONDS = 1e-9
    }
}

@Serializable
private data class GoldenTraceFile(val t0: Double, val inputs: List<GoldenInput>)

@Serializable
private data class GoldenInput(
    val kind: String,
    val t: Double,
    val lat: Double? = null,
    val lon: Double? = null,
    val accuracy: Double? = null,
    val speed: Double? = null,
    val gate: String? = null,
    val value: Boolean? = null,
)

@Serializable
private data class GoldenRecordLine(
    val i: Int,
    val events: List<GoldenEventLine>? = null,
    val state: GoldenStateLine? = null,
)

@Serializable
private data class GoldenEventLine(val type: String, val id: String? = null, val meters: Double? = null)

@Serializable
private data class GoldenStateLine(val lastNoticeSeconds: Double? = null)
