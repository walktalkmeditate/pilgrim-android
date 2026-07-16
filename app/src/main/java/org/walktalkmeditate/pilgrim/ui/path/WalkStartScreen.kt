// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.path

import android.app.Activity
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.core.celestial.MoonCalc
import org.walktalkmeditate.pilgrim.data.sounds.LocalSoundsEnabled
import org.walktalkmeditate.pilgrim.domain.WalkMode
import org.walktalkmeditate.pilgrim.domain.isInProgress
import org.walktalkmeditate.pilgrim.domain.walkModeOrNull
import org.walktalkmeditate.pilgrim.ui.design.BreathingLogo
import org.walktalkmeditate.pilgrim.ui.design.LocalReduceMotion
import org.walktalkmeditate.pilgrim.ui.design.MoonPhaseGlyph
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType
import org.walktalkmeditate.pilgrim.ui.walk.WalkViewModel

/**
 * iOS parity (`WalkStartView.swift:52-65@db4196e`): footprint
 * active-mode swap waits 0.45s after the user taps a new mode. The
 * 0.3s fade-out animation in `PathFootprints` runs first, then the
 * swap, then the new mode's 0.3s fade-in. Reduce-motion skips the
 * delay (and the haptic) entirely.
 */
private const val MODE_TAP_DISSOLVE_MS = 450L

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
    onEnterActiveWalk: (WalkMode) -> Unit,
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
    // Compute moon phase at the START of today's local day so the
    // result agrees with the `remember(today)` key. Previously
    // `MoonCalc.moonPhase(Instant.now())` could drift around midnight
    // UTC when the local day hadn't ticked yet — the key would still
    // be yesterday-local while the instant was already today-UTC.
    val lunarPhase = remember(today) {
        MoonCalc.moonPhase(today.atStartOfDay(ZoneId.systemDefault()).toInstant())
    }
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
    // Redirects into an ALREADY-RUNNING walk pass the running walk's
    // mode when it's knowable (accumulator carries it); the mode arg
    // only drives the seek setup ritual, which the recovery guard on
    // ActiveWalkScreen skips for in-progress compositions anyway.
    LaunchedEffect(Unit) {
        if (didCheck.value) return@LaunchedEffect
        didCheck.value = true
        if (isInProgress) {
            onEnterActiveWalk(walkState.walkModeOrNull ?: WalkMode.Wander)
        }
    }

    // Post-tap redirect AND post-restore redirect: fires when state
    // flips Idle → in-progress, gated on didCheck so the
    // cold-launch path's first composition (where didCheck is still
    // false) doesn't fire spuriously on the initial Idle observation.
    LaunchedEffect(isInProgress) {
        if (isInProgress && didCheck.value) {
            onEnterActiveWalk(walkState.walkModeOrNull ?: WalkMode.Wander)
        }
    }

    val reduceMotion = LocalReduceMotion.current
    val pulseActive by walkViewModel.collectivePulseActive.collectAsStateWithLifecycle()
    // iOS Dynamic Type ≥ accessibility2 collapses the hero to 60pt logo.
    // Android proxy: fontScale > 1.3 (system "Larger" font setting).
    val isLargeText = LocalConfiguration.current.fontScale > 1.3f
    val logoSize: Dp = if (isLargeText) 60.dp else 100.dp

    // Staggered entrance: logo (immediate) → quote (+400ms) → moon
    // (+600ms), each a 500ms decelerate fade; logo also scales 0.95→1.0.
    // reduceMotion shows all three at once (defaults true → no animation).
    val showLogo = remember { mutableStateOf(reduceMotion) }
    val showQuote = remember { mutableStateOf(reduceMotion) }
    val showMoon = remember { mutableStateOf(reduceMotion) }
    LaunchedEffect(reduceMotion) {
        if (reduceMotion) return@LaunchedEffect
        showLogo.value = true
        kotlinx.coroutines.delay(400)
        showQuote.value = true
        kotlinx.coroutines.delay(200)
        showMoon.value = true
    }
    val logoAnim by animateFloatAsState(
        targetValue = if (showLogo.value) 1f else 0f,
        animationSpec = tween(durationMillis = 500, easing = LinearOutSlowInEasing),
        label = "entrance-logo",
    )
    val quoteAnim by animateFloatAsState(
        targetValue = if (showQuote.value) 1f else 0f,
        animationSpec = tween(durationMillis = 500, easing = LinearOutSlowInEasing),
        label = "entrance-quote",
    )
    val moonAnim by animateFloatAsState(
        targetValue = if (showMoon.value) 1f else 0f,
        animationSpec = tween(durationMillis = 500, easing = LinearOutSlowInEasing),
        label = "entrance-moon",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(pilgrimColors.parchment),
    ) {
        // iOS parity `WalkStartView.swift:85-122@db4196e`: layered
        // background = parchment + time-of-day tint + animated radial
        // gradient + per-mode atmosphere overlay.
        PathBackgroundLayers(
            selectedMode = selectedMode,
            reduceMotion = reduceMotion,
            modifier = Modifier.matchParentSize(),
        )
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
                .padding(PilgrimSpacing.big)
                // Reserve space for the floating pill bar at the bottom
                // so the Wander button isn't covered by the overlay.
                .padding(bottom = 80.dp),
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
                Box(
                    modifier = Modifier.graphicsLayer {
                        alpha = logoAnim
                        val s = 0.95f + 0.05f * logoAnim
                        scaleX = s
                        scaleY = s
                    },
                ) {
                    BreathingLogo(size = logoSize, pulseActive = pulseActive)
                }
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
                    modifier = Modifier.graphicsLayer { alpha = quoteAnim },
                )
                Spacer(Modifier.height(PilgrimSpacing.big))
                MoonPhaseGlyph(
                    phase = lunarPhase,
                    size = 44.dp,
                    modifier = Modifier.graphicsLayer { alpha = moonAnim },
                )
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
                // The selected mode rides the nav argument — for Seek it
                // drives the setup ritual on the active-walk surface (iOS
                // `MainCoordinator.startWalk(mode:)@c1745e8`).
                onClick = { onEnterActiveWalk(selectedMode) },
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
    val soundsEnabled = LocalSoundsEnabled.current
    val reduceMotion = LocalReduceMotion.current
    // iOS parity `WalkStartView.swift:46-65@db4196e` — the footprint
    // active-mode swap LAGS the label/underline swap by 0.45s, with a
    // 0.3s fade-out → swap+haptic → 0.3s fade-in cadence. selectedMode
    // tracks the label/underline (immediate visual feedback);
    // activeFootprintMode tracks the footprint (delayed swap).
    var activeFootprintMode by rememberSaveable { mutableStateOf(selectedMode) }
    var firstFrame by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(selectedMode) {
        if (firstFrame) {
            firstFrame = false
            activeFootprintMode = selectedMode
            return@LaunchedEffect
        }
        if (reduceMotion) {
            // 0.2s linear crossfade, no haptic (iOS skips haptic under
            // ReduceMotion to keep the swap quiet).
            activeFootprintMode = selectedMode
            return@LaunchedEffect
        }
        // Cancel-on-rapid-retap: if user picks a third mode mid-dissolve,
        // LaunchedEffect(selectedMode) re-keys and cancels this delay.
        kotlinx.coroutines.delay(MODE_TAP_DISSOLVE_MS)
        activeFootprintMode = selectedMode
        if (soundsEnabled) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
        ) {
            WalkMode.entries.forEach { mode ->
                ModeButton(
                    mode = mode,
                    selected = mode == selectedMode,
                    footprintActive = mode == activeFootprintMode,
                    onClick = {
                        if (mode != selectedMode) {
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
internal fun ModeButton(
    mode: WalkMode,
    selected: Boolean,
    footprintActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // indication = null suppresses the default Material ripple — the
    // mode tabs use a selected-underline as their tap feedback; the
    // bounded grey ripple over the label area reads as broken UX.
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        // selectable (not clickable) so TalkBack announces the tab role +
        // the selected state — the selection is otherwise conveyed only by
        // text color + the underline gradient (AF58).
        modifier = modifier.selectable(
            selected = selected,
            interactionSource = interactionSource,
            indication = null,
            role = Role.Tab,
            onClick = onClick,
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PathFootprints(
            mode = mode,
            isActive = footprintActive,
        )
        Spacer(Modifier.height(PilgrimSpacing.small))
        Text(
            text = stringResource(modeLabelFor(mode)),
            style = pilgrimType.button,
            color = if (selected) pilgrimColors.stone else pilgrimColors.fog.copy(alpha = 0.3f),
            maxLines = 1,
        )
        Spacer(Modifier.height(PilgrimSpacing.xs))
        // iOS parity `WalkStartView.trailUnderline(for:)@v1.6.0` —
        // selected-tab underline is a horizontal stone gradient that
        // fades toward the row's outer edges so the three tabs read as
        // one soft band: Wander solid→faded, Together faded both ends,
        // Seek faded→solid. Unselected = transparent.
        val stone = pilgrimColors.stone
        val underline: Brush = if (selected) {
            when (mode) {
                WalkMode.Wander -> Brush.horizontalGradient(
                    listOf(stone, stone.copy(alpha = 0.2f)),
                )
                WalkMode.Together -> Brush.horizontalGradient(
                    listOf(stone.copy(alpha = 0.3f), stone, stone.copy(alpha = 0.3f)),
                )
                WalkMode.Seek -> Brush.horizontalGradient(
                    listOf(stone.copy(alpha = 0.2f), stone),
                )
            }
        } else {
            Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(underline),
        )
    }
}

@StringRes
private fun modeLabelFor(mode: WalkMode): Int = when (mode) {
    WalkMode.Wander -> R.string.path_mode_wander
    WalkMode.Together -> R.string.path_mode_together
    WalkMode.Seek -> R.string.path_mode_seek
}
