// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.meditation

import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.sounds.BreathRhythm
import org.walktalkmeditate.pilgrim.data.sounds.LocalBreathRhythm
import org.walktalkmeditate.pilgrim.ui.settings.sounds.BreathRhythmPickerSheet
import org.walktalkmeditate.pilgrim.domain.WalkState
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType
import org.walktalkmeditate.pilgrim.ui.walk.WalkViewModel

/**
 * Stage 5-A: contemplative meditation surface. Entered from
 * `ActiveWalkScreen` when the walk state transitions to
 * [WalkState.Meditating]. Breathing circle + session timer + Done
 * button. No audio; no rhythm picker; no sensors. Domain layer
 * (reducer + `MEDITATION_START/END` events + `replayWalkEventTotals`)
 * already handles the accounting.
 *
 * State observer: when the walk state transitions AWAY from
 * Meditating — either because the user tapped Done (→ Active) or
 * because the walk was externally finished (→ Finished) — fires
 * [onEnded] so the NavHost can pop back to ActiveWalk. Mirrors
 * `ActiveWalkScreen`'s Finished→onFinished pattern.
 *
 * Keeps the screen on for the session via
 * [WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON]. Cleared on
 * dispose so a meditation session doesn't leak the flag into
 * subsequent screens.
 *
 * See `docs/superpowers/specs/2026-04-20-stage-5a-meditation-core-design.md`.
 */
