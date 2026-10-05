// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.debug.honor

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.SystemClock
import android.util.Log
import androidx.core.content.edit
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil
import kotlin.math.roundToLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.walktalkmeditate.pilgrim.domain.honor.WayCoordinate
import org.walktalkmeditate.pilgrim.domain.honor.WayGeometry
import org.walktalkmeditate.pilgrim.domain.honor.WayPoint
import org.walktalkmeditate.pilgrim.location.MockLocationReplay

/** One fix as a replay plays it, [offsetMillis] after the first. */
data class ReplayStep(
    val offsetMillis: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double?,
    val accuracyMeters: Float,
    val speedMetersPerSecond: Float,
    val bearingDegrees: Float,
    /** Where along the Way the point sits, for the log: the one position a debug line may print. */
    val frac: Double,
)

/** A step stamped with the clocks at the moment it plays, so its timestamps are now. */
data class MockFix(
    val step: ReplayStep,
    val timeMillis: Long,
    val elapsedRealtimeNanos: Long,
) {
    fun toLocation(): Location = Location(FUSED_PROVIDER).also { location ->
        location.latitude = step.latitude
        location.longitude = step.longitude
        step.altitudeMeters?.let { location.altitude = it }
        location.accuracy = step.accuracyMeters
        location.speed = step.speedMetersPerSecond
        location.bearing = step.bearingDegrees
        location.time = timeMillis
        location.elapsedRealtimeNanos = elapsedRealtimeNanos
    }

    private companion object {
        const val FUSED_PROVIDER = "fused"
    }
}

/**
 * A Way's route as mock fixes: the part of the line a [Timing] names, at
 * the recorded pace or at its own.
 */
object WayReplayTimeline {

    /** Well inside the walk pipeline's 20 m gate, so every fix counts. */
    const val ACCURACY_METERS = 5f

    /**
     * The longest gap between two paced fixes. The walk asks the fused
     * provider for a fix every 2 s and takes none closer than 1 s, so a
     * paced replay spaces its fixes evenly, at most this far apart, and
     * every one reaches the walk.
     */
    const val PACED_FIX_SECONDS = 2.0

    /** A pace so slow it would need more fixes than this is refused, rather than filling `:tracker`'s memory. */
    const val MAX_PACED_FIXES = 100_000

    /**
     * Which part of the route plays, as fracs of its length, and at what
     * pace: null for the recorded one. [WayReplayTimeline.refusal] says
     * whether it can play.
     */
    data class Timing(
        val paceMetersPerSecond: Double? = null,
        val fromFrac: Double = 0.0,
        val toFrac: Double = 1.0,
    )

    /** Why [timing] can't replay [route], or null when it can. */
    fun refusal(route: List<WayPoint>, timing: Timing): String? {
        val pace = timing.paceMetersPerSecond
        if (pace != null && !(pace.isFinite() && pace > 0)) {
            return "the pace must be a number of metres per second above 0 (--ef pace <m/s>)"
        }
        if (!(timing.fromFrac in 0.0..1.0 && timing.toFrac in 0.0..1.0)) {
            return "from and to must be fracs of the route, 0 to 1 (--ef from <frac> --ef to <frac>)"
        }
        if (!(timing.fromFrac < timing.toFrac)) return "from must come before to"
        if (pace != null && pacedLegs(spanMeters(WayGeometry(route), timing), pace) >= MAX_PACED_FIXES) {
            return "that pace would take more than $MAX_PACED_FIXES fixes"
        }
        return null
    }

    /**
     * At the recorded pace the fixes are the route's points inside the
     * window, plus a point interpolated at an end no route point sits on.
     * Each carries the speed and bearing of the leg it ends (the first, of
     * the leg it starts); a leg of no length keeps the heading before it,
     * and a leg of no time keeps the speed. At a [Timing.paceMetersPerSecond]
     * the fixes are evenly spaced along the line, each carrying the pace
     * and the heading of the route leg it lies on (on a leg of no length,
     * the heading before it).
     *
     * @throws IllegalArgumentException when [refusal] refuses [timing].
     */
    fun steps(route: List<WayPoint>, timing: Timing = Timing()): List<ReplayStep> {
        refusal(route, timing)?.let { throw IllegalArgumentException(it) }
        if (route.isEmpty()) return emptyList()
        val geometry = WayGeometry(route)
        val pace = timing.paceMetersPerSecond
        return if (pace == null) recorded(windowed(geometry, timing)) else paced(geometry, timing, pace)
    }

