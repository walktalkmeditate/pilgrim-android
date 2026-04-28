// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.recordings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType
import org.walktalkmeditate.pilgrim.ui.walk.WalkFormat

/**
 * Top-level Recordings screen — iOS RecordingsListView parity.
 *
 * iOS reference: `pilgrim-ios/.../RecordingsListView.swift`.
 *
 * Layout:
 *  - TopAppBar: title "Recordings" + back arrow.
 *  - Search bar: [Icons.Filled.Search] OutlinedTextField with clear-X.
 *  - LazyColumn:
 *    - per-walk section header (date • "duration of recordings" •
 *      chevron) — tapping opens that walk's WalkSummary.
 *    - one swipe-to-dismiss row per recording, wrapping [RecordingRow].
 *      Swipe StartToEnd → retranscribe. Swipe EndToStart → confirmation
 *      dialog → delete file (transcription kept).
 *    - delete-all button at the very bottom.
 *  - Empty state: GraphicEq icon + "Your voice recordings will appear
 *    here", centered.
 *  - Search-no-match state: "No recordings match", centered (delete-all
 *    button hidden).
 *
 * The screen owns the dialog flag + pending-id for the per-row delete
 * confirmation. The VM owns playback / edit-mode / search state and the
 * underlying delete + retranscribe operations — see [RecordingsListViewModel].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingsListScreen(
    onBack: () -> Unit,
    onWalkClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RecordingsListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = pilgrimColors

    Scaffold(
        modifier = modifier,
        // The outer PilgrimNavHost Scaffold already consumed system bar
        // insets — pass WindowInsets(0) to avoid the double-counted
        // whitespace gap (same reason as VoiceGuidePickerScreen).
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.recordings_screen_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(
                                R.string.settings_back_content_description,
                            ),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.parchment,
                    titleContentColor = colors.ink,
                    navigationIconContentColor = colors.ink,
                ),
            )
        },
        containerColor = colors.parchment,
    ) { padding ->
        when (val s = state) {
            RecordingsListUiState.Loading -> {
                // Loading is observed for a single Compose frame at most
                // (the upstream `combine` emits as soon as Room's flows
                // publish). No spinner — a flash of empty parchment is
                // less jarring than a half-second indicator.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                )
            }
            is RecordingsListUiState.Loaded -> {
                LoadedContent(
                    state = s,
                    viewModel = viewModel,
                    onWalkClick = onWalkClick,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                )
            }
        }
    }
}

@Composable
private fun LoadedContent(
    state: RecordingsListUiState.Loaded,
    viewModel: RecordingsListViewModel,
    onWalkClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = pilgrimColors

    // Single-recording delete dialog.
    var pendingDeleteId by rememberSaveable { mutableStateOf<Long?>(null) }
    // Delete-all dialog.
    var showDeleteAllDialog by rememberSaveable { mutableStateOf(false) }

    Column(modifier = modifier) {
        SearchBar(
            query = state.searchQuery,
            onQueryChange = viewModel::onSearchChange,
        )

        when {
            state.searchQuery.isEmpty() && !state.hasAnyRecordings -> EmptyState(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 32.dp),
            )
            state.searchQuery.isNotEmpty() && state.visibleSections.isEmpty() -> NoMatchState(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 32.dp),
            )
            else -> RecordingsList(
                state = state,
                viewModel = viewModel,
                onWalkClick = onWalkClick,
                onRequestDelete = { pendingDeleteId = it },
                onRequestDeleteAll = { showDeleteAllDialog = true },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    pendingDeleteId?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = {
                Text(stringResource(R.string.recordings_dialog_delete_one_title))
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.onDeleteFile(id)
                    pendingDeleteId = null
                }) {
                    Text(
                        text = stringResource(R.string.recordings_dialog_delete_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) {
                    Text(stringResource(R.string.recordings_dialog_cancel))
                }
            },
            containerColor = colors.parchmentSecondary,
            titleContentColor = colors.ink,
        )
    }

    if (showDeleteAllDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAllDialog = false },
            title = {
                Text(stringResource(R.string.recordings_dialog_delete_all_title))
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.onDeleteAllFiles()
                    showDeleteAllDialog = false
                }) {
                    Text(
                        text = stringResource(R.string.recordings_dialog_delete_all_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAllDialog = false }) {
                    Text(stringResource(R.string.recordings_dialog_cancel))
                }
            },
            containerColor = colors.parchmentSecondary,
            titleContentColor = colors.ink,
        )
    }
}

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    val colors = pilgrimColors
    val clearDescription = stringResource(R.string.recordings_search_clear_description)
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        singleLine = true,
        placeholder = {
            Text(
                text = stringResource(R.string.recordings_search_prompt),
                style = pilgrimType.body,
                color = colors.fog,
            )
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = colors.fog,
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Filled.Clear,
                        contentDescription = clearDescription,
                        tint = colors.fog,
                    )
                }
            }
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = colors.parchmentSecondary,
            unfocusedContainerColor = colors.parchmentSecondary,
            disabledContainerColor = colors.parchmentSecondary,
            focusedBorderColor = Color.Transparent,
            unfocusedBorderColor = Color.Transparent,
            cursorColor = colors.stone,
            focusedTextColor = colors.ink,
            unfocusedTextColor = colors.ink,
        ),
        shape = RoundedCornerShape(12.dp),
    )
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    val colors = pilgrimColors
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.GraphicEq,
                contentDescription = null,
                tint = colors.fog,
                modifier = Modifier.size(64.dp),
            )
            Text(
                text = stringResource(R.string.recordings_empty_title),
                style = pilgrimType.body,
                color = colors.fog,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun NoMatchState(modifier: Modifier = Modifier) {
    val colors = pilgrimColors
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.recordings_search_no_match),
            style = pilgrimType.body,
            color = colors.fog,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun RecordingsList(
    state: RecordingsListUiState.Loaded,
    viewModel: RecordingsListViewModel,
    onWalkClick: (Long) -> Unit,
    onRequestDelete: (Long) -> Unit,
    onRequestDeleteAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (section in state.visibleSections) {
            item(key = "header-${section.walk.id}") {
                SectionHeader(
                    walk = section.walk,
                    totalDurationMillis = section.recordings.sumOf { it.durationMillis },
                    onClick = { onWalkClick(section.walk.id) },
                )
            }
            for ((index, recording) in section.recordings.withIndex()) {
                item(key = "recording-${recording.id}") {
                    SwipeableRecordingRow(
                        recording = recording,
                        indexInSection = index + 1,
                        state = state,
                        viewModel = viewModel,
                        onRequestDelete = onRequestDelete,
                    )
                }
            }
        }

        item(key = "delete-all") {
            AnimatedVisibility(
                visible = state.hasAnyRecordings,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                TextButton(
                    onClick = onRequestDeleteAll,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                ) {
                    Text(stringResource(R.string.recordings_action_delete_all))
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(
    walk: Walk,
    totalDurationMillis: Long,
    onClick: () -> Unit,
) {
    val colors = pilgrimColors
    val type = pilgrimType
    val chevronDescription = stringResource(R.string.recordings_section_header_chevron_description)

    val dateText = remember(walk.startTimestamp) { formatHeaderDate(walk.startTimestamp) }
    val subtitleText = stringResource(
        R.string.recordings_section_header_subtitle,
        WalkFormat.duration(totalDurationMillis),
    )

    // Wrap the whole header in a single clickable Row so the entire
    // strip (date + subtitle + chevron) is one tap target — matches
    // the iOS list-row affordance.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.parchmentSecondary)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = dateText,
                style = type.caption,
                color = colors.ink,
            )
            Text(
                text = subtitleText,
                style = type.caption,
                color = colors.fog,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = chevronDescription,
            tint = colors.fog,
        )
    }
}

/**
 * Wraps [RecordingRow] in a Material 3 [SwipeToDismissBox]. In BOM
 * 2026.03.01 the box delivers two callbacks:
 *  - `confirmValueChange` (on the state) decides whether the swipe is
 *    allowed to settle on a directional anchor.
 *  - the box itself calls `onDismiss(value)` once a swipe lands.
 *
 * For our case we never want the row to actually leave the list — the
 * action (retranscribe / show-delete-dialog) fires in `confirmValueChange`,
 * and we return `false` so the box rebounds to `Settled`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableRecordingRow(
    recording: org.walktalkmeditate.pilgrim.data.entity.VoiceRecording,
    indexInSection: Int,
    state: RecordingsListUiState.Loaded,
    viewModel: RecordingsListViewModel,
    onRequestDelete: (Long) -> Unit,
) {
    val colors = pilgrimColors
    val swipeState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    viewModel.onRetranscribe(recording.id)
                    false
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    onRequestDelete(recording.id)
                    false
                }
                SwipeToDismissBoxValue.Settled -> false
            }
        },
        // Half-anchor positional threshold — matches Material 3's
        // documented default and feels right for a 60-90 dp tall row.
        positionalThreshold = { distance -> distance * 0.5f },
    )

    SwipeToDismissBox(
        state = swipeState,
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            SwipeBackground(swipeState = swipeState.dismissDirection)
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.parchment)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            RecordingRow(
                recording = recording,
                indexInSection = indexInSection,
                fileSystem = viewModel.recordingFileSystem,
                waveformCache = viewModel.recordingWaveformCache,
                fileAvailable = state.fileExistenceById[recording.id] ?: false,
                isPlayingThisRow = state.playingRecordingId == recording.id,
                playbackPositionFraction = state.playbackPositionFraction,
                playbackSpeed = state.playbackSpeed,
                isEditing = state.editingRecordingId == recording.id,
                onPlay = viewModel::onPlay,
                onPause = viewModel::onPause,
                onSeek = viewModel::onSeek,
                onSpeedCycle = viewModel::onSpeedCycle,
                onStartEditing = viewModel::onStartEditing,
                onStopEditing = viewModel::onStopEditing,
                onTranscriptionEdit = viewModel::onTranscriptionEdit,
            )
        }
    }
}

@Composable
private fun SwipeBackground(swipeState: SwipeToDismissBoxValue) {
    val colors = pilgrimColors
    val type = pilgrimType
    val errorColor = MaterialTheme.colorScheme.error

    val (background, alignment, icon, label, foreground) = when (swipeState) {
        SwipeToDismissBoxValue.StartToEnd -> SwipeChrome(
            background = colors.stone,
            alignment = Alignment.CenterStart,
            icon = Icons.Filled.Refresh,
            label = stringResource(R.string.recordings_action_retranscribe),
            foreground = colors.parchment,
        )
        SwipeToDismissBoxValue.EndToStart -> SwipeChrome(
            background = errorColor,
            alignment = Alignment.CenterEnd,
            icon = Icons.Filled.Delete,
            label = stringResource(R.string.recordings_action_delete),
            foreground = Color.White,
        )
        SwipeToDismissBoxValue.Settled -> SwipeChrome(
            background = Color.Transparent,
            alignment = Alignment.Center,
            icon = null,
            label = null,
            foreground = Color.Transparent,
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(background)
            .padding(horizontal = 24.dp),
        contentAlignment = alignment,
    ) {
        if (icon != null && label != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = icon, contentDescription = null, tint = foreground)
                Spacer(Modifier.size(8.dp))
                Text(text = label, style = type.caption, color = foreground)
            }
        }
    }
}

private data class SwipeChrome(
    val background: Color,
    val alignment: Alignment,
    val icon: androidx.compose.ui.graphics.vector.ImageVector?,
    val label: String?,
    val foreground: Color,
)

private fun formatHeaderDate(epochMillis: Long): String {
    val instant = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault())
    // Locale.US for stable English month names + AM/PM in iOS-parity
    // tests. The home list's relative-date formatter uses the device
    // locale; recordings sections lean on Locale.US the way iOS pins
    // month-name display to the en_US format (matches the
    // `String(localized:)` pattern used in iOS RecordingsListView).
    return DateTimeFormatter.ofPattern("MMMM d, h:mm a", Locale.US).format(instant)
}

