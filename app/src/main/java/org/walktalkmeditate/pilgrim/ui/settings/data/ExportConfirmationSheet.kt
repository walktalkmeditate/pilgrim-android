// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.data

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * iOS-faithful export confirmation. Shows summary + optional photo
 * toggle + Cancel/Export footer. Caller owns the visibility flag and
 * dismisses the sheet via `onDismissRequest` / `onCancel` / `onExport`.
 *
 * `hasCommitted` guard mirrors iOS — fast double-tap on Export
 * fires `onExport` once. The sheet's exit animation leaves the
 * Export button hit-testable for ~300ms.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportConfirmationSheet(
    walkCount: Int,
    dateRangeText: String,
    pinnedPhotoCount: Int,
    estimatedPhotoSizeBytes: Long,
    onDismissRequest: () -> Unit,
    onCancel: () -> Unit,
    onExport: (includePhotos: Boolean) -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
) {
    val showsPhotoToggle = pinnedPhotoCount > 0
    var includePhotos by rememberSaveable { mutableStateOf(true) }
    var hasCommitted by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = pilgrimColors.parchment,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Header()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 24.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    Summary(walkCount = walkCount, dateRangeText = dateRangeText)
                    if (showsPhotoToggle) {
                        PhotoToggle(
                            pinnedPhotoCount = pinnedPhotoCount,
                            estimatedPhotoSizeBytes = estimatedPhotoSizeBytes,
                            includePhotos = includePhotos,
                            onIncludePhotosChange = { includePhotos = it },
                        )
                    }
                }
            }
            ButtonBar(
                onCancel = onCancel,
                onExport = {
                    if (hasCommitted) return@ButtonBar
                    hasCommitted = true
                    onExport(effectiveIncludePhotos(pinnedPhotoCount, includePhotos))
                },
            )
        }
    }
}

/**
 * Resolves the `includePhotos` value passed back to the caller. When
 * the user has zero pinned photos, the toggle row is hidden and we
 * never pass `true` to the builder regardless of the [userToggle]
 * state — extracted for testability and to ensure the invariant
 * survives refactors.
 */
internal fun effectiveIncludePhotos(pinnedPhotoCount: Int, userToggle: Boolean): Boolean =
    pinnedPhotoCount > 0 && userToggle

/**
 * Returns "1 walk" / "2 walks" with correct plural. Static so it can
 * be unit tested. Uses `Locale.getDefault()` plurals — for English
 * "walks" / "walk"; other locales degrade gracefully (we don't ship
 * `<plurals>` at this resource yet — future stage if non-English
 * locales add walk-count plural diversity).
 */
internal fun walkCountText(walkCount: Int): String =
    if (walkCount == 1) "1 walk" else "$walkCount walks"

/**
 * Returns "18 photos · ≈1.4 MB" for typical case. Static for
 * unit testability. Decimal KB/MB matches iOS `ByteCountFormatter
 * .file` style.
 */
internal fun photoSizeText(photoCount: Int, bytes: Long): String {
    val noun = if (photoCount == 1) "photo" else "photos"
    val size = formatBytes(bytes)
    return "$photoCount $noun · ≈$size"
}

private fun formatBytes(bytes: Long): String {
    val kb = bytes / 1_000.0
    return if (kb < 1_000) {
        "%.0f KB".format(java.util.Locale.getDefault(), kb)
    } else {
        "%.1f MB".format(java.util.Locale.getDefault(), kb / 1_000)
    }
}

@Composable
private fun Header() {
    Text(
        text = stringResource(R.string.export_confirmation_title),
        style = pilgrimType.heading,
        color = pilgrimColors.ink,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp),
    )
}

@Composable
private fun Summary(walkCount: Int, dateRangeText: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = walkCountText(walkCount),
            style = pilgrimType.body,
            color = pilgrimColors.ink,
        )
        if (dateRangeText.isNotEmpty()) {
            Text(
                text = dateRangeText,
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
            )
        }
    }
}

@Composable
private fun PhotoToggle(
    pinnedPhotoCount: Int,
    estimatedPhotoSizeBytes: Long,
    includePhotos: Boolean,
    onIncludePhotosChange: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(R.string.export_confirmation_include_photos),
                    style = pilgrimType.body,
                    color = pilgrimColors.ink,
                )
                Text(
                    text = photoSizeText(pinnedPhotoCount, estimatedPhotoSizeBytes),
                    style = pilgrimType.caption,
                    color = pilgrimColors.fog,
                )
            }
            Switch(
                checked = includePhotos,
                onCheckedChange = onIncludePhotosChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = pilgrimColors.parchment,
                    checkedTrackColor = pilgrimColors.stone,
                    uncheckedThumbColor = pilgrimColors.fog,
                    uncheckedTrackColor = pilgrimColors.parchmentTertiary,
                ),
            )
        }
        Text(
            text = stringResource(R.string.export_confirmation_photos_caption),
            style = pilgrimType.caption,
            color = pilgrimColors.fog,
        )
    }
}

@Composable
private fun ButtonBar(onCancel: () -> Unit, onExport: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .clickable(onClick = onCancel)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.export_confirmation_cancel),
                style = pilgrimType.body,
                color = pilgrimColors.fog,
            )
        }
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(percent = 50))
                .background(pilgrimColors.stone)
                .clickable(onClick = onExport)
                .padding(horizontal = 24.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.export_confirmation_export),
                style = pilgrimType.button,
                color = pilgrimColors.parchment,
            )
        }
    }
}
