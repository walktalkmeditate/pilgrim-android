// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.core.prompt.CustomPromptStyle
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "en")
class CustomPromptEditorDialogTest {

    @get:Rule val composeRule = createComposeRule()

    private val sampleEditing = CustomPromptStyle(
        id = "abc",
        title = "Letter to Future Self",
        icon = "envelope.fill",
        instruction = "Write a kind letter from my future self.",
    )

    @Test
    fun renders_createMode_emptyFields() {
        composeRule.setContent {
            PilgrimTheme {
                CustomPromptEditorContent(
                    editing = null,
                    existingStyleCount = 0,
                    onSave = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.onNodeWithText("Create Your Own").assertIsDisplayed()
        composeRule.onNodeWithTag(CUSTOM_PROMPT_EDITOR_TITLE_FIELD_TAG)
            .assertIsDisplayed()
        composeRule.onNodeWithTag(CUSTOM_PROMPT_EDITOR_INSTRUCTION_FIELD_TAG)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun renders_editMode_prefilledFields() {
        composeRule.setContent {
            PilgrimTheme {
                CustomPromptEditorContent(
                    editing = sampleEditing,
                    existingStyleCount = 2,
                    onSave = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.onNodeWithText("Edit Custom Prompt").assertIsDisplayed()
        composeRule.onNodeWithText("Letter to Future Self").assertIsDisplayed()
        composeRule.onNodeWithText("Write a kind letter from my future self.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun saveButton_disabled_whenTitleBlank() {
        composeRule.setContent {
            PilgrimTheme {
                CustomPromptEditorContent(
                    editing = null,
                    existingStyleCount = 0,
                    onSave = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.onNodeWithTag(CUSTOM_PROMPT_EDITOR_INSTRUCTION_FIELD_TAG)
            .performTextInput("only an instruction")
        composeRule.onNodeWithTag(CUSTOM_PROMPT_EDITOR_SAVE_BUTTON_TAG)
            .assertIsNotEnabled()
    }

    @Test
    fun saveButton_disabled_whenInstructionBlank() {
        composeRule.setContent {
            PilgrimTheme {
                CustomPromptEditorContent(
                    editing = null,
                    existingStyleCount = 0,
                    onSave = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.onNodeWithTag(CUSTOM_PROMPT_EDITOR_TITLE_FIELD_TAG)
            .performTextInput("only a title")
        composeRule.onNodeWithTag(CUSTOM_PROMPT_EDITOR_SAVE_BUTTON_TAG)
            .assertIsNotEnabled()
    }

    @Test
    fun saveButton_enabled_whenBothNonBlank() {
        composeRule.setContent {
            PilgrimTheme {
                CustomPromptEditorContent(
                    editing = null,
                    existingStyleCount = 0,
                    onSave = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.onNodeWithTag(CUSTOM_PROMPT_EDITOR_TITLE_FIELD_TAG)
            .performTextInput("Title")
        composeRule.onNodeWithTag(CUSTOM_PROMPT_EDITOR_INSTRUCTION_FIELD_TAG)
            .performTextInput("Instruction body")
        composeRule.onNodeWithTag(CUSTOM_PROMPT_EDITOR_SAVE_BUTTON_TAG)
            .assertIsEnabled()
    }

    @Test
    fun saveButton_disabled_whenTitleOnlyWhitespace() {
        composeRule.setContent {
            PilgrimTheme {
                CustomPromptEditorContent(
                    editing = null,
                    existingStyleCount = 0,
                    onSave = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.onNodeWithTag(CUSTOM_PROMPT_EDITOR_TITLE_FIELD_TAG)
            .performTextInput("   ")
        composeRule.onNodeWithTag(CUSTOM_PROMPT_EDITOR_INSTRUCTION_FIELD_TAG)
            .performTextInput("Real instruction")
        composeRule.onNodeWithTag(CUSTOM_PROMPT_EDITOR_SAVE_BUTTON_TAG)
            .assertIsNotEnabled()
    }

    @Test
    fun saveButton_invokesOnSave_withTrimmedFields() {
        var saved: CustomPromptStyle? = null
        composeRule.setContent {
            PilgrimTheme {
                CustomPromptEditorContent(
                    editing = null,
                    existingStyleCount = 0,
                    onSave = { saved = it },
                    onDismiss = {},
                )
            }
        }
        composeRule.onNodeWithTag(CUSTOM_PROMPT_EDITOR_TITLE_FIELD_TAG)
            .performTextInput("  My Title  ")
        composeRule.onNodeWithTag(CUSTOM_PROMPT_EDITOR_INSTRUCTION_FIELD_TAG)
            .performTextInput("  My instruction body  ")
        composeRule.onNodeWithTag(CUSTOM_PROMPT_EDITOR_SAVE_BUTTON_TAG)
            .performClick()
        composeRule.waitForIdle()

        val result = checkNotNull(saved)
        assertEquals("My Title", result.title)
        assertEquals("My instruction body", result.instruction)
    }

    @Test
    fun saveButton_invokesOnSave_inEditMode_preservesId() {
        var saved: CustomPromptStyle? = null
        composeRule.setContent {
            PilgrimTheme {
                CustomPromptEditorContent(
                    editing = sampleEditing,
                    existingStyleCount = 2,
                    onSave = { saved = it },
                    onDismiss = {},
                )
            }
        }
        composeRule.onNodeWithTag(CUSTOM_PROMPT_EDITOR_TITLE_FIELD_TAG)
            .performTextReplacement("Renamed")
        composeRule.onNodeWithTag(CUSTOM_PROMPT_EDITOR_SAVE_BUTTON_TAG)
            .performClick()
        composeRule.waitForIdle()

        val result = checkNotNull(saved)
        assertEquals("abc", result.id)
        assertEquals("Renamed", result.title)
        assertEquals("envelope.fill", result.icon)
    }

    @Test
    fun iconCell_clickSelectsIcon() {
        var saved: CustomPromptStyle? = null
        composeRule.setContent {
            PilgrimTheme {
                CustomPromptEditorContent(
                    editing = null,
                    existingStyleCount = 0,
                    onSave = { saved = it },
                    onDismiss = {},
                )
            }
        }
        composeRule.onNodeWithTag(CUSTOM_PROMPT_EDITOR_TITLE_FIELD_TAG)
            .performTextInput("Title")
        composeRule.onNodeWithTag(CUSTOM_PROMPT_EDITOR_INSTRUCTION_FIELD_TAG)
            .performTextInput("Instruction body")
        composeRule.onNodeWithTag(customPromptEditorIconCellTag("envelope.fill"))
            .performClick()
        composeRule.onNodeWithTag(CUSTOM_PROMPT_EDITOR_SAVE_BUTTON_TAG)
            .performClick()
        composeRule.waitForIdle()

        val result = checkNotNull(saved)
        assertEquals("envelope.fill", result.icon)
    }

    @Test
    fun cancelButton_invokesOnDismiss() {
        var dismissed = 0
        composeRule.setContent {
            PilgrimTheme {
                CustomPromptEditorContent(
                    editing = null,
                    existingStyleCount = 0,
                    onSave = {},
                    onDismiss = { dismissed++ },
                )
            }
        }
        composeRule.onNodeWithTag(CUSTOM_PROMPT_EDITOR_CANCEL_BUTTON_TAG)
            .performClick()
        composeRule.waitForIdle()

        assertEquals(1, dismissed)
    }

    @Test
    fun counter_createMode_showsExistingPlusOne() {
        composeRule.setContent {
            PilgrimTheme {
                CustomPromptEditorContent(
                    editing = null,
                    existingStyleCount = 2,
                    onSave = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.onNodeWithTag(CUSTOM_PROMPT_EDITOR_COUNTER_TAG)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("3 of 3 custom styles").assertIsDisplayed()
    }

    @Test
    fun counter_editMode_showsExistingOnly() {
        composeRule.setContent {
            PilgrimTheme {
                CustomPromptEditorContent(
                    editing = sampleEditing,
                    existingStyleCount = 2,
                    onSave = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.onNodeWithTag(CUSTOM_PROMPT_EDITOR_COUNTER_TAG)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("2 of 3 custom styles").assertIsDisplayed()
    }

    @Test
    fun resolveCustomPromptIcon_unknownKey_fallsBackToFirstOption() {
        val resolved = resolveCustomPromptIcon("not.a.real.key")
        assertEquals(CUSTOM_PROMPT_ICON_OPTIONS.first().second, resolved)
        // Sanity: known keys resolve correctly too.
        assertEquals(
            CUSTOM_PROMPT_ICON_OPTIONS[2].second,
            resolveCustomPromptIcon("envelope.fill"),
        )
    }

    @Test
    fun customPromptIconOptions_count_is20() {
        assertEquals(20, CUSTOM_PROMPT_ICON_OPTIONS.size)
        assertEquals(20, CUSTOM_PROMPT_ICON_OPTIONS.map { it.first }.toSet().size)
    }
}
