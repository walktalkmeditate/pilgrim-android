// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider as DayNightColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontStyle
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import org.walktalkmeditate.pilgrim.MainActivity
import org.walktalkmeditate.pilgrim.R

/**
 * Stage 9-A: Pilgrim home-screen widget.
 *
 * Hybrid content: when a finished walk exists, renders distance +
 * active duration + relative-date label + tap-to-summary. When no
 * walks yet (or persisted state is Empty), renders a daily-rotating
 * mantra (iOS-parity, indexed by day-of-year). Tap on Mantra opens
 * the app to Home.
 *
 * State source: `WidgetStateRepository` (app-owned DataStore) — the
 * composable subscribes via `collectAsState`. The Worker writes new
 * state and calls `updateAll(context)` to trigger re-render.
 *
 * Repository handle resolved via Hilt `EntryPointAccessors` since
 * Glance widgets are constructed reflectively and can't be Hilt-
 * injected directly.
 */
class PilgrimWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            WidgetEntryPoint::class.java,
        )
        val repository = entryPoint.widgetStateRepository()
        val mantras = context.getString(R.string.widget_mantras)
        // Pre-read the first DataStore emission BEFORE provideContent so
        // the composable's `collectAsState(initial = ...)` snapshots the
        // real state on first render — avoids a flash-of-Empty for the
        // user when last-walk content is what should appear.
        val initial = repository.stateFlow.first()

        provideContent {
            Body(
                stateFlow = repository.stateFlow,
                initial = initial,
                mantras = mantras,
                today = remember { LocalDate.now() },
            )
        }
    }
}

@Composable
private fun Body(
    stateFlow: kotlinx.coroutines.flow.Flow<WidgetState>,
    initial: WidgetState,
    mantras: String,
    today: LocalDate,
) {
    val state by stateFlow.collectAsState(initial = initial)
    val context = LocalContext.current
    val description = when (val s = state) {
        is WidgetState.LastWalk -> context.getString(
            R.string.widget_a11y_last_walk,
            formatDistance(s.distanceMeters),
            formatDuration(s.activeDurationMs),
            relativeDateLabel(context, s.endTimestampMs, today),
        )
        WidgetState.Empty -> context.getString(R.string.widget_a11y_mantra)
    }
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(parchment)
            .cornerRadius(12.dp)
            .padding(12.dp)
            .semantics { contentDescription = description }
            .clickable(onClickFor(state)),
        contentAlignment = Alignment.Center,
    ) {
        when (val s = state) {
            is WidgetState.LastWalk -> LastWalkContent(s, today)
            WidgetState.Empty -> MantraContent(mantras = mantras, today = today)
        }
    }
}

@Composable
private fun LastWalkContent(state: WidgetState.LastWalk, today: LocalDate) {
    val context = LocalContext.current
    val isMedium = LocalSize.current.width >= 200.dp
    val distanceLabel = formatDistance(state.distanceMeters)
    val durationLabel = formatDuration(state.activeDurationMs)
    val relativeLabel = relativeDateLabel(context, state.endTimestampMs, today)

    if (isMedium) {
        Row(
            modifier = GlanceModifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = GlanceModifier.padding(horizontal = 8.dp),
                horizontalAlignment = Alignment.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = distanceLabel,
                    maxLines = 1,
                    style = TextStyle(
                        color = ink,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
                Spacer(GlanceModifier.height(4.dp))
                Text(
                    text = durationLabel,
                    maxLines = 1,
                    style = TextStyle(
                        color = fog,
                        fontSize = 14.sp,
                    ),
                )
                Spacer(GlanceModifier.height(8.dp))
                Text(
                    text = relativeLabel,
                    maxLines = 1,
                    style = TextStyle(
                        color = fog,
                        fontSize = 12.sp,
                        fontStyle = FontStyle.Italic,
                    ),
                )
            }
        }
    } else {
        Column(
            modifier = GlanceModifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = distanceLabel,
                maxLines = 1,
                style = TextStyle(
                    color = ink,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                ),
            )
            Spacer(GlanceModifier.height(2.dp))
            Text(
                text = durationLabel,
                maxLines = 1,
                style = TextStyle(
                    color = fog,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                ),
            )
            Spacer(GlanceModifier.height(6.dp))
            Text(
                text = relativeLabel,
                maxLines = 1,
                style = TextStyle(
                    color = fog,
                    fontSize = 11.sp,
                    fontStyle = FontStyle.Italic,
                    textAlign = TextAlign.Center,
                ),
            )
        }
    }
}

