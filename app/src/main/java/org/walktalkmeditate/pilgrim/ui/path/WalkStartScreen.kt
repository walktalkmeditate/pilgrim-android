// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.path

import android.app.Activity
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.LocalDate
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
    // Stage 5G trap (memorized): WalkViewModel.uiState uses
    // WhileSubscribed(5s); after Path disposes for >5s (e.g., during a
    // walk on ACTIVE_WALK), its upstream unsubscribes and the StateFlow's
    // value freezes at the last seen emission. Reading isInProgress from
    // uiState on tab-return would yield STALE in-progress=true, kicking
    // off a spurious onEnterActiveWalk() loop. Use the direct
    // hot-Singleton passthrough WalkViewModel.walkState — exists for
    // exactly this purpose (mirrors ActiveWalkScreen line 53).
    val walkState by walkViewModel.walkState.collectAsStateWithLifecycle()
    val isInProgress = walkState.isInProgress
    val recoveredWalkId by walkViewModel.recoveredWalkId.collectAsStateWithLifecycle()

    // Back from the Path tab (the effective root) should background
    // the app, not destroy it. Launcher re-tap then resumes here.
    // Matches the platform convention for tab-rooted apps.
    BackHandler {
        (context as? Activity)?.moveTaskToBack(true)
    }

    var selectedMode by rememberSaveable { mutableStateOf(WalkMode.Wander) }
    var currentQuote by rememberSaveable(selectedMode) {
        mutableStateOf(pickRandomQuote(context, selectedMode))
    }
    // Re-keyed on the calendar day so when the screen recomposes
    // (e.g., on tab return or config change), the moon phase
    // recomputes if the day rolled over since last composition.
    // A foregrounded screen left untouched across midnight will NOT
    // refresh — Compose recomposes only on state changes, not
    // wall-clock ticks. Acceptable: the user will navigate
    // somewhere within hours either way.
    val today = LocalDate.now()
    val lunarPhase = remember(today) { MoonCalc.moonPhase(Instant.now()) }
    // Local "starting" flag was a 1-shot guard that never reset; if
    // startWalk silently fails (state-machine rejection, FGS denial),
    // the button would stay disabled forever. Drive disabled state
    // directly off isInProgress instead — safe because the auto-redirect
    // below navigates AWAY from PATH the moment isInProgress flips
    // true, so the user never sees the button after that point.

    // Cold-launch one-shot resume-check. didCheck is rememberSaveable
    // so a config change doesn't re-fire the redirect.
    //
    // After the launch-side recovery refactor, there's no longer an
    // unfinished walk to RESTORE — `PilgrimApp.onCreate.recoverStaleWalks`
    // finalizes any walk-with-endTimestamp-null on cold launch and
    // arms the recovery banner. So this LaunchedEffect just redirects
    // to ActiveWalk if a walk was somehow already in-progress on the
    // controller (warm launch case where the @Singleton survived).
    val didCheck = rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (didCheck.value) return@LaunchedEffect
        didCheck.value = true
        if (isInProgress) {
            onEnterActiveWalk()
        }
    }

    // Post-tap redirect AND post-restore redirect: fires when state
    // flips Idle → in-progress, gated on didCheck so the
    // cold-launch path's first composition (where didCheck is still
    // false) doesn't fire spuriously on the initial Idle observation.
    LaunchedEffect(isInProgress) {
        if (isInProgress && didCheck.value) {
            onEnterActiveWalk()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(pilgrimColors.parchment),
    ) {
        // iOS-parity recovery banner: shows when a walk was auto-finalized
        // because the user swiped the app from recents mid-walk. Auto-
        // dismisses after 4s via the banner's internal LaunchedEffect.
        // Aligned to the top of the screen so it doesn't push the rest of
        // the layout around — overlays via the outer Box.
        RecoveryBanner(
            visible = recoveredWalkId != null,
            onDismiss = { walkViewModel.dismissRecovery() },
            modifier = Modifier.align(Alignment.TopCenter),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(PilgrimSpacing.big),
        ) {
            // Centered content. We use Modifier.weight(1f) to take all
            // remaining vertical space, then Arrangement.Center inside
            // a NON-scrolling Column to vertically center logo + quote
            // + moon. Phone screens fit comfortably; if a future
            // accessibility scale breaks the fit, ModeSelector +
            // Button still pin to the bottom (not scrolled off-screen).
            // No verticalScroll: nesting an infinite-height parent
            // around a Column.fillMaxSize would throw on layout.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                BreathingLogo(size = 100.dp)
                Spacer(Modifier.height(PilgrimSpacing.big))
                Text(
                    text = currentQuote,
                    // displayMedium (28sp) is too large for the longest
                    // quote ("The journey of a thousand miles...") on
                    // typical phone widths — "miles" wraps onto its own
                    // line. 22sp fits every shipping quote on a single
                    // logical line per the explicit `\n` rhythm.
                    style = pilgrimType.displayMedium.copy(fontSize = 22.sp),
                    color = pilgrimColors.fog,
                    textAlign = TextAlign.Center,
                    maxLines = 4,
                )
                Spacer(Modifier.height(PilgrimSpacing.big))
                MoonPhaseGlyph(phase = lunarPhase, size = 44.dp)
            }
            ModeSelector(
                selectedMode = selectedMode,
                onSelect = { selectedMode = it },
            )
            Spacer(Modifier.height(PilgrimSpacing.normal))
            Button(
                // iOS parity: button navigates to the active-walk surface
                // in its "ready" state. The walk does NOT start recording
                // until the user taps the Start button on that screen.
                onClick = { onEnterActiveWalk() },
                enabled = selectedMode.isAvailable && !isInProgress,
                modifier = Modifier.fillMaxWidth(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
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
    if (quotes.isEmpty()) {
        // Defensive: a future translation could ship an empty array;
        // random.nextInt(0) would throw IAE. Fall back to a hardcoded
        // contemplative line so the Path screen never goes blank.
        android.util.Log.w("WalkStartScreen", "empty quote array for $mode; check translations")
        return "Walk well."
    }
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
    // indication = null suppresses the default Material ripple — the
    // mode tabs use a selected-underline as their tap feedback; the
    // bounded grey ripple over the label area reads as broken UX.
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        modifier = modifier.clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick,
        ),
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
