// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.path

import android.app.Activity
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.annotation.ArrayRes
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimCornerRadius
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

/** iOS `.fog.opacity(0.55)` for an unselected mode label (`WalkStartView.swift:331@7c200bf`, since `cbd24fc`). */
internal const val UNSELECTED_MODE_LABEL_ALPHA = 0.55f

internal const val PATH_START_BUTTON_TAG = "start_walk_button"

private const val START_SHADOW_ALPHA = 0.2f

/**
 * The Path tab — Pilgrim's contemplative pre-walk hub. Ports iOS
 * `WalkStartView`'s structure: breathing logo at top, rotating quote
 * (re-rolls on mode change, no timer), moon-phase glyph, 3-mode
 * selector, big primary action button at bottom. With [honorEnabled]
 * the middle slot is Honor and its button opens the Ways sheet through
 * [onChooseWay] (iOS `MainTabView.swift:22-28@7c200bf`); without it the
 * slot keeps the 1.5.0 Together look and "coming soon" (AE12).
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
    onChooseWay: () -> Unit,
    honorEnabled: Boolean,
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

    // Back from the Path tab (the effective root) should background
    // the app, not destroy it. Launcher re-tap then resumes here.
    // Matches the platform convention for tab-rooted apps.
    BackHandler {
        (context as? Activity)?.moveTaskToBack(true)
    }

    var selectedMode by rememberSaveable { mutableStateOf(WalkMode.Wander) }
    var currentQuote by rememberSaveable(selectedMode) {
        mutableStateOf(pickRandomQuote(context, selectedMode, honorEnabled = honorEnabled))
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
            honorEnabled = honorEnabled,
            modifier = Modifier.matchParentSize(),
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
                honorEnabled = honorEnabled,
                onSelect = { selectedMode = it },
            )
            Spacer(Modifier.height(PilgrimSpacing.normal))
            PathStartButton(
                label = stringResource(pathModeCopy(selectedMode, honorEnabled).button),
                enabled = selectedMode.isAvailable(honorEnabled = honorEnabled) && !isInProgress,
                // iOS parity: Wander and Seek open the active-walk surface
                // in its "ready" state, and the walk starts only at that
                // screen's Start; the selected mode rides the nav argument
                // (for Seek it drives the setup ritual, iOS
                // `MainCoordinator.startWalk(mode:)@c1745e8`). Honor opens
                // the Ways sheet instead (F §3.5).
                onClick = {
                    when (pathButtonAction(selectedMode, honorEnabled)) {
                        PathButtonAction.EnterWalk -> onEnterActiveWalk(selectedMode)
                        PathButtonAction.ChooseWay -> onChooseWay()
                    }
                },
            )
        }
    }
}

/**
 * iOS's one button for every mode (`WalkStartView.swift:198-215@7c200bf`).
 * TalkBack reads "Begin your journey", never the visible mode name.
 *
 * iOS's stone shadow (`.stone.opacity(0.2), radius: 8, y: 3`, none while
 * disabled) is an elevation shadow in stone. Its second shadow, a glow
 * centred on the button that breathes with the logo, has no elevation
 * equivalent (Android's shadows fall away from the light), so it isn't drawn.
 */
@Composable
internal fun PathStartButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val journey = stringResource(R.string.path_start_a11y)
    val shape = RoundedCornerShape(PilgrimCornerRadius.normal)
    val shadowColor = pilgrimColors.stone.copy(alpha = if (enabled) START_SHADOW_ALPHA else 0f)
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .shadow(elevation = 8.dp, shape = shape, ambientColor = shadowColor, spotColor = shadowColor)
            .testTag(PATH_START_BUTTON_TAG)
            .semantics { contentDescription = journey },
        shape = shape,
        contentPadding = PaddingValues(vertical = 12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = pilgrimColors.stone,
            contentColor = pilgrimColors.parchment,
            disabledContainerColor = pilgrimColors.fog.copy(alpha = 0.2f),
            disabledContentColor = pilgrimColors.parchment.copy(alpha = 0.6f),
        ),
    ) {
        Text(label, modifier = Modifier.clearAndSetSemantics {})
    }
}

/** What the Path tab shows for one mode slot. */
internal data class PathModeCopy(
    @param:StringRes val label: Int,
    @param:StringRes val subtitle: Int,
    @param:StringRes val button: Int,
    @param:ArrayRes val quotes: Int,
    /** TalkBack's name for the slot; null reads the visible label, as 1.5.0 did. */
    @param:StringRes val talkBackLabel: Int?,
)

/**
 * With [honorEnabled] the slots read as iOS's (`WalkMode.swift:3-31@7c200bf`),
 * TalkBack naming each by its lowercase raw value (F §17.1). Without it the
 * middle slot keeps the 1.5.0 Together copy, "coming soon", and visible-text
 * labels (AE12).
 */
