// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain.honor

import kotlin.math.abs
import kotlin.math.tan
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.walktalkmeditate.pilgrim.domain.ArrivalDebounce
import org.walktalkmeditate.pilgrim.domain.Clock
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.domain.wgs84MidLatitudeMeters

/**
 * The golden engine traces (plan U16): each trace in `honor/golden/corpus/`
 * run through Android's [HonorEngine] against what iOS's own engine did
 * with the same Way and the same inputs, `honor/golden/expected/`. The
 * README there says how `capture/capture.sh` drove `HonorEngine.swift`
 * @7c200bf (through its real `bind`, as `ActiveWalkViewModel+Honor.swift`
 * does) and what each trace proves.
 *
 * For every input, in order:
 * - the same events, with the same moment ids, the same fix index, and a
 *   voice whose file is missing handing the turn straight back, as the
 *   view model does;
 * - the same state after it: the published values, and, read from private
 *   fields on both sides, the soft tap's state, the arrival count, the
 *   re-acquire clocks, and the voice queue;
 * - the same `CLLocation.distance` calls (moment radii, voice drops,
 *   arrival), in the same order, to the same places.
 *
 * Tolerances. Both engines evaluate the same formulas in the same order,
 * so they differ only where Apple's libm and the JVM round `sin`, `cos`,
 * `atan2`, and `hypot` differently in the last bit. Measured over the
 * corpus the largest differences are 2.2e-16 in a frac, 2.3e-13 m, and
 * 3.3e-16 s. [FRAC], [METERS], and [SECONDS] sit at least four thousand
 * times above that and far below any behavior: a centimetre along a 1 km
 * Way is 1e-5 of it.
 *
 * Distances. At every call the pinned distance must equal CoreLocation's
 * value with no history to 1e-14 relative (measured: 3.9e-16), and must sit within
 * [cacheBoundMeters] of the value the iOS engine actually got (CoreLocation
 * reuses cached radii; the README has the model). Every call must also be
 * farther than that bound from each threshold it decides, so no event in
 * the corpus could go either way with some other iOS history. That is why
 * no trace needs an allowance at a threshold crossing: none is taken.
 */
class HonorGoldenTraceTest {

    @Test
    fun `straight Way at 42_88N`() = golden("straight-42n") { ios ->
        // Begin replays the pre-Begin fix before bind's closed gates arrive:
        // the trailhead voice starts on the engine's default open gates.
        assertEquals(listOf("voiceStart voice-1"), ios.eventsAt(0))
        assertEquals(listOf("voicePause"), ios.eventsAt(2))
        assertEquals(listOf("voiceResume"), ios.eventsAt(3))
        assertEquals(
            listOf("waypoint-1", "photo-1", "rest-1", "sit-1"),
            ios.events().filter { it.type == "momentReached" }.map { it.id },
        )
        assertEquals(1, ios.count("arrived"))
    }

    @Test
    fun `AE2 on a loop longer than the window`() = golden("loop-long-0n") { ios ->
        val standingAtBegin = ios.records.filter { it.fix != null && it.fix < 9 }
        assertTrue(standingAtBegin.all { it.state!!.arrivalCount == 0 && it.state.progressFrac < 0.01 })
        val arrival = ios.single("arrived")
        assertTrue(ios.records[arrival].state!!.progressFrac >= 0.9)
        assertTrue("voices are off", ios.events().none { it.type.startsWith("voice") })
    }

    @Test
    fun `a short loop arrives at Begin, as iOS ships it (pilgrim-ios #100)`() = golden("loop-short-60n") { ios ->
        assertEquals(2, ios.records[ios.single("arrived")].fix)
        assertEquals(listOf("waypoint-1", "photo-1"), ios.events().filter { it.type == "momentReached" }.map { it.id })
    }

