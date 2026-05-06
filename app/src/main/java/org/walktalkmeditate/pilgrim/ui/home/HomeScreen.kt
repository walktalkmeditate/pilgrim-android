// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.collectLatest
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.sounds.LocalSoundsEnabled
import org.walktalkmeditate.pilgrim.permissions.PermissionsViewModel
import org.walktalkmeditate.pilgrim.ui.design.calligraphy.CalligraphyPath
import org.walktalkmeditate.pilgrim.ui.design.calligraphy.CalligraphyStrokeSpec
import org.walktalkmeditate.pilgrim.ui.design.calligraphy.SeasonalInkFlavor
import org.walktalkmeditate.pilgrim.ui.design.calligraphy.dotPositions
import org.walktalkmeditate.pilgrim.ui.design.calligraphy.toBaseColor
import org.walktalkmeditate.pilgrim.ui.home.dot.WalkDot
import org.walktalkmeditate.pilgrim.ui.home.dot.WalkDotMath
import org.walktalkmeditate.pilgrim.ui.home.scroll.JournalHapticDispatcher
import org.walktalkmeditate.pilgrim.ui.home.scroll.ScrollHapticState
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.SeasonalColorEngine

private val JOURNAL_ROW_HEIGHT = 90.dp
private val JOURNAL_TOP_INSET_DP = 40.dp
private val JOURNAL_MAX_MEANDER = 100.dp

/**
 * Stage 14-A LazyColumn migration: each row is a [WalkDot]; chrome
 * (header, expand sheet, FAB, BatteryExemptionCard) returns in Bucket
 * 14-B / 14-D via the dedicated [JournalScreen]. The viewport-center
 * Y is fed into [ScrollHapticState] → [JournalHapticDispatcher] so a
 * scroll fires light/heavy haptics as dots cross center.
 *
 * `permissionsViewModel` and `onEnterWalkSummary` are unused for 14-A
 * (BatteryExemptionCard moves to JournalScreen in Bucket 14-D; row tap
 * opens the expand sheet via `setExpandedSnapshotId` rather than
 * navigating to the summary directly). Kept as parameters so the
 * NavHost call site doesn't need to change mid-stage.
 */
