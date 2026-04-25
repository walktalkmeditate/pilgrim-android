// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.share

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.share.ExpiryOption
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * Stage 8-A: Journey-share card section on Walk Summary. Mirrors iOS
 * `WalkSharingButtons`'s journeySection — three visual states
 * (Fresh, Active, Expired) selected from [state].
 *
 * Visual parity notes:
 *  - Active state shows the expiry kanji (月/季/巡) as a watermark
 *    BEHIND the URL + buttons, opacity fades linearly from 7% at
 *    shareDate to 2.5% at expiry. Port of iOS
 *    `watermarkOpacity(cached)`.
 *  - Tapping the URL text ROW opens the modal in its success state
 *    (NOT the browser); the dedicated Share button invokes the
 *    system chooser.
 *  - Copy / Share buttons live in a Row beneath the URL.
 */
sealed interface JourneyRowState {
    data object Fresh : JourneyRowState
    data class Active(
        val url: String,
        val expiryEpochMs: Long,
        val shareDateEpochMs: Long,
        val expiryOption: ExpiryOption?,
    ) : JourneyRowState
    data class Expired(val expiryOption: ExpiryOption?) : JourneyRowState
}

@Composable
fun WalkShareJourneyRow(
    state: JourneyRowState,
    onShareJourney: () -> Unit,
    onReshare: () -> Unit,
    onCopyUrl: (url: String) -> Unit,
    onShareUrl: (url: String) -> Unit,
    onReopenModal: () -> Unit,
    modifier: Modifier = Modifier,
    nowEpochMs: Long = Instant.now().toEpochMilli(),
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = PilgrimSpacing.small),
        colors = CardDefaults.cardColors(containerColor = pilgrimColors.parchmentSecondary),
    ) {
        Box(
            modifier = Modifier.padding(PilgrimSpacing.normal),
            contentAlignment = Alignment.Center,
        ) {
            when (state) {
                JourneyRowState.Fresh -> FreshContent(onShareJourney = onShareJourney)
                is JourneyRowState.Active -> ActiveContent(
                    state = state,
                    nowEpochMs = nowEpochMs,
                    onCopyUrl = onCopyUrl,
                    onShareUrl = onShareUrl,
                    onReopenModal = onReopenModal,
                )
                is JourneyRowState.Expired -> ExpiredContent(
                    state = state,
                    onReshare = onReshare,
                )
            }
        }
    }
}

@Composable
private fun FreshContent(onShareJourney: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.xs),
    ) {
        OutlinedButton(onClick = onShareJourney) {
            Icon(Icons.Outlined.IosShare, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(PilgrimSpacing.small))
            Text(stringResource(R.string.share_journey_action))
        }
        Text(
            text = stringResource(R.string.share_journey_create_web_page),
            style = pilgrimType.caption,
            color = pilgrimColors.fog,
        )
        Text(
            text = "walk.pilgrimapp.org",
            style = pilgrimType.caption,
            color = pilgrimColors.fog,
        )
    }
}

@Composable
private fun ActiveContent(
    state: JourneyRowState.Active,
    nowEpochMs: Long,
    onCopyUrl: (String) -> Unit,
    onShareUrl: (String) -> Unit,
    onReopenModal: () -> Unit,
) {
    val kanji = state.expiryOption?.kanji
    val watermarkAlpha = watermarkOpacity(
        shareDateEpochMs = state.shareDateEpochMs,
        expiryEpochMs = state.expiryEpochMs,
        nowEpochMs = nowEpochMs,
    )
    Box(contentAlignment = Alignment.Center) {
        if (kanji != null) {
            Text(
                text = kanji,
                fontSize = 120.sp,
                fontWeight = FontWeight.Thin,
                color = pilgrimColors.stone.copy(alpha = watermarkAlpha),
                textAlign = TextAlign.Center,
            )
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.xs),
        ) {
            state.expiryOption?.label?.let {
                Text(
                    text = it.uppercase(Locale.ROOT),
                    style = pilgrimType.caption,
                    color = pilgrimColors.stone,
                )
            }
            TextButton(onClick = onReopenModal) {
                Text(
                    text = state.url,
                    style = pilgrimType.caption,
                    color = pilgrimColors.stone,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
            }
            Text(
                text = stringResource(
                    R.string.share_journey_returns_on,
                    formatExpiryDate(state.expiryEpochMs),
                ),
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = PilgrimSpacing.small),
                horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
            ) {
                OutlinedButton(
                    onClick = { onCopyUrl(state.url) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        Icons.Outlined.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(PilgrimSpacing.small))
                    Text(stringResource(R.string.share_journey_copy))
                }
                OutlinedButton(
                    onClick = { onShareUrl(state.url) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        Icons.Outlined.IosShare,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(PilgrimSpacing.small))
                    Text(stringResource(R.string.share_journey_share))
                }
            }
        }
    }
}

@Composable
private fun ExpiredContent(
    state: JourneyRowState.Expired,
    onReshare: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.xs),
    ) {
        Text(
            text = stringResource(R.string.share_journey_returned),
            style = pilgrimType.caption,
            color = pilgrimColors.fog,
        )
        state.expiryOption?.label?.let {
            Text(
                text = stringResource(R.string.share_journey_shared_for, it),
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
            )
        }
        Spacer(Modifier.height(PilgrimSpacing.small))
        TextButton(onClick = onReshare) {
            Text(stringResource(R.string.share_journey_share_again))
        }
    }
}

/**
 * Linear fade 7% → 2.5% from shareDate → expiry (iOS
 * `watermarkOpacity(cached)` parity). Clamps to ensure the watermark
 * never fully fades, even past expiry (expired state is rendered by
 * a different composable anyway).
 */
private fun watermarkOpacity(
    shareDateEpochMs: Long,
    expiryEpochMs: Long,
    nowEpochMs: Long,
): Float {
    val total = (expiryEpochMs - shareDateEpochMs).coerceAtLeast(1L)
    val elapsed = (nowEpochMs - shareDateEpochMs).coerceIn(0L, total)
    val fraction = elapsed.toFloat() / total.toFloat()
    return 0.07f - (fraction * 0.045f)
}

/**
 * Month name uses the user's locale (wire-visible user-facing
 * content); day digit is forced to ASCII via `DecimalStyle.STANDARD`
 * so Arabic / Persian / Hindi locales don't mix non-ASCII digits
 * into the Latin-surround "Returns to the trail on …" copy
 * (Stage 6-B lesson).
 */
private val expiryDateFormatter =
    DateTimeFormatter.ofPattern("MMMM d", Locale.getDefault())
        .withDecimalStyle(java.time.format.DecimalStyle.STANDARD)

private fun formatExpiryDate(epochMs: Long): String =
    expiryDateFormatter
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMs))
