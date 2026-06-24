// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.meditation

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.sounds.BreathRhythm
import org.walktalkmeditate.pilgrim.ui.design.LocalReduceMotion
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

/**
 * Composition smoke tests for [MeditationScreenContent]. Animation
 * timing + `FLAG_KEEP_SCREEN_ON` aren't asserted — Robolectric stubs
 * both; manual on-device QA is authoritative for those.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MeditationScreenTest {

    @get:Rule val composeRule = createComposeRule()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private val moss = Color(0xFF7A8B6F)

    // BreathRhythm.byId(6) = "None" — skips the InfiniteTransition
    // inside BreathingCircle (see `if (rhythm.isNone) ... SCALE_INHALED`
    // branch). Tests that call `assertIsDisplayed` need `waitForIdle()`
    // to actually settle; the default Calm rhythm spins forever and
    // never finalises layout, so the text-node assertion blocks
    // (Robolectric flake). The cadence/breath-cycle behavior is
    // covered by on-device QA and the cadenced Done-click tests below.
    private val noneRhythm = BreathRhythm.byId(6)

    @Test fun `renders at elapsed 0 shows 0 colon 00 timer`() {
        composeRule.setContent {
            PilgrimTheme {
                Box(Modifier.size(400.dp, 800.dp)) {
                    MeditationScreenContent(
                        elapsedSeconds = 0,
                        mossColor = moss,
                        enabled = true,
                        onDone = {},
                        breathRhythm = noneRhythm,
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
                        mossColor = moss,
                        enabled = true,
                        onDone = {},
                        breathRhythm = noneRhythm,
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
                        mossColor = moss,
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

    @Test fun `Done button is disabled when enabled flag is false`() {
        var doneCalls = 0
        composeRule.setContent {
            PilgrimTheme {
                Box(Modifier.size(400.dp, 800.dp)) {
                    MeditationScreenContent(
                        elapsedSeconds = 10,
                        mossColor = moss,
                        enabled = false,
                        onDone = { doneCalls++ },
                    )
                }
            }
        }
        composeRule.waitForIdle()
        // A disabled OutlinedButton should not fire its onClick.
        composeRule.onNodeWithText("Done").performClick()
        assertEquals(0, doneCalls)
    }

    @Test fun `composes without crashing at long elapsed time`() {
        composeRule.setContent {
            PilgrimTheme {
                Box(Modifier.size(400.dp, 800.dp)) {
                    MeditationScreenContent(
                        elapsedSeconds = 3_600,
                        mossColor = moss,
                        enabled = true,
                        onDone = {},
                        breathRhythm = noneRhythm,
                    )
                }
            }
        }
        composeRule.waitForIdle()
        // 60:00 — minute format scales past 60; no zero-padding on mins.
        composeRule.onNodeWithText("60:00").assertIsDisplayed()
        composeRule.onRoot().assertExists()
    }

    // AF47: the soundscape label's tap + long-press are `pointerInput`
    // gestures, which are invisible to TalkBack. They must surface as a
    // button OnClick (tap) plus a "Meditation options" custom action
    // (long-press) so screen-reader users reach the same controls.
    @Test fun `soundscape label exposes button click and options custom action`() {
        var tapCalls = 0
        var longPressCalls = 0
        composeRule.setContent {
            PilgrimTheme {
                Box(Modifier.size(400.dp, 800.dp)) {
                    MeditationScreenContent(
                        elapsedSeconds = 0,
                        mossColor = moss,
                        enabled = true,
                        onDone = {},
                        breathRhythm = noneRhythm,
                        onSoundscapeTap = { tapCalls++ },
                        onSoundscapeLongPress = { longPressCalls++ },
                    )
                }
            }
        }
        composeRule.waitForIdle()

        // TalkBack invokes the SEMANTICS OnClick action (not a touch), which
        // is exactly the path AF47 adds for the gesture-only label.
        val silence = context.getString(R.string.meditation_soundscape_silence)
        composeRule.onNodeWithText(silence)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, tapCalls)

        invokeOptionsAction(composeRule.onNodeWithText(silence))
        assertEquals(1, longPressCalls)
    }

    // The semantics block is attached to the soundscape label unconditionally,
    // not gated to the silence variant — verify the playing variant
    // (soundscapeName != null) carries the same button + custom action so a
    // future branch-gating regression is caught.
    @Test fun `playing soundscape label exposes button click and options custom action`() {
        var tapCalls = 0
        var longPressCalls = 0
        composeRule.setContent {
            PilgrimTheme {
                Box(Modifier.size(400.dp, 800.dp)) {
                    MeditationScreenContent(
                        elapsedSeconds = 0,
                        mossColor = moss,
                        enabled = true,
                        onDone = {},
                        breathRhythm = noneRhythm,
                        soundscapeName = "Rain",
                        onSoundscapeTap = { tapCalls++ },
                        onSoundscapeLongPress = { longPressCalls++ },
                    )
                }
            }
        }
        composeRule.waitForIdle()

        val playing = context.getString(R.string.meditation_soundscape_playing, "Rain")
        composeRule.onNodeWithText(playing)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, tapCalls)

        invokeOptionsAction(composeRule.onNodeWithText(playing))
        assertEquals(1, longPressCalls)
    }

    // AF47: the breathing circle's long-press (open meditation options) is a
    // `pointerInput` gesture — invisible to TalkBack. When a handler is wired
    // it must expose the same "Meditation options" custom action.
    @Test fun `breathing circle exposes options custom action for long-press`() {
        var circleLongPress = 0
        var soundscapeLongPress = 0
        composeRule.setContent {
            PilgrimTheme {
                Box(Modifier.size(400.dp, 800.dp)) {
                    MeditationScreenContent(
                        elapsedSeconds = 0,
                        mossColor = moss,
                        enabled = true,
                        onDone = {},
                        breathRhythm = noneRhythm,
                        onSoundscapeLongPress = { soundscapeLongPress++ },
                        onCircleLongPress = { circleLongPress++ },
                    )
                }
            }
        }
        composeRule.waitForIdle()

        // The circle node is labeled so TalkBack announces it rather than an
        // unlabeled element with a bare action.
        composeRule.onNode(circleActionMatcher)
            .assertContentDescriptionEquals(
                context.getString(R.string.meditation_breathing_circle_description),
            )
        invokeOptionsAction(composeRule.onNode(circleActionMatcher))
        assertEquals(1, circleLongPress)
        assertEquals(0, soundscapeLongPress)
    }

    // The circle adds no semantic action when no long-press handler is wired
    // (e.g. a preview / non-interactive host), so TalkBack isn't told about a
    // control that does nothing.
    @Test fun `breathing circle exposes no custom action without a long-press handler`() {
        composeRule.setContent {
            PilgrimTheme {
                Box(Modifier.size(400.dp, 800.dp)) {
                    MeditationScreenContent(
                        elapsedSeconds = 0,
                        mossColor = moss,
                        enabled = true,
                        onDone = {},
                        breathRhythm = noneRhythm,
                        onCircleLongPress = null,
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNode(circleActionMatcher).assertDoesNotExist()
    }

    // AF48: with the system Remove Animations setting on, the breathing pulse
    // must hold a static scale — no `InfiniteTransition`. We render a
    // *cadenced* (non-None) rhythm under reduce-motion: if the transition were
    // NOT gated it would animate forever, `waitForIdle()` would never settle,
    // and the 30s timeout would fire. Settling proves the static-scale path.
    @Test(timeout = 30_000) fun `reduce-motion holds breathing circle static for a cadenced rhythm`() {
        val calm = BreathRhythm.byId(BreathRhythm.DEFAULT_ID)
        assertEquals(false, calm.isNone)
        composeRule.setContent {
            CompositionLocalProvider(LocalReduceMotion provides true) {
                BreathingCircle(moss = moss, breathRhythm = calm)
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().assertExists()
    }

    // AF48 (ceremony half): coverage for the snap() branch of `ceremonySpec`.
    // The closing-ceremony cross-fades use graphicsLayer alpha, which Robolectric
    // can't read, and finite tweens settle under waitForIdle either way — so this
    // exercises the reduce-motion ceremony path (no crash, summary branch composes)
    // rather than asserting visual timing, which is on-device QA territory.
    @Test fun `reduce-motion composes the closing ceremony summary phase`() {
        composeRule.setContent {
            PilgrimTheme {
                CompositionLocalProvider(LocalReduceMotion provides true) {
                    Box(Modifier.size(400.dp, 800.dp)) {
                        MeditationScreenContent(
                            elapsedSeconds = 300,
                            mossColor = moss,
                            enabled = false,
                            onDone = {},
                            breathRhythm = noneRhythm,
                            closingPhase = ClosingPhase.Summary,
                            closingPhrase = "Rest in the quiet",
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Rest in the quiet").assertExists()
    }

    // The breathing circle's node carries CustomActions but no OnClick; the
    // soundscape label carries BOTH (it's also a button), so this pair
    // uniquely identifies the circle.
    private val circleActionMatcher =
        SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions)
            .and(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))

    // CustomActions is a semantics *property* (a List), not an AccessibilityAction,
    // so `performSemanticsAction` can't target it — fetching the node and invoking
    // the action lambda is the standard idiom. `runOnIdle` waits for the
    // composition to settle, then runs on the UI thread.
    private fun invokeOptionsAction(interaction: SemanticsNodeInteraction) {
        val label = context.getString(R.string.meditation_options_action)
        val action = interaction.fetchSemanticsNode()
            .config[SemanticsActions.CustomActions]
            .first { it.label == label }
        composeRule.runOnIdle { action.action() }
    }
}