    @Test
    fun `out-and-back begun from a car park`() = golden("out-and-back-33s") { ios ->
        val first = ios.records[0].state!!
        assertEquals(0.0, first.startFrac!!, 0.0)
        assertTrue("fallback anchor", !first.isAnchoredOnWay)
        val join = ios.records.indexOfFirst { it.state?.isAnchoredOnWay == true }
        val approach = ios.records.subList(0, join).mapNotNull { it.state }
        assertTrue("the soft tap stays silent on the approach", approach.all { it.softTap == "armed" })
        assertTrue("failed re-acquires", approach.mapNotNull { it.lastReacquireAttempt }.distinct().size >= 2)
        val returnVoice = ios.eventInput("voiceStart", "voice-2")
        assertTrue("the return leg's voice waits for the return", ios.records[returnVoice].state!!.progressFrac > 0.5)
        assertEquals(1, ios.count("arrived"))
    }

    @Test
    fun `detour with a re-acquire and the soft tap`() = golden("detour-reacquire-47n") { ios ->
        val states = ios.records.mapNotNull { it.state }
        val since = states.firstNotNullOf { it.offWaySince }
        val attempts = states.mapNotNull { it.lastReacquireAttempt }.distinct()
        assertEquals("the first re-acquire at exactly 120 s", since + 120, attempts.first(), 0.0)
        assertEquals("then every 10 s", 10.0, attempts[1] - attempts[0], 0.0)
        assertEquals(
            listOf("armed", "timing", "armed", "timing", "disarmed", "armed"),
            states.map { it.softTap }.changes(),
        )
        assertEquals(1, ios.count("softTap"))
        assertEquals(listOf("voice-1"), ios.events().filter { it.type == "voiceDropped" }.map { it.id })
        assertEquals(1, ios.count("arrived"))
    }

    @Test
    fun `accuracy blackout`() = golden("blackout-42n") { ios ->
        assertTrue("a photo passed in the blackout is never reached", ios.events().none { it.id == "photo-1" })
        assertEquals(
            "a jump resets the count; bad fixes neither advance nor reset it",
            listOf(0, 1, 2, 0, 1, 2, 3),
            ios.records.mapNotNull { it.state?.arrivalCount }.changes(),
        )
    }

    @Test
    fun `stationary walker at a voice`() = golden("stationary-voice-35n") { ios ->
        val drop = ios.eventInput("voiceDropped", "voice-1")
        assertNull("dropped on the fix with an unknown speed", ios.trace.inputs[drop].speed)
        val first = ios.way.moments.single { it.id == "voice-1" }.at!!
        val standingFarthest = ios.trace.inputs.subList(0, drop)
            .filter { it.kind == "fix" && it.speed != null && it.speed < HonorTuning.STATIONARY_SPEED }
            .maxOf { wgs84MidLatitudeMeters(it.lat!!, it.lon!!, first.lat, first.lon) }
        assertTrue(
            "a stationary walker $standingFarthest m away keeps it",
            standingFarthest > HonorTuning.VOICE_DROP_METERS + 0.5,
        )
        val missing = ios.eventInput("voiceStart", "voice-3")
        assertEquals(listOf("voiceStart voice-3"), ios.eventsAt(missing))
        assertNull("its missing file hands the turn straight back", ios.records[missing].state!!.playing)
    }

    @Test
    fun `pause mid-voice`() = golden("pause-mid-voice-51n") { ios ->
        val pause = ios.trace.inputs.withIndex().filter { it.value.kind == "gate" && it.value.gate == "paused" }
        val start = pause.first { it.value.value == true }.index
        val end = pause.first { it.index > start && it.value.value == false }.index
        val frozen = ios.records.subList(start, end).mapNotNull { it.companionFrac }.distinct()
        assertEquals("the companion holds through the pause", 1, frozen.size)
        val drop = ios.eventInput("voiceDropped", "voice-2")
        val before = ios.records.subList(0, drop).last { it.state != null }.state!!
        assertTrue("the paused voice is dropped", before.voicePaused)
        assertEquals(1, ios.count("arrived"))
    }