@Composable
fun MeditationScreen(
    onEnded: () -> Unit,
    viewModel: WalkViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    // Navigation observer reads this (not ui.walkState) — bypasses
    // the WhileSubscribed stateIn's stale-cache trap.
    val navWalkState by viewModel.walkState.collectAsStateWithLifecycle()
    // Snapshot the latest `onEnded` so a parent passing a fresh lambda
    // each recomposition doesn't leave us firing a stale closure when
    // the state-observer LaunchedEffect resumes. Same rememberUpdatedState
    // pattern as Stage 4-B's SealRevealOverlay.
    val currentOnEnded by rememberUpdatedState(onEnded)

    // Session timer: start at 0 on screen entry, tick once per second.
    // Intentionally NOT derived from `WalkState.Meditating.meditationStartedAt`
    // — the user's mental model is "the timer started when I saw this
    // screen". Accounting truth lives in the reducer via
    // `totalMeditatedMillis`, unaffected by what the UI displays.
    //
    // `rememberSaveable` so the timer survives configuration changes
    // (screen rotation mid-session); `mutableIntStateOf` has a built-in
    // saver that handles the int-specialization correctly.
    var elapsedSeconds by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(TIMER_TICK_MS)
            elapsedSeconds += 1
        }
    }

    // Observe state transitions AWAY from Meditating. Keyed on state
    // class (not the full state) so Active→Active recompositions on
    // location samples wouldn't re-fire. The `hasSeenMeditating`
    // latch prevents a spurious initial fire if the StateFlow's first
    // emission arrives non-Meditating (cold-restart race, WhileSubscribed
    // grace gap from an upstream subscription flicker, process-death
    // restore where state settles to `Finished` before first composition).
    // onEnded only fires after the screen has witnessed Meditating at
    // least once — the intended "state transitioned away" semantics.
    // `rememberSaveable` so the latch survives a configuration change
    // (screen rotation). Without this, rotating during the ~1-2 frame
    // window between Done tap and the state transition landing would:
    //   (a) reset `hasSeenMeditating` to false,
    //   (b) leave state=Active (the transition committed before
    //       rotation),
    //   (c) the LaunchedEffect below runs with state=Active but
    //       hasSeen=false → neither branch fires → user is STUCK on
    //       MeditationScreen with an Active walk; re-tapping Done
    //       is a reducer no-op so they can't escape.
    // `mutableStateOf<Boolean>` has a built-in saver, same as
    // `mutableIntStateOf` used for `elapsedSeconds` above.
    var hasSeenMeditating by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(navWalkState::class) {
        when {
            navWalkState is WalkState.Meditating -> hasSeenMeditating = true
            hasSeenMeditating -> currentOnEnded()
        }
    }

    // FLAG_KEEP_SCREEN_ON for the duration of the composable.
    // `LocalActivity.current` is the correct Compose accessor (as of
    // androidx.activity.compose 1.10+) — casting `LocalContext.current`
    // to Activity triggers the `ContextCastToActivity` lint error
    // because a Context isn't guaranteed to be an Activity (preview,
    // ComponentDialog, etc.). `LocalActivity` is nullable, which
    // matches the preview / Robolectric case cleanly.
    val activity = LocalActivity.current
    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // iOS parity `MeditationView.swift:609-650@db4196e` — Done-tap
    // captures the end timestamp synchronously (the user's mental
    // model is "the meditation ended when I tapped Done"). The 6.5s
    // ceremony plays locally; endMeditation receives the CAPTURED
    // millis so the 6.5s playback never inflates the recorded
    // interval.
    //
    // PR-review fixes folded in here:
    //  - `viewModel.nowMillis()` reads the same `Clock` the controller
    //    dispatches against, so a mocked clock in tests stays aligned
    //    with the captured end-millis (vs an earlier
    //    `System.currentTimeMillis()` which drifted from injected
    //    clock-time bases).
    //  - `dispatched` latch prevents double-fire of `endMeditation` —
    //    LaunchedEffect runs once and flips the latch; DisposableEffect
    //    onDispose was removed entirely because it fires on every
    //    config-change (rotation) and either finalized too early or
    //    double-finalized after ceremony complete.
    //  - LaunchedEffect computes remaining delays from elapsed since
    //    `doneAtMillis` instead of replaying the full sequence — a
    //    rotation mid-ceremony resumes at the correct phase + remaining
    //    delay rather than restarting from t=0.
    var didEnd by rememberSaveable { mutableStateOf(false) }
    var doneAtMillis by rememberSaveable { mutableStateOf(0L) }
    var dispatched by rememberSaveable { mutableStateOf(false) }
    var closingPhase by rememberSaveable { mutableStateOf(ClosingPhase.None) }
    val endSession: () -> Unit = {
        if (!didEnd) {
            didEnd = true
            doneAtMillis = viewModel.nowMillis()
            closingPhase = ClosingPhase.Dissolving
        }
    }
    LaunchedEffect(didEnd) {
        if (!didEnd || dispatched) return@LaunchedEffect
        // Elapsed since Done-tap. After a rotation mid-ceremony this is
        // > 0; the schedule resumes at the correct phase instead of
        // restarting from t=0.
        val elapsedAtEntry = (viewModel.nowMillis() - doneAtMillis).coerceAtLeast(0L)

        fun phaseFor(elapsed: Long): ClosingPhase = when {
            elapsed >= CEREMONY_FADE_OUT_DELAY_MS -> ClosingPhase.FadeOut
            elapsed >= CEREMONY_SUMMARY_DELAY_MS -> ClosingPhase.Summary
            else -> ClosingPhase.Dissolving
        }
        closingPhase = phaseFor(elapsedAtEntry)

        if (elapsedAtEntry < CEREMONY_SUMMARY_DELAY_MS) {
            delay(CEREMONY_SUMMARY_DELAY_MS - elapsedAtEntry)
            closingPhase = ClosingPhase.Summary
        }
        if (elapsedAtEntry < CEREMONY_FADE_OUT_DELAY_MS) {
            val nowElapsed = (viewModel.nowMillis() - doneAtMillis).coerceAtLeast(0L)
            delay(CEREMONY_FADE_OUT_DELAY_MS - nowElapsed)
            closingPhase = ClosingPhase.FadeOut
        }
        val finalElapsed = (viewModel.nowMillis() - doneAtMillis).coerceAtLeast(0L)
        if (finalElapsed < CEREMONY_TOTAL_MS) {
            delay(CEREMONY_TOTAL_MS - finalElapsed)
        }
        if (!dispatched) {
            dispatched = true
            viewModel.endMeditation(endMillis = doneAtMillis)
        }
    }

    // Intercept hardware back; treat as Done. Without this, back pops
    // to ActiveWalk with the controller still in Meditating, and
    // ActiveWalkScreen's state observer would immediately bounce back
    // to MeditationScreen — oscillation bug.
    //
    // Enabled unconditionally (including after `didEnd`): once the user
    // has tapped Done, `endSession` is idempotent (guarded by `didEnd`),
    // and the state transition to Active fires `onEnded` via the
    // observer above — pop happens naturally. Guarding `BackHandler`
    // on `!didEnd` would let the system default back fire during the
    // ~1-2 frame window between Done tap and state settle, letting the
    // user escape to ActiveWalk while still in Meditating → oscillation
    // via ActiveWalk's own state observer.
    BackHandler { endSession() }

    // iOS parity `MeditationView.swift:743-745@db4196e` — pulse the
    // breathing-ring at the {300, 600, 900, 1200, 1800}s milestones to
    // mark elapsed meditation.
    //
    // iOS uses `withAnimation(.easeInOut(duration: 1.5))` for the
    // 0→1 attack (symmetric cubic-in-out) and `withAnimation(.easeOut
    // (duration: 1.5))` for the 1→0 decay. Compose's
    // `FastOutSlowInEasing` is Material's ASYMMETRIC curve (0.4, 0,
    // 0.2, 1) — visually quicker start than iOS. The
    // `CubicBezierEasing(0.42, 0, 0.58, 1)` and `(0, 0, 0.58, 1)`
    // constants below are the canonical CSS/iOS curves so the pulse
    // shape matches.
    //
    // Fire condition uses `seconds >= nextUnfiredMilestone` (not
    // exact-set membership) so a discontinuity in the counter (future
    // wall-clock-derived timer, debugger fast-forward, etc.) still
    // fires the first un-fired milestone passed instead of silently
    // skipping it. Today's `elapsedSeconds += 1` producer is exact-
    // integer monotone so the difference is invisible, but the
    // defensive predicate keeps the comment claim load-bearing.
    //
    // `highestFiredMilestone` rememberSaveable latch survives
    // rotation / process-death restore so a recomposition at a
    // milestone-equal second doesn't double-fire. Stage 5-A `hasSeen`
    // pattern. Milestones are strictly monotonic
    // (300 < 600 < 900 < 1200 < 1800), so a single highest-int
    // captures the full set.
    val milestoneFlash = remember { Animatable(0f) }
    var highestFiredMilestone by rememberSaveable { mutableIntStateOf(-1) }
    LaunchedEffect(Unit) {
        snapshotFlow { elapsedSeconds }
            .collect { seconds ->
                val nextUnfired = MILESTONE_SECONDS_SORTED
                    .firstOrNull { it > highestFiredMilestone }
                    ?: return@collect
                if (seconds >= nextUnfired) {
                    highestFiredMilestone = nextUnfired
                    milestoneFlash.snapTo(0f)
                    milestoneFlash.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(
                            durationMillis = MILESTONE_FLASH_IN_MS,
                            easing = EASE_IN_OUT,
                        ),
                    )
                    delay(MILESTONE_FLASH_HOLD_MS)
                    milestoneFlash.animateTo(
                        targetValue = 0f,
                        animationSpec = tween(
                            durationMillis = MILESTONE_FLASH_OUT_MS,
                            easing = EASE_OUT,
                        ),
                    )
                }
            }
    }

    val moss = pilgrimColors.moss

    // iOS parity `MeditationView.swift:559-568@db4196e` — 100ms re-arm.
    // The user picks a new rhythm from the inline picker (long-press on
    // the breathing circle); we want a clean 100ms hold-at-current-scale
    // pause before the BreathingCircle's keyframe transition restarts at
    // SCALE_EXHALED with the new rhythm. Without this gap, Compose's
    // `key(rhythm.id)` snaps to SCALE_EXHALED on the same frame the
    // settings DataStore emits the new id, producing a visible jump
    // mid-inhale or mid-exhale.
    //
    // `sourceRhythmId` is the live settings-backed value (updates
    // immediately on `viewModel.setBreathRhythm`). `displayedRhythmId`
    // is what we pass to BreathingCircle; it lags `sourceRhythmId` by
    // ~100ms via the LaunchedEffect below. Initial-composition path:
    // both are equal → no delay → no pause. Rotation: rememberSaveable
    // restores both to the same value → no delay.
    val sourceRhythmId = LocalBreathRhythm.current
    var displayedRhythmId by rememberSaveable { mutableIntStateOf(sourceRhythmId) }
    LaunchedEffect(sourceRhythmId) {
        if (sourceRhythmId != displayedRhythmId) {
            delay(BREATH_RHYTHM_REARM_DELAY_MS)
            displayedRhythmId = sourceRhythmId
        }
    }
    val breathRhythm = BreathRhythm.byId(displayedRhythmId)

    // iOS parity `MeditationView.swift:60-63@db4196e` — 1.0s long-press
    // on the breathing circle opens the inline rhythm picker. Soft
    // haptic on press. ModalBottomSheet hosts BreathRhythmPickerSheet
    // (the same sheet used in Settings). On rhythm select:
    // `viewModel.setBreathRhythm(id)` writes to DataStore; the 100ms
    // re-arm above handles the visual transition.
    var showRhythmPicker by rememberSaveable { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    // iOS parity `MeditationView.swift:214-230, 726-734@db4196e` — emit a
    // ripple ring at end-of-inhale on every breath cycle. Cap at 3 rings
    // on-screen at once (drop-oldest on append). Each ring auto-removes
    // after [RIPPLE_LIFESPAN_MS] (3.0s).
    //
    // The breath-cycle observer is INDEPENDENT of BreathingCircle's
    // internal `rememberInfiniteTransition` — both started at the same
    // composition time so the phase boundaries stay synchronized in
    // practice; a tiny drift after many minutes is invisible.
    //
    // No reduce-motion guard — iOS doesn't suppress ripples either.
    val rippleRings = remember { mutableStateListOf<RippleRing>() }
    val ringIdSeq = remember { AtomicLong(0L) }
    LaunchedEffect(breathRhythm.id) {
        rippleRings.clear()
        if (breathRhythm.isNone) return@LaunchedEffect
        val inhaleMs = (breathRhythm.inhaleSeconds * 1000L).toLong()
        val holdInMs = (breathRhythm.holdInSeconds * 1000L).toLong()
        val exhaleMs = (breathRhythm.exhaleSeconds * 1000L).toLong()
        val holdOutMs = (breathRhythm.holdOutSeconds * 1000L).toLong()
        val tailMs = holdInMs + exhaleMs + holdOutMs
        if (inhaleMs <= 0L) return@LaunchedEffect
        while (isActive) {
            delay(inhaleMs)
            // iOS: `if rippleRings.count > 3 { rippleRings.removeFirst() }`
            // — drop oldest BEFORE append so on-screen count never
            // exceeds RIPPLE_RING_CAP.
            if (rippleRings.size >= RIPPLE_RING_CAP) rippleRings.removeAt(0)
            val ring = RippleRing(
                id = ringIdSeq.incrementAndGet(),
                spawnedAtMs = SystemClock.elapsedRealtime(),
            )
            rippleRings.add(ring)
            launch {
                delay(RIPPLE_LIFESPAN_MS)
                rippleRings.removeAll { it.id == ring.id }
            }
            if (tailMs > 0L) delay(tailMs)
        }
    }

    MeditationScreenContent(
        elapsedSeconds = elapsedSeconds,
        mossColor = moss,
        enabled = !didEnd,
        onDone = endSession,
        breathRhythm = breathRhythm,
        closingPhase = closingPhase,
        milestoneFlash = milestoneFlash.value,
        rippleRings = rippleRings,
        onCircleLongPress = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            showRhythmPicker = true
        },
    )
    if (showRhythmPicker) {
        BreathRhythmPickerSheet(
            currentRhythmId = sourceRhythmId,
            onSelect = { id ->
                viewModel.setBreathRhythm(id)
            },
            onDismiss = { showRhythmPicker = false },
        )
    }
}

