// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import android.app.Application
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Spa
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.core.prompt.GeneratedPrompt
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

/**
 * U10 clipboard hardening: a copied prompt whose text carries a Thought
 * Threads dossier gets `ClipDescription.EXTRA_IS_SENSITIVE` on API 33+.
 * Two separate `@Config(sdk=)` classes (mirroring the
 * `PermissionChecksApi33Test` precedent) since Robolectric SDK level is
 * class-scoped, not parameterizable per-test in this codebase.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "en")
class PromptClipboardSensitivityApi34Test {

    @get:Rule val composeRule = createComposeRule()

    private fun appContext(): Context = ApplicationProvider.getApplicationContext<Application>()

    private fun clipExtras() =
        (appContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .primaryClip?.description?.extras

    @Test
    fun `copying a prompt with a threads dossier marks the clip sensitive`() {
        val prompt = GeneratedPrompt(
            id = "dossier-prompt",
            style = null,
            customStyle = null,
            title = "Contemplative",
            subtitle = "A quiet sit-with",
            text = "Hello dossier-bearing prompt body",
            icon = Icons.Outlined.Spa,
            hasThreadsDossier = true,
        )
        composeRule.setContent {
            PilgrimTheme {
                PromptDetailContent(prompt = prompt, onDismiss = {})
            }
        }
        composeRule.onNodeWithTag(PROMPT_DETAIL_COPY_BUTTON_TAG).performClick()
        composeRule.waitForIdle()

        assertEquals(true, clipExtras()?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE))
    }

    @Test
    fun `copying a prompt without a threads dossier leaves the clip unmarked`() {
        val prompt = GeneratedPrompt(
            id = "plain-prompt",
            style = null,
            customStyle = null,
            title = "Contemplative",
            subtitle = "A quiet sit-with",
            text = "Hello plain prompt body",
            icon = Icons.Outlined.Spa,
            hasThreadsDossier = false,
        )
        composeRule.setContent {
            PilgrimTheme {
                PromptDetailContent(prompt = prompt, onDismiss = {})
            }
        }
        composeRule.onNodeWithTag(PROMPT_DETAIL_COPY_BUTTON_TAG).performClick()
        composeRule.waitForIdle()

        assertNull(clipExtras())
    }
}

/** Below API 33: `EXTRA_IS_SENSITIVE` does not exist yet — must not crash, must not be set. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "en")
class PromptClipboardSensitivityBelowApi33Test {

    @get:Rule val composeRule = createComposeRule()

    private fun appContext(): Context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `copying a dossier-bearing prompt below API 33 does not crash and leaves the clip unmarked`() {
        val prompt = GeneratedPrompt(
            id = "dossier-prompt-legacy",
            style = null,
            customStyle = null,
            title = "Contemplative",
            subtitle = "A quiet sit-with",
            text = "Hello dossier-bearing prompt body",
            icon = Icons.Outlined.Spa,
            hasThreadsDossier = true,
        )
        composeRule.setContent {
            PilgrimTheme {
                PromptDetailContent(prompt = prompt, onDismiss = {})
            }
        }
        composeRule.onNodeWithTag(PROMPT_DETAIL_COPY_BUTTON_TAG).performClick()
        composeRule.waitForIdle()

        val cm = appContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals("Hello dossier-bearing prompt body", cm.primaryClip?.getItemAt(0)?.text?.toString())
        assertNull(cm.primaryClip?.description?.extras)
    }
}
