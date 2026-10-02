// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.honor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.ui.settings.SettingNavRow
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimCornerRadius
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * iOS `HonorWaysSheet` ("Choose a way", `HonorWaysSheet.swift:23-92@7c200bf`,
 * parity spec F §4). iOS's four sections, in order:
 *  1. "Shared with you": the accepted shared Ways (their rows are U28's;
 *     until then the section shows iOS's empty copy, as it does for any
 *     walker with no accepted shares);
 *  2. "Your own walks": one nav row into the "Walk again" picker;
 *  3. "A pilgrimage" (Stage 21-2) and
 *  4. "From a shared walk" (the paste field, U28) are not shown yet.
 */
@Composable
fun HonorWaysSheetContent(
    onClose: () -> Unit,
    onWalkOneOfYours: () -> Unit,
    modifier: Modifier = Modifier,
) {
    HonorSheetScaffold(
        title = stringResource(R.string.honor_ways_title),
        onClose = onClose,
        modifier = modifier,
    ) {
        HonorSheetSection(header = stringResource(R.string.honor_ways_shared_header)) {
            Text(
                text = stringResource(R.string.honor_ways_shared_empty),
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
                modifier = Modifier.padding(vertical = PilgrimSpacing.small),
            )
        }
        HonorSheetSection(header = stringResource(R.string.honor_ways_own_header)) {
            // iOS's Button with no label or hint of its own (F §17.2).
            SettingNavRow(
                label = stringResource(R.string.honor_ways_own_row),
                onClick = onWalkOneOfYours,
                modifier = Modifier.fillMaxWidth(),
                role = Role.Button,
                onClickLabel = null,
            )
        }
    }
}

/**
 * The chrome both Honor sheets share: iOS's inline navigation bar, "Close"
 * leading in stone and the title centred in heading ink, over sections.
 */
@Composable
internal fun HonorSheetScaffold(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(pilgrimColors.parchment)
            .navigationBarsPadding(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .padding(horizontal = PilgrimSpacing.small),
        ) {
            TextButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterStart)) {
                Text(
                    text = stringResource(R.string.honor_close),
                    style = pilgrimType.button,
                    color = pilgrimColors.stone,
                )
            }
            Text(
                text = title,
                style = pilgrimType.heading,
                color = pilgrimColors.ink,
                modifier = Modifier
                    .align(Alignment.Center)
                    .semantics { heading() },
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(horizontal = PilgrimSpacing.normal)
                .padding(bottom = PilgrimSpacing.big),
            verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.normal),
            content = content,
        )
    }
}

/** A grouped-list section: its caption header over one rounded group. */
@Composable
internal fun HonorSheetSection(
    header: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.xs)) {
        Text(
            text = header,
            style = pilgrimType.caption,
            color = pilgrimColors.fog,
            modifier = Modifier
                .padding(horizontal = PilgrimSpacing.small)
                .semantics { heading() },
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(PilgrimCornerRadius.normal))
                .background(pilgrimColors.parchmentSecondary)
                .padding(horizontal = PilgrimSpacing.normal, vertical = PilgrimSpacing.xs),
            content = content,
        )
    }
}

/**
 * A full-height sheet with no drag handle, as iOS's large-detent sheets
 * show none. [content] gets a `hideThen` that slides the sheet down
 * before running its action, so the sheet is gone before whatever opens
 * next; a swipe or Back closes it through [onDismissed].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HonorSheetHost(
    onDismissed: () -> Unit,
    content: @Composable (hideThen: (() -> Unit) -> Unit) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val dismissed by rememberUpdatedState(onDismissed)
    val hideThen: (() -> Unit) -> Unit = remember(sheetState, scope) {
        { action ->
            scope.launch { sheetState.hide() }.invokeOnCompletion {
                if (!sheetState.isVisible) action()
            }
        }
    }
    ModalBottomSheet(
        onDismissRequest = { dismissed() },
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = PilgrimCornerRadius.big, topEnd = PilgrimCornerRadius.big),
        dragHandle = null,
        containerColor = pilgrimColors.parchment,
    ) {
        content(hideThen)
    }
}

/** The Ways sheet as a route: "Walk one of yours again" leaves the sheet for the picker. */
@Composable
fun HonorWaysSheetRoute(
    onClosed: () -> Unit,
    onOpenOwnWalks: () -> Unit,
) {
    HonorSheetHost(onDismissed = onClosed) { hideThen ->
        HonorWaysSheetContent(
            onClose = { hideThen(onClosed) },
            onWalkOneOfYours = { hideThen(onOpenOwnWalks) },
        )
    }
}