/**
 * iOS parity `MeditationView.swift:855-857@db4196e`. Four-phase
 * ceremony that plays after the user taps Done. The captured
 * Done-tap millis is what gets recorded as the meditation end, so
 * the ceremony's 6.5s playback never inflates the interval — this
 * is the iOS bug-fix pattern (originally Date() was sampled at
 * dismiss time and the meditation row showed 6.5s longer than the
 * user's session).
 */
enum class ClosingPhase { None, Dissolving, Summary, FadeOut }

/**
 * iOS parity timing constants (`MeditationView.swift:631-650@db4196e`):
 *   t=0.0s — Dissolving (dim breathing circle, hide timer)
 *   t=2.0s — Summary (show session-end summary)
 *   t=5.0s — FadeOut (1.5s overlay tween to parchment)
 *   t=6.5s — onDismiss(endDate) invoked
 */
private const val CEREMONY_SUMMARY_DELAY_MS = 2_000L
private const val CEREMONY_FADE_OUT_DELAY_MS = 5_000L
private const val CEREMONY_TOTAL_MS = 6_500L

/**
 * Pure composable — takes explicit state + colors so tests and
 * previews don't need a `WalkViewModel` or `PilgrimTheme`. Matches
 * the `GoshuinScreenContent` pattern from Stage 4-C.
 */
