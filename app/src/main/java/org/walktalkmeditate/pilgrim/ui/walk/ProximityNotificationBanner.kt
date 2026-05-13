// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.cairn.CairnTier
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * iOS parity `ProximityNotificationView.swift@db4196e`. Floating
 * top-center banner that fades + slides in for 0.3s, holds 5s, then
 * fades + slides out for 0.3s. Mid-banner swap interrupts with a
 * 0.2s dismiss + 0.22s gap before the next banner shows.
 *
 * `generation` counter guards auto-dismiss + mid-swap from re-entrancy:
 * the dismiss task captures gen at launch; if gen has advanced by the
 * time the 5s delay expires, it bails — matches iOS exactly.
 */
sealed class ProximityNotification {
    /** "A whisper lingers nearby…" */
    object Whisper : ProximityNotification()
    /** Tier-based copy; iOS includes stone count in medium+. */
    data class Cairn(val tier: CairnTier, val stoneCount: Int) : ProximityNotification()
}

@Composable
fun ProximityNotificationBanner(
    event: ProximityNotification?,
    modifier: Modifier = Modifier,
) {
    // Local one-shot mirror of the latest event so we can drive the
    // animated-visibility properly. `pendingEvent` is what's currently
    // visible; transitioning to a new event runs the mid-swap dance.
    var pendingEvent by remember { mutableStateOf<ProximityNotification?>(null) }
    var generation by remember { mutableLongStateOf(0L) }
    val currentEvent by rememberUpdatedState(event)

    LaunchedEffect(event) {
        val incoming = event ?: return@LaunchedEffect
        val myGen = ++generation
        if (pendingEvent != null) {
            // Mid-swap: dismiss the current banner over 0.2s, wait
            // [BANNER_SWAP_GAP_MS], then show the new one.
            pendingEvent = null
            delay(BANNER_SWAP_GAP_MS)
            if (myGen != generation) return@LaunchedEffect
        }
        pendingEvent = incoming
        delay(BANNER_HOLD_MS)
        if (myGen != generation) return@LaunchedEffect
        pendingEvent = null
        // Extra post-dismiss-animation delay so a follow-up event
        // doesn't race the AnimatedVisibility exit transition.
        delay(BANNER_POST_DISMISS_DELAY_MS)
        if (myGen == generation && currentEvent == null) {
            // Caller already cleared the event upstream; nothing to do.
        }
    }

    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(
            visible = pendingEvent != null,
            enter = fadeIn(animationSpec = tween(BANNER_ANIM_MS.toInt())) +
                slideInVertically(animationSpec = tween(BANNER_ANIM_MS.toInt())) { -it / 2 },
            exit = fadeOut(animationSpec = tween(BANNER_ANIM_MS.toInt())) +
                slideOutVertically(animationSpec = tween(BANNER_ANIM_MS.toInt())) { -it / 2 },
        ) {
            val current = pendingEvent
            if (current != null) {
                BannerSurface(notification = current)
            }
        }
    }
}

@Composable
private fun BannerSurface(notification: ProximityNotification) {
    Box(
        modifier = Modifier
            .padding(horizontal = PilgrimSpacing.big, vertical = PilgrimSpacing.normal)
            .clip(RoundedCornerShape(12.dp))
            .background(pilgrimColors.parchmentSecondary)
            .padding(horizontal = PilgrimSpacing.normal, vertical = PilgrimSpacing.small),
    ) {
        Text(
            text = bannerText(notification),
            style = pilgrimType.body,
            color = pilgrimColors.ink,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun bannerText(notification: ProximityNotification): String = when (notification) {
    ProximityNotification.Whisper -> stringResource(R.string.proximity_whisper)
    is ProximityNotification.Cairn -> when (notification.tier) {
        CairnTier.Faint, CairnTier.Small ->
            stringResource(R.string.proximity_cairn_small)
        CairnTier.Medium, CairnTier.Large ->
            stringResource(R.string.proximity_cairn_medium, notification.stoneCount)
        CairnTier.Great, CairnTier.Sacred ->
            stringResource(R.string.proximity_cairn_great, notification.stoneCount)
        CairnTier.Eternal ->
            stringResource(R.string.proximity_cairn_eternal, notification.stoneCount)
    }
}

private const val BANNER_HOLD_MS = 5_000L
private const val BANNER_ANIM_MS = 300L
private const val BANNER_SWAP_GAP_MS = 220L
private const val BANNER_POST_DISMISS_DELAY_MS = 400L
