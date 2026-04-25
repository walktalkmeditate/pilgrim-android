// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Stage 9-A: schedule a widget refresh on day-rollover so the
 * relative-date label transitions "Today" → "Yesterday" without a
 * 24-hour delay AND the daily-rotating mantra rotates at midnight.
 *
 * `android:updatePeriodMillis="86400000"` in pilgrim_widget_info.xml is
 * NOT a midnight-aligned scheduler — the system fires it 24h after
 * widget add, then 24h after that, so a user who installs the widget
 * at 23:50 sees yesterday's content for nearly the full next day.
 * Listening for ACTION_DATE_CHANGED + ACTION_TIMEZONE_CHANGED closes
 * that gap.
 *
 * The system fires ACTION_DATE_CHANGED (sticky) at midnight to all
 * registered receivers in the foreground; manifest registration is the
 * canonical path because day-rollover often happens while the app is
 * not running.
 */
@AndroidEntryPoint
class DateRolloverReceiver : BroadcastReceiver() {

    @Inject lateinit var widgetRefreshScheduler: WidgetRefreshScheduler

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED -> {
                widgetRefreshScheduler.scheduleRefresh()
            }
        }
    }
}
