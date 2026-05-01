// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
 * Walk Summary top bar. Centered date title with a trailing Done button.
 * Mirrors iOS `WalkSummaryView.toolbar` (`WalkSummaryView.swift:106-116`).
 *
 * Custom Row instead of `TopAppBar` because the screen is composed
 * inside a sheet/modal-like container, not at an Activity nav-host
 * boundary. M3 TopAppBar's Insets handling assumes the latter and
 * paints status-bar padding we don't want here.
 */
@Composable
fun WalkSummaryTopBar(
    startTimestamp: Long,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dateText = remember(startTimestamp) { formatLongDate(startTimestamp) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(pilgrimColors.parchment)
            .height(64.dp)
            .padding(horizontal = PilgrimSpacing.normal),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = dateText,
                style = pilgrimType.heading,
                color = pilgrimColors.ink,
            )
        }
        TextButton(onClick = onDone) {
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
    return DateTimeFormatter
        .ofPattern("MMMM d, yyyy", Locale.getDefault())
        .format(date)
}
