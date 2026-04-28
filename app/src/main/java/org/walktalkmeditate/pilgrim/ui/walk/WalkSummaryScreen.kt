// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.data.share.CachedShare
import org.walktalkmeditate.pilgrim.ui.walk.share.JourneyRowState
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.ZoneId
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.ui.design.seals.SealRevealOverlay
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.SeasonalColorEngine
import org.walktalkmeditate.pilgrim.ui.walk.reliquary.PhotoReliquarySection

@Composable
fun WalkSummaryScreen(
    onDone: () -> Unit,
    onShareJourney: () -> Unit = {},
    viewModel: WalkSummaryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val recordings by viewModel.recordings.collectAsStateWithLifecycle()
    val playbackUiState by viewModel.playbackUiState.collectAsStateWithLifecycle()
    val hemisphere by viewModel.hemisphere.collectAsStateWithLifecycle()
    val pinnedPhotos by viewModel.pinnedPhotos.collectAsStateWithLifecycle()
    val distanceUnits by viewModel.distanceUnits.collectAsStateWithLifecycle()
    val lightReadingDisplay by viewModel.lightReadingDisplay.collectAsStateWithLifecycle()
    // Stage 8-A: must be collected unconditionally here, not inside
    // the Loaded branch's `if (routePoints.size >= 2)` nested block.
    // `collectAsStateWithLifecycle` calls `remember` internally, and
    // Compose's slot-table traversal requires `remember` calls to be
    // made from a stable call site. Putting it inside the branch
    // would shift positions if `state` ever transitions back to
    // Loading (future pull-to-refresh / deletion flow) and crash
    // with IllegalStateException. Today's single-emission state
    // flow happens to mask the bug; hoist pre-emptively.
    val cachedShare by viewModel.cachedShareFlow.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.runStartupSweep() }

    // Stage 7-D: etegami share + save wiring. The row is slotted
    // directly under WalkEtegamiCard below; VM events drive snackbar
    // feedback + chooser-intent dispatch. `LocalActivity.current` is
    // non-null in practice — MainActivity hosts every screen —
    // but we fall back to a snackbar on null rather than crashing.
    val snackbarHostState = remember { SnackbarHostState() }
    val etegamiBusy by viewModel.etegamiBusy.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    val context = LocalContext.current
    val msgSaveSuccess = stringResource(R.string.etegami_save_success)
    val msgSaveFailed = stringResource(R.string.etegami_save_failed)
    val msgShareFailed = stringResource(R.string.etegami_share_failed)
    val msgNeedsPermission = stringResource(R.string.etegami_save_needs_permission)
    val msgCopied = stringResource(R.string.share_journey_copied)
    val msgChooserTitle = stringResource(R.string.share_modal_chooser_title)
    LaunchedEffect(viewModel) {
        viewModel.etegamiEvents.collect { ev ->
            when (ev) {
                is WalkSummaryViewModel.EtegamiShareEvent.DispatchShare -> {
                    // startActivity can throw ActivityNotFoundException
                    // on edge-case devices with no share chooser
                    // installed (rare but possible on stripped-down
                    // ROMs). Catch to keep the collector alive and
                    // surface a snackbar instead of tearing down the
                    // whole event pipeline. `activity` is null if
                    // WalkSummaryScreen is hosted outside a real
                    // Activity — unreachable in Pilgrim (MainActivity
                    // hosts every screen) but handled defensively.
                    try {
                        activity?.startActivity(ev.chooser)
                            ?: snackbarHostState.showSnackbar(msgShareFailed)
                    } catch (t: android.content.ActivityNotFoundException) {
                        snackbarHostState.showSnackbar(msgShareFailed)
                    }
                }
                WalkSummaryViewModel.EtegamiShareEvent.SaveSucceeded ->
                    snackbarHostState.showSnackbar(msgSaveSuccess)
                WalkSummaryViewModel.EtegamiShareEvent.SaveFailed ->
                    snackbarHostState.showSnackbar(msgSaveFailed)
                WalkSummaryViewModel.EtegamiShareEvent.ShareFailed ->
                    snackbarHostState.showSnackbar(msgShareFailed)
                WalkSummaryViewModel.EtegamiShareEvent.SaveNeedsPermission ->
                    snackbarHostState.showSnackbar(msgNeedsPermission)
            }
        }
    }

    // Stage 4-B: the reveal plays on this entry to WalkSummaryScreen.
    // After the overlay calls onDismiss (auto-dismiss at 2.5s or
    // tap-to-dismiss early), flip the flag so it doesn't replay on
    // unrelated recompositions (scroll, recordings flow update, etc.).
    // Accepts reveal-replay on back-nav + re-entry — iOS has the same
    // behavior and a proper single-play guard needs a Room migration.
    var showReveal by remember { mutableStateOf(true) }

    Box(modifier = Modifier.fillMaxSize()) {
        // Summary content renders first (z-order: behind the overlay).
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(PilgrimSpacing.big),
        ) {
            Text(
                text = stringResource(R.string.summary_title),
                style = pilgrimType.displayMedium,
                color = pilgrimColors.ink,
            )
            Spacer(Modifier.height(PilgrimSpacing.big))

            when (val s = state) {
                is WalkSummaryUiState.Loading -> {
                    SummaryMapPlaceholder()
                    Spacer(Modifier.height(PilgrimSpacing.big))
                    LoadingRow()
                }
                is WalkSummaryUiState.NotFound -> {
                    SummaryMapPlaceholder()
                    Spacer(Modifier.height(PilgrimSpacing.big))
                    Text(
                        text = stringResource(R.string.summary_unavailable),
                        style = pilgrimType.body,
                        color = pilgrimColors.fog,
                    )
                }
                is WalkSummaryUiState.Loaded -> {
                    SummaryMap(points = s.summary.routePoints)
                    Spacer(Modifier.height(PilgrimSpacing.big))
                    SummaryStats(summary = s.summary, units = distanceUnits)
                    // Stage 7-A: the reliquary sits between the
                    // objective stats and the interpretive Light
                    // Reading — tangible artifacts first, felt content
                    // after. Section always renders; its header
                    // carries the "Add photos" affordance even with an
                    // empty grid.
                    Spacer(Modifier.height(PilgrimSpacing.big))
                    PhotoReliquarySection(
                        photos = pinnedPhotos,
                        onPinPhotos = viewModel::pinPhotos,
                        onUnpinPhoto = viewModel::unpinPhoto,
                    )
                    // Stage 6-B: contemplative payoff card below the
                    // stats. VM's runCatching means a compute failure
                    // yields null here and the card just doesn't
                    // render. Room's autoGenerate guarantees the
                    // walkId > 0 precondition LightReading.from needs,
                    // so null is unreachable in production today.
                    //
                    // Stage 10-C: read from `lightReadingDisplay` (live
                    // combine of summary + celestialAwarenessEnabled
                    // pref) so toggling the pref while the summary is
                    // open immediately shows / hides the card.
                    lightReadingDisplay?.let { reading ->
                        Spacer(Modifier.height(PilgrimSpacing.big))
                        WalkLightReadingCard(reading = reading)
                    }
                    // Stage 8-A: wrap BOTH the 7-C/7-D etegami card +
                    // share row AND the 8-A journey-share row in a
                    // single hasRoute guard — iOS WalkSharingButtons
                    // parity (whole card is absent for walks with
                    // fewer than 2 GPS points). Share endpoint also
                    // rejects routes < 2 points server-side.
                    if (s.summary.routePoints.size >= 2) {
                        s.summary.etegamiSpec?.let { etegami ->
                            Spacer(Modifier.height(PilgrimSpacing.big))
                            WalkEtegamiCard(spec = etegami)
                            WalkEtegamiShareRow(
                                busyAction = etegamiBusy,
                                onShare = { viewModel.shareEtegami(etegami) },
                                onSave = { viewModel.saveEtegamiToGallery(etegami) },
                                onSavePermissionDenied = {
                                    viewModel.notifyEtegamiSaveNeedsPermission()
                                },
                            )
                        }
                        // Stage 8-A: journey-share section (web page).
                        // `cachedShare` is collected unconditionally at the
                        // composable's top (see hoisting comment there);
                        // map to the row's tri-state at the call site.
                        val rowState = cachedShare.toJourneyRowState()
                        Spacer(Modifier.height(PilgrimSpacing.big))
                        org.walktalkmeditate.pilgrim.ui.walk.share.WalkShareJourneyRow(
                            state = rowState,
                            onShareJourney = onShareJourney,
                            onReshare = onShareJourney,
                            onReopenModal = onShareJourney,
                            onCopyUrl = { url ->
                                org.walktalkmeditate.pilgrim.ui.walk.share.copyUrl(
                                    context,
                                    url,
                                    msgCopied,
                                )
                            },
                            onShareUrl = { url ->
                                org.walktalkmeditate.pilgrim.ui.walk.share.launchShareChooser(
                                    activity ?: context,
                                    url,
                                    msgChooserTitle,
                                )
                            },
                        )
                    }
                    if (recordings.isNotEmpty()) {
                        Spacer(Modifier.height(PilgrimSpacing.big))
                        VoiceRecordingsSection(
                            walkStartTimestamp = s.summary.walk.startTimestamp,
                            recordings = recordings,
                            playbackUiState = playbackUiState,
                            onPlay = viewModel::playRecording,
                            onPause = viewModel::pausePlayback,
                        )
                    }
                }
            }

            Spacer(Modifier.height(PilgrimSpacing.breathingRoom))

            Button(
                onClick = onDone,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.summary_action_done))
            }
        }

        // Stage 4-B reveal overlay: renders on top of the summary
        // content. Only for Loaded state — we need the sealSpec.
        // Loading / NotFound branches skip the overlay.
        val loaded = state as? WalkSummaryUiState.Loaded
        if (showReveal && loaded != null) {
            val baseInk = pilgrimColors.rust
            val walkDate = remember(loaded.summary.walk.startTimestamp) {
                Instant.ofEpochMilli(loaded.summary.walk.startTimestamp)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
            }
            // Cache the seasonal-shifted spec so unrelated recompositions
            // (e.g., a recordings-flow update while the overlay is
            // visible) don't re-run the HSV shift + allocate a fresh
            // SealSpec. Matches Stage 3-E's JournalThread pattern.
            val specForReveal = remember(loaded.summary.sealSpec, baseInk, walkDate, hemisphere) {
                val tintedInk = SeasonalColorEngine.applySeasonalShift(
                    base = baseInk,
                    intensity = SeasonalColorEngine.Intensity.Full,
                    date = walkDate,
                    hemisphere = hemisphere,
                )
                loaded.summary.sealSpec.copy(ink = tintedInk)
            }
            SealRevealOverlay(
                spec = specForReveal,
                onDismiss = { showReveal = false },
                isMilestone = loaded.summary.milestone != null,
            )
        }

        // Stage 7-D: snackbar anchored to the bottom of the screen so
        // Save/Share feedback doesn't collide with the SealReveal
        // overlay (which lives above the summary column).
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun LoadingRow() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // CircularProgressIndicator needs equal width + height — height()
        // alone collapses to zero width and renders invisibly.
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = pilgrimColors.stone,
        )
    }
}

