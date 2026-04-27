// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.app.Application
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
        composeRule.onNodeWithText("Save").performClick()
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
        composeRule.onNodeWithText("Save").performClick()
        assertEquals(140, saved?.length)
    }
}
