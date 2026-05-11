// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.reliquary

import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import org.walktalkmeditate.pilgrim.data.entity.WalkPhoto
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing

/**
 * iOS-parity horizontal photo carousel for the Walk Summary reliquary.
 * Replaces the prior 3-column grid (Stage 7-A). Tap an activated
 * thumbnail → opens the PhotoPreviewSheet (Stage 4).
 *
 * Activation state machine:
 *  - Long-press 400ms on a thumbnail → activated (1.05× spring scale)
 *  - User-drag of the carousel → clears activation
 *  - Programmatic scroll (`scrollToItem`) → activation persists
 *  - Tap on an activated thumbnail → commit (fires onThumbnailCommit)
 */
@Composable
internal fun PhotoCarousel(
    photos: List<WalkPhoto>,
    pinnedIds: Set<Long>,
    onThumbnailCommit: (WalkPhoto) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    var activatedId by remember { mutableStateOf<Long?>(null) }
    val haptic = LocalHapticFeedback.current

    // Touch-drag detection (NOT programmatic scroll) — see
    // `collectIsDraggedAsState` contract: emits true ONLY while the
    // user's finger is on the list and dragging.
    val isDragged by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(isDragged) {
        if (isDragged) activatedId = null
    }

    LazyRow(
        modifier = modifier.testTag("photo-carousel"),
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.xs),
        contentPadding = PaddingValues(horizontal = PilgrimSpacing.normal),
    ) {
        items(items = photos, key = { it.id }) { photo ->
            PhotoThumbnail(
                photo = photo,
                isPinned = photo.id in pinnedIds,
                isActivated = activatedId == photo.id,
                onLongPress = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    activatedId = photo.id
                },
                onTap = {
                    if (activatedId == photo.id) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onThumbnailCommit(photo)
                        activatedId = null
                    }
                },
            )
        }
    }
}
