// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import kotlinx.coroutines.delay
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.domain.WalkAccumulator
import org.walktalkmeditate.pilgrim.domain.WalkState
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

/**
 * BUG C: the "Begin with intention" Settings toggle was inert because
 * a product-override block hard-disabled `showAutoIntention` and the
 * predicate wrongly required `WalkState.Active` (the pre-walk surface
 * is Idle/Finished, never Active).
 *
 * iOS (`ActiveWalkView.swift:362-379@v1.6.0`) fires the prompt in the
 * pre-walk `.onAppear`, opening the SAME IntentionSettingView the
 * manual "Set Intention" row opens, 0.5s after the surface appears,
 * with a recovery guard so re-entering an in-progress walk does NOT
 * pop it.
 *
 * The full `ActiveWalkScreen` needs Hilt + Mapbox; following the
 * `ActiveWalkAmbientRowTest` precedent this harness replicates the
 * exact production wiring (the `remember` latch, the
 * `isRecoveryComposition` guard, the `LaunchedEffect(navWalkState
 * ::class)` calling [shouldAutoPromptIntention] then opening the
 * `showPreWalkIntention` sheet) so the auto-pop behavior is exercised
 * against the real [IntentionSettingSheet].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ActiveWalkScreenAutoIntentionSheetTest {

    @get:Rule val composeRule = createComposeRule()

    private val accumulator = WalkAccumulator(walkId = 1L, startedAt = 1_000L)

    @Composable
    private fun AutoIntentionHarness(
        walkState: WalkState,
        beginWithIntention: Boolean,
        preWalkIntention: String?,
    ) {
        var showPreWalkIntention by remember { mutableStateOf(false) }
        val hasCheckedAutoIntention = remember { mutableStateOf(false) }
        val isRecoveryComposition = remember { walkState !is WalkState.Idle }
        LaunchedEffect(walkState::class) {
            if (isRecoveryComposition) return@LaunchedEffect
            if (shouldAutoPromptIntention(
                    walkState = walkState,
                    beginWithIntention = beginWithIntention,
                    intention = preWalkIntention,
                    hasCheckedAutoIntention = hasCheckedAutoIntention.value,
                )
            ) {
                hasCheckedAutoIntention.value = true
                delay(AUTO_INTENTION_DELAY_MS)
                showPreWalkIntention = true
            }
        }
        if (showPreWalkIntention) {
            IntentionSettingSheet(
                initial = preWalkIntention,
                recents = emptyList(),
                suggestions = emptyList(),
                onSave = {},
                onDismiss = { showPreWalkIntention = false },
            )
        }
    }

    @Test
    fun `auto-pops the pre-walk intention sheet on fresh Idle when toggle on`() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            PilgrimTheme {
                AutoIntentionHarness(
                    walkState = WalkState.Idle,
                    beginWithIntention = true,
                    preWalkIntention = null,
                )
            }
        }
        composeRule.mainClock.advanceTimeBy(0)
        composeRule.onNodeWithText("Set Your Intention").assertDoesNotExist()

        composeRule.mainClock.advanceTimeBy(AUTO_INTENTION_DELAY_MS + 50)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Set Your Intention").assertExists()
    }

    @Test
    fun `does not auto-pop under isRecoveryComposition (entering an in-progress walk)`() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            PilgrimTheme {
                AutoIntentionHarness(
                    walkState = WalkState.Active(accumulator),
                    beginWithIntention = true,
                    preWalkIntention = null,
                )
            }
        }
        composeRule.mainClock.advanceTimeBy(AUTO_INTENTION_DELAY_MS + 500)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Set Your Intention").assertDoesNotExist()
    }

    @Test
    fun `does not auto-pop when toggle off`() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            PilgrimTheme {
                AutoIntentionHarness(
                    walkState = WalkState.Idle,
                    beginWithIntention = false,
                    preWalkIntention = null,
                )
            }
        }
        composeRule.mainClock.advanceTimeBy(AUTO_INTENTION_DELAY_MS + 500)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Set Your Intention").assertDoesNotExist()
    }

    @Test
    fun `does not auto-pop when an intention draft is already set`() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            PilgrimTheme {
                AutoIntentionHarness(
                    walkState = WalkState.Idle,
                    beginWithIntention = true,
                    preWalkIntention = "silence",
                )
            }
        }
        composeRule.mainClock.advanceTimeBy(AUTO_INTENTION_DELAY_MS + 500)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Set Your Intention").assertDoesNotExist()
    }
}
