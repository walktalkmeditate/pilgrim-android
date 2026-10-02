// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.honor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.honor.HonorImportCopy
import org.walktalkmeditate.pilgrim.honor.HonorImportState
import org.walktalkmeditate.pilgrim.honor.HonorLinkToast
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/** SwiftUI's `.easeInOut`, which iOS gives no duration: 0.35 s. */
private const val TOAST_MOTION_MS = 350

/**
 * iOS `HonorLinkToast` (`MainCoordinatorView.swift:405-423@7c200bf`, S2
 * §7.2): caption ink on parchmentSecondary at 0.95, a literal radius of 8,
 * 16 × 8 inside, 16 at the sides and 8 at the top outside, centred and
 * multi-line. It slides in from the top and fades; a new line on a toast
 * still showing crossfades as its box resizes, iOS's animation being keyed
 * on the text (`MainTabView.swift:159@7c200bf`). Nothing in it takes a
 * pointer, so taps reach what lies under it. TalkBack can land on it but
 * hears no announcement, as VoiceOver hears none (owner decision 3).
 */
@Composable
fun HonorLinkToastView(toast: HonorLinkToast?, modifier: Modifier = Modifier) {
    // The toast leaves still reading its last line.
    val last = remember { LastToast() }
    if (toast != null) last.value = toast
    val shown = toast ?: last.value
    AnimatedVisibility(
        visible = toast != null,
        modifier = modifier,
        enter = fadeIn(tween(TOAST_MOTION_MS, easing = EaseInOut)) +
            slideInVertically(tween(TOAST_MOTION_MS, easing = EaseInOut)) { -it },
        exit = fadeOut(tween(TOAST_MOTION_MS, easing = EaseInOut)) +
            slideOutVertically(tween(TOAST_MOTION_MS, easing = EaseInOut)) { -it },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .padding(horizontal = PilgrimSpacing.normal)
                .padding(top = PilgrimSpacing.small)
                .animateContentSize(tween(TOAST_MOTION_MS, easing = EaseInOut))
                .clip(RoundedCornerShape(8.dp))
                .background(pilgrimColors.parchmentSecondary.copy(alpha = 0.95f))
                .padding(horizontal = PilgrimSpacing.normal, vertical = PilgrimSpacing.small),
        ) {
            Crossfade(
                targetState = shown,
                animationSpec = tween(TOAST_MOTION_MS, easing = EaseInOut),
                label = "honor link toast line",
            ) { line ->
                Text(
                    text = line?.let { honorLinkToastText(it) }.orEmpty(),
                    style = pilgrimType.caption,
                    color = pilgrimColors.ink,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** Plain, not snapshot state: written during composition, read only by that composition. */
private class LastToast {
    var value: HonorLinkToast? = null
}

/** Every line verbatim (S2 §7.1): the refusal, and the import's own copy for the fetch and its failures. */
@Composable
private fun honorLinkToastText(toast: HonorLinkToast): String {
    val resources = LocalResources.current
    return when (toast) {
        HonorLinkToast.FinishWalkFirst -> resources.getString(R.string.honor_link_finish_walk_first)
        HonorLinkToast.Reaching -> HonorImportCopy.line(resources, HonorImportState.Fetching)
        is HonorLinkToast.Failed -> HonorImportCopy.line(resources, HonorImportState.Failed(toast.error))
    }.orEmpty()
}
