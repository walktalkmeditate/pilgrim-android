// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.honor

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import coil3.request.ImageRequest
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors

/** iOS requests an own-walk photo at 900 × 900, aspect-fit (`WayPlaceCard.swift:310-317@7c200bf`). */
private const val PLATE_REQUEST_SIZE_PX = 900

/**
 * iOS `WayPhotoPlate` (`WayPlaceCard.swift:274-325@7c200bf`, parity spec E
 * §13): the photo fit to the width up to [maxHeight], 4 dp corners, in a
 * 6 dp parchment mat. While it loads, or when it never does (a photo since
 * deleted, or a shared photo not on this phone: a null [photoUri]), a
 * plain parchment block stands in, not tappable and silent to TalkBack. A
 * tap opens [WayPhotoViewer].
 */
@Composable
fun WayPhotoPlate(
    photoUri: String?,
    maxHeight: Dp,
    modifier: Modifier = Modifier,
) {
    if (photoUri == null) {
        Box(modifier = modifier.fillMaxWidth()) { WayPhotoPlaceholder(maxHeight) }
        return
    }
    var enlarged by rememberSaveable(photoUri) { mutableStateOf(false) }
    val request = ImageRequest.Builder(LocalContext.current)
        .data(photoUri)
        .size(PLATE_REQUEST_SIZE_PX)
        .build()
    SubcomposeAsyncImage(
        model = request,
        contentDescription = null,
        modifier = modifier.fillMaxWidth(),
        loading = { WayPhotoPlaceholder(maxHeight) },
        error = { WayPhotoPlaceholder(maxHeight) },
        success = {
            LoadedWayPhotoPlate(maxHeight = maxHeight, onEnlarge = { enlarged = true }) {
                SubcomposeAsyncImageContent(contentScale = ContentScale.Fit)
            }
        },
    )
    if (enlarged) {
        WayPhotoViewer(photoUri = photoUri, onClose = { enlarged = false })
    }
}

@Composable
internal fun LoadedWayPhotoPlate(
    maxHeight: Dp,
    onEnlarge: () -> Unit,
    image: @Composable () -> Unit,
) {
    val enlarge = stringResource(R.string.honor_moment_enlarge_photo)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(pilgrimColors.parchment)
            .clickable(role = Role.Button, onClickLabel = null, onClick = onEnlarge)
            .semantics { contentDescription = enlarge }
            .padding(6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .clip(RoundedCornerShape(4.dp)),
        ) {
            image()
        }
    }
}

@Composable
private fun WayPhotoPlaceholder(maxHeight: Dp) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(minOf(maxHeight, 120.dp))
            .clip(RoundedCornerShape(6.dp))
            .background(pilgrimColors.parchment),
    )
}

/**
 * iOS `WayPhotoViewer` (`WayPhotoViewer.swift:3-55@7c200bf`): the photo on
 * black, three ways out (the close button, a tap, a swipe down past 100 dp
 * at 1×), pinch from 1× to 4×, a tap while zoomed only zooming back. Both
 * the image and the button are "Close photo".
 */
@Composable
fun WayPhotoViewer(photoUri: String, onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        WayPhotoViewerContent(onClose = onClose) {
            AsyncImage(
                model = photoUri,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
internal fun WayPhotoViewerContent(onClose: () -> Unit, image: @Composable () -> Unit) {
    val close = stringResource(R.string.honor_moment_close_photo)
    val density = LocalDensity.current
    var scale by remember { mutableFloatStateOf(1f) }
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val settledOffset by animateFloatAsState(if (dragging) dragOffsetPx else 0f, label = "photo-drag")
    val closeThresholdPx = with(density) { VIEWER_CLOSE_DRAG.toPx() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationY = if (dragging) dragOffsetPx else settledOffset
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var gestureZoom = 1f
                        do {
                            val event = awaitPointerEvent()
                            if (event.changes.size > 1) {
                                gestureZoom *= event.calculateZoom()
                                scale = gestureZoom.coerceIn(1f, 4f)
                            }
                        } while (event.changes.any { it.pressed })
                        if (scale < 1.05f) scale = 1f
                    }
                }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { if (scale == 1f) dragging = true },
                        onDragEnd = {
                            if (scale == 1f && dragOffsetPx > closeThresholdPx) onClose()
                            dragging = false
                            dragOffsetPx = 0f
                        },
                        onDragCancel = {
                            dragging = false
                            dragOffsetPx = 0f
                        },
                        onVerticalDrag = { _, amount ->
                            if (dragging) dragOffsetPx = (dragOffsetPx + amount).coerceAtLeast(0f)
                        },
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { if (scale > 1f) scale = 1f else onClose() })
                }
                .semantics {
                    contentDescription = close
                    role = Role.Button
                    onClick { onClose(); true }
                },
        ) {
            image()
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .systemBarsPadding()
                .sizeIn(minWidth = 44.dp, minHeight = 44.dp)
                .clickable(role = Role.Button, onClick = onClose)
                .semantics { contentDescription = close }
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Cancel,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

private val VIEWER_CLOSE_DRAG = 100.dp
