// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.weather

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred

/**
 * Stage 12-A test double for [WeatherFetching]. Counts every
 * `fetchCurrent` call and (when configured) suspends on a
 * [CompletableDeferred] gate so cancellation tests can race the
 * walk-finalize / discard signals against an in-flight fetch.
 *
 * Defaults to `null` snapshot — sites that don't actually exercise
 * weather behavior (most of `WalkViewModel`'s test surface is
 * orthogonal to weather) get a no-op fake by passing `()`.
 *
 * The cancellation-semantics signal is observed through the WalkDAO:
 * after a test cancels the weatherJob and releases the gate, the
 * walk row's `weather_condition` column must remain null (or the row
 * itself purged for the discard path). This avoids tracking persist
 * state inside the fake, which would race the cancellation cleanup.
 *
 * Sequence support: pass `snapshots` (a `List<WeatherSnapshot?>`) to
 * vend different values across successive calls — required to test
 * the iOS-faithful `null → +10s retry → success` path. The single-
 * snapshot constructor is preserved for backwards compatibility with
 * the ~10 existing call sites.
 */
class FakeWeatherFetching private constructor(
    private val snapshots: List<WeatherSnapshot?>,
    private val gate: CompletableDeferred<WeatherSnapshot?>?,
) : WeatherFetching {
    val callCount = AtomicInteger(0)

    constructor(
        snapshot: WeatherSnapshot? = null,
        gate: CompletableDeferred<WeatherSnapshot?>? = null,
    ) : this(snapshots = listOf(snapshot), gate = gate)

    constructor(
        snapshots: List<WeatherSnapshot?>,
    ) : this(snapshots = snapshots, gate = null)

    override suspend fun fetchCurrent(latitude: Double, longitude: Double): WeatherSnapshot? {
        val idx = callCount.getAndIncrement()
        if (gate != null) return gate.await()
        // Last-element pinning: callers that pass fewer snapshots than
        // calls (e.g. the no-arg `()` constructor) keep getting the
        // final entry forever, matching the previous single-snapshot
        // behavior.
        return snapshots[idx.coerceAtMost(snapshots.size - 1)]
    }
}
