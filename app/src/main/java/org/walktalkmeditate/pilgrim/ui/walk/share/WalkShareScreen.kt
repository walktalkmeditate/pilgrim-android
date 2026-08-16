// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.share

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.ui.util.CustomTabs
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
// iOS `WalkShareView.triggerRitualIfNeeded` beat before auto-presenting
// the shared scroll on a successful share.
private const val SHARE_RITUAL_DELAY_MS = 800L
private val ROW_VERTICAL_PADDING = 10.dp

/**
 * The Share Walk modal. Opens on "Share Journey" tap from Walk
 * Summary. Sections mirror iOS `WalkShareView@3f9f9e8`: route thumbnail
 * → stat toggles → Interactive → reflection → expiry picker → bottom
 * status card. The share action lives at the BOTTOM of the form (iOS
 * parity) — the top bar carries only Cancel (pre-share, unless
 * [WalkShareTopBar]'s `isDismissLocked` hides it) / Done (once shared).
 * After a successful share the body flips to [ShareStatusSection]'s
 * `Success`/`Partial` card, mounted alone (Phase 19 U7 —
 * `InteractiveShareSection` + `ShareStatusSection`).
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
    val cardState by viewModel.shareCardState.collectAsStateWithLifecycle()
    val interactiveState by viewModel.interactiveSection.collectAsStateWithLifecycle()
    val repairUnavailable by viewModel.repairUnavailable.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    // Shared latch so the manual "View scroll" tap and the delayed
    // auto-present can't both launch the Custom Tab (iOS cancels the
    // pending reveal on a manual tap; this is the Compose equivalent).
    val scrollOpened = remember { AtomicBoolean(false) }
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
                    // emits on re-entry of an already-shared walk. The
                    // latch guards the case where the user taps "View
                    // scroll" during the 800ms beat — whichever fires first
                    // wins, so the scroll never opens twice.
                    delay(SHARE_RITUAL_DELAY_MS)
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    if (scrollOpened.compareAndSet(false, true)) {
                        CustomTabs.launch(context, ev.url.toUri())
                    }
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
    // iOS `WalkShareView.isShared` mirrors the VM's own `isShared`
    // (`WalkShareView.swift:24@3f9f9e8`) — `.partial` counts as shared,
    // the page is live. The cache check stays in the OR so an
    // already-shared walk shows its card before the restore observer has
    // had a chance to run (the first-share-only short-circuit, R4/AE1).
    val isShared = isSharedState(cardState) || activeShare != null

    // iOS `isDismissLocked` (`WalkShareView.swift:38-48@3f9f9e8`) — a
    // deliberate 2-case SUBSET of isShareInFlight: only states with
    // something already server-side (POST landed, PUTs streaming) lock
    // dismissal; a local, cancellable photo export and the pre-POST
    // consent pause do not.
    val isDismissLocked = isDismissLocked(cardState)
    BackHandler(enabled = isDismissLocked) {
        // Absorb the back gesture — iOS parity `.interactiveDismissDisabled(isDismissLocked)`.
    }

    Scaffold(
        // Outer PilgrimNavHost Scaffold already consumed system bar
        // insets; pass WindowInsets(0) to avoid double-counting.
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
        topBar = {
            WalkShareTopBar(
                isShared = isShared,
                isDismissLocked = isDismissLocked,
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
                    // UI-56 parity: at the pin, `.disabled(isShareInFlight)`
                    // wraps the WHOLE form (StatToggles/Journal/Expiry too),
                    // not just the Interactive section — "editing toggles,
                    // the journal, or expiry now would desync the payload
                    // already sent from what these controls show"
                    // (`WalkShareView.swift:63-77@3f9f9e8`). Compose has no
                    // container-level `.disabled`, so the one boolean is
                    // threaded to each of the four sections instead.
                    val formFrozen = isShareInFlight(cardState)
                    // Safe fallback: `expiryText` only ever RENDERS inside
                    // ShareStatusSection's Success/Partial branches, which
                    // are only reachable once a share exists.
                    val expiryText = activeShare?.let { formatExpiryDateLong(it.expiryEpochMs) }.orEmpty()
                    val onOpenPreview: (String) -> Unit = { url ->
                        // Manual tap always opens; mark the latch so a
                        // pending auto-present beat doesn't re-open.
                        scrollOpened.set(true)
                        CustomTabs.launch(context, url.toUri())
                    }

                    if (isShared) {
                        ShareStatusSection(
                            state = cardState,
                            canShare = canShare,
                            repairUnavailable = repairUnavailable,
                            expiryText = expiryText,
                            routePoints = s.inputs.routePoints,
                            onShare = viewModel::share,
                            onOpenPreview = onOpenPreview,
                            onRetryMissingFiles = viewModel::retryFailedMedia,
                            onShareWithoutDroppedPhotos = viewModel::continueShareWithoutDroppedPhotos,
                            onCancelDroppedPhotoShare = viewModel::cancelDroppedPhotoShare,
                        )
                    } else {
                        ShareRouteThumbnail(points = s.inputs.routePoints)
                        StatToggles(
                            inputs = s.inputs,
                            units = units,
                            enabled = !formFrozen,
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
                        InteractiveShareSection(
                            state = interactiveState,
                            onInteractiveEnabledChange = viewModel::setInteractiveEnabled,
                            onToggleRowInclude = viewModel::toggleRowInclude,
                            onFlipRowKind = viewModel::flipRowKind,
                            onTrimEnabledChange = viewModel::toggleTrim,
                        )
                        JournalInput(
                            journal = journal,
                            enabled = !formFrozen,
                            onJournalChange = viewModel::updateJournal,
                        )
                        ExpiryPicker(
                            selected = selectedExpiry,
                            enabled = !formFrozen,
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
                        ShareStatusSection(
                            state = cardState,
                            canShare = canShare,
                            repairUnavailable = repairUnavailable,
                            expiryText = expiryText,
                            routePoints = s.inputs.routePoints,
                            onShare = viewModel::share,
                            onOpenPreview = onOpenPreview,
                            onRetryMissingFiles = viewModel::retryFailedMedia,
                            onShareWithoutDroppedPhotos = viewModel::continueShareWithoutDroppedPhotos,
                            onCancelDroppedPhotoShare = viewModel::cancelDroppedPhotoShare,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatToggles(
    inputs: ShareInputs,
    units: UnitSystem,
    enabled: Boolean,
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
        ShareSectionLabel(stringResource(R.string.share_modal_stats_header))
        StatToggleRow(
            title = stringResource(R.string.share_modal_stat_distance),
            value = ShareStatFormat.distance(inputs.distanceMeters, units),
            on = distance,
            enabled = enabled,
            onChange = onDistance,
        )
        StatToggleRow(
            title = stringResource(R.string.share_modal_stat_duration),
            value = ShareStatFormat.duration(inputs.activeDurationSeconds),
            on = duration,
            enabled = enabled,
            onChange = onDuration,
        )
        StatToggleRow(
            title = stringResource(R.string.share_modal_stat_elevation),
            value = ShareStatFormat.elevation(inputs.elevationAscentMeters, units),
            on = elevation,
            enabled = enabled,
            onChange = onElevation,
        )
        StatToggleRow(
            title = stringResource(R.string.share_modal_stat_activity),
            value = ShareStatFormat.activityBreakdown(
                meditateSeconds = inputs.meditateDurationSeconds,
                talkSeconds = inputs.talkDurationSeconds,
            ),
            on = activity,
            enabled = enabled,
            onChange = onActivity,
        )
        StatToggleRow(
            title = stringResource(R.string.share_modal_stat_steps),
            value = ShareStatFormat.steps(inputs.steps),
            on = steps,
            enabled = enabled,
            onChange = onSteps,
        )
        val waypointCount = inputs.waypoints.size
        if (waypointCount > 0) {
            StatToggleRow(
                title = stringResource(R.string.share_modal_include_waypoints, waypointCount),
                value = null,
                on = waypoints,
                enabled = enabled,
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
                enabled = enabled,
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
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PilgrimCornerRadius.small))
            .background(pilgrimColors.parchmentSecondary)
            .clickable(enabled = enabled) { onChange(!on) }
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
            enabled = enabled,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = pilgrimColors.moss,
                checkedThumbColor = pilgrimColors.parchment,
            ),
        )
    }
}

@Composable
private fun JournalInput(journal: String, enabled: Boolean, onJournalChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.small)) {
        ShareSectionLabel(stringResource(R.string.share_modal_journal_header))
        OutlinedTextField(
            value = journal,
            onValueChange = onJournalChange,
            enabled = enabled,
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
private fun ExpiryPicker(selected: ExpiryOption, enabled: Boolean, onSelect: (ExpiryOption) -> Unit) {
    val expiresMs = remember(selected) {
        Instant.now().toEpochMilli() + selected.days.toLong() * MILLIS_PER_DAY
    }
    Column(verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.small)) {
        ShareSectionLabel(stringResource(R.string.share_modal_expiry_header))
        Row(
            horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
            modifier = Modifier.fillMaxWidth(),
        ) {
            ExpiryOption.entries.forEach { option ->
                ExpiryButton(
                    option = option,
                    selected = selected == option,
                    enabled = enabled,
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
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(PilgrimCornerRadius.small))
            .background(if (selected) pilgrimColors.stone else pilgrimColors.parchmentSecondary)
            .clickable(enabled = enabled, onClick = onClick)
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

/**
 * Share Walk modal top bar. Centered title, leading Cancel before the
 * share, trailing Done after. No top-bar share action — the primary
 * "Share Walk" trigger lives at the bottom of the form (iOS parity,
 * `WalkShareView.swift` toolbar + bottom `shareButton`).
 *
 * [isDismissLocked] hides Cancel even pre-share once something is
 * server-side already (iOS UI-57/UI-59, `WalkShareView.swift:98-106@3f9f9e8`)
 * — distinct from [isShared], which flips the title and swaps Cancel for Done.
 */
@Composable
private fun WalkShareTopBar(
    isShared: Boolean,
    isDismissLocked: Boolean,
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
        } else if (!isDismissLocked) {
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
