// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import android.app.Application
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Spa
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.core.prompt.CustomPromptStyle
import org.walktalkmeditate.pilgrim.core.prompt.GeneratedPrompt
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "en")
class PromptListSheetTest {

    @get:Rule val composeRule = createComposeRule()

    private fun fakePrompt(
        title: String,
        subtitle: String = "subtitle for $title",
        id: String = title,
    ): GeneratedPrompt = GeneratedPrompt(
        id = id,
        style = null,
        customStyle = null,
        title = title,
        subtitle = subtitle,
        text = "prompt body",
        icon = Icons.Outlined.Spa,
    )

    private fun fakeCustomStyle(
        title: String,
        instruction: String = "instruction for $title",
        id: String = "style-$title",
    ): CustomPromptStyle = CustomPromptStyle(
        id = id,
        title = title,
        icon = "leaf",
        instruction = instruction,
    )

    /**
     * Custom prompts MUST carry a non-null `customStyle` per the
     * PromptGenerator contract — `PromptListSheetContent` derives the
     * Edit / Delete style from `prompt.customStyle` directly. Test
     * fixtures that previously paired a `fakePrompt` with a separate
     * `fakeCustomStyle` should now use this helper so the fields stay
     * coupled.
     */
    private fun fakeCustomPrompt(
        title: String,
        subtitle: String = "instruction for $title",
        id: String = title,
        styleId: String = "style-$title",
    ): GeneratedPrompt = GeneratedPrompt(
        id = id,
        style = null,
        customStyle = fakeCustomStyle(title = title, instruction = subtitle, id = styleId),
        title = title,
        subtitle = subtitle,
        text = "prompt body",
        icon = Icons.Outlined.Spa,
    )

    private val sixBuiltIns = listOf(
        fakePrompt("Reflective"),
        fakePrompt("Gratitude"),
        fakePrompt("Creative"),
        fakePrompt("Contemplative"),
        fakePrompt("Philosophical"),
        fakePrompt("Journaling"),
    )

    @Test fun `renders 6 built-in rows`() {
        composeRule.setContent {
            PilgrimTheme {
                PromptListSheetContent(
                    builtInPrompts = sixBuiltIns,
                    customPrompts = emptyList(),
                    onPromptClick = {},
                    onCreateCustom = {},
                    onEditCustom = {},
                    onDeleteCustom = {},
                )
            }
        }
        // First 2 rows fit in the Robolectric viewport without scrolling;
        // later rows must be scrolled into view through the LazyColumn.
        val list = composeRule.onNode(hasScrollAction())
        listOf("Reflective", "Gratitude", "Creative", "Contemplative", "Philosophical", "Journaling")
            .forEach { title ->
                list.performScrollToNode(hasText(title))
                composeRule.onNodeWithText(title).assertIsDisplayed()
            }
    }

    @Test fun `renders 6 built-in plus 2 custom rows`() {
        val customs = listOf(fakeCustomPrompt("Letter to Self"), fakeCustomPrompt("Future Me"))
        composeRule.setContent {
            PilgrimTheme {
                PromptListSheetContent(
                    builtInPrompts = sixBuiltIns,
                    customPrompts = customs,
                    onPromptClick = {},
                    onCreateCustom = {},
                    onEditCustom = {},
                    onDeleteCustom = {},
                )
            }
        }
        // All 8 prompt titles render — scroll each into view through the
        // LazyColumn since later items sit below the Robolectric viewport.
        val list = composeRule.onNode(hasScrollAction())
        (sixBuiltIns + customs).forEach { prompt ->
            list.performScrollToNode(hasText(prompt.title))
            composeRule.onNodeWithText(prompt.title).assertIsDisplayed()
        }
    }

    @Test fun `Create Your Own enabled at zero customs invokes callback on click`() {
        var created = 0
        composeRule.setContent {
            PilgrimTheme {
                PromptListSheetContent(
                    builtInPrompts = sixBuiltIns,
                    customPrompts = emptyList(),
                    onPromptClick = {},
                    onCreateCustom = { created++ },
                    onEditCustom = {},
                    onDeleteCustom = {},
                )
            }
        }
        composeRule.onNode(hasScrollAction())
            .performScrollToNode(hasText("Create Your Own"))
        composeRule.onNodeWithText("Create Your Own").performClick()
        assertEquals(1, created)
    }

    @Test fun `Create Your Own enabled at two customs`() {
        val customs = listOf(fakeCustomPrompt("A"), fakeCustomPrompt("B"))
        var created = 0
        composeRule.setContent {
            PilgrimTheme {
                PromptListSheetContent(
                    builtInPrompts = sixBuiltIns,
                    customPrompts = customs,
                    onPromptClick = {},
                    onCreateCustom = { created++ },
                    onEditCustom = {},
                    onDeleteCustom = {},
                )
            }
        }
        composeRule.onNode(hasScrollAction())
            .performScrollToNode(hasText("Create Your Own"))
        composeRule.onNodeWithText("Create Your Own").performClick()
        assertEquals(1, created)
    }

