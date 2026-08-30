// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests target [IntentionSheetContent] directly (not the
 * [IntentionSettingSheet] ModalBottomSheet shell) so assertions run
 * under Robolectric without the sheet window/animation layer — the
 * content carries all load-bearing logic (char clamp, resetKey draft
 * discard, chip taps).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class IntentionSettingSheetTest {

    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `Set callback fires with trimmed text`() {
        var saved: String? = null
        composeRule.setContent {
            IntentionSheetContent(
                initial = null, recents = emptyList(), suggestions = emptyList(),
                onSave = { saved = it }, onDismiss = {},
            )
        }
        composeRule.onNodeWithText("What purpose guides this walk?")
            .performTextInput("  walk well  ")
        composeRule.onNodeWithText("Set").performClick()
        assertEquals("walk well", saved)
    }

    @Test
    fun `Cancel callback fires`() {
        var dismissed = false
        composeRule.setContent {
            IntentionSheetContent(
                initial = null, recents = emptyList(), suggestions = emptyList(),
                onSave = {}, onDismiss = { dismissed = true },
            )
        }
        composeRule.onNodeWithText("Cancel").performClick()
        assertTrue(dismissed)
    }

    @Test
    fun `text input clamps at 140 chars`() {
        var saved: String? = null
        composeRule.setContent {
            IntentionSheetContent(
                initial = null, recents = emptyList(), suggestions = emptyList(),
                onSave = { saved = it }, onDismiss = {},
            )
        }
        composeRule.onNodeWithText("What purpose guides this walk?")
            .performTextInput("x".repeat(200))
        composeRule.onNodeWithText("Set").performClick()
        assertEquals(140, saved?.length)
    }

    @Test
    fun `confirm button reads Set not Save`() {
        composeRule.setContent {
            IntentionSheetContent(
                initial = null, recents = emptyList(), suggestions = emptyList(),
                onSave = {}, onDismiss = {},
            )
        }
        composeRule.onNodeWithText("Set").assertIsDisplayed()
        composeRule.onNodeWithText("Save").assertDoesNotExist()
    }

    @Test
    fun `header is the iOS-parity title`() {
        composeRule.setContent {
            IntentionSheetContent(
                initial = null, recents = emptyList(), suggestions = emptyList(),
                onSave = {}, onDismiss = {},
            )
        }
        composeRule.onNodeWithText("Set Your Intention").assertIsDisplayed()
    }

    @Test
    fun `character count caption renders and updates`() {
        composeRule.setContent {
            IntentionSheetContent(
                initial = "walk well", recents = emptyList(), suggestions = emptyList(),
                onSave = {}, onDismiss = {},
            )
        }
        composeRule.onNodeWithText("9/140").assertIsDisplayed()
    }

    @Test
    fun `bumping resetKey discards the typed draft on reopen`() {
        var resetKey by mutableStateOf(0)
        val initial by mutableStateOf<String?>(null)
        composeRule.setContent {
            IntentionSheetContent(
                initial = initial, recents = emptyList(), suggestions = emptyList(),
                resetKey = resetKey, onSave = {}, onDismiss = {},
            )
        }
        composeRule.onNode(hasSetTextAction()).performTextInput("abc")
        composeRule.onNodeWithText("3/140").assertIsDisplayed()
        resetKey++
        composeRule.waitForIdle()
        composeRule.onNodeWithText("0/140").assertIsDisplayed()
    }

    @Test
    fun `suggested and recent chips show when empty and tapping fills the field`() {
        composeRule.setContent {
            IntentionSheetContent(
                initial = null,
                recents = listOf("yesterday's intention"),
                suggestions = listOf("Honor the stillness"),
                onSave = {}, onDismiss = {},
            )
        }
        composeRule.onNodeWithText("Suggested").assertIsDisplayed()
        composeRule.onNodeWithText("Recent").assertIsDisplayed()
        composeRule.onNodeWithText("Honor the stillness").performClick()
        composeRule.waitForIdle()
        // Field now holds the suggestion → its length drives the counter.
        composeRule.onNodeWithText("19/140").assertIsDisplayed()
    }

    @Test
    fun `chip sections hide once the field is non-empty`() {
        composeRule.setContent {
            IntentionSheetContent(
                initial = "already typed",
                recents = listOf("a recent one"),
                suggestions = listOf("Find balance"),
                onSave = {}, onDismiss = {},
            )
        }
        composeRule.onNodeWithText("Suggested").assertDoesNotExist()
        composeRule.onNodeWithText("Recent").assertDoesNotExist()
    }

    @Test
    fun `listening state shows countdown and Done and hides the Voice control`() {
        composeRule.setContent {
            IntentionSheetContent(
                initial = null, recents = emptyList(), suggestions = emptyList(),
                onSave = {}, onDismiss = {},
                voiceState = IntentionVoiceState.Listening(level = 0.5f, secondsRemaining = 15),
            )
        }
        composeRule.onNodeWithText("15s").assertIsDisplayed()
        composeRule.onNodeWithText("Done").assertIsDisplayed()
        composeRule.onNodeWithText("Voice").assertDoesNotExist()
    }

    @Test
    fun `mic-denied state surfaces the access-needed label`() {
        composeRule.setContent {
            IntentionSheetContent(
                initial = null, recents = emptyList(), suggestions = emptyList(),
                onSave = {}, onDismiss = {},
                voiceState = IntentionVoiceState.MicDenied,
            )
        }
        composeRule.onNodeWithText("Mic access needed").assertIsDisplayed()
    }

    @Test
    fun `transient-error state surfaces the retry label and tapping it restarts voice`() {
        var started = 0
        composeRule.setContent {
            IntentionSheetContent(
                initial = null, recents = emptyList(), suggestions = emptyList(),
                onSave = {}, onDismiss = {},
                voiceState = IntentionVoiceState.TransientError,
                onStartVoice = { started++ },
            )
        }
        composeRule.onNodeWithText("Try again").assertIsDisplayed()
        composeRule.onNodeWithText("Try again").performClick()
        assertEquals(1, started)
    }

    @Test
    fun `a finished transcript fills the field updates the counter and is consumed once`() {
        var consumed = 0
        composeRule.setContent {
            IntentionSheetContent(
                initial = null, recents = emptyList(), suggestions = emptyList(),
                onSave = {}, onDismiss = {},
                voiceTranscript = "hello",
                onVoiceTranscriptConsumed = { consumed++ },
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("hello").assertIsDisplayed()
        composeRule.onNodeWithText("5/140").assertIsDisplayed()
        assertEquals(1, consumed)
    }

    @Test
    fun `an already-capped transcript at the limit renders the maxed counter`() {
        // The controller caps via cappedIntention upstream (see
        // IntentionVoiceControllerTest); the sheet must accept the
        // already-capped value without re-clamping or rejecting it.
        val capped = "x".repeat(140)
        var saved: String? = null
        composeRule.setContent {
            IntentionSheetContent(
                initial = null, recents = emptyList(), suggestions = emptyList(),
                onSave = { saved = it }, onDismiss = {},
                voiceTranscript = capped,
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("140/140").assertIsDisplayed()
        composeRule.onNodeWithText("Set").performClick()
        assertEquals(140, saved?.length)
    }

    @Test
    fun `no transcript leaves the field empty and does not consume`() {
        var consumed = false
        composeRule.setContent {
            IntentionSheetContent(
                initial = null, recents = emptyList(), suggestions = emptyList(),
                onSave = {}, onDismiss = {},
                voiceTranscript = null,
                onVoiceTranscriptConsumed = { consumed = true },
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("0/140").assertIsDisplayed()
        assertTrue(!consumed)
    }

    // --- U10 Recurring (Thought Threads) chip shelf ------------------------

    @Test
    fun `Recurring section starts absent and appears once the load completes`() {
        val deferred = CompletableDeferred<List<String>>()
        composeRule.setContent {
            IntentionSheetContent(
                initial = null, recents = emptyList(), suggestions = emptyList(),
                onSave = {}, onDismiss = {},
                loadThreadSuggestions = { deferred.await() },
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Recurring").assertDoesNotExist()

        deferred.complete(listOf("walk with 'river'"))
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Recurring").assertIsDisplayed()
        composeRule.onNodeWithText("walk with 'river'").assertIsDisplayed()
        composeRule.onNodeWithTag(INTENTION_RECURRING_CHIPS_TAG).assertExists()
    }

    @Test
    fun `a throwing suggestion loader leaves the sheet interactive with no Recurring section`() {
        composeRule.setContent {
            IntentionSheetContent(
                initial = null, recents = emptyList(), suggestions = emptyList(),
                onSave = {}, onDismiss = {},
                loadThreadSuggestions = { throw IllegalStateException("suggestion load failed") },
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Recurring").assertDoesNotExist()
        composeRule.onNodeWithTag(INTENTION_RECURRING_CHIPS_TAG).assertDoesNotExist()
        composeRule.onNodeWithText("0/140").assertIsDisplayed()
    }

    @Test
    fun `Recurring section absent when the load resolves empty`() {
        composeRule.setContent {
            IntentionSheetContent(
                initial = null, recents = emptyList(), suggestions = emptyList(),
                onSave = {}, onDismiss = {},
                loadThreadSuggestions = { emptyList() },
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Recurring").assertDoesNotExist()
    }

    @Test
    fun `Recurring renders above Suggested and Recent`() {
        composeRule.setContent {
            IntentionSheetContent(
                initial = null,
                recents = listOf("yesterday's intention"),
                suggestions = listOf("Honor the stillness"),
                onSave = {}, onDismiss = {},
                loadThreadSuggestions = { listOf("walk with 'river'") },
            )
        }
        composeRule.waitForIdle()
        val recurringTop = composeRule.onNodeWithText("Recurring").getUnclippedBoundsInRoot().top
        val suggestedTop = composeRule.onNodeWithText("Suggested").getUnclippedBoundsInRoot().top
        val recentTop = composeRule.onNodeWithText("Recent").getUnclippedBoundsInRoot().top
        assertTrue(recurringTop < suggestedTop)
        assertTrue(suggestedTop < recentTop)
    }

    @Test
    fun `Recurring chips hide once the field is non-empty`() {
        composeRule.setContent {
            IntentionSheetContent(
                initial = "already typed", recents = emptyList(), suggestions = emptyList(),
                onSave = {}, onDismiss = {},
                loadThreadSuggestions = { listOf("walk with 'river'") },
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Recurring").assertDoesNotExist()
    }

    @Test
    fun `tapping a Recurring chip replaces the field content`() {
        composeRule.setContent {
            IntentionSheetContent(
                initial = null, recents = emptyList(), suggestions = emptyList(),
                onSave = {}, onDismiss = {},
                loadThreadSuggestions = { listOf("walk with 'river'") },
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("walk with 'river'").performClick()
        composeRule.waitForIdle()
        // Field now holds exactly the chip text (17 chars) -> counter reflects it.
        composeRule.onNodeWithText("17/140").assertIsDisplayed()
    }
}