    @Test
    fun `sitting mid-voice`() = golden("sit-mid-voice-64n") { ios ->
        val sitting = ios.trace.inputs.withIndex().filter { it.value.gate == "meditating" }.map { it.index }
        val during = ios.records.subList(sitting[0], sitting[1])
        val companion = during.mapNotNull { it.companionFrac }
        assertTrue("the companion walks on through the sitting", companion.last() > companion.first())
        assertTrue("a card still appears", during.any { r -> r.events.orEmpty().any { it.id == "photo-1" } })
        assertEquals(listOf("voicePause"), ios.eventsAt(sitting[0]))
        assertEquals(listOf("voiceResume"), ios.eventsAt(sitting[1]))
    }

    // The comparison

    private fun golden(name: String, proves: (Capture) -> Unit) {
        val way = WayJson.decode(resource("$ROOT/corpus/$name/way.json"))
        val trace = json.decodeFromString(GoldenTrace.serializer(), resource("$ROOT/corpus/$name/trace.json"))
        val records = resource("$ROOT/expected/$name.jsonl").lineSequence().filter { it.isNotBlank() }
            .map { json.decodeFromString(GoldenRecord.serializer(), it) }.toList()
        assertEquals("$name: one record per input", trace.inputs.size, records.size)
        val capture = Capture(way, trace, records)
        proves(capture)
        val android = AndroidRun(way, trace)
        var fixes = 0
        trace.inputs.forEachIndexed { i, input ->
            val expected = records[i]
            val at = "$name input $i (${input.kind} at t=${input.t})"
            assertEquals(at, i, expected.i)
            assertEquals(at, input.kind, expected.kind)
            val events = android.step(input)
            if (input.kind == "tick") {
                assertClose("$at companionFrac", expected.companionFrac!!, android.engine.companionFrac, FRAC)
                return@forEachIndexed
            }
            if (input.kind == "fix") assertEquals(at, fixes++, expected.fix)
            assertEvents(at, expected.events!!, events)
            assertState(at, expected.state!!, android.state())
            if (input.kind == "fix") assertCalls(at, input, way, expected.calls!!, android.calls)
        }
    }

    private fun assertEvents(at: String, expected: List<GoldenEvent>, actual: List<HonorEngineEvent>) {
        assertEquals("$at events", expected.map { it.label }, actual.map { it.golden().label })
        expected.zip(actual.map { it.golden() }).forEach { (e, a) ->
            e.offWayMeters?.let { assertClose("$at softTap metres", it, a.offWayMeters!!, METERS) }
            e.theirSeconds?.let { assertClose("$at theirSeconds", it, a.theirSeconds!!, SECONDS) }
            e.yourSeconds?.let { assertClose("$at yourSeconds", it, a.yourSeconds!!, SECONDS) }
        }
    }

    private fun assertState(at: String, e: GoldenState, a: GoldenState) {
        assertClose("$at progressFrac", e.progressFrac, a.progressFrac, FRAC)
        assertClose("$at distanceRemainingMeters", e.distanceRemainingMeters, a.distanceRemainingMeters, METERS)
        assertClose("$at offWayMeters", e.offWayMeters, a.offWayMeters, METERS)
        assertEquals("$at isOnWay", e.isOnWay, a.isOnWay)
        assertClose("$at companionFrac", e.companionFrac, a.companionFrac, FRAC)
        assertEquals("$at phase", e.phase, a.phase)
        assertClose("$at startFrac", e.startFrac, a.startFrac, FRAC)
        assertClose("$at companionT0", e.companionT0, a.companionT0, SECONDS)
        assertClose("$at distanceWalkedMeters", e.distanceWalkedMeters, a.distanceWalkedMeters, METERS)
        assertEquals("$at isAnchoredOnWay", e.isAnchoredOnWay, a.isAnchoredOnWay)
        assertEquals("$at softTap", e.softTap, a.softTap)
        assertEquals("$at arrivalCount", e.arrivalCount, a.arrivalCount)
        assertClose("$at offWaySince", e.offWaySince, a.offWaySince, SECONDS)
        assertClose("$at lastReacquireAttempt", e.lastReacquireAttempt, a.lastReacquireAttempt, SECONDS)
        assertEquals("$at playing", e.playing, a.playing)
        assertEquals("$at voicePaused", e.voicePaused, a.voicePaused)
        assertEquals("$at queue", e.queue, a.queue)
    }

