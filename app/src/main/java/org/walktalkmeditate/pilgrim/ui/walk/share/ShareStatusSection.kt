// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.share

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimCornerRadius
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

// ---- Magic values the parity spec explicitly marks as untokenized -------
// (docs/parity/2026-08-15-walk-share-interactive-port.md §3, cited per use).

/** UI-54: fixed thumbnail height, shared verbatim between the pre-share preview and the post-share card. */
private val ROUTE_THUMBNAIL_HEIGHT = 200.dp

/** UI-53/UI-42: the primary-button and progress-row vertical padding — the SAME magic 14dp at two different call sites, neither a Constants token. */
private val CHROME_V_PADDING = 14.dp

/** UI-39/UI-45/UI-51: the secondary/plain-button vertical padding, recurring at three call sites (retry, Don't-share-yet, View-scroll) without ever being named on iOS. */
private val PLAIN_BUTTON_V_PADDING = 12.dp

/** UI-42: `Color.stone.opacity(0.6)` — far from any `PilgrimOpacity` token; an easy transcription slip (e.g. mistaking it for 0.06) would make the chip nearly invisible. */
private const val PROGRESS_BACKGROUND_ALPHA = 0.6f

/** UI-47: the decorative chevron's fog opacity — distinct from any PilgrimOpacity tier. */
private const val CHEVRON_ALPHA = 0.4f

/** UI-49: `HStack(spacing: 6)` for the "Shared ✓" badge row — not [PilgrimSpacing.xs] (4). */
private val SHARED_BADGE_SPACING = 6.dp

/**
 * The share flow's full progress/result state. Ports iOS `WalkShareViewModel.ShareState`
 * (`WalkShareViewModel.swift:110-119@3f9f9e8`, spec UI-33/UI-66) one-to-one —
 * all 8 cases, including the 5 Android cannot yet reach until U8 wires
 * WAV→AAC prep + media PUTs (only [Idle]/[Uploading]/[Success] are reachable
 * pre-U8; the rest compile against fakes per this unit's brief).
 */
@Immutable
internal sealed interface ShareCardState {
    data object Idle : ShareCardState
    data class PreparingPhotos(val completed: Int, val total: Int) : ShareCardState
    data class PhotosDropped(val prepared: Int, val dropped: Int) : ShareCardState
    data object Uploading : ShareCardState
    data class UploadingMedia(val completed: Int, val total: Int) : ShareCardState
    data class Success(val url: String) : ShareCardState
    data class Partial(val url: String, val failedCount: Int) : ShareCardState
    data class Error(val message: String) : ShareCardState
}

/**
 * The Share button through all its states — idle, in-flight progress, the
 * success/partial result card, and error retry. iOS parity:
 * `ShareStatusSection.swift@3f9f9e8`. [canShare] and [repairUnavailable] are
 * separate parameters rather than fields on [ShareCardState] because iOS
 * reads them the same way — as sibling `@Published` VM properties the
 * `switch` consults independently of which case is active (`viewModel.canShare`,
 * `viewModel.repairUnavailable`) — not as payload carried by the state cases
 * themselves (`WalkShareViewModel.swift:44-45,73@3f9f9e8`).
 */
