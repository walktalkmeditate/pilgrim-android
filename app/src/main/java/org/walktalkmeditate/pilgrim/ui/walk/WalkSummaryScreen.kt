// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.walktalkmeditate.pilgrim.core.prompt.CustomPromptStyle
import org.walktalkmeditate.pilgrim.data.entity.WalkFavicon
import org.walktalkmeditate.pilgrim.data.share.CachedShare
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.data.walk.RouteSegment
import org.walktalkmeditate.pilgrim.data.walk.WalkMapAnnotation
import org.walktalkmeditate.pilgrim.data.weather.WeatherCondition
import org.walktalkmeditate.pilgrim.ui.walk.share.JourneyRowState
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.ui.design.seals.SealRevealOverlay
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.SeasonalColorEngine
import org.walktalkmeditate.pilgrim.ui.walk.reliquary.PhotoReliquarySection
import org.walktalkmeditate.pilgrim.ui.walk.summary.AIPromptsRow
import org.walktalkmeditate.pilgrim.ui.walk.summary.COUNT_UP_DURATION_MS
import org.walktalkmeditate.pilgrim.ui.walk.summary.CelestialLineRow
import org.walktalkmeditate.pilgrim.ui.walk.summary.CustomPromptEditorDialog
import org.walktalkmeditate.pilgrim.ui.walk.summary.ElevationProfile
import org.walktalkmeditate.pilgrim.ui.walk.summary.FaviconSelectorCard
import org.walktalkmeditate.pilgrim.ui.walk.summary.MapCameraBounds
import org.walktalkmeditate.pilgrim.ui.walk.summary.MilestoneCalloutRow
import org.walktalkmeditate.pilgrim.ui.walk.summary.PromptDetailDialog
import org.walktalkmeditate.pilgrim.ui.walk.summary.PromptListSheet
import org.walktalkmeditate.pilgrim.ui.walk.summary.REVEAL_DELAY_BREAKDOWN_MS
import org.walktalkmeditate.pilgrim.ui.walk.summary.REVEAL_DELAY_CELESTIAL_MS
import org.walktalkmeditate.pilgrim.ui.walk.summary.REVEAL_DELAY_STATS_MS
import org.walktalkmeditate.pilgrim.ui.walk.summary.REVEAL_DURATION_CALLOUT_MS
import org.walktalkmeditate.pilgrim.ui.walk.summary.REVEAL_DURATION_DEFAULT_MS
import org.walktalkmeditate.pilgrim.ui.walk.summary.REVEAL_DURATION_HERO_MS
import org.walktalkmeditate.pilgrim.ui.walk.summary.REVEAL_DURATION_QUOTE_MS
import org.walktalkmeditate.pilgrim.ui.walk.summary.RevealPhase
import org.walktalkmeditate.pilgrim.ui.walk.summary.rememberRevealAlpha
import org.walktalkmeditate.pilgrim.ui.walk.summary.RouteSegmentColors
import org.walktalkmeditate.pilgrim.ui.walk.summary.SmoothStepEasing
import org.walktalkmeditate.pilgrim.ui.walk.summary.WalkAnnotationColors
import org.walktalkmeditate.pilgrim.ui.walk.summary.computeBoundsForTimeRange
import org.walktalkmeditate.pilgrim.ui.walk.summary.WalkActivityInsightsCard
import org.walktalkmeditate.pilgrim.ui.walk.summary.WalkActivityListCard
import org.walktalkmeditate.pilgrim.ui.walk.summary.WalkActivityTimelineCard
import org.walktalkmeditate.pilgrim.ui.walk.summary.WalkDurationHero
import org.walktalkmeditate.pilgrim.ui.walk.summary.WalkIntentionCard
import org.walktalkmeditate.pilgrim.ui.walk.summary.WalkJourneyQuote
import org.walktalkmeditate.pilgrim.ui.walk.summary.WalkStatsRow
import org.walktalkmeditate.pilgrim.ui.walk.summary.WalkSummaryDetailsCard
import org.walktalkmeditate.pilgrim.ui.walk.summary.WalkSummaryTopBar
import org.walktalkmeditate.pilgrim.ui.walk.summary.WalkTimeBreakdownGrid
import org.walktalkmeditate.pilgrim.ui.walk.summary.ZOOM_HOLD_MS

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
    val selectedFavicon by viewModel.selectedFavicon.collectAsStateWithLifecycle()
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
    val celestialSnapshot by viewModel.celestialSnapshotDisplay.collectAsStateWithLifecycle()
    val walkSummaryCalloutProse by viewModel.walkSummaryCalloutProseDisplay.collectAsStateWithLifecycle()
    // Stage 13-XZ: AI Prompts surface state. Sheet stays Closed until
    // the user taps the section-17 row; transitions through Loading →
    // Listing → Detail / Editor.
    val promptsSheetState by viewModel.promptsSheetState.collectAsStateWithLifecycle()
    val customPromptStyles by viewModel.customPromptStyles.collectAsStateWithLifecycle()

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

    // Stage 13-B: reveal phase machine. Hidden -> Zoomed -> Revealed.
    // Re-keys on the loaded walkId so re-entering a different walk replays;
    // re-entering the SAME walk via back-nav also replays (matches iOS).
    val loadedWalkId = (state as? WalkSummaryUiState.Loaded)?.summary?.walk?.id
    var revealPhase by remember(loadedWalkId) { mutableStateOf(RevealPhase.Hidden) }
    var zoomTargetBounds by remember(loadedWalkId) {
        mutableStateOf<MapCameraBounds?>(null)
    }
    val reduceMotion = remember {
        android.provider.Settings.Global.getFloat(
            context.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }
    LaunchedEffect(loadedWalkId) {
        val s = state
        if (s !is WalkSummaryUiState.Loaded) return@LaunchedEffect
        if (s.summary.routePoints.isEmpty() || reduceMotion) {
            revealPhase = RevealPhase.Revealed
            return@LaunchedEffect
        }
        revealPhase = RevealPhase.Zoomed
        delay(ZOOM_HOLD_MS)
        revealPhase = RevealPhase.Revealed
    }
    val targetDistance =
        (state as? WalkSummaryUiState.Loaded)?.summary?.distanceMeters?.toFloat() ?: 0f
    // Reduce-motion: snap to target instantly with a zero-duration tween.
    // iOS uses `@Environment(\.accessibilityReduceMotion)` to bypass the
    // count-up entirely; Android's equivalent is `ANIMATOR_DURATION_SCALE`.
    val animatedDistanceMeters by animateFloatAsState(
        targetValue = if (revealPhase == RevealPhase.Revealed) targetDistance else 0f,
        animationSpec = if (reduceMotion) {
            tween(durationMillis = 0)
        } else {
            tween(durationMillis = COUNT_UP_DURATION_MS, easing = SmoothStepEasing)
        },
        label = "summary-distance-countup",
    )

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
        Column(modifier = Modifier.fillMaxSize()) {
            // Stage 13-A: top bar lives OUTSIDE the scroll — iOS parity
            // (a top bar shouldn't slide off as the user scrolls). The
            // title is null during Loading + NotFound — TopBar renders
            // an empty title slot rather than displaying a placeholder
            // date like "January 1, 1970".
            val titleTimestamp = (state as? WalkSummaryUiState.Loaded)
                ?.summary?.walk?.startTimestamp
            WalkSummaryTopBar(
                startTimestamp = titleTimestamp,
                onDone = onDone,
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(PilgrimSpacing.big),
            ) {
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
                        // Stage 13-B: theme-resolved per-activity polyline colors.
                        // Lives inside the Loaded branch because pilgrimColors is
                        // theme-scoped at composition.
                        val segmentColors = RouteSegmentColors(
                            walking = pilgrimColors.moss,
                            talking = pilgrimColors.rust,
                            meditating = pilgrimColors.dawn,
                        )
                        val walkAnnotationColors = WalkAnnotationColors(
                            startEnd = pilgrimColors.stone,
                            meditation = pilgrimColors.dawn,
                            voice = pilgrimColors.rust,
                        )

                        // 1. Map — Stage 13-B bumps height to 320dp + adds the
                        // radial-gradient circular mask + plumbs the reveal
                        // phase + segment colors through to PilgrimMap.
                        SummaryMap(
                            points = s.summary.routePoints,
                            routeSegments = s.summary.routeSegments,
                            revealPhase = revealPhase,
                            segmentColors = segmentColors,
                            reduceMotion = reduceMotion,
                            walkAnnotations = s.summary.walkAnnotations,
                            walkAnnotationColors = walkAnnotationColors,
                            zoomTargetBounds = zoomTargetBounds,
                        )
                        Spacer(Modifier.height(PilgrimSpacing.normal))

                        // 2. Photo Reliquary
                        PhotoReliquarySection(
                            photos = pinnedPhotos,
                            onPinPhotos = viewModel::pinPhotos,
                            onUnpinPhoto = viewModel::unpinPhoto,
                        )

                        // 3. Intention card (guarded — only when set)
                        val intention = s.summary.walk.intention
                        if (!intention.isNullOrBlank()) {
                            Spacer(Modifier.height(PilgrimSpacing.normal))
                            WalkIntentionCard(intention = intention)
                        }

                        // Stage 13-XZ: per-section reveal stagger replaces the
                        // single-block AnimatedVisibility wrapper. Each of
                        // sections 5-11 fades in independently via
                        // Modifier.alpha(rememberRevealAlpha(...)), matching
                        // iOS WalkSummaryView.swift line 320-542 timing.
                        // ElevationProfile + sections 12-15 render WITHOUT
                        // alpha (iOS doesn't fade them either). The Spacer
                        // here provides the gap from the section above
                        // (Reliquary or IntentionCard); inter-section spacing
                        // is handled by the spacedBy arrangement of the
                        // wrapper Column below.
                        Spacer(Modifier.height(PilgrimSpacing.normal))
                        Column(
                            verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.normal),
                        ) {
                            // 4. Elevation profile (Stage 13-F). NO alpha —
                            // iOS doesn't fade the chart on reveal.
                            // `remember` the altitudeMeters projection — the
                            // animated count-up below re-invalidates this
                            // Column lambda every frame for ~1s during reveal,
                            // and the underlying altitudeSamples list is stable
                            // across recomposes (immutable WalkSummary).
                            val altitudeMeters = remember(s.summary.altitudeSamples) {
                                s.summary.altitudeSamples.map { it.altitudeMeters }
                            }
                            ElevationProfile(
                                altitudes = altitudeMeters,
                                units = distanceUnits,
                            )

                            // 5. Journey quote — duration 800ms, delay 0.
                            WalkJourneyQuote(
                                talkMillis = s.summary.talkMillis,
                                meditateMillis = s.summary.totalMeditatedMillis,
                                distanceMeters = s.summary.distanceMeters,
                                distanceUnits = distanceUnits,
                                modifier = Modifier.alpha(
                                    rememberRevealAlpha(
                                        revealPhase = revealPhase,
                                        durationMs = REVEAL_DURATION_QUOTE_MS,
                                        delayMs = 0,
                                        reduceMotion = reduceMotion,
                                    ),
                                ),
                            )

                            // 6. Duration hero — duration 600ms, delay 0,
                            // fires on Zoomed (one phase earlier than the
                            // rest) so the duration text appears WITH the
                            // map zoom, matching iOS.
                            WalkDurationHero(
                                durationMillis = s.summary.activeMillis,
                                modifier = Modifier.alpha(
                                    rememberRevealAlpha(
                                        revealPhase = revealPhase,
                                        durationMs = REVEAL_DURATION_HERO_MS,
                                        delayMs = 0,
                                        reduceMotion = reduceMotion,
                                        fireOnZoomed = true,
                                    ),
                                ),
                            )

                            // 7. Milestone callout (Stage 13-Cel — iOS computeMilestone
                            // priority chain: SeasonalMarker → LongestMeditation →
                            // LongestWalk → TotalDistance → null. NO fallthrough to
                            // FirstWalk/FirstOfSeason/NthWalk on Walk Summary callout
                            // — those only appear on Goshuin grid).
                            // Duration 800ms, delay 300ms.
                            walkSummaryCalloutProse?.let { prose ->
                                MilestoneCalloutRow(
                                    prose = prose,
                                    modifier = Modifier.alpha(
                                        rememberRevealAlpha(
                                            revealPhase = revealPhase,
                                            durationMs = REVEAL_DURATION_CALLOUT_MS,
                                            delayMs = REVEAL_DELAY_CELESTIAL_MS,
                                            reduceMotion = reduceMotion,
                                        ),
                                    ),
                                )
                            }

                            // 8. Stats row — distance value animates 0 → final
                            // on reveal. Duration 600ms, delay 200ms.
                            WalkStatsRow(
                                distanceMeters = animatedDistanceMeters.toDouble(),
                                ascendMeters = s.summary.ascendMeters,
                                units = distanceUnits,
                                modifier = Modifier.alpha(
                                    rememberRevealAlpha(
                                        revealPhase = revealPhase,
                                        durationMs = REVEAL_DURATION_DEFAULT_MS,
                                        delayMs = REVEAL_DELAY_STATS_MS,
                                        reduceMotion = reduceMotion,
                                    ),
                                ),
                            )

                            // 9. Weather line (Stage 12-A). Renders only when the
                            // persisted condition resolves to a known enum AND a
                            // temperature is present — legacy walks captured before
                            // Stage 12-A have null weather columns and skip silently.
                            // Imperial coupling follows the user's distance
                            // preference, matching iOS where temperature units track
                            // `distanceMeasurementType`. Duration 600ms, delay 200ms.
                            s.summary.walk.weatherCondition?.let { conditionRaw ->
                                val condition = WeatherCondition.fromRawValue(conditionRaw)
                                    ?: return@let
                                val temperature = s.summary.walk.weatherTemperature
                                    ?: return@let
                                WalkSummaryWeatherLine(
                                    condition = condition,
                                    temperatureCelsius = temperature,
                                    imperial = distanceUnits == UnitSystem.Imperial,
                                    modifier = Modifier.alpha(
                                        rememberRevealAlpha(
                                            revealPhase = revealPhase,
                                            durationMs = REVEAL_DURATION_DEFAULT_MS,
                                            delayMs = REVEAL_DELAY_STATS_MS,
                                            reduceMotion = reduceMotion,
                                        ),
                                    ),
                                )
                            }

                            // 10. Celestial line (Stage 13-Cel). Gated by VM's
                            // celestialSnapshotDisplay flow which combines summary
                            // + practicePreferences.celestialAwarenessEnabled.
                            // Duration 600ms, delay 300ms.
                            celestialSnapshot?.let { snap ->
                                CelestialLineRow(
                                    snapshot = snap,
                                    modifier = Modifier.alpha(
                                        rememberRevealAlpha(
                                            revealPhase = revealPhase,
                                            durationMs = REVEAL_DURATION_DEFAULT_MS,
                                            delayMs = REVEAL_DELAY_CELESTIAL_MS,
                                            reduceMotion = reduceMotion,
                                        ),
                                    ),
                                )
                            }

                            // 11. Time breakdown grid — Walk card uses
                            // activeWalkingMillis (paused-AND-meditate-excluded).
                            // Duration 600ms, delay 400ms.
                            WalkTimeBreakdownGrid(
                                walkMillis = s.summary.activeWalkingMillis,
                                talkMillis = s.summary.talkMillis,
                                meditateMillis = s.summary.totalMeditatedMillis,
                                modifier = Modifier.alpha(
                                    rememberRevealAlpha(
                                        revealPhase = revealPhase,
                                        durationMs = REVEAL_DURATION_DEFAULT_MS,
                                        delayMs = REVEAL_DELAY_BREAKDOWN_MS,
                                        reduceMotion = reduceMotion,
                                    ),
                                ),
                            )

                            // 12. Favicon selector (Stage 13-E) — NO alpha,
                            // renders immediately (matches iOS).
                            FaviconSelectorCard(
                                selected = selectedFavicon,
                                onSelect = viewModel::setFavicon,
                            )

                            // 13. Activity timeline bar (Stage 13-C)
                            WalkActivityTimelineCard(
                                startTimestamp = s.summary.walk.startTimestamp,
                                endTimestamp = s.summary.walk.endTimestamp ?: s.summary.walk.startTimestamp,
                                voiceRecordings = s.summary.voiceRecordings,
                                activityIntervals = s.summary.meditationIntervals,
                                routeSamples = s.summary.routeSamples,
                                units = distanceUnits,
                                onSegmentSelected = { startMs, endMs ->
                                    zoomTargetBounds = computeBoundsForTimeRange(
                                        samples = s.summary.routeSamples,
                                        startMs = startMs,
                                        endMs = endMs,
                                    )
                                },
                                onSegmentDeselected = { zoomTargetBounds = null },
                            )

                            // 14. Activity insights (Stage 13-C)
                            if (s.summary.talkMillis > 0L || s.summary.meditationIntervals.isNotEmpty()) {
                                WalkActivityInsightsCard(
                                    talkMillis = s.summary.talkMillis,
                                    activeMillis = s.summary.activeMillis,
                                    meditationIntervals = s.summary.meditationIntervals,
                                )
                            }

                            // 15. Activity list (Stage 13-C)
                            if (s.summary.voiceRecordings.isNotEmpty() || s.summary.meditationIntervals.isNotEmpty()) {
                                WalkActivityListCard(
                                    voiceRecordings = s.summary.voiceRecordings,
                                    meditationIntervals = s.summary.meditationIntervals,
                                )
                            }
                        }

                        // 16. Voice recordings (Stage 2-E)
                        if (recordings.isNotEmpty()) {
                            Spacer(Modifier.height(PilgrimSpacing.normal))
                            VoiceRecordingsSection(
                                walkStartTimestamp = s.summary.walk.startTimestamp,
                                recordings = recordings,
                                playbackUiState = playbackUiState,
                                onPlay = viewModel::playRecording,
                                onPause = viewModel::pausePlayback,
                            )
                        }

                        // 17. AI Prompts button (Stage 13-XZ). Subtitle reflects
                        // the count of recordings whose transcription has
                        // resolved — see AIPromptsRow for the plural / "Reflect
                        // on your walk" branch when count == 0.
                        Spacer(Modifier.height(PilgrimSpacing.normal))
                        val transcribedRecordingsCount = recordings.count {
                            it.transcription != null
                        }
                        AIPromptsRow(
                            transcribedRecordingsCount = transcribedRecordingsCount,
                            onClick = viewModel::openPromptsSheet,
                        )

                        // 18. Details (Stage 13-G)
                        if (s.summary.totalPausedMillis > 0L) {
                            Spacer(Modifier.height(PilgrimSpacing.normal))
                            WalkSummaryDetailsCard(pausedMillis = s.summary.totalPausedMillis)
                        }

                        // 19. Light Reading card (Stage 6-B / 10-C). VM's
                        // runCatching means a compute failure yields null
                        // here and the card just doesn't render. Read from
                        // `lightReadingDisplay` (live combine of summary +
                        // celestialAwarenessEnabled pref) so toggling the
                        // pref while the summary is open immediately shows /
                        // hides the card.
                        lightReadingDisplay?.let { reading ->
                            Spacer(Modifier.height(PilgrimSpacing.normal))
                            WalkLightReadingCard(reading = reading)
                        }

                        // 20. Etegami + Share Journey (Stage 7-D + 8-A).
                        // Wrap BOTH the etegami card + share row AND the
                        // journey-share row in a single hasRoute guard — iOS
                        // WalkSharingButtons parity (whole card is absent for
                        // walks with fewer than 2 GPS points). Share endpoint
                        // also rejects routes < 2 points server-side.
                        if (s.summary.routePoints.size >= 2) {
                            s.summary.etegamiSpec?.let { etegami ->
                                Spacer(Modifier.height(PilgrimSpacing.normal))
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
                            // `cachedShare` is collected unconditionally at the
                            // composable's top (see hoisting comment there);
                            // map to the row's tri-state at the call site.
                            val rowState = cachedShare.toJourneyRowState()
                            Spacer(Modifier.height(PilgrimSpacing.normal))
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
                    }
                }

                Spacer(Modifier.height(PilgrimSpacing.breathingRoom))
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

        // Stage 13-XZ: AI Prompts sheet + dialogs. The Listing sheet
        // stays mounted under the Detail / Editor dialog so dismissing
        // the dialog returns the user to the listing without rebuilding.
        // Loading state intentionally renders nothing — first open shows
        // a brief gap (~0-2s) before the listing materialises; subsequent
        // opens hit the cache and go straight to Listing instantly.
        val promptsListing: PromptsSheetState.Listing? = when (val sheet = promptsSheetState) {
            is PromptsSheetState.Listing -> sheet
            is PromptsSheetState.Detail -> sheet.listing
            is PromptsSheetState.Editor -> sheet.listing
            else -> null
        }
        promptsListing?.let { listing ->
            val (builtIn, custom) = listing.prompts.splitAt(6)
            PromptListSheet(
                builtInPrompts = builtIn,
                customPrompts = custom,
                onPromptClick = viewModel::openPromptDetail,
                onCreateCustom = { viewModel.openCustomPromptEditor(editing = null) },
                onEditCustom = { viewModel.openCustomPromptEditor(editing = it) },
                onDeleteCustom = viewModel::deleteCustomPrompt,
                onDismiss = viewModel::closePromptsSheet,
            )
        }
        when (val sheet = promptsSheetState) {
            is PromptsSheetState.Detail -> PromptDetailDialog(
                prompt = sheet.prompt,
                onDismiss = viewModel::dismissDetailOrEditor,
            )
            is PromptsSheetState.Editor -> CustomPromptEditorDialog(
                editing = sheet.editing,
                existingStyleCount = customPromptStyles.size,
                onSave = viewModel::saveCustomPrompt,
                onDismiss = viewModel::dismissDetailOrEditor,
            )
            else -> Unit
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

/**
 * Stage 13-XZ: split a flat prompts list at the built-in/custom boundary.
 * `PromptsCoordinator` returns 6 built-in prompts followed by N custom
 * prompts (one per [CustomPromptStyle]) — see
 * [org.walktalkmeditate.pilgrim.core.prompt.PromptsCoordinator] contract.
 * This split is then handed to [PromptListSheet] as the parallel built-in
 * + custom lists it expects.
 */
private fun <T> List<T>.splitAt(index: Int): Pair<List<T>, List<T>> {
    if (index >= size) return this to emptyList()
    return take(index) to drop(index)
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
private fun SummaryMap(
    points: List<org.walktalkmeditate.pilgrim.domain.LocationPoint>,
    routeSegments: List<RouteSegment>,
    revealPhase: RevealPhase,
    segmentColors: RouteSegmentColors,
    reduceMotion: Boolean,
    walkAnnotations: List<WalkMapAnnotation>,
    walkAnnotationColors: WalkAnnotationColors,
    zoomTargetBounds: MapCameraBounds?,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(320.dp)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithCache {
                // iOS RadialGradient mask: opaque center -> transparent edge.
                // 0.45 stop matches iOS's 80/180 startRadius/endRadius ratio.
                val brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0f to Color.White,
                        0.45f to Color.White,
                        1f to Color.Transparent,
                    ),
                    center = Offset(size.width / 2f, size.height / 2f),
                    radius = size.minDimension / 2f,
                )
                onDrawWithContent {
                    drawContent()
                    drawRect(brush = brush, blendMode = BlendMode.DstIn)
                }
            },
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
                routeSegments = routeSegments,
                segmentColors = segmentColors,
                revealPhase = revealPhase,
                reduceMotion = reduceMotion,
                followLatest = false,
                modifier = Modifier.fillMaxSize(),
                walkAnnotations = walkAnnotations,
                walkAnnotationColors = walkAnnotationColors,
                zoomTargetBounds = zoomTargetBounds,
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
            .height(320.dp),
        colors = CardDefaults.cardColors(
            containerColor = pilgrimColors.parchmentSecondary,
            contentColor = pilgrimColors.fog,
        ),
    ) {}
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

/**
 * Stage 12-A: weather summary line. Renders unconditionally when
 * called — the WalkSummaryScreen caller is responsible for the
 * `weatherCondition != null && weatherTemperature != null` guard.
 *
 * Visuals mirror iOS `WalkSummaryView.weatherLine` (16dp icon, xs
 * spacer, caption-styled "{label}, {N}°{unit}" text, fog tint).
 *
 * `imperial = true` switches both the conversion (C → F) and the
 * suffix glyph. The coupling matches iOS where temperature units
 * track the user's distance preference.
 */
@Composable
fun WalkSummaryWeatherLine(
    condition: WeatherCondition,
    temperatureCelsius: Double,
    imperial: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(condition.iconRes),
            contentDescription = null,
            tint = pilgrimColors.fog,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(PilgrimSpacing.xs))
        Text(
            text = "${stringResource(condition.labelRes)}, ${
                formatTemperature(temperatureCelsius, imperial)
            }",
            style = pilgrimType.caption,
            color = pilgrimColors.fog,
        )
    }
}

private fun formatTemperature(celsius: Double, imperial: Boolean): String {
    val rounded = if (imperial) celsius * 9.0 / 5.0 + 32.0 else celsius
    return String.format(Locale.US, "%.0f°%s", rounded, if (imperial) "F" else "C")
}
