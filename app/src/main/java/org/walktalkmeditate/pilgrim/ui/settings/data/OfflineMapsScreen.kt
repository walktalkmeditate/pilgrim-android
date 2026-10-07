// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.data

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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
 * Settings → Maps (iOS `OfflineMapsView`, spec D C4 §3.3). It leaves on its
 * own once a walk starts or one waits for its Honor step.
 */
@Composable
fun OfflineMapsScreen(
    onBack: () -> Unit,
    viewModel: OfflineMapsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val hidden by viewModel.hidden.collectAsStateWithLifecycle()
    val confirmingDelete by viewModel.confirmingDelete.collectAsStateWithLifecycle()
    val back by rememberUpdatedState(onBack)
    LaunchedEffect(hidden) {
        if (hidden) back()
    }
    PilgrimDetailScaffold(title = stringResource(R.string.settings_maps_title), onBack = onBack) { padding ->
        OfflineMapsContent(
            state = state,
            confirmingDelete = confirmingDelete,
            onDelete = viewModel::onDeleteTapped,
            onConfirmDelete = viewModel::onDeleteConfirmed,
            onCancelDelete = viewModel::onDeleteCancelled,
            modifier = Modifier.padding(padding),
        )
    }
}

/**
 * One grouped list, as the Ways list draws iOS's: nothing while loading;
 * "no maps saved" when empty, with no Delete and no way to a save; or the
 * route over "N MB · S of T stages", then "Delete maps", which asks first
 * with iOS's title, message and buttons.
 */
@Composable
fun OfflineMapsContent(
    state: OfflineMapsUiState,
    confirmingDelete: Boolean,
    onDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    onCancelDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        OfflineMapsUiState.Loading -> Unit
        OfflineMapsUiState.Empty -> MapsCard(modifier) {
            Text(
                text = stringResource(R.string.settings_maps_empty),
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }
        is OfflineMapsUiState.Saved -> MapsCard(modifier) { SavedRows(state, onDelete) }
    }
    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = onCancelDelete,
            title = { Text(stringResource(R.string.settings_maps_delete_title)) },
            text = { Text(stringResource(R.string.settings_maps_delete_message)) },
            confirmButton = {
                TextButton(onClick = onConfirmDelete) {
                    Text(
                        text = stringResource(R.string.settings_maps_delete_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onCancelDelete) {
                    Text(stringResource(R.string.settings_maps_delete_cancel))
                }
            },
        )
    }
}

@Composable
private fun MapsCard(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().settingsCard(), content = content)
    }
}

@Composable
private fun SavedRows(saved: OfflineMapsUiState.Saved, onDelete: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(text = saved.routeName, style = pilgrimType.body, color = pilgrimColors.ink)
        Text(
            text = OfflineMapsModel.savedLine(LocalResources.current, saved),
            style = pilgrimType.caption,
            color = pilgrimColors.fog,
        )
    }
    SettingsDivider()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onDelete),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = stringResource(R.string.settings_maps_delete),
            style = pilgrimType.button,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
