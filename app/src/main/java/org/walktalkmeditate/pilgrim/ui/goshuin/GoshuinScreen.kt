// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.goshuin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.ui.design.seals.SealRenderer
import org.walktalkmeditate.pilgrim.ui.design.seals.SealSpec
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.Hemisphere
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.SeasonalColorEngine

private val CELL_SEAL_SIZE = 140.dp
private val CELL_FRAME_SIZE = 148.dp
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
    GoshuinScreenContent(
        uiState = uiState,
        hemisphere = hemisphere,
        onBack = onBack,
        onSealTap = onSealTap,
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
) {
    val totalCount = (uiState as? GoshuinUiState.Loaded)?.totalCount ?: 0
    val patinaAlpha = patinaAlphaFor(totalCount)

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
                is GoshuinUiState.Loaded -> GoshuinGrid(
                    seals = uiState.seals,
                    hemisphere = hemisphere,
                    onSealTap = onSealTap,
                )
            }
        }
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
) {
    val gridState = rememberLazyGridState()
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
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
            modifier = Modifier.size(CELL_FRAME_SIZE),
            contentAlignment = Alignment.Center,
        ) {
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
            text = seal.shortDateLabel,
            style = pilgrimType.caption,
            color = pilgrimColors.fog,
        )
    }
}
