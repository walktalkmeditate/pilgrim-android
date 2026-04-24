// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.reliquary

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import org.walktalkmeditate.pilgrim.data.entity.WalkPhoto
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimCornerRadius
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * Stage 7-A: Walk Summary's photo reliquary. A non-lazy 3-column grid
 * of pinned photos below a header row with an "Add" affordance.
 *
 * Uses the Android Photo Picker (`PickMultipleVisualMedia`) — no runtime
 * permission needed because the system picker IS the consent moment.
 * Long-press a tile → confirmation dialog → unpin. TalkBack users get
 * the same action via a `customActions` semantic on each tile (the
 * `pointerInput` gesture alone is invisible to the accessibility tree,
 * per Stage 6-B's lesson).
 */
@Composable
fun PhotoReliquarySection(
    photos: List<WalkPhoto>,
    onPinPhotos: (List<Uri>) -> Unit,
    onUnpinPhoto: (WalkPhoto) -> Unit,
    modifier: Modifier = Modifier,
) {
    val slots = (MAX_PINS_PER_WALK - photos.size).coerceAtLeast(0)
    // Reserve state for the tile awaiting a remove-confirmation. Null
    // means no dialog is showing. Reset to null on both confirm and
    // dismiss paths so a tile whose pin was already removed by another
    // surface (future: deep-link, widget) doesn't leave a dead dialog.
    var photoToRemove by remember { mutableStateOf<WalkPhoto?>(null) }

    // Branch on slots at launch time:
    //  - 0 slots: button is disabled; never launch anything.
    //  - 1 slot: PickMultipleVisualMedia requires maxItems > 1, so use
    //    the single-pick contract.
    //  - 2..N slots: multi-pick clamped to `slots`.
    val multiLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(
            maxItems = slots.coerceAtLeast(2),
        ),
    ) { uris -> if (uris.isNotEmpty()) onPinPhotos(uris) }
    val singleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> if (uri != null) onPinPhotos(listOf(uri)) }

    val haptics = LocalHapticFeedback.current

    Column(modifier = modifier.fillMaxWidth()) {
        ReliquaryHeader(
            slotsAvailable = slots,
            onAddClick = {
                val request = PickVisualMediaRequest(
                    ActivityResultContracts.PickVisualMedia.ImageOnly,
                )
                when (slots) {
                    0 -> Unit
                    1 -> singleLauncher.launch(request)
                    else -> multiLauncher.launch(request)
                }
            },
        )

        if (photos.isNotEmpty()) {
            Spacer(Modifier.height(PilgrimSpacing.small))
            PhotoGrid(
                photos = photos,
                onLongPressTile = { photo ->
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    photoToRemove = photo
                },
            )
        }
    }

    val target = photoToRemove
    if (target != null) {
        UnpinConfirmationDialog(
            onConfirm = {
                onUnpinPhoto(target)
                photoToRemove = null
            },
            onDismiss = { photoToRemove = null },
        )
    }
}

@Composable
private fun ReliquaryHeader(
    slotsAvailable: Int,
    onAddClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "Reliquary",
            style = pilgrimType.heading,
            color = pilgrimColors.ink,
        )
        OutlinedButton(
            enabled = slotsAvailable > 0,
            onClick = onAddClick,
        ) {
            Icon(
                imageVector = Icons.Default.AddAPhoto,
                contentDescription = null,
            )
            Spacer(Modifier.width(PilgrimSpacing.xs))
            Text(if (slotsAvailable > 0) "Add" else "Full")
        }
    }
}

@Composable
private fun PhotoGrid(
    photos: List<WalkPhoto>,
    onLongPressTile: (WalkPhoto) -> Unit,
) {
    // Non-lazy chunked grid: Walk Summary already hosts a verticalScroll
    // so a LazyVerticalGrid here would crash at measure time. With a
    // 20-photo cap the cost is negligible — 7 rows max.
    val cols = 3
    val rows = remember(photos) { photos.chunked(cols) }
    Column(verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.xs)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.xs)) {
                row.forEach { photo ->
                    PhotoTile(
                        photo = photo,
                        onLongPress = { onLongPressTile(photo) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // Pad trailing cells so partial rows keep column widths.
                repeat(cols - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun PhotoTile(
    photo: WalkPhoto,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Stable key for the gesture so the composable re-uses the pointer
    // input handler when a different photo slides into this slot. The
    // semantics action wraps the same onLongPress so TalkBack users
    // reach parity with touch users (pointerInput is invisible to the
    // accessibility tree — Stage 6-B lesson).
    //
    // `contentDescription` lives on this outer Box rather than on
    // SubcomposeAsyncImage because Coil only stamps the description
    // into the semantics tree once the image loads — during the
    // loading / error sub-compositions the description is absent,
    // which breaks both accessibility and the UI tests that rely on
    // it. Owning the description here keeps the semantics stable
    // across image load states.
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(PilgrimCornerRadius.small))
            .background(pilgrimColors.parchmentSecondary)
            .pointerInput(photo.id) {
                detectTapGestures(onLongPress = { onLongPress() })
            }
            .semantics {
                contentDescription = "Photo from this walk"
                customActions = listOf(
                    CustomAccessibilityAction(label = "Remove from walk") {
                        onLongPress()
                        true
                    },
                )
            },
    ) {
        SubcomposeAsyncImage(
            model = Uri.parse(photo.photoUri),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            error = { ReliquaryErrorTile() },
            loading = { /* transparent; parchment background shows through */ },
        )
    }
}

@Composable
private fun ReliquaryErrorTile() {
    // Minimal fallback when a pinned photo is no longer readable
    // (user deleted from library, unmounted SD card, SAF grant
    // expired on a restart). Shows a muted broken-image glyph on the
    // existing tile background. A true tombstone UX with an "unpin
    // broken" action lives in Stage 7-B.
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.BrokenImage,
            contentDescription = null,
            tint = pilgrimColors.fog,
        )
    }
}

@Composable
private fun UnpinConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove from walk?") },
        text = {
            Text("This photo will be unpinned. Your photo library isn't changed.")
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Remove") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Keep") }
        },
    )
}