@Composable
internal fun MeditationScreenContent(
    elapsedSeconds: Int,
    mossColor: Color,
    enabled: Boolean,
    onDone: () -> Unit,
    breathRhythm: BreathRhythm = BreathRhythm.byId(BreathRhythm.DEFAULT_ID),
    closingPhase: ClosingPhase = ClosingPhase.None,
    milestoneFlash: Float = 0f,
    rippleRings: List<RippleRing> = emptyList(),
    onCircleLongPress: (() -> Unit)? = null,
) {
    // Animated phase-driven opacities. Reduce-motion users get the
    // values pinned to their target so the screen still transitions
    // cleanly without playing the cross-fade tweens.
    val ceremonyActive = closingPhase != ClosingPhase.None
    val breathingAlpha = androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (ceremonyActive) 0f else 1f,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 1500),
        label = "ceremony-breathing-alpha",
    ).value
    val summaryAlpha = androidx.compose.animation.core.animateFloatAsState(
        targetValue = when (closingPhase) {
            ClosingPhase.Summary -> 1f
            ClosingPhase.FadeOut -> 1f
            else -> 0f
        },
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 1500),
        label = "ceremony-summary-alpha",
    ).value
    val fadeOverlayAlpha = androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (closingPhase == ClosingPhase.FadeOut) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 1500),
        label = "ceremony-fadeout-alpha",
    ).value
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(pilgrimColors.parchment),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(PilgrimSpacing.big)
                .graphicsLayer { alpha = breathingAlpha },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Layer ripple rings BEHIND BreathingCircle in a centered
            // Box. iOS structure (ZStack: rippleLayer below breathingCircle).
            // The Box sizes to the larger child (400dp ring footprint vs
            // 320dp circle), so the rings extend visibly past the
            // breathing-circle gradient halo.
            //
            // `key(breathRhythm.id)` forces a full re-composition of
            // BreathingCircle (and its rememberInfiniteTransition) when
            // the user picks a new rhythm mid-meditation. Without it,
            // changing rhythms can resume the new keyframe spec at the
            // old cycle's offset — visually jumping to a nonsensical
            // phase. The key restart trades a brief snap to
            // SCALE_EXHALED for a clean, predictable cycle on the new
            // rhythm.
            Box(contentAlignment = Alignment.Center) {
                MeditationRippleRings(rings = rippleRings, mossColor = mossColor)
                key(breathRhythm.id) {
                    BreathingCircle(
                        moss = mossColor,
                        breathRhythm = breathRhythm,
                        milestoneFlash = milestoneFlash,
                        modifier = if (onCircleLongPress != null) {
                            Modifier.pointerInput(Unit) {
                                detectTapGestures(onLongPress = { onCircleLongPress() })
                            }
                        } else {
                            Modifier
                        },
                    )
                }
            }
            Spacer(Modifier.height(PilgrimSpacing.big))
            Text(
                text = formatTimer(elapsedSeconds),
                style = pilgrimType.statValue,
                color = pilgrimColors.fog,
            )
        }
        // Session summary text — visible during Summary + FadeOut
        // phases. Cross-fades over 1.5s.
        if (summaryAlpha > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = summaryAlpha },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = formatTimer(elapsedSeconds),
                    style = pilgrimType.displayMedium,
                    color = pilgrimColors.ink,
                )
            }
        }
        // Final fade-to-parchment overlay over the last 1.5s.
        if (fadeOverlayAlpha > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(pilgrimColors.parchment.copy(alpha = fadeOverlayAlpha)),
            )
        }
        OutlinedButton(
            onClick = onDone,
            enabled = enabled,
            shape = RoundedCornerShape(DONE_BUTTON_CORNER_DP.dp),
            // Set the content color on the button itself so M3 can
            // auto-derive `disabledContentColor` (contentColor × 0.38
            // alpha). An explicit `color` on the child Text would
            // bypass `LocalContentColor` — the text would stay full
            // `fog` even when disabled, giving the user no visual
            // signal that their tap was accepted during the ~1-2
            // frame state-transition window.
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = pilgrimColors.fog,
            ),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = PilgrimSpacing.big),
        ) {
            Text(
                text = stringResource(R.string.meditation_done),
                style = pilgrimType.button,
            )
        }
    }
}