@Composable
private fun SummaryMap(points: List<org.walktalkmeditate.pilgrim.domain.LocationPoint>) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp),
        colors = CardDefaults.cardColors(
            containerColor = pilgrimColors.parchmentSecondary,
        ),
    ) {
        if (points.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.walk_map_no_route),
                    style = pilgrimType.caption,
                    color = pilgrimColors.fog,
                )
            }
        } else {
            PilgrimMap(
                points = points,
                followLatest = false,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun SummaryMapPlaceholder() {
    // Shown during loading / not-found states to reserve the same visual
    // area a rendered map occupies — avoids a layout jump once the
    // summary resolves.
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp),
        colors = CardDefaults.cardColors(
            containerColor = pilgrimColors.parchmentSecondary,
            contentColor = pilgrimColors.fog,
        ),
    ) {}
}

@Composable
private fun SummaryStats(
    summary: WalkSummary,
    units: org.walktalkmeditate.pilgrim.data.units.UnitSystem,
) {
    Column(verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.normal)) {
        SummaryRow(
            label = stringResource(R.string.walk_stat_duration),
            value = WalkFormat.duration(summary.totalElapsedMillis),
        )
        SummaryRow(
            label = stringResource(R.string.walk_stat_active_walking),
            value = WalkFormat.duration(summary.activeWalkingMillis),
        )
        SummaryRow(
            label = stringResource(R.string.walk_stat_distance),
            value = WalkFormat.distance(summary.distanceMeters, units),
        )
        SummaryRow(
            label = stringResource(R.string.walk_stat_pace),
            value = WalkFormat.pace(summary.paceSecondsPerKm, units),
        )
        if (summary.totalPausedMillis > 0) {
            SummaryRow(
                label = stringResource(R.string.walk_stat_paused_time),
                value = WalkFormat.duration(summary.totalPausedMillis),
            )
        }
        if (summary.totalMeditatedMillis > 0) {
            SummaryRow(
                label = stringResource(R.string.walk_stat_meditation_time),
                value = WalkFormat.duration(summary.totalMeditatedMillis),
            )
        }
        if (summary.waypointCount > 0) {
            SummaryRow(
                label = stringResource(R.string.walk_stat_waypoints),
                value = summary.waypointCount.toString(),
            )
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = pilgrimType.body,
            color = pilgrimColors.fog,
        )
        Text(
            text = value,
            style = pilgrimType.statValue,
            color = pilgrimColors.ink,
        )
    }
}


/**
 * Stage 8-A: CachedShare → JourneyRowState projection. `null` or
 * expired → Fresh / Expired respectively; non-null + non-expired →
 * Active. Wall-clock comparison at read time (iOS parity, no ticker).
 */
private fun CachedShare?.toJourneyRowState(): JourneyRowState = when {
    this == null -> JourneyRowState.Fresh
    isExpiredAt() -> JourneyRowState.Expired(expiryOption)
    else -> JourneyRowState.Active(
        url = url,
        expiryEpochMs = expiryEpochMs,
        shareDateEpochMs = shareDateEpochMs,
        expiryOption = expiryOption,
    )
}
