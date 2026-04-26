// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.path

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import kotlin.random.Random
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.core.celestial.MoonCalc
import org.walktalkmeditate.pilgrim.domain.WalkMode
import org.walktalkmeditate.pilgrim.domain.isInProgress
import org.walktalkmeditate.pilgrim.ui.design.BreathingLogo
import org.walktalkmeditate.pilgrim.ui.design.MoonPhaseGlyph
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType
import org.walktalkmeditate.pilgrim.ui.walk.WalkViewModel

/**
 * The Path tab — Pilgrim's contemplative pre-walk hub. Ports iOS
 * `WalkStartView`'s structure: breathing logo at top, rotating quote
 * (re-rolls on mode change, no timer), moon-phase glyph, 3-mode
 * selector (Wander available; Together / Seek "coming soon"), big
 * primary action button at bottom.
 *
 * Cold-launch behavior: if the controller is already in-progress
 * (crash-recovery via [WalkViewModel.restoreActiveWalk]), the screen
 * redirects to ACTIVE_WALK exactly once via a `didCheck`
 * rememberSaveable latch + one-shot LaunchedEffect(Unit). Sub-state
 * transitions (Active → Paused → Meditating) do NOT re-fire the
 * redirect — the second LaunchedEffect (state-change observer) is
 * gated on `didCheck` to handle the post-tap startWalk case without
 * double-firing.
 */
@Composable
fun WalkStartScreen(
    onEnterActiveWalk: () -> Unit,
    walkViewModel: WalkViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val walkState by walkViewModel.uiState.collectAsStateWithLifecycle()

    var selectedMode by rememberSaveable { mutableStateOf(WalkMode.Wander) }
    var currentQuote by rememberSaveable(selectedMode) {
        mutableStateOf(pickRandomQuote(context, selectedMode))
    }
    val lunarPhase = remember { MoonCalc.moonPhase(Instant.now()) }
    var starting by remember { mutableStateOf(false) }

    // Cold-launch one-shot resume-check. didCheck is rememberSaveable
    // so a config change doesn't re-fire the redirect.
    val didCheck = rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (didCheck.value) return@LaunchedEffect
        didCheck.value = true
        if (walkState.walkState.isInProgress) {
            onEnterActiveWalk()
            return@LaunchedEffect
        }
        val restored = walkViewModel.restoreActiveWalk()
        if (restored != null) onEnterActiveWalk()
    }

    // Post-tap redirect: when startWalk dispatches and state transitions
    // Idle → Active, route to ACTIVE_WALK. Gated on didCheck so the
    // cold-launch path's redirect isn't double-fired.
    LaunchedEffect(walkState.walkState.isInProgress) {
        if (walkState.walkState.isInProgress && didCheck.value) {
            onEnterActiveWalk()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(pilgrimColors.parchment),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(PilgrimSpacing.big),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    BreathingLogo(size = 100.dp)
                    Spacer(Modifier.height(PilgrimSpacing.big))
                    Text(
                        text = currentQuote,
                        style = pilgrimType.displayMedium,
                        color = pilgrimColors.fog,
                        textAlign = TextAlign.Center,
                        maxLines = 4,
                    )
                    Spacer(Modifier.height(PilgrimSpacing.big))
                    MoonPhaseGlyph(phase = lunarPhase, size = 44.dp)
                }
            }
            ModeSelector(
                selectedMode = selectedMode,
                onSelect = { selectedMode = it },
            )
            Spacer(Modifier.height(PilgrimSpacing.normal))
            Button(
                onClick = {
                    if (!starting) {
                        starting = true
                        walkViewModel.startWalk()
                    }
                },
                enabled = selectedMode.isAvailable && !starting,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = pilgrimColors.stone,
                    contentColor = pilgrimColors.parchment,
                    disabledContainerColor = pilgrimColors.fog.copy(alpha = 0.2f),
                    disabledContentColor = pilgrimColors.parchment.copy(alpha = 0.6f),
                ),
            ) {
                Text(stringResource(buttonLabelFor(selectedMode)))
            }
        }
    }
}

@StringRes
private fun buttonLabelFor(mode: WalkMode): Int = when (mode) {
    WalkMode.Wander -> R.string.path_button_wander
    WalkMode.Together -> R.string.path_button_together
    WalkMode.Seek -> R.string.path_button_seek
}

/**
 * Picks a random quote from the per-mode string-array. The [random]
 * parameter is injectable for test determinism.
 */
internal fun pickRandomQuote(
    context: Context,
    mode: WalkMode,
    random: Random = Random.Default,
): String {
    val arrayId = when (mode) {
        WalkMode.Wander -> R.array.path_quotes_wander
        WalkMode.Together -> R.array.path_quotes_together
        WalkMode.Seek -> R.array.path_quotes_seek
    }
    val quotes = context.resources.getStringArray(arrayId)
    return quotes[random.nextInt(quotes.size)]
}

@Composable
private fun ModeSelector(
    selectedMode: WalkMode,
    onSelect: (WalkMode) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
        ) {
            WalkMode.entries.forEach { mode ->
                ModeButton(
                    mode = mode,
                    selected = mode == selectedMode,
                    onClick = {
                        if (mode != selectedMode) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onSelect(mode)
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(PilgrimSpacing.small))
        AnimatedContent(targetState = selectedMode, label = "mode-subtitle") { mode ->
            val subtitleId = if (mode.isAvailable) {
                when (mode) {
                    WalkMode.Wander -> R.string.path_mode_wander_subtitle
                    WalkMode.Together -> R.string.path_mode_together_subtitle
                    WalkMode.Seek -> R.string.path_mode_seek_subtitle
                }
            } else {
                R.string.path_mode_unavailable_subtitle
            }
            Text(
                stringResource(subtitleId),
                style = pilgrimType.caption,
                color = pilgrimColors.fog.copy(alpha = 0.5f),
            )
        }
    }
}

@Composable
private fun ModeButton(
    mode: WalkMode,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(modeLabelFor(mode)),
            style = pilgrimType.button,
            color = if (selected) pilgrimColors.stone else pilgrimColors.fog.copy(alpha = 0.3f),
            maxLines = 1,
        )
        Spacer(Modifier.height(PilgrimSpacing.xs))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(if (selected) pilgrimColors.stone else Color.Transparent),
        )
    }
}

@StringRes
private fun modeLabelFor(mode: WalkMode): Int = when (mode) {
    WalkMode.Wander -> R.string.path_mode_wander
    WalkMode.Together -> R.string.path_mode_together
    WalkMode.Seek -> R.string.path_mode_seek
}
