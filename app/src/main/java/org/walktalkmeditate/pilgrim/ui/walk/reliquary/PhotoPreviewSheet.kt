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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors

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
    candidate: PhotoCandidate,
    isPinningInFlight: Boolean,
    onPin: () -> Unit,
    onOpenInGallery: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Resolve theme colors HERE, in the parent composition, before
    // entering the Dialog. `LocalPilgrimColors` is a
    // `compositionLocalOf` whose default is the LIGHT palette; a
    // Compose `Dialog` hosts its content in a separate composition
    // that does not reliably inherit the appearance-mode override,
    // so reading pilgrimColors inside the dialog rendered the
    // pin/gallery icons with the light-mode ink (≈black) even in
    // dark mode. Capturing the resolved values out here and passing
    // them down keeps the pills theme-correct in both modes.
    val pillContent = pilgrimColors.ink
    val pillBackground = pilgrimColors.parchment.copy(alpha = 0.85f)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        PhotoPreviewSheetContent(
            candidate = candidate,
            isPinningInFlight = isPinningInFlight,
            pillContent = pillContent,
            pillBackground = pillBackground,
            onPin = onPin,
            onOpenInGallery = onOpenInGallery,
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun PhotoPreviewSheetContent(
    candidate: PhotoCandidate,
    isPinningInFlight: Boolean,
    pillContent: Color,
    pillBackground: Color,
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
            .pointerInput(candidate.uri) {
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
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = candidate.uri,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("preview-sheet-image"),
            )
            // iOS parity: capsule pills overlaid at the TOP — pin
            // top-left, open-in-gallery top-right.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .systemBarsPadding()
                    .padding(PilgrimSpacing.normal),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CapsulePill(
                    icon = if (candidate.isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                    label = stringResource(
                        if (candidate.isPinned) R.string.preview_sheet_unpin
                        else R.string.preview_sheet_pin,
                    ),
                    enabled = !isPinningInFlight,
                    contentColor = pillContent,
                    backgroundColor = pillBackground,
                    onClick = {
                        // iOS parity: commit the toggle then dismiss
                        // immediately. The captured candidate is a
                        // snapshot; the parent's togglePin reads the
                        // canonical pinned state before persisting.
                        onPin()
                        onDismiss()
                    },
                    modifier = Modifier.testTag("preview-sheet-pin-button"),
                )
                CapsulePill(
                    icon = Icons.Filled.Photo,
                    label = stringResource(R.string.preview_sheet_open_in_gallery),
                    enabled = true,
                    contentColor = pillContent,
                    backgroundColor = pillBackground,
                    onClick = onOpenInGallery,
                    modifier = Modifier.testTag("preview-sheet-open-in-gallery"),
                )
            }
        }
    }
}

/**
 * iOS-parity capsule action pill: rounded translucent parchment
 * background, ink-colored icon + label. Matches the
 * `.regularMaterial` + `.ink` Label capsules in
 * `PhotoPreviewSheet.swift`. Colors are theme-aware (ink + parchment
 * flip with appearance mode) — the prior implementation tinted the
 * icon with the default onSurface color, which rendered black in
 * both modes.
 */
@Composable
private fun CapsulePill(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    contentColor: Color,
    backgroundColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(backgroundColor)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = PilgrimSpacing.normal, vertical = PilgrimSpacing.small),
        horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.height(18.dp),
        )
        Text(text = label, color = contentColor)
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
