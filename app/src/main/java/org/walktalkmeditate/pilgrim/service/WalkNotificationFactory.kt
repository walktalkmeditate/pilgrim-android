// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.service

import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import java.util.Locale
import kotlin.math.roundToLong
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.domain.WalkMode
import org.walktalkmeditate.pilgrim.domain.WalkState
import org.walktalkmeditate.pilgrim.domain.seek.SeekDirectionHint
import org.walktalkmeditate.pilgrim.domain.seek.SeekGlanceModel
import org.walktalkmeditate.pilgrim.domain.seek.SeekGlanceState
import org.walktalkmeditate.pilgrim.ui.walk.WalkFormat
import org.walktalkmeditate.pilgrim.walk.honor.HonorGlanceState

/**
 * Cached PendingIntents for the five notification actions. Built once
 * in `WalkTrackingService.onCreate` so the per-tick notification rebuild
 * isn't re-allocating PendingIntents 60 times a minute.
 */
internal data class WalkNotificationActions(
    val pause: PendingIntent,
    val resume: PendingIntent,
    val endMeditation: PendingIntent,
    val markWaypoint: PendingIntent,
    val finish: PendingIntent,
)

/**
 * Add the per-state action button set to [builder]. Extracted as a
 * top-level helper so notification-shape tests can exercise the path
 * without the full service + Hilt lifecycle.
 *
 * Per-state action sets:
 *  - Active     → Pause | Waypoint | Finish
 *  - Paused     → Resume | Waypoint | Finish
 *  - Meditating → End Meditation | Finish
 *  - Idle/Finished → no actions (transient promote-window /
 *    stop-self trigger respectively)
 */
internal fun addWalkActionsForState(
    builder: NotificationCompat.Builder,
    context: Context,
    state: WalkState,
    actions: WalkNotificationActions,
) {
    when (state) {
        is WalkState.Active -> {
            builder.addAction(
                R.drawable.ic_notification_pause,
                context.getString(R.string.walk_notification_action_pause),
                actions.pause,
            )
            builder.addAction(
                R.drawable.ic_notification_waypoint,
                context.getString(R.string.walk_notification_action_mark_waypoint),
                actions.markWaypoint,
            )
            builder.addAction(
                R.drawable.ic_notification_stop,
                context.getString(R.string.walk_notification_action_finish),
                actions.finish,
            )
        }
        is WalkState.Paused -> {
            builder.addAction(
                R.drawable.ic_notification_resume,
                context.getString(R.string.walk_notification_action_resume),
                actions.resume,
            )
            builder.addAction(
                R.drawable.ic_notification_waypoint,
                context.getString(R.string.walk_notification_action_mark_waypoint),
                actions.markWaypoint,
            )
            builder.addAction(
                R.drawable.ic_notification_stop,
                context.getString(R.string.walk_notification_action_finish),
                actions.finish,
            )
        }
        is WalkState.Meditating -> {
            builder.addAction(
                R.drawable.ic_notification_end_meditation,
                context.getString(R.string.walk_notification_action_end_meditation),
                actions.endMeditation,
            )
            builder.addAction(
                R.drawable.ic_notification_stop,
                context.getString(R.string.walk_notification_action_finish),
                actions.finish,
            )
        }
        WalkState.Idle, is WalkState.Finished -> Unit
    }
}

/**
 * [honorGlance] and [honorUnits] are the Honor session's glance and the
 * units fixed at the walk's start; the Honor line shows in every walk
 * state, as iOS's row does (spec D §10.2).
 */
internal fun walkNotificationText(
    context: Context,
    state: WalkState,
    units: UnitSystem,
    seekGlance: SeekGlanceState? = null,
    honorGlance: HonorGlanceState? = null,
    honorUnits: UnitSystem = units,
): String {
    val honorLine = shownHonorGlance(state, honorGlance)?.let { honorGlanceLine(it, honorUnits) }
    return when (state) {
        WalkState.Idle -> context.getString(R.string.walk_notification_starting)
        is WalkState.Active -> {
            // Delegate to WalkFormat so the notification follows the SAME
            // unit conventions as every other display surface — including
            // the Imperial <0.1 mi → feet fallback (e.g., "320 ft" rather
            // than "0.06 mi" early in a walk) and the Metric <100 m → m
            // fallback. Centralizing here matches the goal stated in
            // WalkFormat's KDoc: "the conversion happens at format time only".
            val distance = WalkFormat.distance(state.walk.distanceMeters, units)
            // The seek line renders ONLY for Active seek walks holding a
            // glance (≙ iOS `if let seek = context.state.seek`,
            // PilgrimWidgetLiveActivity.swift:186@c1745e8); a wander walk —
            // or a seek walk whose glance hasn't arrived yet — keeps the
            // exact pre-U10 string (golden-string regression guard).
            val glance = seekGlance.takeIf { state.walk.mode == WalkMode.Seek }
            when {
                glance != null -> context.getString(
                    R.string.walk_notification_seek_active,
                    distance,
                    seekGlanceLine(context, glance, units),
                )
                honorLine != null -> context.getString(R.string.walk_notification_honor_active, distance, honorLine)
                else -> context.getString(R.string.walk_notification_active, distance)
            }
        }
        is WalkState.Paused -> if (honorLine != null) {
            context.getString(R.string.walk_notification_honor_paused, honorLine)
        } else {
            context.getString(R.string.walk_notification_paused)
        }
        is WalkState.Meditating -> if (honorLine != null) {
            context.getString(R.string.walk_notification_honor_meditating, honorLine)
        } else {
            context.getString(R.string.walk_notification_meditating)
        }
        is WalkState.Finished -> context.getString(R.string.walk_notification_finished)
    }
}

