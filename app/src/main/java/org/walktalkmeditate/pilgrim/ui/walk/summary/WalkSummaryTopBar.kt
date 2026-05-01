// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * Walk Summary top bar. Truly-centered date title with a trailing Done
 * button. Mirrors iOS `WalkSummaryView.toolbar` (`WalkSummaryView.swift:106-116`).
 *
 * Custom layout instead of `TopAppBar` because the screen sits in a
 * sheet/modal-like container, not at the Activity nav-host root. The
 * bar carries its own [windowInsetsPadding] for [WindowInsets.statusBars]
 * — `MainActivity` calls `enableEdgeToEdge()`, so without this padding
 * the title and Done button render under the system status bar on
 * physical devices.
 *
 * Layout: `Box(fillMaxWidth)` with the title centered via
 * `Alignment.Center` and the Done button right-aligned via
 * `Alignment.CenterEnd`. This keeps the title centered against the FULL
 * bar width regardless of the Done button's intrinsic width — a `Row +
 * weight(1f)` trick centers within only `(width - DoneWidth)` and the
 * title drifts visibly left of true center.
 */
@Composable
fun WalkSummaryTopBar(
    startTimestamp: Long?,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dateText = remember(startTimestamp) { startTimestamp?.let(::formatLongDate) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(pilgrimColors.parchment)
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(64.dp)
            .padding(horizontal = PilgrimSpacing.normal),
    ) {
        if (dateText != null) {
            // Symmetric horizontal padding equal to the trailing TextButton's
            // touch zone keeps the title TRULY centered (geometric center of
            // the bar) without colliding with the Done button. Without this
            // guard, long month names ("September 28, 2026") on narrow
            // devices grow into the Done button's bounding box — children of
            // a Box are measured independently against the Box's full content
            // constraints, so Alignment.Center centers across the full width
            // and the title can overlap the right-aligned button.
            // 72dp ≈ TextButton min-touch (48dp) + horizontal content padding
            // (~12dp each side). One ellipsis line for any pathological date
            // string the formatter could produce in a future locale.
            Text(
                text = dateText,
                style = pilgrimType.heading,
                color = pilgrimColors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 72.dp),
            )
        }
        TextButton(
            onClick = onDone,
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            Text(
                text = stringResource(R.string.summary_action_done),
                style = pilgrimType.button,
                color = pilgrimColors.stone,
            )
        }
    }
}

private fun formatLongDate(epochMillis: Long): String {
    val date = Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
    // Pinned to Locale.ENGLISH because the rest of the app is English-only
    // (Stage 12-D's values-fr/ stub carries a single locale_resolution_marker
    // and nothing else). Locale.getDefault() would produce mixed-locale output
    // on a French device — "mars 16, 2026" surrounded by English UI. This
    // matches the iOS reference, which uses an `en_US`-equivalent
    // .dateStyle = .long. When a real translation lands, switch to
    // Locale.getDefault() in the same change that ships fr strings.
    return DateTimeFormatter
        .ofPattern("MMMM d, yyyy", Locale.ENGLISH)
        .format(date)
}
