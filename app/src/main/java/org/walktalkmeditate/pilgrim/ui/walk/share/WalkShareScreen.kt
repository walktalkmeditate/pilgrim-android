// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.share

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.util.Locale
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.share.ExpiryOption
import org.walktalkmeditate.pilgrim.data.share.ShareConfig
import org.walktalkmeditate.pilgrim.data.share.ShareInputs
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimCornerRadius
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1_000L
private val ROW_VERTICAL_PADDING = 10.dp
private val ROUTE_PREVIEW_HEIGHT = 200.dp

/**
 * The Share Walk modal. Opens on "Share Journey" tap from Walk
 * Summary. Sections mirror iOS `WalkShareView@v1.6.0`: route shape
 * preview → stat toggles → reflection → expiry picker → bottom Share
 * button. The share action lives at the BOTTOM of the form (iOS
 * parity) — the top bar carries only Cancel (pre-share) / Done (once
 * shared). After a successful share the body flips to [SharedLayout].
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
    val includePhotos by viewModel.includePhotos.collectAsStateWithLifecycle()
    val isSharing by viewModel.isSharing.collectAsStateWithLifecycle()
    val canShare by viewModel.canShare.collectAsStateWithLifecycle()
    val cached by viewModel.cachedShare.collectAsStateWithLifecycle()
    val units by viewModel.distanceUnits.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val errNetwork = stringResource(R.string.share_modal_error_network)
    val errRateLimited = stringResource(R.string.share_modal_error_rate_limited)
    val errUnknown = stringResource(R.string.share_modal_error_unknown)

    LaunchedEffect(viewModel) {
        viewModel.events.collect { ev ->
            when (ev) {
                is WalkShareEvent.Success -> {
                    // CachedShareStore emission drives the UI into the
                    // "Shared" layout reactively. iOS parity
                    // (`WalkShareView.triggerRitualIfNeeded`): after a
                    // ~800ms beat + a soft haptic, auto-present the shared
                    // scroll so the user doesn't have to tap "View scroll".
                    // Fires only on a fresh share — the Success event never
                    // emits on re-entry of an already-shared walk.
                    kotlinx.coroutines.delay(800)
                    haptic.performHapticFeedback(
                        androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress,
                    )
                    org.walktalkmeditate.pilgrim.ui.util.CustomTabs.launch(
                        context,
                        android.net.Uri.parse(ev.url),
                    )
                }
                WalkShareEvent.RateLimited -> snackbarHostState.showSnackbar(errRateLimited)
                is WalkShareEvent.Failed -> snackbarHostState.showSnackbar(
                    ev.message.ifBlank { errUnknown }.ifBlank { errNetwork },
                )
            }
        }
    }

    // Snapshot `cached` once per composition so downstream reads see a
    // consistent value — a second delegated-property read can observe a
    // fresh DataStore emission and transition non-null → null between
    // the `isShared` check and the unwrap, producing an NPE.
    val activeShare = cached?.takeIf { !it.isExpiredAt() }
    val isShared = activeShare != null

    Scaffold(
        // Outer PilgrimNavHost Scaffold already consumed system bar
        // insets; pass WindowInsets(0) to avoid double-counting.
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
        topBar = {
            WalkShareTopBar(
                isShared = isShared,
                onCancel = onDone,
                onDone = onDone,
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
                            points = s.inputs.routePoints,
                            expiryEpochMs = activeShare.expiryEpochMs,
                            onOpenScroll = {
                                org.walktalkmeditate.pilgrim.ui.util.CustomTabs.launch(
                                    context,
                                    android.net.Uri.parse(activeShare.url),
                                )
                            },
                        )
                    } else {
                        RoutePreview(points = s.inputs.routePoints)
                        StatToggles(
                            inputs = s.inputs,
                            units = units,
                            distance = includeDistance,
                            duration = includeDuration,
                            elevation = includeElevation,
                            activity = includeActivity,
                            steps = includeSteps,
                            waypoints = includeWaypoints,
                            photos = includePhotos,
                            onDistance = viewModel::toggleDistance,
                            onDuration = viewModel::toggleDuration,
                            onElevation = viewModel::toggleElevation,
                            onActivity = viewModel::toggleActivityBreakdown,
                            onSteps = viewModel::toggleSteps,
                            onWaypoints = viewModel::toggleWaypoints,
                            onPhotos = viewModel::togglePhotos,
                        )
                        JournalInput(
                            journal = journal,
                            onJournalChange = viewModel::updateJournal,
                        )
                        ExpiryPicker(
                            selected = selectedExpiry,
                            onSelect = viewModel::updateExpiry,
                        )
                        if (!canShare && !isSharing) {
                            Text(
                                text = stringResource(R.string.share_modal_toggle_at_least_one),
                                style = pilgrimType.caption,
                                color = pilgrimColors.fog,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        ShareActionButton(
                            canShare = canShare,
                            isSharing = isSharing,
                            onShare = viewModel::share,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(Locale.ROOT),
        style = pilgrimType.micro,
        color = pilgrimColors.fog,
        letterSpacing = 1.5.sp,
    )
}

@Composable
private fun RoutePreview(points: List<LocationPoint>) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROUTE_PREVIEW_HEIGHT)
            .clip(RoundedCornerShape(PilgrimCornerRadius.normal))
            .background(pilgrimColors.parchmentSecondary),
    ) {
        RouteShapeView(points = points)
    }
}

@Composable
private fun StatToggles(
    inputs: ShareInputs,
    units: UnitSystem,
    distance: Boolean,
    duration: Boolean,
    elevation: Boolean,
    activity: Boolean,
    steps: Boolean,
    waypoints: Boolean,
    photos: Boolean,
    onDistance: (Boolean) -> Unit,
    onDuration: (Boolean) -> Unit,
    onElevation: (Boolean) -> Unit,
    onActivity: (Boolean) -> Unit,
    onSteps: (Boolean) -> Unit,
    onWaypoints: (Boolean) -> Unit,
    onPhotos: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.small)) {
        SectionLabel(stringResource(R.string.share_modal_stats_header))
        StatToggleRow(
            title = stringResource(R.string.share_modal_stat_distance),
            value = ShareStatFormat.distance(inputs.distanceMeters, units),
            on = distance,
            onChange = onDistance,
        )
        StatToggleRow(
            title = stringResource(R.string.share_modal_stat_duration),
            value = ShareStatFormat.duration(inputs.activeDurationSeconds),
            on = duration,
            onChange = onDuration,
        )
        StatToggleRow(
            title = stringResource(R.string.share_modal_stat_elevation),
            value = ShareStatFormat.elevation(inputs.elevationAscentMeters, units),
            on = elevation,
            onChange = onElevation,
        )
        StatToggleRow(
            title = stringResource(R.string.share_modal_stat_activity),
            value = ShareStatFormat.activityBreakdown(
                meditateSeconds = inputs.meditateDurationSeconds,
                talkSeconds = inputs.talkDurationSeconds,
            ),
            on = activity,
            onChange = onActivity,
        )
        StatToggleRow(
            title = stringResource(R.string.share_modal_stat_steps),
            value = ShareStatFormat.steps(inputs.steps),
            on = steps,
            onChange = onSteps,
        )
        val waypointCount = inputs.waypoints.size
        if (waypointCount > 0) {
            StatToggleRow(
                title = stringResource(R.string.share_modal_include_waypoints, waypointCount),
                value = null,
                on = waypoints,
                onChange = onWaypoints,
            )
        }
        // Reliquary photos. The Android reliquary is always on (no
        // master toggle like iOS), so the only gate is "are there
        // pinned photos?" — pinned URIs already carry persistable read
        // grants + stored EXIF GPS, so no runtime permission is needed.
        val photoCount = inputs.pinnedPhotos.size
        if (photoCount > 0) {
            StatToggleRow(
                title = stringResource(R.string.share_modal_stat_photos),
                value = pluralStringResource(R.plurals.share_modal_photos_pinned, photoCount, photoCount),
                on = photos,
                onChange = onPhotos,
            )
            if (photos) {
                Text(
                    text = stringResource(R.string.share_modal_photos_warning),
                    style = pilgrimType.caption,
                    color = pilgrimColors.fog,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PilgrimSpacing.normal),
                )
            }
        }
    }
}

@Composable
private fun StatToggleRow(
    title: String,
    value: String?,
    on: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PilgrimCornerRadius.small))
            .background(pilgrimColors.parchmentSecondary)
            .clickable { onChange(!on) }
            .padding(horizontal = PilgrimSpacing.normal, vertical = ROW_VERTICAL_PADDING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(text = title, style = pilgrimType.body, color = pilgrimColors.ink)
            if (value != null) {
                Text(text = value, style = pilgrimType.caption, color = pilgrimColors.fog)
            }
        }
        Switch(
            checked = on,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = pilgrimColors.moss,
                checkedThumbColor = pilgrimColors.parchment,
            ),
        )
    }
}

@Composable
private fun JournalInput(journal: String, onJournalChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.small)) {
        SectionLabel(stringResource(R.string.share_modal_journal_header))
        OutlinedTextField(
            value = journal,
            onValueChange = onJournalChange,
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            placeholder = {
                Text(
                    text = stringResource(R.string.share_modal_journal_placeholder),
                    style = pilgrimType.body,
                    color = pilgrimColors.fog,
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
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun ExpiryPicker(selected: ExpiryOption, onSelect: (ExpiryOption) -> Unit) {
    val expiresMs = remember(selected) {
        Instant.now().toEpochMilli() + selected.days.toLong() * MILLIS_PER_DAY
    }
    Column(verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.small)) {
        SectionLabel(stringResource(R.string.share_modal_expiry_header))
        Row(
            horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
            modifier = Modifier.fillMaxWidth(),
        ) {
            ExpiryOption.entries.forEach { option ->
                ExpiryButton(
                    option = option,
                    selected = selected == option,
                    onClick = { onSelect(option) },
                )
            }
        }
        Text(
            text = stringResource(R.string.share_modal_expires, formatExpiryDateLong(expiresMs)),
            style = pilgrimType.caption,
            color = pilgrimColors.fog,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Expiry option button — iOS parity with `expiryButton`. The kanji is
 * a faint full-size glyph BEHIND the label (not inline), forced to the
 * system font family because Cormorant Garamond has no CJK coverage.
 * Selected: stone fill + parchment ink. Unselected: parchmentSecondary
 * fill + fog ink.
 */
