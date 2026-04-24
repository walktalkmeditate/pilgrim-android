// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.share

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.LocalActivity
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
    val activity = LocalActivity.current
    val errNetwork = stringResource(R.string.share_modal_error_network)
    val errRateLimited = stringResource(R.string.share_modal_error_rate_limited)
    val errUnknown = stringResource(R.string.share_modal_error_unknown)
    val chooserTitle = stringResource(R.string.share_modal_chooser_title)
    val copiedToast = stringResource(R.string.share_journey_copied)

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

    val isShared = cached != null && cached?.isExpiredAt() == false

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(
                            if (isShared) R.string.share_modal_shared_title
                            else R.string.share_modal_title,
                        ),
                        style = pilgrimType.heading,
                    )
                },
                navigationIcon = {
                    if (!isShared) {
                        TextButton(onClick = onDone) {
                            Text(stringResource(R.string.share_modal_cancel))
                        }
                    }
                },
                actions = {
                    if (isShared) {
                        TextButton(onClick = onDone) {
                            Text(stringResource(R.string.share_modal_done))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = pilgrimColors.parchment,
                ),
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
                    if (isShared) {
                        SharedLayout(
                            url = cached!!.url,
                            onCopy = { copyUrl(context, it, copiedToast) },
                            onShare = { launchShareChooser(activity ?: context, it, chooserTitle) },
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
                        ShareButton(
                            enabled = canShare,
                            isSharing = isSharing,
                            onShare = viewModel::share,
                        )
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

@Composable
private fun ShareButton(enabled: Boolean, isSharing: Boolean, onShare: () -> Unit) {
    Button(
        onClick = onShare,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (isSharing) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
            )
        } else {
            Text(stringResource(R.string.share_modal_share_button))
        }
    }
}

@Composable
private fun SharedLayout(
    url: String,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.normal),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(R.string.share_modal_success_label),
            style = pilgrimType.body,
            color = pilgrimColors.ink,
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = pilgrimColors.parchmentSecondary),
        ) {
            Text(
                text = url,
                style = pilgrimType.caption,
                color = pilgrimColors.stone,
                maxLines = 2,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.padding(PilgrimSpacing.normal),
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedButton(onClick = { onCopy(url) }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(PilgrimSpacing.small))
                Text(stringResource(R.string.share_journey_copy))
            }
            Button(onClick = { onShare(url) }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.IosShare, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(PilgrimSpacing.small))
                Text(stringResource(R.string.share_journey_share))
            }
        }
    }
}

internal fun copyUrl(context: Context, url: String, toast: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("Pilgrim walk", url))
    Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
}

internal fun launchShareChooser(context: Context, url: String, chooserTitle: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, url)
    }
    try {
        context.startActivity(Intent.createChooser(send, chooserTitle))
    } catch (_: android.content.ActivityNotFoundException) {
        // Edge-case devices with no share chooser — swallow silently;
        // the user can still use Copy.
    }
}
