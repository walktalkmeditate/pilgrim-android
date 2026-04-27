// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.path

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

internal const val RECOVERY_BANNER_AUTO_DISMISS_MS = 4_000L

/**
 * Transient banner acknowledging a walk that was auto-finalized when
 * the user swiped the app away from recents mid-walk. Appears at the
 * top of the Path tab on the cold-launch composition that follows
 * such a swipe. Auto-dismisses after [RECOVERY_BANNER_AUTO_DISMISS_MS]
 * via [onDismiss].
 *
 * iOS-parity reference: `MainCoordinatorView.swift` / `RecoveryBanner`.
 * iOS shows for 4s; Android matches.
 */
@Composable
fun RecoveryBanner(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(visible) {
        if (visible) {
            delay(RECOVERY_BANNER_AUTO_DISMISS_MS)
            onDismiss()
        }
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { -it }),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PilgrimSpacing.normal, vertical = PilgrimSpacing.small)
                .clip(RoundedCornerShape(8.dp))
                .background(pilgrimColors.parchmentSecondary.copy(alpha = 0.95f))
                .padding(
                    horizontal = PilgrimSpacing.normal,
                    vertical = PilgrimSpacing.small,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "Walk recovered",
                style = pilgrimType.caption,
                color = pilgrimColors.ink,
            )
        }
    }
}
