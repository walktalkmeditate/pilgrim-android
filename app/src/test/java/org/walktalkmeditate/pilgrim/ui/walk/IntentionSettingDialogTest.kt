// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class IntentionSettingDialogTest {

    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `Save callback fires with trimmed text`() {
        var saved: String? = null
        composeRule.setContent {
            IntentionSettingDialog(
                initial = null,
                onSave = { saved = it },
                onDismiss = {},
            )
        }
        composeRule.onNodeWithText("A line for this walk…").performTextInput("  walk well  ")
        composeRule.onNodeWithText("Set").performClick()
        assertEquals("walk well", saved)
    }

    @Test
    fun `Cancel callback fires`() {
        var dismissed = false
        composeRule.setContent {
            IntentionSettingDialog(
                initial = null,
                onSave = {},
                onDismiss = { dismissed = true },
            )
        }
        composeRule.onNodeWithText("Cancel").performClick()
        assertTrue(dismissed)
    }

    @Test
    fun `text input clamps at 140 chars`() {
        var saved: String? = null
        composeRule.setContent {
            IntentionSettingDialog(
                initial = null,
                onSave = { saved = it },
                onDismiss = {},
            )
        }
        val longText = "x".repeat(200)
        composeRule.onNodeWithText("A line for this walk…").performTextInput(longText)
        composeRule.onNodeWithText("Set").performClick()
        assertEquals(140, saved?.length)
    }

    @Test
    fun `confirm button reads Set after iOS-parity rename`() {
        composeRule.setContent {
            IntentionSettingDialog(initial = null, onSave = {}, onDismiss = {})
        }
        composeRule.onNodeWithText("Set").assertIsDisplayed()
        composeRule.onNodeWithText("Save").assertDoesNotExist()
    }

    @Test
    fun `character count caption renders for empty text`() {
        composeRule.setContent {
            IntentionSettingDialog(initial = null, onSave = {}, onDismiss = {})
        }
        composeRule.onNodeWithText("0/140").assertIsDisplayed()
    }

    @Test
    fun `character count caption updates as user types`() {
        composeRule.setContent {
            IntentionSettingDialog(initial = "walk well", onSave = {}, onDismiss = {})
        }
        composeRule.onNodeWithText("9/140").assertIsDisplayed()
    }

    @Test
    fun `bumping resetKey discards the typed draft on reopen`() {
        // Reproduces the Cancel-then-reopen scenario: user types "abc",
        // the parent dismisses (resetKey++), reopens (same `initial`,
        // new resetKey). The draft must NOT be restored.
        var resetKey by mutableStateOf(0)
        var initial by mutableStateOf<String?>(null)
        composeRule.setContent {
            IntentionSettingDialog(
                initial = initial,
                resetKey = resetKey,
                onSave = {},
                onDismiss = {},
            )
        }
        // The dialog renders an OutlinedTextField with a Compose
        // semantics SetText action — drive it via that action so the
        // assertion doesn't depend on placeholder vs typed-content
        // selectors.
        composeRule.onNode(hasSetTextAction()).performTextInput("abc")
        composeRule.onNodeWithText("3/140").assertIsDisplayed()
        // Parent bumps the key (Cancel handler).
        resetKey++
        composeRule.waitForIdle()
        // Draft is discarded; counter back to 0/140.
        composeRule.onNodeWithText("0/140").assertIsDisplayed()
    }
}