@Composable
fun HomeScreen(
    permissionsViewModel: PermissionsViewModel,
    onEnterWalkSummary: (Long) -> Unit,
    onEnterGoshuin: () -> Unit,
    homeViewModel: HomeViewModel = hiltViewModel(),
) {
    val journalState by homeViewModel.journalState.collectAsStateWithLifecycle()
    val hemisphere by homeViewModel.hemisphere.collectAsStateWithLifecycle()
    val latestSealBitmap by homeViewModel.latestSealBitmap.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val soundsEnabled = LocalSoundsEnabled.current
    // Closure-captured rememberUpdatedState so the dispatcher's
    // `soundsEnabledProvider` always sees the current Compose value
    // without re-instantiating the dispatcher on every flip.
    val soundsEnabledState = rememberUpdatedState(soundsEnabled)
    val dispatcher = remember(context) {
        JournalHapticDispatcher(
            context = context,
            soundsEnabledProvider = { soundsEnabledState.value },
        )
    }
    val density = LocalDensity.current
    val verticalSpacingPx = with(density) { JOURNAL_ROW_HEIGHT.toPx() }
    val topInsetPx = with(density) { JOURNAL_TOP_INSET_DP.toPx() }
    // iOS thresholds are in points; Android equivalent is dp. Convert
    // here so haptic windows are physically consistent across densities
    // (raw `20f` would shrink to 5dp on xxxhdpi screens and miss dots).
    val dotThresholdPx = with(density) { 20.dp.toPx() }
    val milestoneThresholdPx = with(density) { 25.dp.toPx() }
    val largeDotCutoffPx = with(density) { 15.dp.toPx() }

    val maxMeanderPx = with(density) { JOURNAL_MAX_MEANDER.toPx() }
    val loaded = journalState as? JournalUiState.Loaded
    val snapshots = loaded?.snapshots ?: emptyList()
    val sizesPx = remember(snapshots) {
        snapshots.map { with(density) { WalkDotMath.dotSize(it.durationSec).dp.toPx() } }
    }
    // Build calligraphy strokes from snapshots (newest-first ordering
    // matches CalligraphyPath's `index 0 = newest` convention — see
    // segmentOpacity/taperFactor docs). Seasonal HSB shift applied
    // per-stroke via the SeasonalColorEngine. Stage 3-E pattern.
    val inkBase = SeasonalInkFlavor.Ink.toBaseColor()
    val mossBase = SeasonalInkFlavor.Moss.toBaseColor()
    val rustBase = SeasonalInkFlavor.Rust.toBaseColor()
    val dawnBase = SeasonalInkFlavor.Dawn.toBaseColor()
    val baseColors = remember(inkBase, mossBase, rustBase, dawnBase) {
        mapOf(
            SeasonalInkFlavor.Ink to inkBase,
            SeasonalInkFlavor.Moss to mossBase,
            SeasonalInkFlavor.Rust to rustBase,
            SeasonalInkFlavor.Dawn to dawnBase,
        )
    }
    val strokes: List<CalligraphyStrokeSpec> = remember(snapshots, hemisphere, baseColors) {
        snapshots.map { snap ->
            val walkDate = Instant.ofEpochMilli(snap.startMs)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
            val flavor = SeasonalInkFlavor.forMonth(snap.startMs)
            val base = baseColors.getValue(flavor)
            val tint = SeasonalColorEngine.applySeasonalShift(
                base = base,
                intensity = SeasonalColorEngine.Intensity.Moderate,
                date = walkDate,
                hemisphere = hemisphere,
            )
            CalligraphyStrokeSpec(
                uuid = snap.uuid,
                startMillis = snap.startMs,
                distanceMeters = snap.distanceM,
                averagePaceSecPerKm = snap.averagePaceSecPerKm,
                ink = tint,
            )
        }
    }
    // Dot Y in canvas-space matches the formula used by
    // `CalligraphyPath.dotPositions` so when Stage 14-D layers the
    // calligraphy canvas behind the LazyColumn, haptic-fire positions,
    // dot draw positions, and lunar/milestone-marker positions all
    // share one origin: `topInset + spacing * i + spacing / 2`. The
    // LazyColumn applies `contentPadding(top = topInset)` below so
    // item-0's visual top sits at y = topInset.
    val dotYsPx = remember(snapshots, topInsetPx, verticalSpacingPx) {
        snapshots.indices.map {
            topInsetPx + verticalSpacingPx * it + verticalSpacingPx / 2f
        }
    }
    val hapticState = remember(snapshots, dotThresholdPx, milestoneThresholdPx, largeDotCutoffPx) {
        ScrollHapticState(
            dotPositionsPx = dotYsPx,
            dotSizesPx = sizesPx,
            milestonePositionsPx = emptyList(),
            largeDotCutoffPx = largeDotCutoffPx,
            dotThresholdPx = dotThresholdPx,
            milestoneThresholdPx = milestoneThresholdPx,
        )
    }

    val listState = rememberLazyListState()
    LaunchedEffect(listState, hapticState, topInsetPx) {
        snapshotFlow {
            listState.firstVisibleItemIndex * verticalSpacingPx +
                listState.firstVisibleItemScrollOffset
        }.collectLatest { topPx ->
            // Convert into canvas-space (same coordinate frame as
            // `dotYsPx`): item-0-top sits at `topInsetPx` because of
            // `contentPadding(top = topInsetPx)`. Visible viewport
            // height is the gap between the two paddings.
            val visibleHeightPx = (
                listState.layoutInfo.viewportEndOffset -
                    listState.layoutInfo.viewportStartOffset
                ).toFloat()
            val centerPx = topInsetPx + topPx + visibleHeightPx / 2f
            val event = hapticState.handleViewportCenterPx(centerPx)
            dispatcher.dispatch(event)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (val s = journalState) {
            JournalUiState.Loading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = pilgrimColors.stone,
                    )
                }
            }
            JournalUiState.Empty -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(PilgrimSpacing.big),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.home_empty_message),
                        style = pilgrimType.body,
                        color = pilgrimColors.fog,
                    )
                }
            }
            is JournalUiState.Loaded -> {
                // Box-layered: calligraphy canvas behind, LazyColumn on
                // top with meander-offset WalkDots. Stage 3-E pattern,
                // upgraded with per-row LazyColumn anchors. Bucket 14-D
                // will move this assembly into JournalScreen + add
                // overlays (lunar, milestone, date dividers, scenery,
                // turning banner).
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val widthPx = with(density) { maxWidth.toPx() }
                    val meanderXs = remember(strokes, widthPx) {
                        dotPositions(
                            strokes = strokes,
                            widthPx = widthPx,
                            verticalSpacingPx = verticalSpacingPx,
                            topInsetPx = topInsetPx,
                            maxMeanderPx = maxMeanderPx,
                        ).map { it.centerXPx }
                    }
                    // Calligraphy canvas is fixed-size in BoxWithConstraints
                    // (not part of LazyColumn), so it would stay static while
                    // dots scroll. Translate it inversely with the LazyColumn
                    // scroll so line + dots stay locked together. Reads
                    // firstVisibleItemIndex + scrollOffset via derivedStateOf
                    // — Compose only re-runs the lambda body when the inputs
                    // change, no per-frame allocation in graphicsLayer.
                    val pathTranslationY by remember(listState, verticalSpacingPx) {
                        derivedStateOf {
                            -(listState.firstVisibleItemIndex * verticalSpacingPx +
                                listState.firstVisibleItemScrollOffset.toFloat())
                        }
                    }
                    CalligraphyPath(
                        strokes = strokes,
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer { translationY = pathTranslationY },
                        verticalSpacing = JOURNAL_ROW_HEIGHT,
                        topInset = JOURNAL_TOP_INSET_DP,
                        maxMeander = JOURNAL_MAX_MEANDER,
                    )
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = JOURNAL_TOP_INSET_DP),
                    ) {
                        itemsIndexed(s.snapshots, key = { _, snap -> snap.id }) { index, snap ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(JOURNAL_ROW_HEIGHT),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                val sizePx = sizesPx.getOrNull(index) ?: 0f
                                val xPx = meanderXs.getOrNull(index) ?: (widthPx / 2f)
                                val offsetXPx = (xPx - sizePx / 2f).toInt()
                                WalkDot(
                                    snapshot = snap,
                                    sizeDp = WalkDotMath.dotSize(snap.durationSec),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    opacity = WalkDotMath.dotOpacity(index, s.snapshots.size),
                                    isNewest = index == 0,
                                    contentDescription = "walk dot $index",
                                    // Stage 14-A interim: tap navigates
                                    // straight to WalkSummary (Stage 3-E
                                    // behavior). Bucket 14-B replaces this
                                    // with the expand-card sheet.
                                    onTap = { onEnterWalkSummary(snap.id) },
                                    modifier = Modifier.offset { IntOffset(offsetXPx, 0) },
                                )
                            }
                        }
                    }
                }
            }
        }

        // Goshuin button. No FAB chrome — just the latest walk's seal
        // thumbnail floating bottom-right (matches iOS GoshuinFAB
        // intent). Cold-start before the bitmap settles falls back to
        // the compass glyph on bare parchment, also chromeless.
        val goshuinFabInteraction = remember { MutableInteractionSource() }
        val goshuinLabel = stringResource(R.string.home_action_view_goshuin)
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(PilgrimSpacing.big)
                .size(56.dp)
                .clip(CircleShape)
                .clickable(
                    interactionSource = goshuinFabInteraction,
                    indication = null,
                    onClick = onEnterGoshuin,
                )
                .semantics { contentDescription = goshuinLabel },
            contentAlignment = Alignment.Center,
        ) {
            val seal = latestSealBitmap
            if (seal != null) {
                Image(
                    painter = BitmapPainter(seal),
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.Explore,
                    contentDescription = null,
                    tint = pilgrimColors.stone,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}
