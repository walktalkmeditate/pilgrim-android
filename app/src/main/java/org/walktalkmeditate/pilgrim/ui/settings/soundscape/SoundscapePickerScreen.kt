// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.soundscape

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.soundscape.SoundscapeState
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoundscapePickerScreen(
    onBack: () -> Unit,
    viewModel: SoundscapePickerViewModel = hiltViewModel(),
) {
    val soundscapes by viewModel.soundscapeStates.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.soundscape_picker_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = stringResource(
                                R.string.settings_back_content_description,
                            ),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = pilgrimColors.parchment,
                    titleContentColor = pilgrimColors.ink,
                    navigationIconContentColor = pilgrimColors.ink,
                ),
            )
        },
        containerColor = pilgrimColors.parchment,
    ) { padding ->
        SoundscapePickerContent(
            soundscapes = soundscapes,
            onRowTap = viewModel::onRowTap,
            onRowDelete = viewModel::onRowDelete,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SoundscapePickerContent(
    soundscapes: List<SoundscapeState>,
    onRowTap: (SoundscapeState) -> Unit,
    onRowDelete: (SoundscapeState) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (soundscapes.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.soundscape_picker_empty),
                color = pilgrimColors.fog,
                modifier = Modifier.padding(32.dp),
            )
        }
        return
    }

    var pendingDelete by remember { mutableStateOf<SoundscapeState?>(null) }

    LazyColumn(modifier = modifier) {
        items(
            items = soundscapes,
            key = { it.asset.id },
        ) { state ->
            SoundscapeRow(
                state = state,
                onClick = { onRowTap(state) },
                onLongPress = { pendingDelete = state },
            )
            HorizontalDivider(color = pilgrimColors.fog.copy(alpha = 0.25f))
        }
    }

    val toDelete = pendingDelete
    if (toDelete != null && (
        toDelete is SoundscapeState.Downloaded ||
        toDelete is SoundscapeState.Failed
    )) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.soundscape_delete_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.soundscape_delete_message,
                        toDelete.asset.displayName,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRowDelete(toDelete)
                    pendingDelete = null
                }) {
                    Text(stringResource(R.string.soundscape_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.soundscape_delete_cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SoundscapeRow(
    state: SoundscapeState,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(state.asset.displayName, color = pilgrimColors.ink)
                if (state is SoundscapeState.Downloaded && state.isSelected) {
                    Spacer(Modifier.size(8.dp))
                    Text(
                        stringResource(R.string.soundscape_status_selected),
                        color = pilgrimColors.moss,
                    )
                }
            }
        },
        supportingContent = {
            Text(
                text = statusLine(state),
                color = statusColor(state),
            )
        },
        trailingContent = { TrailingIcon(state) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongPress),
    )
}

@Composable
private fun TrailingIcon(state: SoundscapeState) {
    when (state) {
        is SoundscapeState.NotDownloaded -> Icon(
            Icons.Default.CloudDownload,
            contentDescription = stringResource(R.string.soundscape_status_not_downloaded),
            tint = pilgrimColors.fog,
        )
        is SoundscapeState.Downloading -> Box(
            modifier = Modifier.size(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
                color = pilgrimColors.moss,
            )
        }
        is SoundscapeState.Downloaded -> if (state.isSelected) {
            Icon(Icons.Default.Check, contentDescription = null, tint = pilgrimColors.moss)
        } else {
            Spacer(Modifier.size(24.dp))
        }
        is SoundscapeState.Failed -> Icon(
            Icons.Default.ErrorOutline,
            contentDescription = stringResource(R.string.soundscape_status_failed),
            tint = pilgrimColors.rust,
        )
    }
}

@Composable
private fun statusLine(state: SoundscapeState): String = when (state) {
    is SoundscapeState.NotDownloaded ->
        stringResource(R.string.soundscape_status_not_downloaded)
    is SoundscapeState.Downloading ->
        stringResource(R.string.soundscape_status_downloading)
    is SoundscapeState.Downloaded ->
        stringResource(R.string.soundscape_status_downloaded)
    is SoundscapeState.Failed ->
        stringResource(R.string.soundscape_status_failed)
}

@Composable
private fun statusColor(state: SoundscapeState): Color = when (state) {
    is SoundscapeState.Failed -> pilgrimColors.rust
    is SoundscapeState.Downloaded -> pilgrimColors.moss
    else -> pilgrimColors.fog
}