    private fun recorded(placed: List<Placed>): List<ReplayStep> {
        val t0 = placed.first().point.t
        var offset = 0L
        var speed = 0f
        var bearing = 0f
        return placed.mapIndexed { index, (point, frac) ->
            val (from, to) = if (index == 0) {
                point to placed.getOrElse(1) { placed[index] }.point
            } else {
                placed[index - 1].point to point
            }
            val meters = WayGeometry.distanceMeters(from, to)
            val seconds = to.t - from.t
            if (seconds > 0) speed = (meters / seconds).toFloat()
            if (meters > 0) bearing = WayGeometry.bearing(from.coordinate(), to.coordinate()).toFloat()
            offset = maxOf(offset, ((point.t - t0) * MILLIS_PER_SECOND).roundToLong())
            ReplayStep(
                offsetMillis = offset,
                latitude = point.lat,
                longitude = point.lon,
                altitudeMeters = point.alt,
                accuracyMeters = ACCURACY_METERS,
                speedMetersPerSecond = speed,
                bearingDegrees = bearing,
                frac = frac,
            )
        }
    }

    /** Every route point inside the window, its ends included, and an interpolated point at an end none sits on. */
    private fun windowed(geometry: WayGeometry, timing: Timing): List<Placed> {
        val start = timing.fromFrac * geometry.totalMeters
        val end = timing.toFrac * geometry.totalMeters
        val cumulative = geometry.cumulative
        val inside = geometry.points.indices.filter { cumulative[it] in start..end }
        val cursor = LineCursor(geometry)
        val placed = mutableListOf<Placed>()
        if (inside.isEmpty() || cumulative[inside.first()] > start) {
            placed += Placed(cursor.at(start).point, geometry.fracAt(start))
        }
        inside.mapTo(placed) { Placed(geometry.points[it], geometry.fracAt(cumulative[it])) }
        if (inside.isEmpty() || cumulative[inside.last()] < end) {
            placed += Placed(cursor.at(end).point, geometry.fracAt(end))
        }
        return placed
    }

    private fun paced(geometry: WayGeometry, timing: Timing, pace: Double): List<ReplayStep> {
        val start = timing.fromFrac * geometry.totalMeters
        val end = timing.toFrac * geometry.totalMeters
        val legs = pacedLegs(spanMeters(geometry, timing), pace).toInt()
        val cursor = LineCursor(geometry)
        var bearing = 0f
        return (0..legs).map { fix ->
            val meters = if (fix == legs) end else start + (end - start) * fix / legs
            val onLine = cursor.at(meters)
            onLine.bearing?.let { bearing = it }
            ReplayStep(
                offsetMillis = ((meters - start) / pace * MILLIS_PER_SECOND).roundToLong(),
                latitude = onLine.point.lat,
                longitude = onLine.point.lon,
                altitudeMeters = onLine.point.alt,
                accuracyMeters = ACCURACY_METERS,
                speedMetersPerSecond = pace.toFloat(),
                bearingDegrees = bearing,
                frac = geometry.fracAt(meters),
            )
        }
    }

    /** How many even legs, each at most [PACED_FIX_SECONDS] at [pace], cover [meters]; a Double so no pace overflows it. */
    private fun pacedLegs(meters: Double, pace: Double): Double =
        if (meters > 0) ceil(meters / (pace * PACED_FIX_SECONDS)) else 0.0

    private fun spanMeters(geometry: WayGeometry, timing: Timing): Double =
        timing.toFrac * geometry.totalMeters - timing.fromFrac * geometry.totalMeters

    private fun WayGeometry.fracAt(meters: Double): Double = if (totalMeters > 0) meters / totalMeters else 0.0

    private fun WayPoint.coordinate() = WayCoordinate(lat = lat, lon = lon)

    /** A route point, or one between two, with its frac of the whole line. */
    private data class Placed(val point: WayPoint, val frac: Double)

    /** A point on the line, and the bearing of the route leg it lies on: null on a leg of no length. */
    private class OnLine(val point: WayPoint, val bearing: Float?)

