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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.share.ExpiryOption
import org.walktalkmeditate.pilgrim.data.share.ShareConfig
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType
import org.walktalkmeditate.pilgrim.ui.walk.PilgrimMap

/**
 * Stage 8-A: the Share Walk modal. Opens on "Share Journey" tap
 * from Walk Summary. Sections mirror iOS `WalkShareView`:
 * route preview → stat toggles → expiry picker → journal input →
 * waypoint opt-in → Share button. After successful share, flips to
 * a success layout showing the URL + Copy / Share / Done.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalkShareScreen(
    onDone: () -> Unit,
    viewModel: WalkShareViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val journal by viewModel.journal.collectAsStateWithLifecycle()
    val selectedExpiry by viewModel.selectedExpiry.collectAsStateWithLifecycle()
    val includeDistance by viewModel.includeDistance.collectAsStateWithLifecycle()
    val includeDuration by viewModel.includeDuration.collectAsStateWithLifecycle()
    val includeElevation by viewModel.includeElevation.collectAsStateWithLifecycle()
    val includeActivity by viewModel.includeActivityBreakdown.collectAsStateWithLifecycle()
    val includeSteps by viewModel.includeSteps.collectAsStateWithLifecycle()
    val includeWaypoints by viewModel.includeWaypoints.collectAsStateWithLifecycle()
    val isSharing by viewModel.isSharing.collectAsStateWithLifecycle()
    val canShare by viewModel.canShare.collectAsStateWithLifecycle()
    val cached by viewModel.cachedShare.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val errNetwork = stringResource(R.string.share_modal_error_network)
    val errRateLimited = stringResource(R.string.share_modal_error_rate_limited)
    val errUnknown = stringResource(R.string.share_modal_error_unknown)

    LaunchedEffect(viewModel) {
        viewModel.events.collect { ev ->
            when (ev) {
                is WalkShareEvent.Success -> {
                    // CachedShareStore emission drives the UI into
                    // the "Shared" layout reactively; nothing else to
                    // do here (snackbar would be redundant).
                }
                WalkShareEvent.RateLimited -> snackbarHostState.showSnackbar(errRateLimited)
                is WalkShareEvent.Failed -> snackbarHostState.showSnackbar(
                    ev.message.ifBlank { errUnknown }.ifBlank { errNetwork },
                )
            }
        }
    }

    // Snapshot `cached` once per composition so the downstream
    // reads see a consistent value — a second delegated-property
    // read can observe a fresh DataStore emission (e.g.
    // `clear(walkUuid)` from some future expiry-sweeper) and
    // transition non-null → null between the `isShared` check and
    // the `activeShare!!` unwrap, producing a NullPointerException.
    val activeShare = cached?.takeIf { !it.isExpiredAt() }
    val isShared = activeShare != null

    Scaffold(
        // Stage 9.5-A: outer PilgrimNavHost Scaffold already consumed
        // system bar insets; pass WindowInsets(0) to avoid double-counting.
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
        topBar = {
            WalkShareTopBar(
                isShared = isShared,
                canShare = canShare,
                isSharing = isSharing,
                onCancel = onDone,
                onDone = onDone,
                onShare = viewModel::share,
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        containerColor = pilgrimColors.parchment,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(PilgrimSpacing.normal),
            verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.big),
        ) {
            when (val s = state) {
                WalkShareUiState.Loading -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                WalkShareUiState.NotFound -> Text(
                    text = stringResource(R.string.share_modal_not_found),
                    style = pilgrimType.body,
                    color = pilgrimColors.fog,
                )
                is WalkShareUiState.Loaded -> {
                    if (activeShare != null) {
                        SharedLayout(
                            points = s.inputs.routePoints.map {
                                org.walktalkmeditate.pilgrim.domain.LocationPoint(
                                    timestamp = it.timestamp,
                                    latitude = it.latitude,
                                    longitude = it.longitude,
                                )
                            },
                            expiryEpochMs = activeShare.expiryEpochMs,
                            onOpenScroll = {
                                org.walktalkmeditate.pilgrim.ui.util.CustomTabs.launch(
                                    context,
                                    android.net.Uri.parse(activeShare.url),
                                )
                            },
                        )
                    } else {
                        RoutePreview(points = s.inputs.routePoints.map {
                            org.walktalkmeditate.pilgrim.domain.LocationPoint(
                                timestamp = it.timestamp,
                                latitude = it.latitude,
                                longitude = it.longitude,
                            )
                        })
                        StatToggles(
                            distance = includeDistance,
                            duration = includeDuration,
                            elevation = includeElevation,
                            activity = includeActivity,
                            steps = includeSteps,
                            onDistance = viewModel::toggleDistance,
                            onDuration = viewModel::toggleDuration,
                            onElevation = viewModel::toggleElevation,
                            onActivity = viewModel::toggleActivityBreakdown,
                            onSteps = viewModel::toggleSteps,
                        )
                        JournalInput(
                            journal = journal,
                            onJournalChange = viewModel::updateJournal,
                        )
                        ExpiryPicker(
                            selected = selectedExpiry,
                            onSelect = viewModel::updateExpiry,
                        )
                        val waypointCount = s.inputs.waypoints.size
                        if (waypointCount > 0) {
                            WaypointToggle(
                                on = includeWaypoints,
                                count = waypointCount,
                                onToggle = viewModel::toggleWaypoints,
                            )
                        }
                        if (!canShare && !isSharing) {
                            Text(
                                text = stringResource(R.string.share_modal_toggle_at_least_one),
                                style = pilgrimType.caption,
                                color = pilgrimColors.fog,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoutePreview(points: List<org.walktalkmeditate.pilgrim.domain.LocationPoint>) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp),
        colors = CardDefaults.cardColors(containerColor = pilgrimColors.parchmentSecondary),
    ) {
        if (points.size >= 2) {
            PilgrimMap(points = points, followLatest = false, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun StatToggles(
    distance: Boolean,
    duration: Boolean,
    elevation: Boolean,
    activity: Boolean,
    steps: Boolean,
    onDistance: (Boolean) -> Unit,
    onDuration: (Boolean) -> Unit,
    onElevation: (Boolean) -> Unit,
    onActivity: (Boolean) -> Unit,
    onSteps: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.small)) {
        Text(
            text = stringResource(R.string.share_modal_stats_header),
            style = pilgrimType.caption,
            color = pilgrimColors.fog,
        )
        ToggleRow(stringResource(R.string.share_modal_stat_distance), distance, onDistance)
        ToggleRow(stringResource(R.string.share_modal_stat_duration), duration, onDuration)
        ToggleRow(stringResource(R.string.share_modal_stat_elevation), elevation, onElevation)
        ToggleRow(stringResource(R.string.share_modal_stat_activity), activity, onActivity)
        ToggleRow(stringResource(R.string.share_modal_stat_steps), steps, onSteps)
    }
}

@Composable
private fun ToggleRow(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!on) }
            .padding(vertical = PilgrimSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = pilgrimType.body, color = pilgrimColors.ink)
        Switch(checked = on, onCheckedChange = onChange)
    }
}

@Composable
private fun JournalInput(journal: String, onJournalChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.xs)) {
        Text(
            text = stringResource(R.string.share_modal_journal_header),
            style = pilgrimType.caption,
            color = pilgrimColors.fog,
        )
        OutlinedTextField(
            value = journal,
            onValueChange = onJournalChange,
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            placeholder = {
                Text(
                    text = stringResource(R.string.share_modal_journal_placeholder),
                    style = pilgrimType.body,
                )
            },
        )
        Text(
            text = stringResource(
                R.string.share_modal_journal_counter,
                journal.length,
                ShareConfig.JOURNAL_MAX_LEN,
            ),
            style = pilgrimType.caption,
            color = pilgrimColors.fog,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExpiryPicker(selected: ExpiryOption, onSelect: (ExpiryOption) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.small)) {
        Text(
            text = stringResource(R.string.share_modal_expiry_header),
            style = pilgrimType.caption,
            color = pilgrimColors.fog,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
            modifier = Modifier.fillMaxWidth(),
        ) {
            ExpiryOption.entries.forEach { option ->
                FilterChip(
                    selected = selected == option,
                    onClick = { onSelect(option) },
                    label = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(option.kanji, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(PilgrimSpacing.xs))
                            Text(option.label)
                        }
                    },
                    colors = FilterChipDefaults.filterChipColors(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun WaypointToggle(on: Boolean, count: Int, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(!on) }
            .padding(vertical = PilgrimSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(R.string.share_modal_include_waypoints, count),
            style = pilgrimType.body,
            color = pilgrimColors.ink,
        )
        Switch(checked = on, onCheckedChange = onToggle)
    }
}

/**
 * Share Walk modal top bar. Truly-centered title with a leading Cancel
 * (pre-share) and a trailing pill action — "Share Walk" while composing
 * (the primary share trigger, per the user's request to move the action
 * to the top-right) or "Done" once shared. Mirrors iOS
 * `WalkShareView.swift:53-71` (centered principal title, leading Cancel
 * when !shared, trailing Done when shared) and reuses the repo
 * [org.walktalkmeditate.pilgrim.ui.walk.summary.WalkSummaryTopBar]
 * Box-centered + parchmentTertiary pill pattern.
 *
 * No [windowInsetsPadding]: the screen sits inside the PilgrimNavHost
 * Scaffold which already consumes system-bar insets.
 */
