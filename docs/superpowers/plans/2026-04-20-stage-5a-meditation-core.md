# Stage 5-A Implementation Plan — Meditation core UX

Spec: `docs/superpowers/specs/2026-04-20-stage-5a-meditation-core-design.md`.

A new `MeditationScreen` entered from an ActiveWalk state transition to `Meditating`. Breathing circle + timer + Done. Keeps screen on. Domain layer already complete.

---

## Task 1 — `BreathingCircle.kt` (pure visual composable)

**Create:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/meditation/BreathingCircle.kt`

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.meditation

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * A soft moss-glow circle that breathes — scales 0.45 → 1.0 and back
 * over a 6-second cycle (3s inhale + 3s exhale) via
 * [rememberInfiniteTransition] with [FastOutSlowInEasing] on each
 * half-cycle.
 *
 * Pure visual composable: takes the moss color as a param (no theme
 * read) so previews and tests don't need a `PilgrimTheme` wrapper.
 * Stage 5-A's single delight — no particles, no rings, no milestone
 * flashes (iOS's `MeditationView.swift` has all of those; we defer).
 */
@Composable
internal fun BreathingCircle(
    moss: Color,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "breath")
    val scale by transition.animateFloat(
        initialValue = SCALE_EXHALED,
        targetValue = SCALE_INHALED,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = HALF_CYCLE_MS,
                easing = FastOutSlowInEasing,
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breathScale",
    )

    Canvas(modifier = modifier.size(CIRCLE_SIZE_DP.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val outerRadius = (size.minDimension / 2f) * scale
        // Outer halo — moss at 50% fading to 0%, broad and soft.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    moss.copy(alpha = 0.5f),
                    moss.copy(alpha = 0.15f),
                    moss.copy(alpha = 0f),
                ),
                center = center,
                radius = outerRadius,
            ),
            radius = outerRadius,
            center = center,
        )
        // Inner core — moss at 70% fading to 30%, denser focal point.
        val innerRadius = outerRadius * 0.5f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    moss.copy(alpha = 0.7f),
                    moss.copy(alpha = 0.3f),
                ),
                center = center,
                radius = innerRadius,
            ),
            radius = innerRadius,
            center = center,
        )
    }
}

private const val SCALE_EXHALED = 0.45f
private const val SCALE_INHALED = 1.0f
private const val HALF_CYCLE_MS = 3_000
private const val CIRCLE_SIZE_DP = 320
```

**Verify:** `./gradlew :app:compileDebugKotlin`.

---

## Task 2 — `BreathingCircleTest.kt` (Robolectric smoke)

**Create:** `app/src/test/java/org/walktalkmeditate/pilgrim/ui/meditation/BreathingCircleTest.kt`

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.meditation

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BreathingCircleTest {

    @get:Rule val composeRule = createComposeRule()

    @Test fun `circle composes without crashing`() {
        composeRule.setContent {
            Box(Modifier.size(400.dp, 800.dp)) {
                BreathingCircle(moss = Color(0xFF5F7553))
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().assertExists()
    }
}
```

**Verify:** `./gradlew :app:testDebugUnitTest --tests "*BreathingCircleTest"`.

---

## Task 3 — `MeditationScreen.kt`

**Create:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/meditation/MeditationScreen.kt`

Structure: public `MeditationScreen(onEnded, viewModel)` hooks the VM + nav + keep-screen-on; internal `MeditationScreenContent(elapsedSeconds, onDone, mossColor)` is the pure composable for tests.

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.meditation

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.domain.WalkState
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType
import org.walktalkmeditate.pilgrim.ui.walk.WalkViewModel

/**
 * Stage 5-A: contemplative meditation surface. Entered from
 * ActiveWalkScreen when the walk state transitions to
 * [WalkState.Meditating]. Breathing circle + session timer + Done
 * button. No audio; no rhythm picker; no sensors. Domain layer
 * (reducer + events + `replayWalkEventTotals.totalMeditatedMillis`)
 * already handles the accounting.
 *
 * State observer: when the walk state transitions AWAY from
 * Meditating — either because the user tapped Done (→ Active) or
 * because the walk was externally finished (→ Finished) — fires
 * [onEnded] so the NavHost can pop back to ActiveWalk. Mirrors
 * `ActiveWalkScreen`'s Finished→onFinished pattern.
 *
 * Keeps the screen on for the duration via
 * [WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON] on the host
 * Activity. Cleared on dispose so a mediation session doesn't
 * leak the flag into subsequent screens.
 *
 * See `docs/superpowers/specs/2026-04-20-stage-5a-meditation-core-design.md`.
 */
@Composable
fun MeditationScreen(
    onEnded: () -> Unit,
    viewModel: WalkViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val currentOnEnded by rememberUpdatedState(onEnded)

    // Session timer: start at 0 on screen entry, tick once per second
    // while the composable is alive. Intentionally NOT using
    // WalkState.Meditating.meditationStartedAt — the user's mental
    // model is "the timer started when I saw this screen," which may
    // lag the state transition by a frame or two. Accounting truth
    // lives in the reducer via totalMeditatedMillis.
    var elapsedSeconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(TIMER_TICK_MS)
            elapsedSeconds += 1
        }
    }

    // Observe state transitions AWAY from Meditating. Keyed on state
    // class (not the full state) so Active → Active recompositions
    // on location samples don't re-fire.
    LaunchedEffect(ui.walkState::class) {
        if (ui.walkState !is WalkState.Meditating) {
            currentOnEnded()
        }
    }

    // FLAG_KEEP_SCREEN_ON for the duration of the composable. Guard
    // the cast — previews and Robolectric may not host an Activity.
    val activity = LocalContext.current as? Activity
    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Double-tap guard on Done: the endMeditation call is async; the
    // state transition lands a frame or two later. Without the guard,
    // a user tapping Done twice fires two endMeditation coroutines —
    // the reducer ignores the second (Active has no MeditateEnd
    // branch) so no persisted duplicate event, but two haptic
    // dismissal animations is messy UX. Also blocks hardware-back
    // triggering endMeditation after the button tap already did.
    var didEnd by remember { mutableStateOf(false) }
    val endSession: () -> Unit = {
        if (!didEnd) {
            didEnd = true
            viewModel.endMeditation()
        }
    }

    // Intercept hardware back; treat as Done. Without this, back pops
    // to ActiveWalk with the controller still in Meditating, and
    // ActiveWalkScreen's state observer would bounce right back to
    // MeditationScreen — oscillation bug.
    BackHandler(enabled = !didEnd) { endSession() }

    val moss = pilgrimColors.moss
    MeditationScreenContent(
        elapsedSeconds = elapsedSeconds,
        mossColor = moss,
        enabled = !didEnd,
        onDone = endSession,
    )
}

