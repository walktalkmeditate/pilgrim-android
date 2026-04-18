// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home

import android.content.Context
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import org.walktalkmeditate.pilgrim.R

/** Formatters for home-surface row labels. Pure functions, test-only dependency is [Context]. */
object HomeFormat {

    private val dayOfWeekFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEEE", Locale.US)
    private val shortDateFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MMM d", Locale.US)

    /**
     * Relative-date label for the home list. Defaults to absolute
     * (`"MMM d"`) for anything 7+ days old so the list doesn't fill
     * with "23 days ago" — the iOS app's tone is low-information date
     * chrome.
     *
     * Thresholds (nowMs − timestampMs):
     *  - `< 1 min`:    "Just now"
     *  - `< 60 min`:   "%d minutes ago"
     *  - `< 24 hours`: "%d hours ago"
     *  - `< 7 days`:   day name ("Tuesday")
     *  - otherwise:    "MMM d" (Apr 15)
     */
    fun relativeDate(
        context: Context,
        timestampMs: Long,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val deltaMs = (nowMs - timestampMs).coerceAtLeast(0L)
        val minutes = deltaMs / 60_000L
        if (minutes < 1L) return context.getString(R.string.home_relative_just_now)
        if (minutes < 60L) {
            return if (minutes == 1L) {
                context.getString(R.string.home_relative_minute_ago)
            } else {
                context.getString(R.string.home_relative_minutes_ago, minutes.toInt())
            }
        }
        val hours = minutes / 60L
        if (hours < 24L) {
            return if (hours == 1L) {
                context.getString(R.string.home_relative_hour_ago)
            } else {
                context.getString(R.string.home_relative_hours_ago, hours.toInt())
            }
        }
        val walkDate = Instant.ofEpochMilli(timestampMs).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val daysBetween = ChronoUnit.DAYS.between(walkDate, today)
        return if (daysBetween < 7L) dayOfWeekFormatter.format(walkDate)
        else shortDateFormatter.format(walkDate)
    }

    /** "3 voice notes" / "1 voice note" / null when count is 0. */
    fun recordingCountLabel(context: Context, count: Int): String? = when {
        count <= 0 -> null
        count == 1 -> context.getString(R.string.home_recording_count_singular)
        else -> context.getString(R.string.home_recording_count, count)
    }
}
