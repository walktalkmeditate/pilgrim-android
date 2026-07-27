// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.OffsetDateTime
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.cairn.CachedCairn
import org.walktalkmeditate.pilgrim.data.cairn.CairnTier
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * iOS parity `CairnDetailView.swift@9a418e4` — medium-detent
 * ModalBottomSheet shown when the user taps a cairn pin on the map.
 * Read-only summary: tier kanji watermark + the tier's vector master
 * (U16 glyph spec `docs/parity/2026-07-27-port-glyph-sheets-u16.md`) +
 * stone count + tier description + first/last-stone relative times +
 * progress toward the next tier (or the eternal 108 badge).
 *
 * Deferred (decorative, not parity-critical): the breathing-scale
 * animation, entry spring, and radial glow ring for great+ tiers
 * (shipped iOS glow: 260dp frame, 116-130 breathing radius).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CairnDetailSheet(
    cairn: CachedCairn,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = pilgrimColors.parchment.copy(alpha = 0.95f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PilgrimSpacing.big, vertical = PilgrimSpacing.normal),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
        ) {
            // Hero: faint tier kanji watermark behind the tier's vector
            // master, at the per-tier sizes iOS ships (64..136dp — the
            // doubled table, U16 glyph spec L4).
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = tierKanji(cairn.tier),
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.Thin,
                    fontSize = 120.sp,
                    color = pilgrimColors.stone.copy(
                        alpha = 0.04f + cairn.tier.ordinal * 0.006f,
                    ),
                    modifier = Modifier.clearAndSetSemantics {},
                )
                val glyphSizeDp = 64 + cairn.tier.ordinal * 12
                Image(
                    painter = painterResource(cairn.tier.glyphRes),
                    contentDescription = stringResource(
                        R.string.cairn_detail_hero_a11y,
                        stringResource(cairn.tier.displayNameWithArticleRes),
                    ),
                    modifier = Modifier.size(glyphSizeDp.dp),
                )
            }
            Text(
                text = cairn.stoneCount.toString(),
                style = pilgrimType.displayLarge,
                color = pilgrimColors.ink,
            )
            Text(
                text = pluralStringResource(
                    R.plurals.cairn_detail_stones_plural,
                    cairn.stoneCount,
                    cairn.stoneCount,
                ),
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
            )
            Text(
                text = stringResource(tierDescriptionRes(cairn.tier)),
                style = pilgrimType.body,
                color = pilgrimColors.stone,
                fontStyle = FontStyle.Italic,
                textAlign = TextAlign.Center,
            )

            // Timestamps — first stone always, last stone only when >1.
            relativeTime(cairn.createdAt ?: cairn.lastPlacedAt)?.let { first ->
                Spacer(Modifier.height(PilgrimSpacing.xs))
                Text(
                    text = stringResource(R.string.cairn_detail_first_stone, first),
                    style = pilgrimType.caption,
                    color = pilgrimColors.fog,
                )
            }
            if (cairn.stoneCount > 1) {
                relativeTime(cairn.lastPlacedAt)?.let { last ->
                    Text(
                        text = stringResource(R.string.cairn_detail_last_stone, last),
                        style = pilgrimType.caption,
                        color = pilgrimColors.fog,
                    )
                }
            }

            Spacer(Modifier.height(PilgrimSpacing.small))
            // Progress toward the next tier, or the eternal badge.
            val nextTier = cairn.tier.next
            if (nextTier != null) {
                CairnProgress(cairn = cairn, nextTier = nextTier)
            } else {
                Text(
                    text = stringResource(R.string.cairn_detail_eternal_badge),
                    style = pilgrimType.displayLarge,
                    color = pilgrimColors.dawn,
                    modifier = Modifier.padding(vertical = PilgrimSpacing.small),
                )
            }

            Spacer(Modifier.height(PilgrimSpacing.small))
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = pilgrimColors.stone,
                    contentColor = pilgrimColors.parchment,
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 14.dp),
            ) {
                Text(
                    text = stringResource(R.string.cairn_detail_close),
                    style = pilgrimType.button,
                )
            }
        }
    }
}

@Composable
private fun CairnProgress(cairn: CachedCairn, nextTier: CairnTier) {
    val needed = cairn.tier.stonesToNext(cairn.stoneCount) ?: 0
    val progress = cairn.tier.progressToNext(cairn.stoneCount)
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.xs),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(pilgrimColors.parchmentTertiary),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress.coerceIn(0.02f, 1f))
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(pilgrimColors.stone),
            )
        }
        Text(
            text = stringResource(
                R.string.cairn_detail_progress,
                needed,
                stringResource(nextTierNameRes(nextTier)),
            ),
            style = pilgrimType.caption,
            color = pilgrimColors.fog,
            textAlign = TextAlign.Center,
        )
    }
}

/** Relative "3 days ago" string from an ISO-8601 timestamp, or null if unparseable. */
private fun relativeTime(iso: String): String? {
    val millis = runCatching { Instant.parse(iso).toEpochMilli() }
        .recoverCatching { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }
        .getOrNull() ?: return null
    return DateUtils.getRelativeTimeSpanString(
        millis,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
    ).toString()
}

/** iOS `CairnDetailView.tierKanji` — one glyph per tier. */
private fun tierKanji(tier: CairnTier): String = when (tier) {
    CairnTier.Faint -> "石"
    CairnTier.Small -> "積"
    CairnTier.Medium -> "道"
    CairnTier.Large -> "導"
    CairnTier.Great -> "山"
    CairnTier.Sacred -> "聖"
    CairnTier.Eternal -> "永"
}

private fun nextTierNameRes(next: CairnTier): Int = when (next) {
    CairnTier.Small -> R.string.cairn_next_small
    CairnTier.Medium -> R.string.cairn_next_medium
    CairnTier.Large -> R.string.cairn_next_large
    CairnTier.Great -> R.string.cairn_next_great
    CairnTier.Sacred -> R.string.cairn_next_sacred
    CairnTier.Eternal -> R.string.cairn_next_eternal
    CairnTier.Faint -> R.string.cairn_next_small
}

private fun tierDescriptionRes(tier: CairnTier): Int = when (tier) {
    CairnTier.Faint -> R.string.cairn_tier_faint_desc
    CairnTier.Small -> R.string.cairn_tier_small_desc
    CairnTier.Medium -> R.string.cairn_tier_medium_desc
    CairnTier.Large -> R.string.cairn_tier_large_desc
    CairnTier.Great -> R.string.cairn_tier_great_desc
    CairnTier.Sacred -> R.string.cairn_tier_sacred_desc
    CairnTier.Eternal -> R.string.cairn_tier_eternal_desc
}
