// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
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
import org.walktalkmeditate.pilgrim.ui.home.dot.walkDotBaseColor
import org.walktalkmeditate.pilgrim.ui.home.header.JourneySummaryHeader
import org.walktalkmeditate.pilgrim.ui.home.scenery.SceneryGenerator
import org.walktalkmeditate.pilgrim.ui.home.scenery.SceneryItem
import org.walktalkmeditate.pilgrim.ui.home.scenery.ScenerySide
import org.walktalkmeditate.pilgrim.ui.home.scroll.JournalHapticDispatcher
import org.walktalkmeditate.pilgrim.ui.home.scroll.ScrollHapticState
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.SeasonalColorEngine
import org.walktalkmeditate.pilgrim.ui.walk.WalkFormat

private val JOURNAL_ROW_HEIGHT = 90.dp
private val JOURNAL_TOP_INSET_DP = 40.dp
private val JOURNAL_MAX_MEANDER = 100.dp
// Match SettingsScreen title chrome: 16dp scaffold-content top inset
// + 8dp text top padding + 16dp text bottom padding.
private val JOURNAL_TITLE_OUTER_TOP = 16.dp
private val JOURNAL_TITLE_INNER_TOP = 8.dp
private val JOURNAL_TITLE_INNER_BOTTOM = 16.dp
private val DISTANCE_LABEL_OFFSET_DP = 32.dp
private val DISTANCE_LABEL_Y_OFFSET_DP = 14.dp
private val MONTH_LABEL_MARGIN_DP = 36.dp

/**
 * Stage 14 Journal screen. Layout:
 *  - Sticky "Pilgrim Log" title at the very top with minimal margin.
 *  - Everything below scrolls together: JourneySummaryHeader, the
 *    calligraphy thread, dots, distance labels, month markers.
 *
 * Calligraphy + dots layered in a Box sized to N walks × 90 dp + top
 * inset; dots are positioned at the same `(x, y)` formula
 * `CalligraphyPath.dotPositions` uses so the line passes through
 * every dot. Stage 3-E pattern, upgraded to canvas-with-overlay so
 * adding lunar / milestone / scenery overlays in 14-C/14-D doesn't
 * require a layout reshuffle.
 *
 * `Column.verticalScroll` rather than LazyColumn for now: bucket 14-D
 * will revisit virtualization. Typical user has < 100 walks, so the
 * eager-render cost is acceptable.
 */
