// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.reliquary

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimCornerRadius
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors

internal const val THUMBNAIL_SIZE_DP = 88
private const val ACTIVATED_SCALE = 1.05f
private const val CENTER_ICON_BG_DP = 44
private const val CENTER_ICON_DP = 28
private const val PINNED_BADGE_DP = 18

/**
 * iOS-parity reliquary thumbnail. Three visual states match
 * `PhotoThumbnailView.swift @v1.6.0`:
 *
 *  - Inactive + unpinned: just the photo
 *  - Inactive + pinned: photo + small filled-pin badge top-right
 *  - Active (post-long-press): photo scaled 1.05× with a centered
 *    pin button (variant: filled for currently-pinned candidates,
 *    outlined for currently-unpinned). Tapping the button commits
 *    the pin/unpin via [onTogglePin]; tapping outside the button
 *    dismisses activation + opens preview via [onPreview].
 */
@Composable
internal fun PhotoThumbnail(
    candidate: PhotoCandidate,
    isActivated: Boolean,
    onLongPress: () -> Unit,
    onPinTap: () -> Unit,
    onPhotoTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scale by animateFloatAsState(
        targetValue = if (isActivated) ACTIVATED_SCALE else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "thumbnail-activation-scale",
    )

    val thumbnailLabel = stringResource(R.string.reliquary_photo_thumbnail_a11y)
    val activateLabel = stringResource(R.string.reliquary_photo_activate_a11y)
    val openLabel = stringResource(R.string.reliquary_photo_open_a11y)
    val keyId = candidate.pinnedPhotoId ?: candidate.uri.hashCode().toLong()

    Box(
        modifier = modifier
            .size(THUMBNAIL_SIZE_DP.dp)
            .testTag("photo-thumbnail-$keyId")
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(PilgrimCornerRadius.small))
            .background(pilgrimColors.parchmentSecondary)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = thumbnailLabel
                customActions = listOf(
                    CustomAccessibilityAction(label = activateLabel) {
                        onLongPress()
                        true
                    },
                    CustomAccessibilityAction(label = openLabel) {
                        onPhotoTap()
                        true
                    },
                )
            }
            .pointerInput(keyId) {
                detectTapGestures(
                    onLongPress = { onLongPress() },
                    onTap = { onPhotoTap() },
                )
            },
    ) {
        SubcomposeAsyncImage(
            model = candidate.uri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        // Inactive pinned candidates get a small badge in the
        // top-right corner (iOS parity).
        if (!isActivated && candidate.isPinned) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(PINNED_BADGE_DP.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .testTag("photo-thumbnail-$keyId-pinned-badge"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.PushPin,
                    contentDescription = null,
                    tint = pilgrimColors.parchment,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
        // Active candidates get a centered pin button overlay. The
        // filled variant for currently-pinned (tap = unpin); the
        // outlined variant for currently-unpinned (tap = pin).
        if (isActivated) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(CENTER_ICON_BG_DP.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .testTag("photo-thumbnail-$keyId-activated")
                    .pointerInput(keyId) {
                        detectTapGestures(onTap = { onPinTap() })
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (candidate.isPinned) {
                        Icons.Filled.PushPin
                    } else {
                        Icons.Outlined.PushPin
                    },
                    contentDescription = null,
                    tint = pilgrimColors.parchment,
                    modifier = Modifier.size(CENTER_ICON_DP.dp),
                )
            }
        }
    }
}