    @Test fun `Create Your Own disabled at three customs swallows clicks`() {
        val customs = listOf(fakeCustomPrompt("A"), fakeCustomPrompt("B"), fakeCustomPrompt("C"))
        var created = 0
        composeRule.setContent {
            PilgrimTheme {
                PromptListSheetContent(
                    builtInPrompts = sixBuiltIns,
                    customPrompts = customs,
                    onPromptClick = {},
                    onCreateCustom = { created++ },
                    onEditCustom = {},
                    onDeleteCustom = {},
                )
            }
        }
        composeRule.onNode(hasScrollAction())
            .performScrollToNode(hasText("Create Your Own"))
        composeRule.onNodeWithText("Create Your Own").performClick()
        assertEquals(0, created)
    }

    @Test fun `clicking a built-in row invokes onPromptClick with the row's prompt`() {
        var clicked: GeneratedPrompt? = null
        composeRule.setContent {
            PilgrimTheme {
                PromptListSheetContent(
                    builtInPrompts = sixBuiltIns,
                    customPrompts = emptyList(),
                    onPromptClick = { clicked = it },
                    onCreateCustom = {},
                    onEditCustom = {},
                    onDeleteCustom = {},
                )
            }
        }
        // Reflective is row 0 — fits in initial viewport without scrolling.
        composeRule.onNodeWithText("Reflective").performClick()
        assertEquals("Reflective", clicked?.title)
    }

    @Test fun `clicking a custom row invokes onPromptClick with the row's prompt`() {
        val custom = fakeCustomPrompt("Letter to Self")
        var clicked: GeneratedPrompt? = null
        composeRule.setContent {
            PilgrimTheme {
                PromptListSheetContent(
                    builtInPrompts = sixBuiltIns,
                    customPrompts = listOf(custom),
                    onPromptClick = { clicked = it },
                    onCreateCustom = {},
                    onEditCustom = {},
                    onDeleteCustom = {},
                )
            }
        }
        composeRule.onNode(hasScrollAction())
            .performScrollToNode(hasText("Letter to Self"))
        composeRule.onNodeWithText("Letter to Self").performClick()
        assertEquals("Letter to Self", clicked?.title)
    }

    @Test fun `clicking Edit on a custom row invokes onEditCustom with the parallel-indexed style`() {
        val customA = fakeCustomPrompt("A")
        val customB = fakeCustomPrompt("B")
        val styleA = customA.customStyle!!
        var edited: CustomPromptStyle? = null
        composeRule.setContent {
            PilgrimTheme {
                PromptListSheetContent(
                    builtInPrompts = sixBuiltIns,
                    customPrompts = listOf(customA, customB),
                    onPromptClick = {},
                    onCreateCustom = {},
                    onEditCustom = { edited = it },
                    onDeleteCustom = {},
                )
            }
        }
        // Scroll the first custom row into view, then click its Edit button.
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("A"))
        composeRule.onAllNodesWithTag(PROMPT_LIST_EDIT_BUTTON_TAG)[0].performClick()
        assertEquals(styleA, edited)
    }

    @Test fun `clicking Delete on a custom row invokes onDeleteCustom with the parallel-indexed style`() {
        val customA = fakeCustomPrompt("A")
        val customB = fakeCustomPrompt("B")
        val styleB = customB.customStyle!!
        var deleted: CustomPromptStyle? = null
        composeRule.setContent {
            PilgrimTheme {
                PromptListSheetContent(
                    builtInPrompts = sixBuiltIns,
                    customPrompts = listOf(customA, customB),
                    onPromptClick = {},
                    onCreateCustom = {},
                    onEditCustom = {},
                    onDeleteCustom = { deleted = it },
                )
            }
        }
        // Scroll the second custom row into view; assert the parallel index
        // by clicking its Delete button (proves alignment, not row 0).
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("B"))
        composeRule.onAllNodesWithTag(PROMPT_LIST_DELETE_BUTTON_TAG).onLast().performClick()
        assertEquals(styleB, deleted)
    }

    @Test fun `custom counter renders pluralized text when one custom`() {
        composeRule.setContent {
            PilgrimTheme {
                PromptListSheetContent(
                    builtInPrompts = sixBuiltIns,
                    customPrompts = listOf(fakeCustomPrompt("A")),
                    onPromptClick = {},
                    onCreateCustom = {},
                    onEditCustom = {},
                    onDeleteCustom = {},
                )
            }
        }
        composeRule.onNode(hasScrollAction())
            .performScrollToNode(hasText("1 of 3 custom styles"))
        composeRule.onNodeWithText("1 of 3 custom styles").assertIsDisplayed()
    }

    @Test fun `custom counter renders pluralized text when three customs`() {
        val customs = listOf(fakeCustomPrompt("A"), fakeCustomPrompt("B"), fakeCustomPrompt("C"))
        composeRule.setContent {
            PilgrimTheme {
                PromptListSheetContent(
                    builtInPrompts = sixBuiltIns,
                    customPrompts = customs,
                    onPromptClick = {},
                    onCreateCustom = {},
                    onEditCustom = {},
                    onDeleteCustom = {},
                )
            }
        }
        composeRule.onNode(hasScrollAction())
            .performScrollToNode(hasText("3 of 3 custom styles"))
        composeRule.onNodeWithText("3 of 3 custom styles").assertIsDisplayed()
    }
}
