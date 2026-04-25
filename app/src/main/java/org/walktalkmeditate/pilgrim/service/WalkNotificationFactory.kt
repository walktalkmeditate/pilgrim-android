// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.service

import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.domain.WalkState

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

internal fun walkNotificationText(context: Context, state: WalkState): String = when (state) {
    WalkState.Idle -> context.getString(R.string.walk_notification_starting)
    is WalkState.Active -> context.getString(
        R.string.walk_notification_active,
        state.walk.distanceMeters / 1_000.0,
    )
    is WalkState.Paused -> context.getString(R.string.walk_notification_paused)
    is WalkState.Meditating -> context.getString(R.string.walk_notification_meditating)
    is WalkState.Finished -> context.getString(R.string.walk_notification_finished)
}
