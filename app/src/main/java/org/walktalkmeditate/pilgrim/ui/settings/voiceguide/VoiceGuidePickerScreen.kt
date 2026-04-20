// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.voiceguide

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.voiceguide.VoiceGuidePackState
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceGuidePickerScreen(
    onBack: () -> Unit,
    onOpenPack: (String) -> Unit,
    viewModel: VoiceGuidePickerViewModel = hiltViewModel(),
) {
    val packs by viewModel.packStates.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.voice_guide_picker_title)) },
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
        VoiceGuidePickerContent(
            packs = packs,
            onOpenPack = onOpenPack,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        )
    }
}

@Composable
internal fun VoiceGuidePickerContent(
    packs: List<VoiceGuidePackState>,
    onOpenPack: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (packs.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.voice_guide_picker_empty),
                color = pilgrimColors.fog,
                modifier = Modifier.padding(32.dp),
            )
        }
        return
    }

    LazyColumn(modifier = modifier) {
        items(
            items = packs,
            key = { it.pack.id },
        ) { state ->
            VoiceGuidePackRow(state = state, onClick = { onOpenPack(state.pack.id) })
            HorizontalDivider(color = pilgrimColors.fog.copy(alpha = 0.25f))
        }
    }
}

@Composable
private fun VoiceGuidePackRow(
    state: VoiceGuidePackState,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(state.pack.name, color = pilgrimColors.ink)
                if (state.isSelected) {
                    Spacer(Modifier.size(8.dp))
                    Text(
                        stringResource(R.string.voice_guide_status_selected),
                        color = pilgrimColors.moss,
                    )
                }
            }
        },
        supportingContent = {
            Column {
                Text(state.pack.tagline, color = pilgrimColors.fog)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = statusLine(state),
                    color = statusColor(state),
                )
            }
        },
        trailingContent = { TrailingState(state) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    )
}

@Composable
private fun TrailingState(state: VoiceGuidePackState) {
    when (state) {
        is VoiceGuidePackState.NotDownloaded -> Icon(
            Icons.Default.CloudDownload,
            contentDescription = stringResource(R.string.voice_guide_status_not_downloaded),
            tint = pilgrimColors.fog,
        )
        is VoiceGuidePackState.Downloading -> Box(
            modifier = Modifier.size(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                progress = { state.fraction },
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
                color = pilgrimColors.moss,
            )
        }
        is VoiceGuidePackState.Downloaded -> if (state.isSelected) {
            Icon(Icons.Default.Check, contentDescription = null, tint = pilgrimColors.moss)
        } else {
            Spacer(Modifier.size(24.dp))
        }
        is VoiceGuidePackState.Failed -> Icon(
            Icons.Default.ErrorOutline,
            contentDescription = stringResource(R.string.voice_guide_status_failed),
            tint = pilgrimColors.rust,
        )
    }
}

@Composable
private fun statusLine(state: VoiceGuidePackState): String = when (state) {
    is VoiceGuidePackState.NotDownloaded ->
        stringResource(R.string.voice_guide_status_not_downloaded)
    is VoiceGuidePackState.Downloading ->
        stringResource(
            R.string.voice_guide_status_downloading,
            state.completed,
            state.total,
        )
    is VoiceGuidePackState.Downloaded ->
        stringResource(R.string.voice_guide_status_downloaded)
    is VoiceGuidePackState.Failed ->
        stringResource(R.string.voice_guide_status_failed)
}

@Composable
private fun statusColor(state: VoiceGuidePackState): Color = when (state) {
    is VoiceGuidePackState.Failed -> pilgrimColors.rust
    is VoiceGuidePackState.Downloaded -> pilgrimColors.moss
    else -> pilgrimColors.fog
}
