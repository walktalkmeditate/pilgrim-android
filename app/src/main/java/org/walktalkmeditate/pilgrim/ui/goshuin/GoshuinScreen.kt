// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.goshuin

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.entity.WalkFavicon
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.ui.design.seals.SealRenderer
import org.walktalkmeditate.pilgrim.ui.design.seals.SealSpec
import org.walktalkmeditate.pilgrim.ui.etegami.share.EtegamiShareIntentFactory
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.Hemisphere
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.SeasonalColorEngine
import org.walktalkmeditate.pilgrim.ui.walk.summary.SealShareBitmapWriter

private val CELL_SEAL_SIZE = 140.dp
private val CELL_FRAME_SIZE = 148.dp
private val CELL_HALO_SIZE = 156.dp
private val CELL_HALO_STROKE = 2.dp
private const val CELL_HALO_ALPHA = 0.5f
private const val SEAL_FRAME_ALPHA = 0.04f
private const val PLACEHOLDER_UUID = "goshuin-empty-placeholder"
private const val PLACEHOLDER_ALPHA = 0.10f

/**
 * Stage 4-C: browsable collection of every earned goshuin seal.
 * Enters from Home's *View goshuin* button. Tapping a seal navigates
 * to that walk's summary via [onSealTap].
 *
 * Layers (bottom → top):
 *  1. Parchment-filled root.
 *  2. Dawn-tinted patina overlay (alpha from [patinaAlphaFor]) so the
 *     page visibly ages as the user accumulates walks.
 *  3. Column: header (title + back) → content.
 *  4. Content: Loading spinner / Empty state / LazyVerticalGrid.
 *
 * Seasonal-ink resolution for each cell happens inside
 * [GoshuinSealCell] via `remember(sealSpec, baseInk, walkDate,
 * hemisphere)` — matches Stage 4-B's `WalkSummaryScreen` pattern and
 * avoids rebuilding specs on unrelated recomposition.
 *
 * See `docs/superpowers/specs/2026-04-19-stage-4c-goshuin-grid-design.md`.
 */
@Composable
fun GoshuinScreen(
    onBack: () -> Unit,
    onSealTap: (Long) -> Unit,
    viewModel: GoshuinViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val hemisphere by viewModel.hemisphere.collectAsStateWithLifecycle()
    val distanceUnits by viewModel.distanceUnits.collectAsStateWithLifecycle()
    val isImperial = distanceUnits == UnitSystem.Imperial

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isSharing by remember { mutableStateOf(false) }
    val chooserTitle = stringResource(R.string.goshuin_share_chooser_title)
    val noChooserMsg = stringResource(R.string.goshuin_share_no_chooser)

    GoshuinScreenContent(
        uiState = uiState,
        hemisphere = hemisphere,
        isImperial = isImperial,
        isSharing = isSharing,
        onBack = onBack,
        onSealTap = onSealTap,
        // iOS `renderShareImage()`: render filtered seals → temp file →
        // share sheet. Android: 1080×1920 bitmap → FileProvider cache
        // → ACTION_SEND chooser. Mirrors WalkSummaryScreen's proven
        // seal-share pipeline (Default render, IO write, chooser).
        onShareGoshuin = { selected ->
            if (!isSharing && selected.isNotEmpty()) {
                isSharing = true
                val total = (uiState as? GoshuinUiState.Loaded)
                    ?.totalIncludingArchived ?: selected.size
                scope.launch {
                    try {
                        val bmp = withContext(Dispatchers.Default) {
                            GoshuinShareRenderer.render(context, selected, total, isImperial)
                        }
                        val file = SealShareBitmapWriter.writeToCache(
                            bmp, "collection", context,
                        )
                        val intent = EtegamiShareIntentFactory.buildFromFile(
                            context, file, chooserTitle,
                        )
                        try {
                            context.startActivity(intent)
                        } catch (_: android.content.ActivityNotFoundException) {
                            Toast.makeText(context, noChooserMsg, Toast.LENGTH_SHORT).show()
                        }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        android.util.Log.w("GoshuinScreen", "goshuin share failed", t)
                    } finally {
                        isSharing = false
                    }
                }
            }
        },
    )
}