private fun formatTimer(elapsedSeconds: Int): String {
    val total = elapsedSeconds.coerceAtLeast(0)
    val minutes = total / 60
    val seconds = total % 60
    // `Locale.US` explicitly — on Arabic / Persian / Hindi system
    // locales, `%d` / `%02d` with `Locale.getDefault()` produce non-
    // ASCII digits (`٣:٤٢`) which breaks both the visual timer format
    // and the test assertions (`"0:00"` etc.). Matches the
    // `WalkFormat.kt` precedent for all numeric formatting in this
    // codebase.
    return String.format(Locale.US, "%d:%02d", minutes, seconds)
}

private const val TIMER_TICK_MS = 1_000L
private const val DONE_BUTTON_CORNER_DP = 24

/**
 * iOS parity `MeditationView.swift:743-745@db4196e`:
 *   • milestone seconds = {300, 600, 900, 1200, 1800}
 *   • pulse: easeInOut(1.5s) 0→1, hold 2.0s, easeOut(1.5s) 1→0
 *
 * iOS uses a ±20s window because its 0.5s float-second timer can land
 * off-tick on the milestone. Android's `elapsedSeconds += 1` integer
 * tick always passes through the exact milestone value, so an exact
 * `seconds in MILESTONE_SECONDS` match fires the ascending edge cleanly.
 */
/**
 * iOS parity `MeditationView.swift:559-568@db4196e`:
 * `DispatchQueue.main.asyncAfter(deadline: .now() + 0.1)` between
 * `isActive = false` and `startBreathCycle()`. 100ms lets the in-flight
 * breath-cycle continuation drain (main-queue FIFO; `asyncAfter(.now()
 * + 0)` arrives in ~1-2ms) and is imperceptible to the user. 50ms would
 * work but with less margin; 200ms would be a faint visible pause.
 */
private const val BREATH_RHYTHM_REARM_DELAY_MS = 100L

private val MILESTONE_SECONDS_SORTED = listOf(300, 600, 900, 1200, 1800)
private const val MILESTONE_FLASH_IN_MS = 1_500
private const val MILESTONE_FLASH_HOLD_MS = 2_000L
private const val MILESTONE_FLASH_OUT_MS = 1_500

/**
 * iOS-parity Bezier curves (`MeditationView.swift:743-745@db4196e`):
 *   `.easeInOut` = symmetric (0.42, 0, 0.58, 1)
 *   `.easeOut`   = decelerating (0,    0, 0.58, 1)
 */
private val EASE_IN_OUT = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
private val EASE_OUT = CubicBezierEasing(0f, 0f, 0.58f, 1f)
