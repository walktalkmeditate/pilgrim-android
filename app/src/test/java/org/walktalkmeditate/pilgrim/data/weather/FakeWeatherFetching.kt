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
 */
class FakeWeatherFetching(
    private val snapshot: WeatherSnapshot? = null,
    private val gate: CompletableDeferred<WeatherSnapshot?>? = null,
) : WeatherFetching {
    val callCount = AtomicInteger(0)

    override suspend fun fetchCurrent(latitude: Double, longitude: Double): WeatherSnapshot? {
        callCount.incrementAndGet()
        return if (gate != null) gate.await() else snapshot
    }
}
