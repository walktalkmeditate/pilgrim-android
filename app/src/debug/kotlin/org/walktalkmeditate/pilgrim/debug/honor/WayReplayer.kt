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

/** One route point as a replay plays it, [offsetMillis] after the first: the recorded gaps. */
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

/** A Way's route as mock fixes, at the recorded pace. */
object WayReplayTimeline {

    /** Well inside the walk pipeline's 20 m gate, so every fix counts. */
    const val ACCURACY_METERS = 5f

    /**
     * Each fix carries the speed and bearing of the leg it ends (the first,
     * of the leg it starts). A leg of no length keeps the heading before
     * it, and a leg of no time keeps the speed.
     */
    fun steps(route: List<WayPoint>): List<ReplayStep> {
        if (route.isEmpty()) return emptyList()
        val geometry = WayGeometry(route)
        val t0 = route.first().t
        var offset = 0L
        var speed = 0f
        var bearing = 0f
        return route.mapIndexed { index, point ->
            val (from, to) = if (index == 0) point to route.getOrElse(1) { point } else route[index - 1] to point
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
                frac = if (geometry.totalMeters > 0) geometry.cumulative[index] / geometry.totalMeters else 0.0,
            )
        }
    }

    private fun WayPoint.coordinate() = WayCoordinate(lat = lat, lon = lon)

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
 * provider at the recorded pace, in `:tracker`, where the walk reads its
 * fixes. Each fix waits out its recorded gap from the one before, so a
 * frozen or busy process delays the rest rather than bursting them.
 *
 * Mock mode goes off on every way a replay ends (played out, stopped,
 * replaced, refused, or failed) and, defensively, at the next `:tracker`
 * service start when this process runs no replay and the [MockModeMarker]
 * says one may have left it on (a process killed mid-replay never reaches
 * its own cleanup). Only then: taking and releasing mock mode empties the
 * device's cached fix, which the Honor overview reads. The log prints walk
 * ids, counts, and fracs only.
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

    /** Replaces any replay in progress. [walkId] names the source walk in the log. */
    suspend fun start(walkId: Long, route: List<WayPoint>) {
        val steps = WayReplayTimeline.steps(route)
        lock.withLock {
            replay?.cancelAndJoin()
            replay = scope.launch { play(walkId, steps) }
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

    private suspend fun play(walkId: Long, steps: List<ReplayStep>) {
        val seconds = (steps.lastOrNull()?.offsetMillis ?: 0) / MILLIS_PER_SECOND
        Log.i(TAG, "replay of walk $walkId: ${steps.size} fixes over ${seconds}s")
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
            Log.i(TAG, "replay of walk $walkId played out ($played fixes)")
        } catch (e: CancellationException) {
            Log.i(TAG, "replay of walk $walkId stopped at ${progress(steps, played)}")
            throw e
        } catch (e: Exception) {
            Log.w(
                TAG,
                "replay of walk $walkId failed at ${progress(steps, played)} (${e::class.simpleName}); " +
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