/**
 * Composable content, extracted so tests can drive state directly
 * without spinning up Hilt + a real [GoshuinViewModel].
 */
@Composable
internal fun GoshuinScreenContent(
    uiState: GoshuinUiState,
    hemisphere: Hemisphere,
    onBack: () -> Unit,
    onSealTap: (Long) -> Unit,
    isImperial: Boolean = false,
    isSharing: Boolean = false,
    onShareGoshuin: (List<GoshuinSeal>) -> Unit = {},
) {
    val totalCount = (uiState as? GoshuinUiState.Loaded)?.totalCount ?: 0
    val patinaAlpha = patinaAlphaFor(totalCount)

    // iOS `@State activeFilter: WalkFavicon?` — null = "All".
    // rememberSaveable so a rotation mid-browse keeps the filter.
    var activeFilter by rememberSaveable { mutableStateOf<WalkFavicon?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(pilgrimColors.parchment),
    ) {
        if (patinaAlpha > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(pilgrimColors.dawn.copy(alpha = patinaAlpha)),
            )
        }

        Column(modifier = Modifier.fillMaxSize()) {
            GoshuinHeader(onBack = onBack)
            Spacer(Modifier.height(PilgrimSpacing.normal))

            when (uiState) {
                is GoshuinUiState.Loading -> GoshuinLoading()
                is GoshuinUiState.Empty -> GoshuinEmpty()
                is GoshuinUiState.Loaded -> {
                    // iOS v1.6.0 stats header — three pill-formatted
                    // counts (walks · distance · meditation min)
                    // that include archived walks.
                    GoshuinStatsHeader(
                        totalWalks = uiState.totalIncludingArchived,
                        totalDistanceMeters = uiState.totalDistanceMeters,
                        totalMeditationSeconds = uiState.totalMeditationSeconds,
                    )
                    Spacer(Modifier.height(PilgrimSpacing.small))
                    GoshuinFilterBar(
                        activeFilter = activeFilter,
                        onSelect = { activeFilter = it },
                    )
                    // iOS `filteredWalks` — null filter = all seals.
                    val filtered = if (activeFilter == null) {
                        uiState.seals
                    } else {
                        uiState.seals.filter { it.favicon == activeFilter }
                    }
                    GoshuinGrid(
                        seals = filtered,
                        hemisphere = hemisphere,
                        onSealTap = onSealTap,
                        modifier = Modifier.weight(1f),
                    )
                    GoshuinShareButton(
                        visible = filtered.isNotEmpty(),
                        isSharing = isSharing,
                        onClick = { onShareGoshuin(filtered) },
                    )
                }
            }
        }
    }
}

/**
 * iOS `GoshuinView.filterBar`: a horizontally-scrolling row of
 * category chips — "All" plus one per [WalkFavicon]. The active chip
 * gets a stone/15 rounded background + stone text; inactive chips are
 * transparent with fog text. iOS animates the swap with
 * `easeInOut(0.2)`; here the grid recomposition is the visible change.
 */
@Composable
private fun GoshuinFilterBar(
    activeFilter: WalkFavicon?,
    onSelect: (WalkFavicon?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(
                horizontal = PilgrimSpacing.normal,
                vertical = PilgrimSpacing.small,
            ),
        horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
    ) {
        GoshuinFilterChip(
            label = stringResource(R.string.goshuin_filter_all),
            icon = null,
            isActive = activeFilter == null,
            onClick = { onSelect(null) },
        )
        WalkFavicon.entries.forEach { fav ->
            GoshuinFilterChip(
                label = stringResource(fav.labelRes),
                icon = fav.icon,
                isActive = activeFilter == fav,
                onClick = { onSelect(fav) },
            )
        }
    }
}