    private fun assertCalls(
        at: String,
        fix: GoldenInput,
        way: Way,
        expected: List<GoldenCall>,
        actual: List<DistanceCall>,
    ) {
        assertEquals("$at distance calls", expected.size, actual.size)
        val voices = way.moments.filter { it.isVoice }.map { it.id }.toSet()
        expected.zip(actual).forEachIndexed { k, (e, a) ->
            val call = "$at call $k to ${e.target}"
            assertEquals("$call from the fix", WayCoordinate(lat = fix.lat!!, lon = fix.lon!!), a.from)
            assertClose("$call to lat", e.to.lat, a.to.lat, DEGREES)
            assertClose("$call to lon", e.to.lon, a.to.lon, DEGREES)
            assertClose("$call pinned vs CLLocation with no history", e.fresh, a.meters, e.fresh * PIN_RELATIVE)
            val bound = cacheBoundMeters(e.fresh, fix.lat, e.to.lat)
            val cached = abs(e.cl - a.meters)
            assertTrue("$call: CoreLocation's cache moved it $cached m, past $bound", cached <= bound)
            val thresholds = when (e.target) {
                "end" -> listOf(HonorTuning.ARRIVAL_RADIUS_METERS)
                in voices -> listOf(HonorTuning.VOICE_RADIUS_METERS, HonorTuning.VOICE_DROP_METERS)
                else -> listOf(HonorTuning.MOMENT_RADIUS_METERS)
            }
            val margin = thresholds.minOf { abs(e.fresh - it) }

            val decisionBound = cacheBoundMeters(maxOf(e.fresh, thresholds.max()), fix.lat, e.to.lat)
            assertTrue("$call: ${e.fresh} m is within $decisionBound m of a threshold", margin > decisionBound)
        }
    }

    // Android's side

    private class DistanceCall(val from: WayCoordinate, val to: WayCoordinate, val meters: Double)

    private class AndroidRun(way: Way, private val trace: GoldenTrace) {
        private val t0Millis = Math.round(trace.t0 * 1000)
        private var nowMillis = t0Millis
        val calls = mutableListOf<DistanceCall>()
        val engine = HonorEngine(
            way = way,
            softTapEnabled = trace.softTapEnabled,
            voicesEnabled = trace.voicesEnabled,
            clock = Clock { nowMillis },
            distance = { from, to -> WGS84_HONOR_DISTANCE(from, to).also { calls += DistanceCall(from, to, it) } },
        )
        private val gates = mutableMapOf(
            "paused" to false,
            "meditating" to false,
            "recording" to false,
            "externalAudio" to false,
        )

        fun step(input: GoldenInput): List<HonorEngineEvent> {
            nowMillis = t0Millis + Math.round(input.t * 1000)
            calls.clear()
            val events = when (input.kind) {
                "fix" -> engine.processLocation(
                    LocationPoint(
                        timestamp = nowMillis,
                        latitude = input.lat!!,
                        longitude = input.lon!!,
                        horizontalAccuracyMeters = input.accuracy?.toFloat(),
                        speedMetersPerSecond = input.speed?.toFloat(),
                    ),
                )
                "tick" -> emptyList<HonorEngineEvent>().also { engine.updateActiveDuration(input.activeSeconds!!) }
                "gates" -> {
                    gates["paused"] = input.paused!!
                    gates["meditating"] = input.meditating!!
                    gates["recording"] = input.recording!!
                    gates["externalAudio"] = input.externalAudio!!
                    setGates()
                }
                "gate" -> {
                    gates[input.gate!!] = input.value!!
                    setGates()
                }
                "finish" -> engine.voiceDidFinish()
                else -> error("unknown input kind ${input.kind}")
            }
            return handedOver(events)
        }