internal fun pathModeCopy(mode: WalkMode, honorEnabled: Boolean): PathModeCopy =
    when (mode) {
        WalkMode.Wander -> PathModeCopy(
            label = R.string.path_mode_wander,
            subtitle = R.string.path_mode_wander_subtitle,
            button = R.string.path_button_wander,
            quotes = R.array.path_quotes_wander,
            talkBackLabel = R.string.path_mode_wander_a11y.takeIf { honorEnabled },
        )
        WalkMode.Honor -> if (honorEnabled) {
            PathModeCopy(
                label = R.string.path_mode_honor,
                subtitle = R.string.path_mode_honor_subtitle,
                button = R.string.path_button_honor,
                quotes = R.array.path_quotes_honor,
                talkBackLabel = R.string.path_mode_honor_a11y,
            )
        } else {
            PathModeCopy(
                label = R.string.path_mode_together,
                subtitle = R.string.path_mode_unavailable_subtitle,
                button = R.string.path_button_together,
                quotes = R.array.path_quotes_together,
                talkBackLabel = null,
            )
        }
        WalkMode.Seek -> PathModeCopy(
            label = R.string.path_mode_seek,
            subtitle = R.string.path_mode_seek_subtitle,
            button = R.string.path_button_seek,
            quotes = R.array.path_quotes_seek,
            talkBackLabel = R.string.path_mode_seek_a11y.takeIf { honorEnabled },
        )
    }

internal enum class PathButtonAction { EnterWalk, ChooseWay }

/**
 * iOS routes `onStartWalk(.honor)` to `chooseWay()`, every other mode to a
 * walk (`MainTabView.swift:22-28@7c200bf`).
 */
internal fun pathButtonAction(mode: WalkMode, honorEnabled: Boolean): PathButtonAction =
    if (mode == WalkMode.Honor && honorEnabled) PathButtonAction.ChooseWay else PathButtonAction.EnterWalk

/**
 * Picks a random quote from the per-mode string-array. The [random]
 * parameter is injectable for test determinism.
 */
internal fun pickRandomQuote(
    context: Context,
    mode: WalkMode,
    random: Random = Random.Default,
    honorEnabled: Boolean = false,
): String {
    val arrayId = pathModeCopy(mode, honorEnabled).quotes
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
    honorEnabled: Boolean,
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
                    honorEnabled = honorEnabled,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(PilgrimSpacing.small))
        AnimatedContent(targetState = selectedMode, label = "mode-subtitle") { mode ->
            Text(
                stringResource(pathModeCopy(mode, honorEnabled).subtitle),
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
    honorEnabled: Boolean = false,
) {
    val copy = pathModeCopy(mode, honorEnabled)
    val talkBackLabel = copy.talkBackLabel?.let { stringResource(it) }
    // indication = null suppresses the default Material ripple — the
    // mode tabs use a selected-underline as their tap feedback; the
    // bounded grey ripple over the label area reads as broken UX.
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        // selectable (not clickable) so TalkBack announces the selected
        // state — the selection is otherwise conveyed only by text color +
        // the underline gradient (AF58). With Honor on it is iOS's Button
        // plus Selected (F §17.1); flag-off keeps 1.5.0's tab role.
        modifier = modifier
            .selectable(
                selected = selected,
                interactionSource = interactionSource,
                indication = null,
                role = if (honorEnabled) Role.Button else Role.Tab,
                onClick = onClick,
            )
            .then(
                if (talkBackLabel != null) {
                    Modifier.semantics { contentDescription = talkBackLabel }
                } else {
                    Modifier
                },
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PathFootprints(
            mode = mode,
            isActive = footprintActive,
            honorEnabled = honorEnabled,
        )
        Spacer(Modifier.height(PilgrimSpacing.small))
        Text(
            text = stringResource(copy.label),
            style = pilgrimType.button,
            color = if (selected) pilgrimColors.stone else pilgrimColors.fog.copy(alpha = UNSELECTED_MODE_LABEL_ALPHA),
            maxLines = 1,
            modifier = if (talkBackLabel != null) Modifier.clearAndSetSemantics {} else Modifier,
        )
        Spacer(Modifier.height(PilgrimSpacing.xs))
        // iOS parity `WalkStartView.trailUnderline(for:)@v1.6.0` —
        // selected-tab underline is a horizontal stone gradient that
        // fades toward the row's outer edges so the three tabs read as
        // one soft band: Wander solid→faded, Honor faded both ends,
        // Seek faded→solid. Unselected = transparent.
        val stone = pilgrimColors.stone
        val underline: Brush = if (selected) {
            when (mode) {
                WalkMode.Wander -> Brush.horizontalGradient(
                    listOf(stone, stone.copy(alpha = 0.2f)),
                )
                WalkMode.Honor -> Brush.horizontalGradient(
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
