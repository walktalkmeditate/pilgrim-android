// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.voiceguide

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.voiceguide.VoiceGuidePackState
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceGuidePackDetailScreen(
    onBack: () -> Unit,
    viewModel: VoiceGuidePackDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        // Stage 9.5-A: outer PilgrimNavHost Scaffold already consumed
        // system bar insets; pass WindowInsets(0) to avoid double-counting.
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.voice_guide_detail_title)) },
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
        VoiceGuidePackDetailContent(
            uiState = uiState,
            onDownload = viewModel::download,
            onCancel = viewModel::cancel,
            onRetry = viewModel::retry,
            onSelect = viewModel::select,
            onDeselect = viewModel::deselect,
            onDelete = viewModel::delete,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        )
    }
}

@Composable
internal fun VoiceGuidePackDetailContent(
    uiState: VoiceGuidePackDetailViewModel.UiState,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onSelect: () -> Unit,
    onDeselect: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (uiState) {
        is VoiceGuidePackDetailViewModel.UiState.Loading -> Box(
            modifier = modifier,
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(32.dp),
                color = pilgrimColors.stone,
            )
        }
        is VoiceGuidePackDetailViewModel.UiState.NotFound -> Box(
            modifier = modifier,
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(R.string.voice_guide_detail_not_found),
                color = pilgrimColors.fog,
                modifier = Modifier.padding(32.dp),
            )
        }
        is VoiceGuidePackDetailViewModel.UiState.Loaded -> LoadedPack(
            state = uiState.state,
            onDownload = onDownload,
            onCancel = onCancel,
            onRetry = onRetry,
            onSelect = onSelect,
            onDeselect = onDeselect,
            onDelete = onDelete,
            modifier = modifier,
        )
    }
}

@Composable
private fun LoadedPack(
    state: VoiceGuidePackState,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onSelect: () -> Unit,
    onDeselect: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pack = state.pack
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(pack.name, style = pilgrimType.displayMedium, color = pilgrimColors.ink)
        if (pack.tagline.isNotBlank()) {
            Text(pack.tagline, color = pilgrimColors.fog)
        }

        if (state is VoiceGuidePackState.Downloading) {
            LinearProgressIndicator(
                progress = { state.fraction },
                modifier = Modifier.fillMaxWidth(),
                color = pilgrimColors.moss,
            )
            Text(
                stringResource(
                    R.string.voice_guide_status_downloading,
                    state.completed,
                    state.total,
                ),
                color = pilgrimColors.fog,
            )
        }

        Spacer(Modifier.height(8.dp))
        LabelValue(
            label = stringResource(R.string.voice_guide_detail_description_label),
            value = pack.description,
        )
        LabelValue(
            label = stringResource(R.string.voice_guide_detail_duration_label),
            value = formatDuration(pack.totalDurationSec),
        )
        LabelValue(
            label = stringResource(R.string.voice_guide_detail_size_label),
            value = formatSize(pack.totalSizeBytes),
        )
        if (pack.hasMeditationGuide) {
            Text(
                stringResource(R.string.voice_guide_detail_meditation_label),
                color = pilgrimColors.moss,
                fontWeight = FontWeight.Medium,
            )
        }

        Spacer(Modifier.height(16.dp))
        PrimaryAction(
            state = state,
            onDownload = onDownload,
            onCancel = onCancel,
            onRetry = onRetry,
            onSelect = onSelect,
            onDeselect = onDeselect,
        )
        if (state is VoiceGuidePackState.Downloaded) {
            OutlinedButton(
                onClick = onDelete,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.voice_guide_detail_action_delete))
            }
        }
    }
}

@Composable
private fun PrimaryAction(
    state: VoiceGuidePackState,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onSelect: () -> Unit,
    onDeselect: () -> Unit,
) {
    when (state) {
        is VoiceGuidePackState.NotDownloaded -> Button(
            onClick = onDownload,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.voice_guide_detail_action_download))
        }
        is VoiceGuidePackState.Downloading -> OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.voice_guide_detail_action_cancel))
        }
        is VoiceGuidePackState.Downloaded -> if (state.isSelected) {
            OutlinedButton(
                onClick = onDeselect,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.voice_guide_detail_action_deselect))
            }
        } else {
            Button(
                onClick = onSelect,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.voice_guide_detail_action_select))
            }
        }
        is VoiceGuidePackState.Failed -> Button(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.voice_guide_detail_action_retry))
        }
    }
}

@Composable
private fun LabelValue(label: String, value: String) {
    if (value.isBlank()) return
    Column {
        Text(
            label,
            color = pilgrimColors.fog,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(2.dp))
        Text(value, color = pilgrimColors.ink)
    }
}

@Composable
private fun formatDuration(totalSec: Double): String {
    val minutes = (totalSec / 60.0).toInt()
    return stringResource(R.string.voice_guide_duration_minutes_format, minutes)
}

@Composable
private fun formatSize(bytes: Long): String {
    val mb = bytes.toDouble() / (1024.0 * 1024.0)
    // Locale.US for dot-decimal (user-facing numeric formatting rule
    // from Stage 5-A memory — default-locale `%f` would emit commas on
    // some locales).
    return String.format(Locale.US, "%.1f MB", mb)
}