    /**
     * Points along the line by their distance from its start, asked for in
     * increasing order. A distance on several points at once (a standstill)
     * lies on the leg after the last of them, as [WayGeometry]'s own
     * lookup puts it, so a point and its fix agree with the engine's
     * [WayGeometry.coordinate]. Latitude, longitude, altitude, and time are
     * linear along the leg; an altitude one end lacks is left out.
     */
    private class LineCursor(private val geometry: WayGeometry) {
        private var leg = 0

        fun at(meters: Double): OnLine {
            val points = geometry.points
            val cumulative = geometry.cumulative
            if (points.size == 1) return OnLine(points[0], bearing = null)
            while (leg < points.size - 2 && cumulative[leg + 1] <= meters) leg++
            val a = points[leg]
            val b = points[leg + 1]
            val length = cumulative[leg + 1] - cumulative[leg]
            val u = if (length > 0) ((meters - cumulative[leg]) / length).coerceIn(0.0, 1.0) else 0.0
            val altitude = if (a.alt != null && b.alt != null) a.alt + (b.alt - a.alt) * u else null
            val point = WayPoint(
                lat = a.lat + (b.lat - a.lat) * u,
                lon = a.lon + (b.lon - a.lon) * u,
                alt = altitude,
                t = a.t + (b.t - a.t) * u,
            )
            val bearing = if (length > 0) WayGeometry.bearing(a.coordinate(), b.coordinate()).toFloat() else null
            return OnLine(point, bearing)
        }
    }

    private const val MILLIS_PER_SECOND = 1_000.0
}

/** The seam over the fused provider's mock mode; the tests fake it. */
interface MockLocationClient {
    suspend fun setMockMode(enabled: Boolean)
    suspend fun setMockLocation(fix: MockFix)
}

/**
 * The fused provider's mock mode. It needs this app chosen as the mock
 * location app (see [HonorDebugReceiver]); mock mode then feeds every fused
 * client on the device, the walk's own included. It belongs to the client
 * that turned it on, and outlives that client's process: see
 * [WayReplayer.releaseMockMode].
 */
class FusedMockLocationClient @Inject constructor(
    @ApplicationContext private val context: Context,
) : MockLocationClient {

    private val client: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(context)
    }

    @SuppressLint("MissingPermission")
    override suspend fun setMockMode(enabled: Boolean) {
        client.setMockMode(enabled).await()
    }

    @SuppressLint("MissingPermission")
    override suspend fun setMockLocation(fix: MockFix) {
        client.setMockLocation(fix.toLocation()).await()
    }
}

/**
 * Whether a replay may have left mock mode on. It outlives the process
 * that took mock mode, as mock mode itself does; the tests fake it.
 */
interface MockModeMarker {
    var taken: Boolean
}

/** [MockModeMarker] in `:tracker`'s own preferences file, written at once so a kill can't drop it. */
class PreferencesMockModeMarker @Inject constructor(
    @ApplicationContext private val context: Context,
) : MockModeMarker {

    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    override var taken: Boolean
        get() = prefs.getBoolean(KEY_TAKEN, false)
        set(value) = prefs.edit(commit = true) { putBoolean(KEY_TAKEN, value) }

    private companion object {
        const val PREFS_NAME = "honor_replay"
        const val KEY_TAKEN = "mock_taken"
    }
}

/**
 * Walks a Way's route from a desk (plan U19): mock fixes through the fused
 * provider at the recorded pace or a chosen one ([WayReplayTimeline]), in
 * `:tracker`, where the walk reads its fixes. Each fix waits out its gap
 * from the one before, so a frozen or busy process delays the rest rather
 * than bursting them.
 *
 * Mock mode goes off on every way a replay ends (played out, stopped,
 * replaced, refused, or failed) and, defensively, at the next `:tracker`
 * service start when this process runs no replay and the [MockModeMarker]
 * says one may have left it on (a process killed mid-replay never reaches
 * its own cleanup). Only then: taking and releasing mock mode empties the
 * device's cached fix, which the Honor overview reads. The log prints the
 * source's name as the caller gives it, counts, and fracs only.
 */