@Composable
private fun WalkShareTopBar(
    isShared: Boolean,
    canShare: Boolean,
    isSharing: Boolean,
    onCancel: () -> Unit,
    onDone: () -> Unit,
    onShare: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(pilgrimColors.parchment)
            .height(64.dp)
            .padding(horizontal = PilgrimSpacing.normal),
    ) {
        Text(
            text = stringResource(
                if (isShared) R.string.share_modal_shared_title
                else R.string.share_modal_title,
            ),
            style = pilgrimType.heading,
            color = pilgrimColors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 72.dp),
        )
        if (!isShared) {
            TextButton(
                onClick = onCancel,
                modifier = Modifier.align(Alignment.CenterStart),
            ) {
                Text(
                    text = stringResource(R.string.share_modal_cancel),
                    style = pilgrimType.button,
                    color = pilgrimColors.stone,
                )
            }
        }
        Button(
            onClick = if (isShared) onDone else onShare,
            enabled = isShared || (canShare && !isSharing),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = pilgrimColors.parchmentTertiary,
                contentColor = pilgrimColors.stone,
            ),
            contentPadding = PaddingValues(
                horizontal = PilgrimSpacing.normal,
                vertical = PilgrimSpacing.small,
            ),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            if (!isShared && isSharing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = pilgrimColors.stone,
                )
            } else {
                Text(
                    text = stringResource(
                        if (isShared) R.string.share_modal_done
                        else R.string.share_modal_title,
                    ),
                    style = pilgrimType.button,
                )
            }
        }
    }
}