@Composable
private fun RowScope.ExpiryButton(
    option: ExpiryOption,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(PilgrimCornerRadius.small))
            .background(if (selected) pilgrimColors.stone else pilgrimColors.parchmentSecondary)
            .clickable(onClick = onClick)
            .padding(vertical = ROW_VERTICAL_PADDING),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = option.kanji,
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.Thin,
            fontSize = 40.sp,
            color = if (selected) {
                pilgrimColors.parchment.copy(alpha = 0.12f)
            } else {
                pilgrimColors.fog.copy(alpha = 0.06f)
            },
        )
        Text(
            text = option.label,
            style = pilgrimType.caption,
            maxLines = 1,
            color = if (selected) pilgrimColors.parchment else pilgrimColors.fog,
        )
    }
}

@Composable
private fun ShareActionButton(
    canShare: Boolean,
    isSharing: Boolean,
    onShare: () -> Unit,
) {
    Button(
        onClick = onShare,
        enabled = canShare && !isSharing,
        shape = RoundedCornerShape(PilgrimCornerRadius.normal),
        colors = ButtonDefaults.buttonColors(
            containerColor = pilgrimColors.stone,
            contentColor = pilgrimColors.parchment,
            // iOS uploading state: stone.opacity(0.6) background.
            disabledContainerColor = pilgrimColors.stone.copy(alpha = 0.6f),
            disabledContentColor = pilgrimColors.parchment,
        ),
        contentPadding = PaddingValues(vertical = 14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (isSharing) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = pilgrimColors.parchment,
            )
            Spacer(Modifier.width(PilgrimSpacing.small))
            Text(text = stringResource(R.string.share_modal_sharing), style = pilgrimType.button)
        } else {
            Text(text = stringResource(R.string.share_modal_share_button), style = pilgrimType.button)
        }
    }
}

