// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.data

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.ui.design.PilgrimDetailScaffold
import org.walktalkmeditate.pilgrim.ui.settings.SettingsDivider
import org.walktalkmeditate.pilgrim.ui.settings.settingsCard
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * Settings → Ways (iOS `WaysListView`, shared-walk spec S4 §3–§5). It
 * leaves on its own once a walk starts or one waits for its Honor step.
 */
@Composable
fun WaysListScreen(
    onBack: () -> Unit,
    viewModel: WaysListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val hidden by viewModel.hidden.collectAsStateWithLifecycle()
    val back by rememberUpdatedState(onBack)
    LaunchedEffect(hidden) {
        if (hidden) back()
    }
    PilgrimDetailScaffold(title = stringResource(R.string.settings_ways_title), onBack = onBack) { padding ->
        WaysListContent(
            state = state,
            onDelete = viewModel::delete,
            onDeleteAll = viewModel::deleteAll,
            modifier = Modifier.padding(padding),
        )
    }
}

/**
 * One grouped list, as iOS's plain `List`: "no ways yet" when empty, then
 * a row per Way (no tap, swipe to delete, unconfirmed), then "Delete all
 * Ways", which asks first with iOS's title, message, and buttons, then the
 * package footer while pilgrimage stages are on the phone.
 */
@Composable
fun WaysListContent(
    state: WaysListUiState,
    onDelete: (wayId: String) -> Unit,
    onDeleteAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val loaded = state as? WaysListUiState.Loaded ?: return
    val rows = loaded.rows
    val footer = WaysListModel.packageFooter(LocalResources.current, loaded.packageRouteName, loaded.packageStageCount)
    var confirmDeleteAll by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().settingsCard()) {
            if (rows.isEmpty()) {
                Text(
                    text = stringResource(R.string.settings_ways_empty),
                    style = pilgrimType.caption,
                    color = pilgrimColors.fog,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
            rows.forEachIndexed { index, row ->
                if (index > 0) SettingsDivider()
                SwipeToDeleteRow(row = row, onDelete = { onDelete(row.wayId) })
            }
            if (rows.isNotEmpty()) {
                SettingsDivider()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(role = Role.Button) { confirmDeleteAll = true },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = stringResource(R.string.settings_ways_delete_all),
                        style = pilgrimType.button,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            footer?.let {
                SettingsDivider()
                Text(
                    text = it,
                    style = pilgrimType.caption,
                    color = pilgrimColors.fog,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
        }
    }
    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text(stringResource(R.string.settings_ways_delete_all_title)) },
            text = { Text(stringResource(R.string.settings_ways_delete_all_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDeleteAll = false
                        onDeleteAll()
                    },
                ) {
                    Text(
                        text = stringResource(R.string.settings_ways_delete_all_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteAll = false }) {
                    Text(stringResource(R.string.settings_ways_delete_all_cancel))
                }
            },
        )
    }
}

/**
 * A row's one gesture: the trailing swipe, whose "Delete" a full swipe
 * also takes, offered to TalkBack as the row's action, as VoiceOver
 * offers `.onDelete`'s.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDeleteRow(row: WayListRow, onDelete: () -> Unit) {
    val delete by rememberUpdatedState(onDelete)
    val deleteLabel = stringResource(R.string.settings_ways_delete)
    val swipe = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) delete()
            false
        },
        positionalThreshold = { distance -> distance * 0.5f },
    )
    SwipeToDismissBox(
        state = swipe,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
        backgroundContent = { DeleteBackground(visible = swipe.dismissDirection == SwipeToDismissBoxValue.EndToStart) },
        modifier = Modifier.semantics {
            customActions = listOf(CustomAccessibilityAction(deleteLabel) { delete(); true })
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(pilgrimColors.parchmentSecondary)
                .padding(vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(text = row.title, style = pilgrimType.body, color = pilgrimColors.ink)
            Text(
                text = WaysListModel.detail(LocalResources.current, row),
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
            )
        }
    }
}

@Composable
private fun DeleteBackground(visible: Boolean) {
    val background = if (visible) MaterialTheme.colorScheme.error else Color.Transparent
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(background)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        if (visible) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = Icons.Filled.Delete, contentDescription = null, tint = Color.White)
                Spacer(Modifier.size(8.dp))
                Text(text = stringResource(R.string.settings_ways_delete), style = pilgrimType.caption, color = Color.White)
            }
        }
    }
}