/**
 * Pure composable — takes explicit state + colors so tests and
 * previews don't need a `WalkViewModel` or `PilgrimTheme`. Matches
 * the [GoshuinScreenContent] pattern from Stage 4-C.
 */
@Composable
internal fun MeditationScreenContent(
    elapsedSeconds: Int,
    mossColor: Color,
    enabled: Boolean,
    onDone: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(pilgrimColors.parchment),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(PilgrimSpacing.big),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            BreathingCircle(moss = mossColor)
            Spacer(Modifier.height(PilgrimSpacing.big))
            Text(
                text = formatTimer(elapsedSeconds),
                style = pilgrimType.statValue,
                color = pilgrimColors.fog,
            )
        }
        OutlinedButton(
            onClick = onDone,
            enabled = enabled,
            shape = RoundedCornerShape(DONE_BUTTON_CORNER_DP.dp),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = PilgrimSpacing.big),
        ) {
            Text(
                text = stringResource(R.string.meditation_done),
                style = pilgrimType.button,
                color = pilgrimColors.fog,
            )
        }
    }
}

private fun formatTimer(elapsedSeconds: Int): String {
    val total = elapsedSeconds.coerceAtLeast(0)
    val minutes = total / 60
    val seconds = total % 60
    return "%d:%02d".format(minutes, seconds)
}

private const val TIMER_TICK_MS = 1_000L
private const val DONE_BUTTON_CORNER_DP = 24
```

**Verify:** `./gradlew :app:compileDebugKotlin`. Watch for `pilgrimColors.moss` existing in the palette (it does — used by calligraphy ink flavor).

---

## Task 4 — `MeditationScreenTest.kt`

**Create:** `app/src/test/java/org/walktalkmeditate/pilgrim/ui/meditation/MeditationScreenTest.kt`

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.meditation

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

/**
 * Composition smoke tests for [MeditationScreenContent]. Animation
 * timing + `FLAG_KEEP_SCREEN_ON` aren't asserted — Robolectric stubs
 * both; manual on-device QA is authoritative.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MeditationScreenTest {

    @get:Rule val composeRule = createComposeRule()

    @Test fun `renders at elapsed 0 shows 0 colon 00 timer`() {
        composeRule.setContent {
            PilgrimTheme {
                Box(Modifier.size(400.dp, 800.dp)) {
                    MeditationScreenContent(
                        elapsedSeconds = 0,
                        mossColor = Color(0xFF5F7553),
                        enabled = true,
                        onDone = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("0:00").assertIsDisplayed()
    }

    @Test fun `renders at elapsed 67 shows 1 colon 07 timer`() {
        composeRule.setContent {
            PilgrimTheme {
                Box(Modifier.size(400.dp, 800.dp)) {
                    MeditationScreenContent(
                        elapsedSeconds = 67,
                        mossColor = Color(0xFF5F7553),
                        enabled = true,
                        onDone = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("1:07").assertIsDisplayed()
    }

    @Test fun `Done button click fires onDone`() {
        var doneCalls = 0
        composeRule.setContent {
            PilgrimTheme {
                Box(Modifier.size(400.dp, 800.dp)) {
                    MeditationScreenContent(
                        elapsedSeconds = 10,
                        mossColor = Color(0xFF5F7553),
                        enabled = true,
                        onDone = { doneCalls++ },
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Done").performClick()
        assertEquals(1, doneCalls)
    }

    @Test fun `composes without crashing`() {
        composeRule.setContent {
            PilgrimTheme {
                Box(Modifier.size(400.dp, 800.dp)) {
                    MeditationScreenContent(
                        elapsedSeconds = 3600,
                        mossColor = Color(0xFF5F7553),
                        enabled = true,
                        onDone = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().assertExists()
    }
}
```