/**
 * Share Walk modal top bar. Centered title, leading Cancel before the
 * share, trailing Done after. No top-bar share action — the primary
 * "Share Walk" trigger lives at the bottom of the form (iOS parity,
 * `WalkShareView.swift` toolbar + bottom `shareButton`).
 */
@Composable
private fun WalkShareTopBar(
    isShared: Boolean,
    onCancel: () -> Unit,
    onDone: () -> Unit,
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
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 72.dp),
        )
        if (isShared) {
            TextButton(
                onClick = onDone,
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                Text(
                    text = stringResource(R.string.share_modal_done),
                    style = pilgrimType.button,
                    color = pilgrimColors.stone,
                )
            }
        } else {
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
    }
}

/**
 * Post-share success state. Mirrors iOS `WalkShareView.swift:330-375`:
 * a single `parchmentSecondary` rounded card containing a tappable
 * route-shape thumbnail, a centered "Shared ✓" row, an italic
 * "Returns to the trail on {date}" caption, and a full-width plain
 * "View scroll" button. Both the thumbnail and "View scroll" open the
 * in-app scroll preview (Custom Tab).
 */
@Composable
private fun SharedLayout(
    points: List<LocationPoint>,
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
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ROUTE_PREVIEW_HEIGHT)
                    .clip(RoundedCornerShape(PilgrimCornerRadius.normal))
                    .background(pilgrimColors.parchment)
                    .clickable(onClick = onOpenScroll),
            ) {
                RouteShapeView(points = points)
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
