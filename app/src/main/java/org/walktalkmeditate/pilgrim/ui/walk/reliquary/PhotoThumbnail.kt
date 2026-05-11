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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import org.walktalkmeditate.pilgrim.data.entity.WalkPhoto
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimCornerRadius
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors

internal const val THUMBNAIL_SIZE_DP = 88
private const val ACTIVATED_SCALE = 1.05f

@Composable
internal fun PhotoThumbnail(
    photo: WalkPhoto,
    isPinned: Boolean,
    isActivated: Boolean,
    onLongPress: () -> Unit,
    onTap: () -> Unit,
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

    Box(
        modifier = modifier
            .size(THUMBNAIL_SIZE_DP.dp)
            .testTag("photo-thumbnail-${photo.id}")
            .graphicsLayer {
                // Lambda form per Stage 5-A perf-cliff lesson — keeps the
                // animated scale value in the render phase, not composition.
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(PilgrimCornerRadius.small))
            .background(pilgrimColors.parchmentSecondary)
            .pointerInput(photo.id) {
                detectTapGestures(
                    onLongPress = { onLongPress() },
                    onTap = { onTap() },
                )
            },
    ) {
        SubcomposeAsyncImage(
            model = photo.photoUri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (isPinned) {
            Icon(
                imageVector = Icons.Filled.Bookmark,
                contentDescription = null,
                tint = pilgrimColors.rust,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(16.dp)
                    .testTag("photo-thumbnail-${photo.id}-pinned-badge"),
            )
        }
        if (isActivated) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("photo-thumbnail-${photo.id}-activated"),
            )
        }
    }
}