@Composable
private fun MantraContent(mantras: String, today: LocalDate) {
    val phrase = MantraPool.phraseFor(today, mantras)
    val isMedium = LocalSize.current.width >= 200.dp
    Text(
        text = phrase,
        style = TextStyle(
            color = fog,
            fontSize = if (isMedium) 16.sp else 13.sp,
            fontStyle = FontStyle.Italic,
            textAlign = TextAlign.Center,
        ),
    )
}

@Composable
private fun onClickFor(state: WidgetState): androidx.glance.action.Action {
    val context = LocalContext.current
    val intent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        when (val s = state) {
            is WidgetState.LastWalk -> {
                putExtra(DeepLinkTarget.EXTRA_DEEP_LINK, DeepLinkTarget.DEEP_LINK_WALK_SUMMARY)
                putExtra(DeepLinkTarget.EXTRA_WALK_ID, s.walkId)
            }
            WidgetState.Empty -> {
                putExtra(DeepLinkTarget.EXTRA_DEEP_LINK, DeepLinkTarget.DEEP_LINK_HOME)
            }
        }
    }
    return actionStartActivity(intent)
}

// --- Helpers (formatters that the composable reads at render time) ---

internal fun relativeDateLabel(context: Context, endTimestampMs: Long, today: LocalDate): String {
    val endDate = Instant.ofEpochMilli(endTimestampMs)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
    val days = ChronoUnit.DAYS.between(endDate, today).coerceAtLeast(0)
    return when (days) {
        0L -> context.getString(R.string.widget_relative_today)
        1L -> context.getString(R.string.widget_relative_yesterday)
        else -> String.format(
            Locale.ROOT,
            context.getString(R.string.widget_relative_n_days_ago),
            days.toInt(),
        )
    }
}

internal fun formatDistance(meters: Double): String {
    val km = meters / 1000.0
    return if (km >= 10.0) {
        String.format(Locale.ROOT, "%.0f km", km)
    } else {
        String.format(Locale.ROOT, "%.2f km", km)
    }
}

internal fun formatDuration(durationMs: Long): String {
    val totalMinutes = (durationMs / 60_000L).coerceAtLeast(0)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) {
        String.format(Locale.ROOT, "%dh %02dm", hours, minutes)
    } else {
        String.format(Locale.ROOT, "%dm", minutes)
    }
}

// --- Theme palette (matches PilgrimColors/iOS PilgrimWidget) ---
//
// Light + dark variants match the values in
// `app/src/main/java/org/walktalkmeditate/pilgrim/ui/theme/Color.kt` so
// the widget aesthetic stays in sync with the in-app theme. Glance's
// two-arg ColorProvider switches automatically based on the system's
// uiMode at render time — we don't need a uiMode listener.

private val parchmentLight = androidx.compose.ui.graphics.Color(0xFFF5F0E8)
private val parchmentDark = androidx.compose.ui.graphics.Color(0xFF1C1914)
private val inkLight = androidx.compose.ui.graphics.Color(0xFF2C2416)
private val inkDark = androidx.compose.ui.graphics.Color(0xFFF0EBE1)
private val fogLight = androidx.compose.ui.graphics.Color(0xFFB8AFA2)
private val fogDark = androidx.compose.ui.graphics.Color(0xFF6B6359)

private val parchment = DayNightColorProvider(day = parchmentLight, night = parchmentDark)
private val ink = DayNightColorProvider(day = inkLight, night = inkDark)
private val fog = DayNightColorProvider(day = fogLight, night = fogDark)