@Composable
fun HomeScreen(
    permissionsViewModel: PermissionsViewModel,
    onEnterWalkSummary: (Long) -> Unit,
    onEnterGoshuin: () -> Unit,
    homeViewModel: HomeViewModel = hiltViewModel(),
) {
    @Suppress("UNUSED_VARIABLE")
    val unused = permissionsViewModel
    val journalState by homeViewModel.journalState.collectAsStateWithLifecycle()
    val hemisphere by homeViewModel.hemisphere.collectAsStateWithLifecycle()
    val units by homeViewModel.distanceUnits.collectAsStateWithLifecycle()
    val latestSealBitmap by homeViewModel.latestSealBitmap.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val soundsEnabled = LocalSoundsEnabled.current
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
    val maxMeanderPx = with(density) { JOURNAL_MAX_MEANDER.toPx() }
    val dotThresholdPx = with(density) { 20.dp.toPx() }
    val milestoneThresholdPx = with(density) { 25.dp.toPx() }
    val largeDotCutoffPx = with(density) { 15.dp.toPx() }
    val labelOffsetPx = with(density) { DISTANCE_LABEL_OFFSET_DP.toPx() }
    val labelYOffsetPx = with(density) { DISTANCE_LABEL_Y_OFFSET_DP.toPx() }
    val monthMarginPx = with(density) { MONTH_LABEL_MARGIN_DP.toPx() }

    val loaded = journalState as? JournalUiState.Loaded
    val snapshots = loaded?.snapshots ?: emptyList()
    val sizesPx = remember(snapshots) {
        snapshots.map { with(density) { WalkDotMath.dotSize(it.durationSec).dp.toPx() } }
    }
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
    val themeColors = pilgrimColors
    val strokes: List<CalligraphyStrokeSpec> = remember(snapshots, hemisphere, themeColors) {
        snapshots.map { snap ->
            val walkDate = Instant.ofEpochMilli(snap.startMs)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
            // Walk color rule: moss by default, turning color on
            // equinox/solstice. Then apply seasonal HSB shift at
            // moderate intensity for the thread (Stage 3-D).
            val baseColor = walkDotBaseColor(snap.startMs, themeColors)
            val tint = SeasonalColorEngine.applySeasonalShift(
                base = baseColor,
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

    val scrollState = rememberScrollState()
    LaunchedEffect(scrollState, hapticState) {
        snapshotFlow { scrollState.value }.collectLatest { offsetPx ->
            // viewportCenter in journal-canvas space.
            val vH = scrollState.viewportSize.toFloat()
            val centerPx = offsetPx + vH / 2f
            val event = hapticState.handleViewportCenterPx(centerPx)
            dispatcher.dispatch(event)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Sticky title — placement matches SettingsScreen exactly:
            // 16dp content top + 8dp text top + 16dp text bottom. NO
            // statusBarsPadding — PilgrimNavHost's outer Scaffold
            // already insets safe-drawing area, applying it again
            // pushes the title below where Settings sits.
            Text(
                text = stringResource(R.string.home_title),
                style = pilgrimType.heading,
                color = pilgrimColors.ink,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = JOURNAL_TITLE_OUTER_TOP)
                    .padding(
                        top = JOURNAL_TITLE_INNER_TOP,
                        bottom = JOURNAL_TITLE_INNER_BOTTOM,
                    ),
                textAlign = TextAlign.Center,
            )

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
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(scrollState),
                        ) {
                            // Scrolls past as user scrolls down.
                            val nowMs = remember(s.summary) { System.currentTimeMillis() }
                            JourneySummaryHeader(
                                summary = s.summary,
                                units = units,
                                nowMs = nowMs,
                            )

                            // Calligraphy + dots + per-dot distance
                            // labels + month markers, all laid out in a
                            // single Box sized to the canvas total.
                            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                                val widthPx = with(density) { maxWidth.toPx() }
                                val canvasHeight = JOURNAL_TOP_INSET_DP +
                                    JOURNAL_ROW_HEIGHT * s.snapshots.size
                                val meanderXs = remember(strokes, widthPx) {
                                    dotPositions(
                                        strokes = strokes,
                                        widthPx = widthPx,
                                        verticalSpacingPx = verticalSpacingPx,
                                        topInsetPx = topInsetPx,
                                        maxMeanderPx = maxMeanderPx,
                                    ).map { it.centerXPx }
                                }
                                // iOS InkScrollView.swift:75 applies
                                // `.blur(radius: 0.6)` for a soft-ink
                                // feel. API 31+ only on Android.
                                val calligraphyBlur =
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                        Modifier.blur(0.6.dp, BlurredEdgeTreatment.Unbounded)
                                    } else {
                                        Modifier
                                    }

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(canvasHeight),
                                ) {
                                    CalligraphyPath(
                                        strokes = strokes,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .then(calligraphyBlur),
                                        verticalSpacing = JOURNAL_ROW_HEIGHT,
                                        topInset = JOURNAL_TOP_INSET_DP,
                                        maxMeander = JOURNAL_MAX_MEANDER,
                                    )
                                    // Walk dots, distance labels, and
                                    // month markers overlaid at canvas
                                    // coordinates matching the path.
                                    val zoneId = remember { ZoneId.systemDefault() }
                                    val monthFormatter = remember {
                                        DateTimeFormatter.ofPattern("MMM", Locale.getDefault())
                                            .withZone(zoneId)
                                    }
                                    s.snapshots.forEachIndexed { index, snap ->
                                        val dotSizeDp = WalkDotMath.dotSize(snap.durationSec)
                                        val dotSizePx = sizesPx.getOrNull(index) ?: 0f
                                        val xPx = meanderXs.getOrNull(index)
                                            ?: (widthPx / 2f)
                                        val yPx = dotYsPx.getOrNull(index)
                                            ?: (verticalSpacingPx * index)
                                        val opacity = WalkDotMath.dotOpacity(
                                            index,
                                            s.snapshots.size,
                                        )
                                        val labelAlpha = WalkDotMath.labelOpacity(
                                            index,
                                            s.snapshots.size,
                                        )
                                        val isDotRightOfCenter = xPx > widthPx / 2f
                                        // Distance label positioned ±32 dp
                                        // from dot center, +14 dp Y.
                                        // iOS InkScrollView.swift:627-637.
                                        val labelXPx = if (isDotRightOfCenter) {
                                            xPx - labelOffsetPx
                                        } else {
                                            xPx + labelOffsetPx
                                        }
                                        val labelYPx = yPx + labelYOffsetPx
                                        val distanceText = remember(snap.distanceM, units) {
                                            val l = WalkFormat.distanceLabel(snap.distanceM, units)
                                            "${l.value}${l.unit}"
                                        }

                                        // Month marker at first walk
                                        // of each new (year, month).
                                        // iOS InkScrollView.swift:677-700.
                                        val showMonth = remember(snap.startMs, index, s.snapshots) {
                                            val current = YearMonth.from(
                                                Instant.ofEpochMilli(snap.startMs)
                                                    .atZone(zoneId),
                                            )
                                            if (index == 0) {
                                                true
                                            } else {
                                                val prev = YearMonth.from(
                                                    Instant.ofEpochMilli(
                                                        s.snapshots[index - 1].startMs,
                                                    ).atZone(zoneId),
                                                )
                                                current != prev
                                            }
                                        }
                                        val monthText = remember(snap.startMs) {
                                            monthFormatter.format(
                                                Instant.ofEpochMilli(snap.startMs),
                                            )
                                        }
                                        val monthXPx = if (isDotRightOfCenter) {
                                            monthMarginPx
                                        } else {
                                            widthPx - monthMarginPx
                                        }

                                        // Animated scenery — drawn
                                        // behind the dot. iOS
                                        // InkScrollView.swift:549-589.
                                        val scenery = remember(snap.uuid, snap.startMs) {
                                            SceneryGenerator.pick(snap)
                                        }
                                        if (scenery != null) {
                                            // Tighter range than iOS (32-56) per
                                            // device QA — scenery felt large on
                                            // Android xxxhdpi. 20-36 dp reads as
                                            // small accent matching iOS visual
                                            // weight.
                                            val sceneryBaseSizeDp = 20f
                                            val sceneryVariation = remember(snap.uuid) {
                                                SceneryGenerator.sizeVariation01(snap)
                                            }
                                            val scenerySizeDp = sceneryBaseSizeDp + sceneryVariation.toFloat() * 16f
                                            val scenerySizePx = with(density) { scenerySizeDp.dp.toPx() }
                                            // scenery box is sized 2× scenerySize for animation room
                                            val sceneryBoxPx = scenerySizePx * 2f
                                            val xSign = if (scenery.side == ScenerySide.Left) -1f else 1f
                                            val sceneryCenterX = xPx + xSign * (40f.dp.let { with(density) { it.toPx() } } + scenerySizePx / 2f) +
                                                with(density) { scenery.offset.dp.toPx() }
                                            val sceneryCenterY = yPx - with(density) { 4.dp.toPx() }
                                            Box(
                                                modifier = Modifier
                                                    .offset {
                                                        IntOffset(
                                                            (sceneryCenterX - sceneryBoxPx / 2f).toInt(),
                                                            (sceneryCenterY - sceneryBoxPx / 2f).toInt(),
                                                        )
                                                    }
                                                    .size(scenerySizeDp.dp * 2f)
                                                    .semantics { contentDescription = "" },
                                            ) {
                                                SceneryItem(
                                                    placement = scenery,
                                                    snapshot = snap,
                                                    sizeDp = scenerySizeDp.dp,
                                                    hemisphere = hemisphere,
                                                )
                                            }
                                        }
                                        // Dot — color is the walk's
                                        // seasonal-tinted ink (same as
                                        // the calligraphy stroke).
                                        // talkColor/meditateColor for
                                        // activity arcs match iOS rust
                                        // + dawn semantics.
                                        val dotColor = strokes.getOrNull(index)?.ink
                                            ?: pilgrimColors.ink
                                        WalkDot(
                                            snapshot = snap,
                                            sizeDp = dotSizeDp,
                                            color = dotColor,
                                            talkColor = pilgrimColors.rust,
                                            meditateColor = pilgrimColors.dawn,
                                            opacity = opacity,
                                            isNewest = index == 0,
                                            contentDescription = "walk dot $index",
                                            onTap = { onEnterWalkSummary(snap.id) },
                                            modifier = Modifier.offset {
                                                IntOffset(
                                                    (xPx - dotSizePx * 1.75f).toInt(),
                                                    (yPx - dotSizePx * 1.75f).toInt(),
                                                )
                                            },
                                        )
                                        // Distance label inline near
                                        // dot.
                                        Text(
                                            text = distanceText,
                                            style = pilgrimType.micro,
                                            color = pilgrimColors.fog.copy(
                                                alpha = 0.5f * labelAlpha,
                                            ),
                                            modifier = Modifier
                                                .offset {
                                                    IntOffset(
                                                        labelXPx.toInt(),
                                                        labelYPx.toInt(),
                                                    )
                                                }
                                                .semantics {
                                                    contentDescription = ""
                                                },
                                        )
                                        // Month marker (only first
                                        // walk of new month).
                                        if (showMonth) {
                                            Text(
                                                text = monthText,
                                                style = pilgrimType.caption,
                                                color = pilgrimColors.fog.copy(alpha = 0.5f),
                                                modifier = Modifier
                                                    .offset {
                                                        IntOffset(
                                                            monthXPx.toInt(),
                                                            yPx.toInt(),
                                                        )
                                                    }
                                                    .semantics {
                                                        contentDescription = ""
                                                    },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Goshuin FAB. Matches iOS GoshuinFAB.swift exactly:
        // 64dp parchmentTertiary disc + stone-stroke ring + 52dp seal
        // thumbnail clipped to circle inside. The disc background +
        // stroke are what give the seal its visible "presence" — the
        // seal interior is transparent so without the disc it floats
        // unmoored against parchment.
        val goshuinFabInteraction = remember { MutableInteractionSource() }
        val goshuinLabel = stringResource(R.string.home_action_view_goshuin)
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(PilgrimSpacing.big)
                .size(64.dp)
                .clip(CircleShape)
                .background(pilgrimColors.parchmentTertiary)
                .border(
                    width = 1.dp,
                    color = pilgrimColors.stone.copy(alpha = 0.3f),
                    shape = CircleShape,
                )
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
                    modifier = Modifier.size(52.dp).clip(CircleShape),
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