@Composable
private fun GoshuinFilterChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    val fg = if (isActive) pilgrimColors.stone else pilgrimColors.fog
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isActive) {
                    pilgrimColors.stone.copy(alpha = 0.15f)
                } else {
                    Color.Transparent
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = PilgrimSpacing.small, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(14.dp),
            )
        }
        Text(text = label, style = pilgrimType.caption, color = fg)
    }
}

/**
 * iOS `GoshuinView.shareButton`: a plain full-width stone text button
 * ("Share Goshuin"). iOS uses `opacity(pages.isEmpty ? 0 : 1)`; here
 * we omit it entirely when there are no seals (a hidden-but-present
 * 0-opacity tappable target is worse on Android).
 */
@Composable
private fun GoshuinShareButton(
    visible: Boolean,
    isSharing: Boolean,
    onClick: () -> Unit,
) {
    if (!visible) return
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isSharing, onClick = onClick)
            .padding(PilgrimSpacing.normal),
        contentAlignment = Alignment.Center,
    ) {
        if (isSharing) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = pilgrimColors.stone,
            )
        } else {
            Text(
                text = stringResource(R.string.goshuin_share_action),
                style = pilgrimType.button,
                color = pilgrimColors.stone,
            )
        }
    }
}

@Composable
private fun GoshuinStatsHeader(
    totalWalks: Int,
    totalDistanceMeters: Double,
    totalMeditationSeconds: Long,
) {
    val km = totalDistanceMeters / 1000.0
    val minutes = totalMeditationSeconds / 60L
    val walksLabel = java.text.NumberFormat.getNumberInstance(java.util.Locale.getDefault())
        .format(totalWalks)
    val kmLabel = String.format(java.util.Locale.getDefault(), "%.1f km", km)
    val minLabel = String.format(java.util.Locale.US, "%d min", minutes)
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PilgrimSpacing.big),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly,
    ) {
        StatPill(value = walksLabel, label = "walks")
        StatPill(value = kmLabel, label = "distance")
        StatPill(value = minLabel, label = "meditation")
    }
}

@Composable
private fun StatPill(value: String, label: String) {
    androidx.compose.foundation.layout.Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value,
            style = pilgrimType.body,
            color = pilgrimColors.ink,
        )
        Text(
            text = label,
            style = pilgrimType.caption,
            color = pilgrimColors.fog,
        )
    }
}

@Composable
private fun GoshuinHeader(onBack: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PilgrimSpacing.normal),
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.goshuin_back_content_description),
                tint = pilgrimColors.ink,
            )
        }
        Text(
            text = stringResource(R.string.goshuin_title),
            style = pilgrimType.displayMedium,
            color = pilgrimColors.ink,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(vertical = PilgrimSpacing.small),
        )
    }
}

@Composable
private fun GoshuinLoading() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(24.dp),
            strokeWidth = 2.dp,
            color = pilgrimColors.stone,
        )
    }
}

@Composable
private fun GoshuinEmpty() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = PilgrimSpacing.big),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Faded seal placeholder — deterministic spec at low alpha so
        // the shape reads as "something will appear here" rather than
        // an empty Box. A zero-distance/duration seed still produces
        // a full hash-derived composition (rings + radials + arcs +
        // dots); only the center text is blank.
        val placeholderSpec = remember {
            SealSpec(
                uuid = PLACEHOLDER_UUID,
                startMillis = 0L,
                distanceMeters = 0.0,
                durationSeconds = 0.0,
                displayDistance = "",
                unitLabel = "",
                ink = Color.Transparent,
            )
        }
        // Use `ink` (the theme's designated content color) rather than
        // `fog`: in dark mode, `fog` is a medium gray that blends
        // invisibly against near-black parchment, which erases the
        // ghost-seal effect. `ink` produces a visible-but-subtle trace
        // in both light (dark brown) and dark (cream) modes.
        val fadedInk = pilgrimColors.ink.copy(alpha = PLACEHOLDER_ALPHA)
        SealRenderer(
            spec = placeholderSpec.copy(ink = fadedInk),
            modifier = Modifier.size(CELL_SEAL_SIZE),
        )
        Spacer(Modifier.height(PilgrimSpacing.normal))
        Text(
            text = stringResource(R.string.goshuin_empty_caption),
            style = pilgrimType.body,
            color = pilgrimColors.fog,
        )
    }
}

