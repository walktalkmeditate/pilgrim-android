// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Spa
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.core.prompt.GeneratedPrompt
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "en")
class PromptDetailDialogTest {

    @get:Rule val composeRule = createComposeRule()

    private val samplePrompt = GeneratedPrompt(
        id = "test-prompt",
        style = null,
        customStyle = null,
        title = "Contemplative",
        subtitle = "A quiet sit-with",
        text = "Hello world prompt body",
        icon = Icons.Outlined.Spa,
    )

    private fun appContext(): Context =
        ApplicationProvider.getApplicationContext<Application>()

    private fun clipboardText(): String? {
        val cm = appContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return cm.primaryClip?.getItemAt(0)?.text?.toString()
    }

    private fun nextStartedIntent(): Intent? =
        shadowOf(appContext() as Application).peekNextStartedActivity()

    @Test
    fun renders_titleAndPromptText() {
        composeRule.setContent {
            PilgrimTheme {
                PromptDetailContent(prompt = samplePrompt, onDismiss = {})
            }
        }
        composeRule.onNodeWithText("Contemplative").assertIsDisplayed()
        composeRule.onNodeWithText("Hello world prompt body").assertIsDisplayed()
    }

    @Test
    fun clickCopy_setsClipboardAndShowsCopiedFeedback_andPills() {
        composeRule.setContent {
            PilgrimTheme {
                PromptDetailContent(prompt = samplePrompt, onDismiss = {})
            }
        }
        composeRule.onNodeWithTag(PROMPT_DETAIL_COPY_BUTTON_TAG).performClick()
        composeRule.waitForIdle()

        assertEquals("Hello world prompt body", clipboardText())
        composeRule.onNodeWithText("Copied!").assertIsDisplayed()
        composeRule.onNodeWithTag(PROMPT_DETAIL_PILL_CHATGPT_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(PROMPT_DETAIL_PILL_CLAUDE_TAG).assertIsDisplayed()
    }

    @Test
    fun clickCopy_8sLater_resetsLabelAndHidesPills() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            PilgrimTheme {
                PromptDetailContent(prompt = samplePrompt, onDismiss = {})
            }
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithTag(PROMPT_DETAIL_COPY_BUTTON_TAG).performClick()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithText("Copied!").assertIsDisplayed()

        composeRule.mainClock.advanceTimeBy(PROMPT_DETAIL_FEEDBACK_RESET_MS + 500L)
        composeRule.mainClock.advanceTimeByFrame()

        composeRule.onNodeWithText("Copy").assertIsDisplayed()
        composeRule.onNodeWithTag(PROMPT_DETAIL_PILL_CHATGPT_TAG).assertDoesNotExist()
    }

    @Test
    fun reTapCopy_resets8sTimer() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            PilgrimTheme {
                PromptDetailContent(prompt = samplePrompt, onDismiss = {})
            }
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithTag(PROMPT_DETAIL_COPY_BUTTON_TAG).performClick()
        composeRule.mainClock.advanceTimeByFrame()

        composeRule.mainClock.advanceTimeBy(7_000L)
        composeRule.onNodeWithText("Copied!").assertIsDisplayed()

        composeRule.onNodeWithTag(PROMPT_DETAIL_COPY_BUTTON_TAG).performClick()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeBy(7_000L)

        composeRule.onNodeWithText("Copied!").assertIsDisplayed()
    }

    @Test
    fun clickShare_dispatchesActionSendChooserIntent_withPromptText() {
        composeRule.setContent {
            PilgrimTheme {
                PromptDetailContent(prompt = samplePrompt, onDismiss = {})
            }
        }
        composeRule.onNodeWithTag(PROMPT_DETAIL_SHARE_BUTTON_TAG).performClick()
        composeRule.waitForIdle()

        val started = nextStartedIntent()
        assertNotNull(started)
        assertEquals(Intent.ACTION_CHOOSER, started!!.action)
        val inner = started.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertNotNull(inner)
        assertEquals(Intent.ACTION_SEND, inner!!.action)
        assertEquals("text/plain", inner.type)
        assertEquals("Hello world prompt body", inner.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun clickChatGPTPill_dispatchesActionViewIntent_toChatGPTUrl() {
        composeRule.setContent {
            PilgrimTheme {
                PromptDetailContent(prompt = samplePrompt, onDismiss = {})
            }
        }
        composeRule.onNodeWithTag(PROMPT_DETAIL_COPY_BUTTON_TAG).performClick()
        composeRule.waitForIdle()
        // Drain the Copy intent (none — clipboard write doesn't start an
        // activity) then click the pill.
        composeRule.onNodeWithTag(PROMPT_DETAIL_PILL_CHATGPT_TAG).performClick()
        composeRule.waitForIdle()

        val started = nextStartedIntent()
        assertNotNull(started)
        assertEquals(Intent.ACTION_VIEW, started!!.action)
        assertEquals(CHATGPT_URL, started.dataString)
    }

    @Test
    fun clickClaudePill_dispatchesActionViewIntent_toClaudeUrl() {
        composeRule.setContent {
            PilgrimTheme {
                PromptDetailContent(prompt = samplePrompt, onDismiss = {})
            }
        }
        composeRule.onNodeWithTag(PROMPT_DETAIL_COPY_BUTTON_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(PROMPT_DETAIL_PILL_CLAUDE_TAG).performClick()
        composeRule.waitForIdle()

        val started = nextStartedIntent()
        assertNotNull(started)
        assertEquals(Intent.ACTION_VIEW, started!!.action)
        assertEquals(CLAUDE_URL, started.dataString)
    }

    @Test
    fun clickDone_invokesOnDismiss() {
        var dismissed = 0
        composeRule.setContent {
            PilgrimTheme {
                PromptDetailContent(prompt = samplePrompt, onDismiss = { dismissed++ })
            }
        }
        composeRule.onNodeWithTag(PROMPT_DETAIL_DONE_BUTTON_TAG).performClick()
        composeRule.waitForIdle()

        assertEquals(1, dismissed)
    }

    @Test
    fun pillsHidden_beforeCopyTap() {
        composeRule.setContent {
            PilgrimTheme {
                PromptDetailContent(prompt = samplePrompt, onDismiss = {})
            }
        }
        composeRule.onNodeWithTag(PROMPT_DETAIL_PILL_CHATGPT_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(PROMPT_DETAIL_PILL_CLAUDE_TAG).assertDoesNotExist()
        // Sanity: clipboard untouched too.
        assertNull(clipboardText())
    }
}