@Composable
internal fun ShareStatusSection(
    state: ShareCardState,
    canShare: Boolean,
    repairUnavailable: Boolean,
    expiryText: String,
    routePoints: List<LocationPoint>,
    onShare: () -> Unit,
    onOpenPreview: (String) -> Unit,
    onRetryMissingFiles: () -> Unit,
    onShareWithoutDroppedPhotos: () -> Unit,
    onCancelDroppedPhotoShare: () -> Unit,
) {
    when (state) {
        ShareCardState.Idle -> PrimaryButton(
            text = stringResource(R.string.share_modal_share_button),
            enabled = canShare,
            onClick = onShare,
            testTag = "share-status-idle-button",
        )

        ShareCardState.Uploading -> ProgressRow(
            text = stringResource(R.string.share_modal_sharing),
            textStyle = pilgrimType.button,
        )

        is ShareCardState.PreparingPhotos -> ProgressRow(
            text = stringResource(R.string.share_status_preparing_photos, state.completed, state.total),
        )

        is ShareCardState.PhotosDropped -> DroppedPhotosPrompt(
            prepared = state.prepared,
            dropped = state.dropped,
            onShareWithoutThem = onShareWithoutDroppedPhotos,
            onDontShareYet = onCancelDroppedPhotoShare,
        )

        is ShareCardState.UploadingMedia -> ProgressRow(
            text = stringResource(R.string.share_status_uploading_media, state.completed, state.total),
            subtitle = stringResource(R.string.share_status_uploading_media_subtitle),
        )

        is ShareCardState.Success -> SharedCard(
            url = state.url,
            expiryText = expiryText,
            routePoints = routePoints,
            onOpenPreview = onOpenPreview,
        )

        is ShareCardState.Partial -> SharedCard(
            url = state.url,
            expiryText = expiryText,
            routePoints = routePoints,
            onOpenPreview = onOpenPreview,
        ) {
            PartialFailureBlock(
                failedCount = state.failedCount,
                repairUnavailable = repairUnavailable,
                onRetry = onRetryMissingFiles,
            )
        }

        is ShareCardState.Error -> Column(
            verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = state.message,
                style = pilgrimType.caption,
                color = pilgrimColors.rust,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            PrimaryButton(
                text = stringResource(R.string.share_status_try_again),
                enabled = canShare,
                onClick = onShare,
                testTag = "share-status-error-retry-button",
            )
        }
    }
}

/** UI-53: the Share Walk / Try Again chrome — dims via the SAME disabled-container convention as the pre-existing `ShareActionButton` this section replaces. */
@Composable
private fun PrimaryButton(text: String, enabled: Boolean, onClick: () -> Unit, testTag: String) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(PilgrimCornerRadius.normal),
        colors = ButtonDefaults.buttonColors(
            containerColor = pilgrimColors.stone,
            contentColor = pilgrimColors.parchment,
            disabledContainerColor = pilgrimColors.stone.copy(alpha = PROGRESS_BACKGROUND_ALPHA),
            disabledContentColor = pilgrimColors.parchment,
        ),
        contentPadding = PaddingValues(vertical = CHROME_V_PADDING),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
    ) {
        Text(text = text, style = pilgrimType.button)
    }
}

/**
 * UI-41/UI-42: shared by `.uploading`, `.preparingPhotos`, and
 * `.uploadingMedia` — same spinner-row chrome, different label/font.
 * [subtitle] is only ever supplied for `.uploadingMedia`'s "keep Pilgrim
 * open..." reminder (UI-37).
 */
@Composable
private fun ProgressRow(
    text: String,
    subtitle: String? = null,
    textStyle: TextStyle = pilgrimType.caption,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PilgrimCornerRadius.normal))
            .background(pilgrimColors.stone.copy(alpha = PROGRESS_BACKGROUND_ALPHA))
            .padding(vertical = CHROME_V_PADDING)
            .testTag("share-status-progress-row"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = pilgrimColors.parchment)
            Text(text = text, style = textStyle, color = pilgrimColors.parchment)
        }
        if (subtitle != null) {
            Text(text = subtitle, style = pilgrimType.caption, color = pilgrimColors.fog)
        }
    }
}

/** UI-43/UI-44/UI-45: the pre-POST dropped-photos consent pause — an inline card-state substitution, NOT a dialog/sheet. */
@Composable
private fun DroppedPhotosPrompt(prepared: Int, dropped: Int, onShareWithoutThem: () -> Unit, onDontShareYet: () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("share-status-photos-dropped"),
    ) {
        Text(
            text = pluralStringResource(R.plurals.share_status_photos_dropped, dropped, dropped, prepared + dropped),
            style = pilgrimType.caption,
            color = pilgrimColors.fog,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        PrimaryButton(
            text = stringResource(R.string.share_status_share_without_them),
            enabled = true,
            onClick = onShareWithoutThem,
            testTag = "share-status-without-them-button",
        )
        TextButton(
            onClick = onDontShareYet,
            contentPadding = PaddingValues(vertical = PLAIN_BUTTON_V_PADDING),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("share-status-dont-share-yet-button"),
        ) {
            Text(text = stringResource(R.string.share_status_dont_share_yet), style = pilgrimType.caption, color = pilgrimColors.fog)
        }
    }
}