@Singleton
class WayReplayer internal constructor(
    private val client: MockLocationClient,
    private val marker: MockModeMarker,
    private val scope: CoroutineScope,
    private val wallClockMillis: () -> Long,
    private val elapsedRealtimeNanos: () -> Long,
) : MockLocationReplay {

    @Inject
    constructor(client: MockLocationClient, marker: MockModeMarker) : this(
        client = client,
        marker = marker,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        wallClockMillis = System::currentTimeMillis,
        elapsedRealtimeNanos = SystemClock::elapsedRealtimeNanos,
    )

    private val lock = Mutex()
    private var replay: Job? = null

    /**
     * Replaces any replay in progress. [name] is the source as the log
     * prints it ("walk 12"): never a title or a share id.
     *
     * @throws IllegalArgumentException when [WayReplayTimeline.refusal] refuses [timing].
     */
    suspend fun start(name: String, route: List<WayPoint>, timing: WayReplayTimeline.Timing = WayReplayTimeline.Timing()) {
        val steps = WayReplayTimeline.steps(route, timing)
        lock.withLock {
            replay?.cancelAndJoin()
            replay = scope.launch { play(name, steps) }
        }
    }

    suspend fun stop() {
        lock.withLock {
            val running = replay
            replay = null
            if (running?.isActive == true) running.cancelAndJoin() else releaseMockMode()
        }
    }

    override fun onTrackerStart() {
        scope.launch {
            lock.withLock { if (replay?.isActive != true && marker.taken) releaseMockMode() }
        }
    }

    private suspend fun play(name: String, steps: List<ReplayStep>) {
        val seconds = (steps.lastOrNull()?.offsetMillis ?: 0) / MILLIS_PER_SECOND
        val fracs = String.format(Locale.US, "frac %.3f to %.3f", steps.firstOrNull()?.frac ?: 0.0, steps.lastOrNull()?.frac ?: 0.0)
        Log.i(TAG, "replay of $name: ${steps.size} fixes over ${seconds}s, $fracs")
        var played = 0
        var previousOffset = 0L
        try {
            marker.taken = true
            client.setMockMode(true)
            for (step in steps) {
                delay(step.offsetMillis - previousOffset)
                previousOffset = step.offsetMillis
                client.setMockLocation(MockFix(step, wallClockMillis(), elapsedRealtimeNanos()))
                played++
            }
            Log.i(TAG, "replay of $name played out ($played fixes)")
        } catch (e: CancellationException) {
            Log.i(TAG, "replay of $name stopped at ${progress(steps, played)}")
            throw e
        } catch (e: Exception) {
            Log.w(
                TAG,
                "replay of $name failed at ${progress(steps, played)} (${e::class.simpleName}); " +
                    "is the debug app the mock location app?",
            )
        } finally {
            withContext(NonCancellable) { turnMockModeOff() }
        }
    }

    /**
     * Play services ties mock mode to the client that turned it on and keeps
     * it on when that process dies, so a `:tracker` killed mid-replay leaves
     * the whole device's fused location mocked. An "off" from the next
     * process alone is ignored (seen on the OnePlus 13, 2026-10-01). With no
     * replay of its own running, this client takes mock mode, then lets it go.
     */
    private suspend fun releaseMockMode() {
        try {
            client.setMockMode(true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "taking mock mode to release it failed (${e::class.simpleName})")
        }
        turnMockModeOff()
    }

    private suspend fun turnMockModeOff() {
        try {
            client.setMockMode(false)
            marker.taken = false
            Log.i(TAG, "mock mode off")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "turning mock mode off failed (${e::class.simpleName})")
        }
    }

    private fun progress(steps: List<ReplayStep>, played: Int): String {
        val frac = steps.getOrNull(played - 1)?.frac ?: 0.0
        return String.format(Locale.US, "fix %d/%d, frac %.3f", played, steps.size, frac)
    }

    private companion object {
        const val TAG = "WayReplayer"
        const val MILLIS_PER_SECOND = 1_000L
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class WayReplayerModule {

    @Binds
    abstract fun bindMockLocationReplay(impl: WayReplayer): MockLocationReplay

    @Binds
    abstract fun bindMockLocationClient(impl: FusedMockLocationClient): MockLocationClient

    @Binds
    abstract fun bindMockModeMarker(impl: PreferencesMockModeMarker): MockModeMarker
}
