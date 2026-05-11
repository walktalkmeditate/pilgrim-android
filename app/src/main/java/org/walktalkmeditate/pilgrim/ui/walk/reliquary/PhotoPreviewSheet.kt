// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.reliquary

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.entity.WalkPhoto
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing

/**
 * Full-screen photo preview matching iOS `PhotoPreviewSheet.swift@db4196e`.
 * Pin-button state derives from repository (`isPinned` + `isPinningInFlight`)
 * per spec D4 — no rememberSaveable latch.
 *
 * Drag-down > 120dp dismisses; ≤ 120dp snaps back. Velocity is NOT part of
 * the dismiss contract — a fast flick under 120dp still snaps back.
 */
@Composable
internal fun PhotoPreviewSheet(
    photo: WalkPhoto,
    isPinned: Boolean,
    isPinningInFlight: Boolean,
    onPin: () -> Unit,
    onOpenInGallery: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        PhotoPreviewSheetContent(
            photo = photo,
            isPinned = isPinned,
            isPinningInFlight = isPinningInFlight,
            onPin = onPin,
            onOpenInGallery = onOpenInGallery,
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun PhotoPreviewSheetContent(
    photo: WalkPhoto,
    isPinned: Boolean,
    isPinningInFlight: Boolean,
    onPin: () -> Unit,
    onOpenInGallery: () -> Unit,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    var dragOffsetDp by remember { mutableStateOf(0.dp) }
    var dismissing by remember { mutableStateOf(false) }
    var isDragging by remember { mutableStateOf(false) }

    // Spring only fires on gesture release (snapback to 0). During an active
    // drag isDragging=true, so displayOffsetDp tracks the finger directly
    // with no spring lag. On release isDragging flips false and the spring
    // animates back from dragOffsetDp to 0.
    val animatedSnapBack by animateFloatAsState(
        targetValue = if (isDragging) dragOffsetDp.value else 0f,
        animationSpec = spring(
            stiffness = Spring.StiffnessLow,
            dampingRatio = 0.8f,
        ),
        label = "preview-sheet-snap-back",
    )
    val displayOffsetDp = if (isDragging) dragOffsetDp.value else animatedSnapBack

    BackHandler(enabled = true) {
        if (dismissing) return@BackHandler
        dismissing = true
        onDismiss()
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .graphicsLayer { translationY = with(density) { displayOffsetDp.dp.toPx() } }
            .pointerInput(photo.id) {
                detectVerticalDragGestures(
                    onDragStart = { isDragging = true },
                    onDragEnd = {
                        isDragging = false
                        if (dragOffsetDp > DRAG_DISMISS_THRESHOLD_DP) {
                            if (!dismissing) {
                                dismissing = true
                                onDismiss()
                            }
                        } else {
                            dragOffsetDp = 0.dp
                        }
                    },
                    onDragCancel = {
                        isDragging = false
                        dragOffsetDp = 0.dp
                    },
                    onVerticalDrag = { _, dragAmount ->
                        val deltaDp = with(density) { dragAmount.toDp() }
                        dragOffsetDp = (dragOffsetDp + deltaDp).coerceAtLeast(0.dp)
                    },
                )
            },
        color = Color.Black,
    ) {
        Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = photo.photoUri,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("preview-sheet-image"),
                )
            }
            BottomActions(
                isPinned = isPinned,
                isPinningInFlight = isPinningInFlight,
                onPin = onPin,
                onOpenInGallery = onOpenInGallery,
            )
        }
    }
}

@Composable
private fun BottomActions(
    isPinned: Boolean,
    isPinningInFlight: Boolean,
    onPin: () -> Unit,
    onOpenInGallery: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(PilgrimSpacing.normal),
        horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.normal),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = onPin,
            enabled = !isPinned && !isPinningInFlight,
            modifier = Modifier
                .weight(1f)
                .testTag("preview-sheet-pin-button"),
        ) {
            Icon(
                imageVector = Icons.Outlined.Bookmark,
                contentDescription = null,
            )
            Spacer(Modifier.height(PilgrimSpacing.xs))
            Text(
                text = stringResource(
                    if (isPinned) R.string.preview_sheet_pinned
                    else R.string.preview_sheet_pin,
                ),
            )
        }
        IconButton(
            onClick = onOpenInGallery,
            modifier = Modifier.testTag("preview-sheet-open-in-gallery"),
        ) {
            Icon(
                imageVector = Icons.Filled.OpenInNew,
                contentDescription = stringResource(R.string.preview_sheet_open_in_gallery),
            )
        }
    }
}

internal val DRAG_DISMISS_THRESHOLD_DP: Dp = 120.dp

/**
 * Helper used by callers to build the "Open in Gallery" intent. iOS
 * uses photos-redirect://; Android uses ACTION_VIEW with the photo's
 * content:// URI + explicit image/star MIME so OEM resolvers route to
 * the gallery rather than a file browser.
 */
internal fun buildOpenInGalleryIntent(contentUriString: String): Intent =
    Intent(Intent.ACTION_VIEW)
        .setDataAndType(contentUriString.toUri(), "image/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