        private fun setGates() = engine.setGates(
            paused = gates.getValue("paused"),
            meditating = gates.getValue("meditating"),
            recording = gates.getValue("recording"),
            externalAudio = gates.getValue("externalAudio"),
        )

        /** A voice with no file goes straight back (`ActiveWalkViewModel+Honor.swift:214-218@7c200bf`). */
        private fun handedOver(events: List<HonorEngineEvent>): List<HonorEngineEvent> = events.flatMap { event ->
            if (event is HonorEngineEvent.VoiceStart && event.moment.id in trace.missingMedia) {
                listOf(event) + handedOver(engine.voiceDidFinish())
            } else {
                listOf(event)
            }
        }

        fun state(): GoldenState {
            val tracker = engine.privateField<HonorMomentTracker>("moments")
            val armed = engine.privateField<Boolean>("softTapArmed")
            val timing = engine.privateField<Long?>("softTapSinceMillis") != null
            return GoldenState(
                progressFrac = engine.progressFrac,
                distanceRemainingMeters = engine.distanceRemainingMeters,
                offWayMeters = engine.offWayMeters,
                isOnWay = engine.isOnWay,
                companionFrac = engine.companionFrac,
                phase = if (engine.phase == HonorPhase.WALKING) "walking" else "arrived",
                startFrac = engine.startFrac,
                companionT0 = engine.companionT0,
                distanceWalkedMeters = engine.distanceWalkedMeters,
                isAnchoredOnWay = engine.isAnchoredOnWay,
                softTap = if (!armed) "disarmed" else if (timing) "timing" else "armed",
                arrivalCount = engine.privateField<ArrivalDebounce>("arrival").consecutiveInside,
                offWaySince = engine.privateField<Long?>("offWaySinceMillis")?.let { (it - t0Millis) / 1000.0 },
                lastReacquireAttempt = engine.privateField<Long?>("lastReacquireAttemptMillis")
                    ?.let { (it - t0Millis) / 1000.0 },
                playing = tracker.playing?.id,
                voicePaused = tracker.isVoicePaused,
                queue = tracker.privateField<List<WayMoment>>("queue").map { it.id },
            )
        }
    }

    // iOS's side

    private class Capture(val way: Way, val trace: GoldenTrace, val records: List<GoldenRecord>) {
        fun events(): List<GoldenEvent> = records.flatMap { it.events.orEmpty() }
        fun eventsAt(input: Int): List<String> = records[input].events.orEmpty().map { it.label }
        fun count(type: String): Int = events().count { it.type == type }
        fun single(type: String): Int =
            records.indices.single { i -> records[i].events.orEmpty().any { it.type == type } }
        fun eventInput(type: String, id: String): Int =
            records.indices.single { i -> records[i].events.orEmpty().any { it.type == type && it.id == id } }
    }

    private companion object {
        const val ROOT = "honor/golden"
        const val FRAC = 1e-12
        const val METERS = 1e-9
        const val SECONDS = 1e-9
        const val DEGREES = 1e-12
        const val PIN_RELATIVE = 1e-14

        val json = Json { ignoreUnknownKeys = false }

        /**
         * How far CoreLocation's cached radii can move its value from the
         * pinned one (golden README): the stored latitude is under 0.005° from
         * the first point's, which is `|Δφ|/2` from the pair's mean, and the
         * east–west radius changes by `tan φ` per radian of latitude, the
         * north–south one by under 0.01.
         */
        fun cacheBoundMeters(meters: Double, lat1: Double, lat2: Double): Double {
            val meanLatitude = Math.toRadians((lat1 + lat2) / 2)
            val staleness = Math.toRadians(0.005 + abs(lat2 - lat1) / 2)
            return meters * (abs(tan(meanLatitude)) + 0.01) * staleness
        }

        fun resource(path: String): String =
            checkNotNull(HonorGoldenTraceTest::class.java.classLoader?.getResourceAsStream(path)) {
                "missing test resource $path"
            }.bufferedReader().use { it.readText() }

        /** The values in order with repeats collapsed: a state machine's transitions. */
        fun <T> List<T>.changes(): List<T> =
            fold(listOf()) { seen, value -> if (seen.lastOrNull() == value) seen else seen + value }

        fun assertClose(message: String, expected: Double, actual: Double, tolerance: Double) {
            assertTrue("$message: iOS $expected, Android $actual", abs(expected - actual) <= tolerance)
        }

        fun assertClose(message: String, expected: Double?, actual: Double?, tolerance: Double) {
            if (expected == null || actual == null) {
                assertEquals(message, expected, actual)
            } else {
                assertClose(message, expected, actual, tolerance)
            }
        }

        inline fun <reified T> Any.privateField(name: String): T =
            javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this) as T

