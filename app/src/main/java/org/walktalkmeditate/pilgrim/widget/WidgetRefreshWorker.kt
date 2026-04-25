// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.widget

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.updateAll
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.domain.replayWalkEventTotals
import org.walktalkmeditate.pilgrim.domain.walkDistanceMeters

/**
 * Reads the most-recent finished walk, computes distance + active
 * duration, persists to [WidgetStateRepository], then triggers a Glance
 * re-render of all widget instances.
 *
 * Distance: haversine-summed from RouteDataSamples via the shared
 * `walkDistanceMeters` helper. Active duration: total elapsed minus
 * paused + meditated, replayed from WalkEvents via `replayWalkEventTotals`
 * (same logic as `WalkSummaryViewModel.buildState`).
 */
@HiltWorker
class WidgetRefreshWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val walkRepository: WalkRepository,
    private val widgetStateRepository: WidgetStateRepository,
    private val widgetRefreshScheduler: WidgetRefreshScheduler,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        // Find the most recent walk that meets the reportable threshold.
        // A simple "is the latest walk valid?" check would tombstone an
        // earlier valid walk if the user accidentally tapped Start/Finish
        // (creating a sub-minute walk that beats the real one). Walking
        // the list lets the widget keep displaying the last meaningful
        // walk despite junk records intervening.
        val reportable = walkRepository.recentFinishedWalks(SEARCH_WINDOW)
            .firstOrNull { walk ->
                val end = walk.endTimestamp ?: return@firstOrNull false
                (end - walk.startTimestamp).coerceAtLeast(0) >= MIN_REPORTABLE_WALK_MS
            }
        val nextState: WidgetState = if (reportable?.endTimestamp == null) {
            WidgetState.Empty
        } else {
            val totalElapsed = (reportable.endTimestamp - reportable.startTimestamp).coerceAtLeast(0)
            val samples = walkRepository.locationSamplesFor(reportable.id)
            val points = samples.map { sample ->
                LocationPoint(
                    timestamp = sample.timestamp,
                    latitude = sample.latitude,
                    longitude = sample.longitude,
                )
            }
            val distance = walkDistanceMeters(points)
            val events = walkRepository.eventsFor(reportable.id)
            val totals = replayWalkEventTotals(events = events, closeAt = reportable.endTimestamp)
            val activeWalking = (totalElapsed - totals.totalPausedMillis - totals.totalMeditatedMillis)
                .coerceAtLeast(0)
            WidgetState.LastWalk(
                walkId = reportable.id,
                endTimestampMs = reportable.endTimestamp,
                distanceMeters = distance,
                activeDurationMs = activeWalking,
            )
        }
        widgetStateRepository.write(nextState)
        // Trigger Glance re-render of all PilgrimWidget instances. Use
        // updateAll(context) — the canonical Glance API — instead of
        // enumerating GlanceIds manually.
        PilgrimWidget().updateAll(appContext)
        // Re-arm the next-midnight refresh so the chain self-perpetuates
        // even if the user never opens the app again. REPLACE policy in
        // the scheduler ensures this is idempotent across multiple Worker
        // runs in the same day.
        widgetRefreshScheduler.scheduleMidnightRefresh()
        Result.success()
    } catch (ce: CancellationException) {
        // Stage 5-C / 8-A audit rule: kotlin's runCatching swallows CE.
        throw ce
    } catch (t: Throwable) {
        Log.w(TAG, "WidgetRefreshWorker.doWork failed", t)
        // Don't infinite-retry on logic bugs; widget will refresh on
        // the next finishWalk or the next midnight tick.
        Result.failure()
    }

    // No `getForegroundInfo()` override. With
    // `setExpedited(RUN_AS_NON_EXPEDITED_WORK_REQUEST)` in the
    // scheduler, the work falls back to non-expedited on API 34+
    // devices where the OS requires a declared `foregroundServiceType`
    // for expedited execution — which we don't have (and getting one
    // requires a Play Store permission justification that's overkill
    // for a sub-second widget refresh). The widget's 1-min success
    // criterion is comfortably met by normal-priority scheduling
    // either way.

    companion object {
        private const val TAG = "WidgetRefreshWorker"
        private const val MIN_REPORTABLE_WALK_MS = 60_000L // 1 minute
        // How many recent walks to scan for the most-recent reportable.
        // Caps the worst case (a user with 20+ accidental short walks
        // burying their last real one) at a bounded query — users that
        // exceed this will see the mantra until their next real walk,
        // an acceptable degraded mode.
        private const val SEARCH_WINDOW = 10
    }
}
