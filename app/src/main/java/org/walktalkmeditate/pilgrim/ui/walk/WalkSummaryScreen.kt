// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.walktalkmeditate.pilgrim.data.entity.WalkFavicon
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.data.walk.RouteSegment
import org.walktalkmeditate.pilgrim.data.walk.WalkMapAnnotation
import org.walktalkmeditate.pilgrim.data.weather.WeatherCondition
import org.walktalkmeditate.pilgrim.ui.etegami.EtegamiBitmapRenderer
import org.walktalkmeditate.pilgrim.ui.etegami.EtegamiSealBitmapRenderer
import org.walktalkmeditate.pilgrim.ui.etegami.share.EtegamiFilename
import org.walktalkmeditate.pilgrim.ui.etegami.share.EtegamiPngWriter
import org.walktalkmeditate.pilgrim.ui.etegami.share.EtegamiShareIntentFactory
import org.walktalkmeditate.pilgrim.ui.walk.summary.SealShareBitmapWriter
import org.walktalkmeditate.pilgrim.ui.walk.summary.WalkSharingButtons
import androidx.compose.runtime.rememberCoroutineScope
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
import org.walktalkmeditate.pilgrim.ui.walk.summary.COUNT_UP_INTERVAL_MS
import org.walktalkmeditate.pilgrim.ui.walk.summary.COUNT_UP_STEPS
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
import org.walktalkmeditate.pilgrim.ui.walk.summary.RevealPhaseSaver
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
    val reliquaryState by viewModel.reliquaryState.collectAsStateWithLifecycle()
    val isPinningInFlight by viewModel.isPinningInFlight.collectAsStateWithLifecycle()
    val distanceUnits by viewModel.distanceUnits.collectAsStateWithLifecycle()
    val lightReadingDisplay by viewModel.lightReadingDisplay.collectAsStateWithLifecycle()
    val selectedFavicon by viewModel.selectedFavicon.collectAsStateWithLifecycle()
    val celestialSnapshot by viewModel.celestialSnapshotDisplay.collectAsStateWithLifecycle()
    val walkSummaryCalloutProse by viewModel.walkSummaryCalloutProseDisplay.collectAsStateWithLifecycle()
    // Stage 13-XZ: AI Prompts surface state. Sheet stays Closed until
    // the user taps the section-17 row; transitions through Loading →
    // Listing → Detail / Editor.
    val promptsSheetState by viewModel.promptsSheetState.collectAsStateWithLifecycle()
    val customPromptStyles by viewModel.customPromptStyles.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.runStartupSweep() }

    // Stage 7-D: VM events drive snackbar feedback + chooser-intent dispatch.
    // `LocalActivity.current` is non-null in practice — MainActivity hosts
    // every screen — but we fall back to a snackbar on null rather than crashing.
    val snackbarHostState = remember { SnackbarHostState() }
    val activity = LocalActivity.current
    val context = LocalContext.current
    val msgSaveSuccess = stringResource(R.string.etegami_save_success)
    val msgSaveFailed = stringResource(R.string.etegami_save_failed)
    val msgShareFailed = stringResource(R.string.etegami_share_failed)
    val msgNeedsPermission = stringResource(R.string.etegami_save_needs_permission)

    // Stage 13-B: reveal phase machine. Hidden -> Zoomed -> Revealed.
    // Re-keys on the loaded walkId so re-entering a different walk replays;
    // re-entering the SAME walk via back-nav also replays (matches iOS).
    val loadedWalkId = (state as? WalkSummaryUiState.Loaded)?.summary?.walk?.id
    var revealPhase by rememberSaveable(
        loadedWalkId,
        stateSaver = RevealPhaseSaver,
    ) { mutableStateOf(RevealPhase.Hidden) }
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
    // iOS WalkSummaryView.swift:378-392 — 31 discrete asyncAfter
    // emissions over 2.0s using smooth-step easing. Android pins
    // 30*67ms = 2010ms total (rounded up from iOS's 66.67ms interval
    // to preserve the perceived rhythm). Animatable is keyed on
    // loadedWalkId so same-walk re-entry resets hard; LaunchedEffect
    // re-key cancels any in-flight loop via Compose structured
    // cancellation. Reduce-motion path snaps to target (matches iOS
    // missing-route fast-path semantics).
    // Latch keyed on loadedWalkId so a rotation mid- or post-cinematic
    // restores the COMPLETED state — the Animatable itself is not
    // saveable (Compose-Foundation gap), so without this latch the
    // LaunchedEffect would re-run the 31-emission loop after every
    // config change. iOS doesn't have this problem because UIKit
    // doesn't recreate views on rotation.
    var countUpCompleted by rememberSaveable(loadedWalkId) { mutableStateOf(false) }
    val countUp = remember(loadedWalkId) {
        Animatable(if (countUpCompleted) targetDistance else 0f)
    }
    LaunchedEffect(loadedWalkId, revealPhase, targetDistance) {
        if (revealPhase != RevealPhase.Revealed) {
            countUp.snapTo(0f)
            countUpCompleted = false
            return@LaunchedEffect
        }
        if (countUpCompleted) {
            countUp.snapTo(targetDistance)
            return@LaunchedEffect
        }
        if (reduceMotion || targetDistance == 0f) {
            countUp.snapTo(targetDistance)
            countUpCompleted = true
            return@LaunchedEffect
        }
        for (i in 0..COUNT_UP_STEPS) {
            val progress = i.toFloat() / COUNT_UP_STEPS
            countUp.snapTo(targetDistance * SmoothStepEasing.transform(progress))
            if (i < COUNT_UP_STEPS) delay(COUNT_UP_INTERVAL_MS)
        }
        countUpCompleted = true
    }
    val animatedDistanceMeters = countUp.value

    // rememberSaveable latch so rotation after the haptic has already
    // fired does not re-fire a second tap. Stays false until the
    // ceremonial moment lands, then sticks for the lifetime of the
    // walk-summary visit (cleared when navigating to a different walk
    // because the latch is keyed on loadedWalkId).
    var hapticFired by rememberSaveable(loadedWalkId) { mutableStateOf(false) }
    // iOS doesn't haptic here, but Pilgrim's Android haptic vocabulary
    // (Stage 5-B temple bell) calls for a single firm tap at the
    // ceremonial moment when the camera releases and the route is
    // revealed. LongPress strength = one firm tap, not slot-machine.
    // Latched per (loadedWalkId, revealPhase) so it fires once per
    // walk visit on the Hidden/Zoomed → Revealed edge; phase machine
    // is monotonic so no oscillation. Suppressed under reduce-motion
    // and on empty-route walks (where the cinematic itself is skipped).
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(loadedWalkId, revealPhase) {
        if (revealPhase != RevealPhase.Revealed) return@LaunchedEffect
        if (hapticFired) return@LaunchedEffect
        if (reduceMotion) return@LaunchedEffect
        val loaded = state as? WalkSummaryUiState.Loaded ?: return@LaunchedEffect
        if (loaded.summary.routePoints.isEmpty()) return@LaunchedEffect
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        hapticFired = true
    }

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

    // Stage 4-B reveal gate. Per-walk-once via SealRevealStore (user-
    // requested divergence from iOS): VM seeds `showSealReveal` from
    // the persisted set on `buildState`; onDismiss calls
    // `viewModel.markSealRevealed()` which writes the uuid to the
    // store + flips the flow false.
    val showReveal by viewModel.showSealReveal.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(pilgrimColors.parchment),
    ) {
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
                        // iOS parity: route + annotation colors are FIXED
                        // assets, not theme-resolved — walk/talk/meditate
                        // must look identical in light/dark/constellation
                        // (was drifting via pilgrimColors.*).
                        val segmentColors = RouteSegmentColors.Fixed
                        val walkAnnotationColors = WalkAnnotationColors.Fixed

                        // 1. Map — Stage 13-B bumps height to 320dp + adds the
                        // radial-gradient circular mask + plumbs the reveal
                        // phase + segment colors through to PilgrimMap.
                        //
                        // iOS parity `WalkSummaryView+Map.swift:34-46@db4196e` —
                        // pinned photos that carry EXIF GPS coords are
                        // appended to the annotation list as Photo pins
                        // at their captured lat/lng. Photos missing GPS
                        // (indoor shots, screenshots, stripped metadata)
                        // are omitted from the map but still appear in
                        // the reliquary carousel below.
                        val combinedAnnotations = remember(s.summary.walkAnnotations, pinnedPhotos) {
                            val photoAnnotations = pinnedPhotos.mapNotNull { p ->
                                val lat = p.capturedLat
                                val lng = p.capturedLng
                                if (lat == null || lng == null) {
                                    null
                                } else {
                                    org.walktalkmeditate.pilgrim.data.walk.WalkMapAnnotation(
                                        kind = org.walktalkmeditate.pilgrim.data.walk
                                            .WalkMapAnnotationKind.Photo(
                                                walkPhotoId = p.id,
                                                photoUri = p.photoUri,
                                            ),
                                        latitude = lat,
                                        longitude = lng,
                                    )
                                }
                            }
                            s.summary.walkAnnotations + photoAnnotations
                        }
                        SummaryMap(
                            points = s.summary.routePoints,
                            routeSegments = s.summary.routeSegments,
                            revealPhase = revealPhase,
                            segmentColors = segmentColors,
                            reduceMotion = reduceMotion,
                            walkAnnotations = combinedAnnotations,
                            walkAnnotationColors = walkAnnotationColors,
                            zoomTargetBounds = zoomTargetBounds,
                        )
                        Spacer(Modifier.height(PilgrimSpacing.normal))

                        // 2. Photo Reliquary
                        PhotoReliquarySection(
                            state = reliquaryState,
                            onTogglePin = viewModel::togglePin,
                            onForegrounded = viewModel::onForegrounded,
                            isPinningInFlight = isPinningInFlight,
                            onSettingsClick = {
                                val intent = android.content.Intent(
                                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    android.net.Uri.fromParts("package", context.packageName, null),
                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                try {
                                    context.startActivity(intent)
                                } catch (e: android.content.ActivityNotFoundException) {
                                    android.util.Log.w(
                                        "PhotoReliquary",
                                        "Settings deep link unavailable",
                                        e,
                                    )
                                }
                            },
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
                                steps = s.summary.walk.steps,
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
                            val playbackSpeed by viewModel.playbackSpeed
                                .collectAsStateWithLifecycle()
                            val waveforms by viewModel.waveforms
                                .collectAsStateWithLifecycle()
                            Spacer(Modifier.height(PilgrimSpacing.normal))
                            VoiceRecordingsSection(
                                walkStartTimestamp = s.summary.walk.startTimestamp,
                                recordings = recordings,
                                playbackUiState = playbackUiState,
                                playbackSpeed = playbackSpeed,
                                // Pass the StateFlow itself (not a
                                // collected snapshot) so the per-row
                                // WaveformBarView can subscribe directly
                                // inside its own composable scope. The
                                // 100ms tick then recomposes the bar
                                // only — screen + header + non-playing
                                // rows stay stable.
                                playbackPositionMillisFlow =
                                    viewModel.playbackPositionMillis,
                                waveforms = waveforms,
                                onPlay = viewModel::playRecording,
                                onPause = viewModel::pausePlayback,
                                onCycleSpeed = viewModel::cyclePlaybackSpeed,
                                onSeek = viewModel::seekPlayback,
                                onSaveTranscription = viewModel::saveTranscription,
                                onRetranscribe = viewModel::retranscribeRecording,
                                onEnsureWaveform = viewModel::ensureWaveform,
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

                        // 19. Light Reading card (iOS body line 86 parity).
                        // Gated on viewModel.hasRevealedLightReading — only
                        // visible AFTER the user has shared this walk via
                        // Goshuin / Etegami / Walk Share Journey. Fades in
                        // over 1200ms (instant under reduce-motion) per iOS
                        // markSharedAndReveal() animation.
                        //
                        // VM's runCatching means a compute failure yields
                        // null in `lightReadingDisplay` and the card just
                        // doesn't render. Read from `lightReadingDisplay`
                        // (live combine of summary + celestialAwarenessEnabled
                        // pref) so toggling the pref while the summary is
                        // open immediately shows / hides the card.
                        val hasRevealedLR by viewModel.hasRevealedLightReading
                            .collectAsStateWithLifecycle()
                        androidx.compose.animation.AnimatedVisibility(
                            visible = hasRevealedLR && lightReadingDisplay != null,
                            enter = androidx.compose.animation.fadeIn(
                                animationSpec = androidx.compose.animation.core.tween(
                                    durationMillis = if (reduceMotion) 0 else 1200,
                                ),
                            ),
                            exit = androidx.compose.animation.fadeOut(),
                        ) {
                            lightReadingDisplay?.let { reading ->
                                androidx.compose.foundation.layout.Column {
                                    Spacer(Modifier.height(PilgrimSpacing.normal))
                                    WalkLightReadingCard(reading = reading)
                                }
                            }
                        }

                        // 20. WalkSharingButtons — iOS body line ~90 shareCard.
                        // Replaces the prior scattered WalkEtegamiCard + WalkEtegamiShareRow +
                        // WalkShareJourneyRow trio with a single parchmentSecondary card.
                        // Light Reading card stays at section 19 for now; Task 4.2 moves + gates it.
                        Spacer(Modifier.height(PilgrimSpacing.normal))
                        // rememberSaveable so a rotation mid-render doesn't
                        // reset the latch and let a duplicate tap through.
                        val isGoshuinGenerating = rememberSaveable { mutableStateOf(false) }
                        val isEtegamiGenerating = rememberSaveable { mutableStateOf(false) }
                        val shareScope = rememberCoroutineScope()
                        // Hoist share-related strings into Composable scope —
                        // lint's LocalContextGetResourceValueCall flags
                        // ctx.getString() inside lambdas even though the
                        // lambda body runs outside the composition.
                        val goshuinChooserTitle =
                            stringResource(R.string.share_button_goshuin)
                        val etegamiChooserTitle =
                            stringResource(R.string.share_button_etegami)
                        val shareNoChooserMessage =
                            stringResource(R.string.share_no_chooser)
                        val baseInk = pilgrimColors.rust
                        val walkDate = remember(s.summary.walk.startTimestamp) {
                            java.time.Instant.ofEpochMilli(s.summary.walk.startTimestamp)
                                .atZone(java.time.ZoneId.systemDefault())
                                .toLocalDate()
                        }
                        val tintedInk = remember(baseInk, walkDate, hemisphere) {
                            org.walktalkmeditate.pilgrim.ui.theme.seasonal.SeasonalColorEngine
                                .applySeasonalShift(
                                    base = baseInk,
                                    intensity = org.walktalkmeditate.pilgrim.ui.theme.seasonal
                                        .SeasonalColorEngine.Intensity.Full,
                                    date = walkDate,
                                    hemisphere = hemisphere,
                                )
                        }
                        WalkSharingButtons(
                            hasRoute = s.summary.routePoints.size >= 2,
                            isGoshuinGenerating = isGoshuinGenerating.value,
                            isEtegamiGenerating = isEtegamiGenerating.value,
                            onGoshuinShare = {
                                if (isGoshuinGenerating.value) return@WalkSharingButtons
                                isGoshuinGenerating.value = true
                                shareScope.launch {
                                    try {
                                        val sealSpec = s.summary.sealSpec
                                        val bmp = withContext(Dispatchers.Default) {
                                            EtegamiSealBitmapRenderer.renderToBitmap(
                                                sealSpec, tintedInk, 512, context,
                                            )
                                        }
                                        val file = SealShareBitmapWriter.writeToCache(
                                            bmp, s.summary.walk.uuid, context,
                                        )
                                        val intent = EtegamiShareIntentFactory.buildFromFile(
                                            context, file,
                                            goshuinChooserTitle,
                                        )
                                        try {
                                            context.startActivity(intent)
                                            viewModel.markCurrentWalkShared()
                                        } catch (_: android.content.ActivityNotFoundException) {
                                            shareScope.launch {
                                                snackbarHostState.showSnackbar(
                                                    shareNoChooserMessage,
                                                )
                                            }
                                        }
                                    } catch (ce: kotlinx.coroutines.CancellationException) {
                                        throw ce
                                    } catch (t: Throwable) {
                                        android.util.Log.w("WalkSummaryScreen", "goshuin share failed", t)
                                    } finally {
                                        isGoshuinGenerating.value = false
                                    }
                                }
                            },
                            onEtegamiShare = {
                                if (isEtegamiGenerating.value) return@WalkSharingButtons
                                val spec = s.summary.etegamiSpec ?: return@WalkSharingButtons
                                isEtegamiGenerating.value = true
                                shareScope.launch {
                                    try {
                                        val bmp = withContext(Dispatchers.Default) {
                                            EtegamiBitmapRenderer.render(spec, context)
                                        }
                                        val filename = EtegamiFilename.forWalk(spec.startedAtEpochMs)
                                        val file = EtegamiPngWriter.writeToCache(bmp, filename, context)
                                        val intent = EtegamiShareIntentFactory.buildFromFile(
                                            context, file,
                                            etegamiChooserTitle,
                                        )
                                        try {
                                            context.startActivity(intent)
                                            viewModel.markCurrentWalkShared()
                                        } catch (_: android.content.ActivityNotFoundException) {
                                            shareScope.launch {
                                                snackbarHostState.showSnackbar(
                                                    shareNoChooserMessage,
                                                )
                                            }
                                        }
                                    } catch (ce: kotlinx.coroutines.CancellationException) {
                                        throw ce
                                    } catch (t: Throwable) {
                                        android.util.Log.w("WalkSummaryScreen", "etegami share failed", t)
                                    } finally {
                                        isEtegamiGenerating.value = false
                                    }
                                }
                            },
                            onWalkJourneyShare = {
                                onShareJourney()
                                viewModel.markCurrentWalkShared()
                            },
                        )
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
                onDismiss = viewModel::markSealRevealed,
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
            // Partition by GeneratedPrompt origin: built-in prompts carry a
            // non-null `style`; custom prompts carry `style == null` +
            // `customStyle != null`. Robust to any future change in the
            // built-in PromptStyle.entries count (was previously a hardcoded
            // splitAt(6) which would silently bucket a 7th built-in into
            // the custom list).
            val (builtIn, custom) = listing.prompts.partition { it.style != null }
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
    // Mapbox MapView renders into a SurfaceView by default — a separate
    // hardware window that punches through Compose graphics layers and
    // can't be masked via DstIn. The PilgrimMap caller below opts in to
    // textureBackend = true so the map renders into the parent canvas;
    // a Canvas overlay then paints a parchment radial-gradient frame ON
    // TOP, producing the same iOS RadialGradient mask effect (transparent
    // center → opaque-parchment corners). Mask color matches the screen
    // bg (pilgrimColors.parchment) so corners dissolve invisibly into the
    // page — iOS parity (WalkSummaryView+Map.swift@db4196e uses
    // .mask(RadialGradient) which fades to whatever's behind = page bg).
    val parchmentMask = pilgrimColors.parchment
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(320.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
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
                    // TextureView so the Canvas radial-gradient mask
                    // actually covers map pixels at the corners.
                    textureBackend = true,
                )
            }
            // Radial-gradient frame overlay. Inner 30% fully transparent
            // (clear map view), 30% → 100% fades to opaque parchment so
            // corners blend with the screen background — more pronounced
            // than the iOS 80/180 (0.44) ratio because Android dark-mode
            // colors are closer to the map style and need the extra
            // contrast. Pointer events pass through.
            Canvas(modifier = Modifier.fillMaxSize()) {
                val brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0f to Color.Transparent,
                        0.30f to Color.Transparent,
                        1f to parchmentMask,
                    ),
                    center = Offset(size.width / 2f, size.height / 2f),
                    radius = size.minDimension / 2f,
                )
                drawRect(brush = brush)
            }
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
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
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