/**
 * Post-share success state. Mirrors iOS `WalkShareView.swift:329-375`:
 * a single `parchmentSecondary` rounded card containing a tappable
 * route-preview thumbnail, a centered "Shared ✓" row, an italic
 * "Returns to the trail on {date}" caption, and a full-width plain
 * "View scroll" button. Both the thumbnail and "View scroll" open the
 * in-app scroll preview (Custom Tab). The actual re-share trigger is
 * the top-bar button (iOS keeps Cancel→Done in the toolbar).
 */
@Composable
private fun SharedLayout(
    points: List<org.walktalkmeditate.pilgrim.domain.LocationPoint>,
    expiryEpochMs: Long,
    onOpenScroll: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = pilgrimColors.parchmentSecondary),
    ) {
        Column(
            modifier = Modifier.padding(PilgrimSpacing.normal),
            verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.normal),
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clickable(onClick = onOpenScroll),
                colors = CardDefaults.cardColors(containerColor = pilgrimColors.parchment),
            ) {
                if (points.size >= 2) {
                    PilgrimMap(points = points, followLatest = false, modifier = Modifier.fillMaxSize())
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.share_modal_shared),
                    style = pilgrimType.body,
                    color = pilgrimColors.stone,
                )
                Spacer(Modifier.width(PilgrimSpacing.xs))
                Icon(
                    Icons.Outlined.Check,
                    contentDescription = null,
                    tint = pilgrimColors.moss,
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(
                text = stringResource(
                    R.string.share_journey_returns_on,
                    formatExpiryDate(expiryEpochMs),
                ),
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
                fontStyle = FontStyle.Italic,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            TextButton(
                onClick = onOpenScroll,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.share_modal_view_scroll),
                    style = pilgrimType.caption,
                    color = pilgrimColors.fog,
                )
            }
        }
    }
}

