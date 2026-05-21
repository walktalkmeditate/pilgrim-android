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
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing

/**
 * iOS-parity horizontal photo carousel for the Walk Summary reliquary.
 * Mirrors `PhotoCarouselView.swift @v1.6.0` — shows both pinned AND
 * unpinned candidates; user pins / unpins via long-press → tap.
 *
 * Activation state machine (per [PhotoThumbnail] kdoc):
 *  - Long-press 400ms on a thumbnail → activated (1.05× spring scale,
 *    centered pin/unpin button overlay)
 *  - User-drag of the carousel → clears activation
 *  - Programmatic scroll (`scrollToItem`) → activation persists
 *  - Tap on activated thumbnail's centered button → [onTogglePin]
 *  - Tap on activated thumbnail outside the button → dismiss
 *    activation + fire [onPreview]
 *  - Tap on inactive thumbnail → fire [onPreview] directly
 */
@Composable
internal fun PhotoCarousel(
    candidates: List<PhotoCandidate>,
    onTogglePin: (PhotoCandidate) -> Unit,
    onPreview: (PhotoCandidate) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    var activatedKey by remember { mutableStateOf<String?>(null) }
    val haptic = LocalHapticFeedback.current

    // Touch-drag detection (NOT programmatic scroll).
    val isDragged by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(isDragged) {
        if (isDragged) activatedKey = null
    }

    LazyRow(
        modifier = modifier.testTag("photo-carousel"),
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.xs),
        contentPadding = PaddingValues(horizontal = PilgrimSpacing.normal),
    ) {
        items(items = candidates, key = { it.uri }) { candidate ->
            PhotoThumbnail(
                candidate = candidate,
                isActivated = activatedKey == candidate.uri,
                onLongPress = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    activatedKey = candidate.uri
                },
                onPinTap = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    activatedKey = null
                    onTogglePin(candidate)
                },
                onPhotoTap = {
                    activatedKey = null
                    onPreview(candidate)
                },
            )
        }
    }
}