        fun HonorEngineEvent.golden(): GoldenEvent = when (this) {
            is HonorEngineEvent.MomentReached -> GoldenEvent("momentReached", id = moment.id)
            is HonorEngineEvent.VoiceStart -> GoldenEvent("voiceStart", id = moment.id)
            HonorEngineEvent.VoicePause -> GoldenEvent("voicePause")
            HonorEngineEvent.VoiceResume -> GoldenEvent("voiceResume")
            is HonorEngineEvent.VoiceDropped -> GoldenEvent("voiceDropped", id = moment.id)
            is HonorEngineEvent.SoftTap -> GoldenEvent("softTap", offWayMeters = offWayMeters)
            is HonorEngineEvent.Arrived ->
                GoldenEvent("arrived", theirSeconds = theirSeconds, yourSeconds = yourSeconds)
        }
    }
}

@Serializable
private data class GoldenTrace(
    val name: String,
    val about: String,
    val t0: Double,
    val softTapEnabled: Boolean,
    val voicesEnabled: Boolean,
    val missingMedia: List<String>,
    val inputs: List<GoldenInput>,
)

@Serializable
private data class GoldenInput(
    val kind: String,
    val t: Double,
    val lat: Double? = null,
    val lon: Double? = null,
    val accuracy: Double? = null,
    val speed: Double? = null,
    val activeSeconds: Double? = null,
    val gate: String? = null,
    val value: Boolean? = null,
    val paused: Boolean? = null,
    val meditating: Boolean? = null,
    val recording: Boolean? = null,
    val externalAudio: Boolean? = null,
)

@Serializable
private data class GoldenRecord(
    val i: Int,
    val kind: String,
    val fix: Int? = null,
    val events: List<GoldenEvent>? = null,
    val calls: List<GoldenCall>? = null,
    val state: GoldenState? = null,
    val companionFrac: Double? = null,
)

@Serializable
private data class GoldenEvent(
    val type: String,
    val id: String? = null,
    val offWayMeters: Double? = null,
    val theirSeconds: Double? = null,
    val yourSeconds: Double? = null,
) {
    val label: String get() = listOfNotNull(type, id).joinToString(" ")
}

@Serializable
private data class GoldenCoordinate(val lat: Double, val lon: Double)

@Serializable
private data class GoldenCall(val target: String, val to: GoldenCoordinate, val cl: Double, val fresh: Double)

@Serializable
private data class GoldenState(
    val progressFrac: Double,
    val distanceRemainingMeters: Double,
    val offWayMeters: Double,
    val isOnWay: Boolean,
    val companionFrac: Double,
    val phase: String,
    val startFrac: Double? = null,
    val companionT0: Double,
    val distanceWalkedMeters: Double,
    val isAnchoredOnWay: Boolean,
    val softTap: String,
    val arrivalCount: Int,
    val offWaySince: Double? = null,
    val lastReacquireAttempt: Double? = null,
    val playing: String? = null,
    val voicePaused: Boolean,
    val queue: List<String>,
)