/** The glance, while an honor walk is in progress to show it; iOS's row has no state gate (spec D §10.2). */
internal fun shownHonorGlance(state: WalkState, glance: HonorGlanceState?): HonorGlanceState? =
    glance?.takeIf { state.inProgressWalk()?.mode == WalkMode.Honor }

/**
 * The Honor row's words (iOS `PilgrimWidgetLiveActivity.swift:190-199,255-264@7c200bf`),
 * first match wins. Not localized on iOS (the row's `Text` takes a String
 * expression, not a key), so these are literals here too, with POSIX numbers.
 */
internal fun honorGlanceLine(glance: HonorGlanceState, units: UnitSystem): String {
    if (glance.isArrived) return "their way, walked"
    if (!glance.isOnWay) return "off the way"
    val bucket = glance.distanceRemainingBucketMeters
    val imperial = units == UnitSystem.Imperial
    if (bucket < SeekGlanceModel.BUCKET_WIDTH_METERS.toInt()) return "almost there"
    // No space before the "+", unlike Seek's "2 km +".
    if (bucket >= SeekGlanceModel.MAX_BUCKET_METERS) return if (imperial) "1.2 mi+ to go" else "2 km+ to go"
    return "${seekGlanceMiddleText(bucket, imperial)} to go"
}

/**
 * One number per distinct [honorGlanceLine], so the notification's
 * fingerprint moves only when the words do: two imperial buckets can
 * read the same "~0.1 mi" (`WalkNotificationHonorGlanceTest` pins the
 * one-to-one match over every bucket).
 */
internal fun honorGlanceDisplayCode(glance: HonorGlanceState, units: UnitSystem): Long {
    val bucket = glance.distanceRemainingBucketMeters
    return when {
        glance.isArrived -> 1L
        !glance.isOnWay -> 2L
        bucket < SeekGlanceModel.BUCKET_WIDTH_METERS.toInt() -> 3L
        bucket >= SeekGlanceModel.MAX_BUCKET_METERS -> if (units == UnitSystem.Imperial) 5L else 4L
        // "~%.1f mi" shows tenths of a mile.
        units == UnitSystem.Imperial -> 100L + (bucket / METERS_PER_MILE * 10).roundToLong()
        // "~%d m" and "~%.1f km" both read the 100 m bucket exactly.
        else -> 200L + bucket / 100
    }
}

/**
 * The glance half of the notification line (iOS `seekGlanceBar`,
 * `PilgrimWidgetLiveActivity.swift:199-220@c1745e8`): "seeking
 * complete" terminal, otherwise the bucket's distance text with the
 * direction word — when the walker is moving with a valid course —
 * joined the way the widget's HStack places them.
 */
internal fun seekGlanceLine(
    context: Context,
    glance: SeekGlanceState,
    units: UnitSystem,
): String {
    if (glance.isComplete) return context.getString(R.string.walk_notification_seek_complete)
    val distance = seekGlanceDistanceText(context, glance.distanceBucketMeters, units)
    val direction = glance.directionHint?.let { context.getString(it.labelRes()) }
    return if (direction != null) "$distance $direction" else distance
}

private fun SeekDirectionHint.labelRes(): Int = when (this) {
    SeekDirectionHint.AHEAD -> R.string.walk_notification_seek_direction_ahead
    SeekDirectionHint.LEFT -> R.string.walk_notification_seek_direction_left
    SeekDirectionHint.RIGHT -> R.string.walk_notification_seek_direction_right
    SeekDirectionHint.BEHIND -> R.string.walk_notification_seek_direction_behind
}

/** iOS `seekDistanceText` (`PilgrimWidgetLiveActivity.swift:232-242@c1745e8`), verbatim ladder. */
internal fun seekGlanceDistanceText(
    context: Context,
    bucketMeters: Int,
    units: UnitSystem,
): String {
    val imperial = units == UnitSystem.Imperial
    if (bucketMeters >= SeekGlanceModel.MAX_BUCKET_METERS) {
        return if (imperial) "1.2 mi +" else "2 km +"
    }
    if (bucketMeters < SeekGlanceModel.BUCKET_WIDTH_METERS.toInt()) {
        return context.getString(R.string.walk_notification_seek_close)
    }
    return seekGlanceMiddleText(bucketMeters, imperial)
}

/** The ladder's middle buckets, 100–1999 m, which iOS's Honor row reuses from Seek's. */
private fun seekGlanceMiddleText(bucketMeters: Int, imperial: Boolean): String = when {
    imperial -> String.format(Locale.US, "~%.1f mi", bucketMeters / METERS_PER_MILE)
    bucketMeters >= 1_000 -> String.format(Locale.US, "~%.1f km", bucketMeters / 1_000.0)
    else -> String.format(Locale.US, "~%d m", bucketMeters)
}

private const val METERS_PER_MILE = 1_609.344
