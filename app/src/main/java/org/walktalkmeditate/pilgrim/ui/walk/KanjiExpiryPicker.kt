// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.whisper.ExpiryDuration
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * iOS parity `KanjiExpiryPicker.swift@db4196e` — 3 horizontal pills,
 * equal-width, with the kanji rendered at 40sp ultraLight as a
 * background watermark and the label on top.
 *
 * Selected pill: `Color.stone` background, `.parchment` foreground.
 * Unselected: `Color.parchmentSecondary` background, `.fog`
 * foreground. Corner radius 12dp matches the iOS "small" token.
 */
@Composable
internal fun KanjiExpiryPicker(
    selected: ExpiryDuration,
    onSelect: (ExpiryDuration) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
    ) {
        ExpiryDuration.entries.forEach { duration ->
            ExpiryPill(
                duration = duration,
                selected = duration == selected,
                onClick = { onSelect(duration) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ExpiryPill(
    duration: ExpiryDuration,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background = if (selected) pilgrimColors.stone else pilgrimColors.parchmentSecondary
    val foreground = if (selected) pilgrimColors.parchment else pilgrimColors.fog
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Kanji watermark: large + faint, sits behind the label text.
        Text(
            text = duration.kanji,
            fontSize = 40.sp,
            fontWeight = FontWeight.Light,
            color = foreground.copy(alpha = 0.18f),
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(duration.labelRes()),
            style = pilgrimType.caption,
            color = foreground,
            textAlign = TextAlign.Center,
        )
    }
}

@StringRes
private fun ExpiryDuration.labelRes(): Int = when (this) {
    ExpiryDuration.OneDay -> R.string.whisper_expiry_one_day
    ExpiryDuration.SevenDays -> R.string.whisper_expiry_seven_days
    ExpiryDuration.OneMonth -> R.string.whisper_expiry_one_month
}