**Verify:** `./gradlew :app:testDebugUnitTest --tests "*MeditationScreenTest"`.

---

## Task 5 — `strings.xml`: one new entry

**Edit:** `app/src/main/res/values/strings.xml`

Add after the existing `walk_action_end_meditation` line:

```xml
    <string name="meditation_done">Done</string>
```

**Verify:** `./gradlew :app:compileDebugResources`.

---

## Task 6 — `ActiveWalkScreen.kt`: add `onEnterMeditation` + state observer branch

**Edit:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/ActiveWalkScreen.kt`

Add new parameter to `ActiveWalkScreen`:

```kotlin
@Composable
fun ActiveWalkScreen(
    onFinished: (walkId: Long) -> Unit,
    onEnterMeditation: () -> Unit,      // NEW
    viewModel: WalkViewModel = hiltViewModel(),
) {
```

Update the state-class observer to fork on Meditating:

```kotlin
    LaunchedEffect(ui.walkState::class) {
        when (val state = ui.walkState) {
            is WalkState.Finished -> onFinished(state.walk.walkId)
            is WalkState.Meditating -> onEnterMeditation()
            else -> Unit
        }
    }
```

(The existing `if (state is WalkState.Finished) onFinished(state.walk.walkId)` becomes the `when` above.)

**Verify:** `./gradlew :app:compileDebugKotlin`.

---

## Task 7 — `PilgrimNavHost.kt`: new route + wire from ActiveWalk

**Edit:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/navigation/PilgrimNavHost.kt`

Add to `Routes`:

```kotlin
    const val MEDITATION = "meditation"
```

Add the `import` for `MeditationScreen`:

```kotlin
import org.walktalkmeditate.pilgrim.ui.meditation.MeditationScreen
```

Add the new composable block near the other walk-flow routes (after `ACTIVE_WALK`, before `WALK_SUMMARY_PATTERN`):

```kotlin
        composable(Routes.MEDITATION) {
            MeditationScreen(
                onEnded = { navController.popBackStack(Routes.ACTIVE_WALK, inclusive = false) },
            )
        }
```

Update `composable(Routes.ACTIVE_WALK)` to pass the new `onEnterMeditation`:

```kotlin
        composable(Routes.ACTIVE_WALK) {
            ActiveWalkScreen(
                onFinished = { walkId ->
                    navController.navigate(Routes.walkSummary(walkId)) {
                        popUpTo(Routes.HOME) { inclusive = false }
                    }
                },
                onEnterMeditation = {
                    // launchSingleTop: protects against a double-emit
                    // of state-class observer firing twice in quick
                    // succession (rare but physically possible if the
                    // reducer bounces through an intermediate state).
                    navController.navigate(Routes.MEDITATION) {
                        launchSingleTop = true
                    }
                },
            )
        }
```

**Verify:** `./gradlew :app:compileDebugKotlin`.

Note: `popBackStack(Routes.ACTIVE_WALK, inclusive = false)` rather than bare `popBackStack()` — guards against the rare case where MeditationScreen is reached via some future deep-link path without ACTIVE_WALK on the stack below it (MeditationScreen still pops cleanly; if ACTIVE_WALK isn't on the stack, popBackStack is a no-op and the user stays on Meditation — the state observer will then fire again on the next recomposition if the walk is truly done, correctly navigating to the summary).

Actually revise: MeditationScreen's `onEnded` fires on state transition away from Meditating. If state went to `Finished`, `popBackStack(Routes.ACTIVE_WALK, inclusive = false)` lands on ActiveWalk, whose state observer IMMEDIATELY fires `onFinished` on the next composition → nav to Summary. Two hops but correct.

---

## Task 8 — Build + test

```bash
export PATH="$HOME/.asdf/shims:$PATH" JAVA_HOME="$(asdf where java 2>/dev/null)"
cd <worktree>
./gradlew :app:testDebugUnitTest :app:lintDebug
```

**Verify:** all green. ~5 new test cases pass. No new lint warnings.

---

## Task 9 — Pre-commit audit

- `git diff --stat` — confirms 4 new + 3 modified files.
- Verify SPDX header on all new files.
- Verify no `// TODO` without a Stage-reference comment.
- Verify no OutRun references.