@Composable
private fun GoshuinGrid(
    seals: List<GoshuinSeal>,
    hemisphere: Hemisphere,
    onSealTap: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyGridState()
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        state = gridState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            start = PilgrimSpacing.normal,
            end = PilgrimSpacing.normal,
            top = PilgrimSpacing.small,
            bottom = PilgrimSpacing.big,
        ),
        horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.normal),
        verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.normal),
    ) {
        items(
            items = seals,
            // Stable key by walkId — avoids cell recomposition thrash
            // on unrelated list updates. Scroll position survives too.
            key = { seal -> seal.walkId },
        ) { seal ->
            GoshuinSealCell(
                seal = seal,
                hemisphere = hemisphere,
                onClick = { onSealTap(seal.walkId) },
            )
        }
    }
}

@Composable
private fun GoshuinSealCell(
    seal: GoshuinSeal,
    hemisphere: Hemisphere,
    onClick: () -> Unit,
) {
    val baseInk = pilgrimColors.rust
    val frameColor = pilgrimColors.ink.copy(alpha = SEAL_FRAME_ALPHA)
    val haloColor = pilgrimColors.dawn.copy(alpha = CELL_HALO_ALPHA)

    // Per-cell seasonal tint — matches
    // `WalkSummaryScreen.specForReveal`. Keyed on the full set of
    // inputs so a hemisphere flip OR a theme change recomputes
    // exactly once per cell.
    val tintedSpec = remember(seal.sealSpec, baseInk, seal.walkDate, hemisphere) {
        val tintedInk = SeasonalColorEngine.applySeasonalShift(
            base = baseInk,
            intensity = SeasonalColorEngine.Intensity.Full,
            date = seal.walkDate,
            hemisphere = hemisphere,
        )
        seal.sealSpec.copy(ink = tintedInk)
    }

    // `indication = null` + no-ripple MutableInteractionSource — the
    // cell is a quiet button, not a raised Material surface. Remember
    // the source so we don't allocate per recompose.
    val interactionSource = remember { MutableInteractionSource() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = PilgrimSpacing.small),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.size(CELL_HALO_SIZE),
            contentAlignment = Alignment.Center,
        ) {
            // Stage 4-D milestone halo — outermost ring, only when this
            // walk hit a milestone. Sits ~4dp outside the inked frame.
            if (seal.milestone != null) {
                Box(
                    modifier = Modifier
                        .size(CELL_HALO_SIZE)
                        .drawBehind {
                            drawCircle(
                                color = haloColor,
                                radius = size.minDimension / 2f,
                                style = Stroke(width = CELL_HALO_STROKE.toPx()),
                            )
                        },
                )
            }
            // Thin ink-outline circle behind the seal: the
            // "stamp-on-paper" frame.
            Box(
                modifier = Modifier
                    .size(CELL_FRAME_SIZE)
                    .drawBehind {
                        drawCircle(
                            color = frameColor,
                            radius = size.minDimension / 2f,
                            style = Stroke(width = 1.dp.toPx()),
                        )
                    },
            )
            SealRenderer(
                spec = tintedSpec,
                modifier = Modifier.size(CELL_SEAL_SIZE),
            )
        }
        Spacer(Modifier.height(PilgrimSpacing.small))
        Text(
            // Milestone label takes precedence over the date when the
            // walk crossed a threshold — the label IS the recognition.
            text = seal.milestone?.let(GoshuinMilestones::label)
                ?: seal.shortDateLabel,
            style = pilgrimType.caption,
            color = if (seal.milestone != null) pilgrimColors.dawn else pilgrimColors.fog,
        )
    }
}
