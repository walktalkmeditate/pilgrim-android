// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.honor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * iOS `OwnWalkPicker` ("Walk again", `HonorWaysSheet.swift:161-206@7c200bf`,
 * F §5.2). Rows read their title then their distance; the empty copy shows
 * only once the list has loaded, so it never flashes.
 */
@Composable
fun OwnWalkPickerContent(
    state: OwnWalkPickerUiState,
    onPick: (walkId: Long) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    HonorSheetScaffold(
        title = stringResource(R.string.honor_picker_title),
        onClose = onClose,
        modifier = modifier,
        scrollable = false,
    ) {
        val rows = (state as? OwnWalkPickerUiState.Loaded)?.rows ?: return@HonorSheetScaffold
        if (rows.isEmpty()) {
            Text(
                text = stringResource(R.string.honor_picker_empty),
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
                modifier = Modifier.padding(PilgrimSpacing.small),
            )
            return@HonorSheetScaffold
        }
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            itemsIndexed(rows, key = { _, row -> row.walkId }) { index, row ->
                OwnWalkRow(
                    row = row,
                    isFirst = index == 0,
                    isLast = index == rows.lastIndex,
                    onClick = { onPick(row.walkId) },
                )
            }
        }
    }
}

@Composable
private fun OwnWalkRow(
    row: OwnWalkPickerRow,
    isFirst: Boolean,
    isLast: Boolean,
    onClick: () -> Unit,
) {
    HonorSheetGroupRow(isFirst = isFirst, isLast = isLast) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = PilgrimSpacing.normal, vertical = PilgrimSpacing.small),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(text = row.title, style = pilgrimType.body, color = pilgrimColors.ink)
            Text(text = row.distance, style = pilgrimType.caption, color = pilgrimColors.fog)
        }
    }
}

/** iOS's alert for a walk the builder can't use (`HonorWaysSheet.swift:100-105@7c200bf`). */
@Composable
fun UnwalkableAlert(onDismiss: () -> Unit) {
    HonorAlert(
        body = stringResource(R.string.honor_picker_unwalkable_body),
        onDismiss = onDismiss,
    )
}

/** "Can't walk this one again" over [body], with a lone "OK", the one voice for "you can't walk this". */
@Composable
fun HonorAlert(body: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.honor_ok), color = pilgrimColors.stone)
            }
        },
        title = { Text(stringResource(R.string.honor_cant_walk_title), style = pilgrimType.heading) },
        text = { Text(body, style = pilgrimType.body) },
        containerColor = pilgrimColors.parchment,
        titleContentColor = pilgrimColors.ink,
        textContentColor = pilgrimColors.ink,
    )
}

/**
 * The picker as a route. A walk that built a Way closes the sheet, then
 * [onOpenOverview] opens its overview; one that didn't raises the alert
 * over the picker, which stays up after OK.
 */
@Composable
fun OwnWalkPickerRoute(
    onClosed: () -> Unit,
    onOpenOverview: (sourceWalkId: Long) -> Unit,
    viewModel: OwnWalkPickerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val unwalkable by viewModel.showsUnwalkable.collectAsStateWithLifecycle()
    val openOverview by rememberUpdatedState(onOpenOverview)
    HonorSheetHost(onDismissed = onClosed) { hideThen ->
        LaunchedEffect(viewModel) {
            viewModel.picked.collect { walkId -> hideThen { openOverview(walkId) } }
        }
        OwnWalkPickerContent(
            state = state,
            onPick = viewModel::pick,
            onClose = { hideThen(onClosed) },
        )
        if (unwalkable) UnwalkableAlert(onDismiss = viewModel::dismissUnwalkable)
    }
}