/**
 * UI-38: the extra failed-files block inserted into [SharedCard] for
 * `.partial` — failedCount pluralization always shown in rust, then EITHER
 * the static repair-unavailable explanation OR the retry button, never both.
 */
@Composable
private fun PartialFailureBlock(failedCount: Int, repairUnavailable: Boolean, onRetry: () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("share-status-partial-failure-block"),
    ) {
        Text(
            text = pluralStringResource(R.plurals.share_status_partial_failed_count, failedCount, failedCount),
            style = pilgrimType.caption,
            color = pilgrimColors.rust,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        if (repairUnavailable) {
            Text(
                text = stringResource(R.string.share_status_repair_unavailable),
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("share-status-repair-unavailable-text"),
            )
        } else {
            Button(
                onClick = onRetry,
                shape = RoundedCornerShape(PilgrimCornerRadius.small),
                colors = ButtonDefaults.buttonColors(containerColor = pilgrimColors.stone, contentColor = pilgrimColors.parchment),
                contentPadding = PaddingValues(vertical = PLAIN_BUTTON_V_PADDING),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("share-status-retry-button"),
            ) {
                Text(text = stringResource(R.string.share_status_retry_button), style = pilgrimType.button)
            }
        }
    }
}

/**
 * UI-46/UI-52: the success/partial container — thumbnail+chevron, Shared
 * badge, expiry note, [extra] slot, View scroll link. [extra] is empty for
 * `.success` and the failed-files block for `.partial` — everything else is
 * byte-for-byte identical between the two states.
 */
@Composable
private fun SharedCard(
    url: String,
    expiryText: String,
    routePoints: List<LocationPoint>,
    onOpenPreview: (String) -> Unit,
    extra: @Composable () -> Unit = {},
) {
    val viewPageLabel = stringResource(R.string.share_status_view_page_a11y_label)
    val viewPageHint = stringResource(R.string.share_status_view_page_a11y_hint)
    Column(
        verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.normal),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PilgrimCornerRadius.normal))
            .background(pilgrimColors.parchmentSecondary)
            .padding(PilgrimSpacing.normal)
            .testTag("share-status-shared-card"),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenPreview(url) }
                .semantics {
                    contentDescription = viewPageLabel
                    onClick(label = viewPageHint) { onOpenPreview(url); true }
                }
                .testTag("share-status-open-preview"),
        ) {
            ShareRouteThumbnail(points = routePoints)
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = pilgrimColors.fog,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .alpha(CHEVRON_ALPHA)
                    .semantics { invisibleToUser() },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = stringResource(R.string.share_modal_shared), style = pilgrimType.body, color = pilgrimColors.stone)
            Spacer(Modifier.width(SHARED_BADGE_SPACING))
            Icon(Icons.Outlined.Check, contentDescription = null, tint = pilgrimColors.moss, modifier = Modifier.size(14.dp))
        }

        Text(
            text = stringResource(R.string.share_journey_returns_on, expiryText),
            style = pilgrimType.caption,
            color = pilgrimColors.fog,
            fontStyle = FontStyle.Italic,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        extra()

        TextButton(
            onClick = { onOpenPreview(url) },
            contentPadding = PaddingValues(vertical = PLAIN_BUTTON_V_PADDING),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("share-status-view-scroll-button"),
        ) {
            Text(text = stringResource(R.string.share_modal_view_scroll), style = pilgrimType.caption, color = pilgrimColors.fog)
        }
    }
}

/**
 * UI-54: the small route-shape preview shown both pre-share ([WalkShareScreen])
 * and post-share (this file's [SharedCard]) — one composable owns the
 * dimension so the two call sites can't drift apart independently.
 */
@Composable
internal fun ShareRouteThumbnail(points: List<LocationPoint>) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROUTE_THUMBNAIL_HEIGHT)
            .clip(RoundedCornerShape(PilgrimCornerRadius.normal))
            .background(pilgrimColors.parchmentSecondary),
    ) {
        RouteShapeView(points = points)
    }
}
