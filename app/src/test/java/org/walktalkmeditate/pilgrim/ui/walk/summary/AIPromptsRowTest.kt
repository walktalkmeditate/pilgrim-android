// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "en")
class AIPromptsRowTest {

    @get:Rule val composeRule = createComposeRule()

    @Test
    fun renders_titleAndNoSpeechSubtitle_whenZeroTranscriptions() {
        composeRule.setContent {
            PilgrimTheme {
                AIPromptsRow(transcribedRecordingsCount = 0, onClick = {})
            }
        }
        composeRule.onNodeWithText("Generate AI Prompts").assertIsDisplayed()
        composeRule.onNodeWithText("Reflect on your walk").assertIsDisplayed()
    }

    @Test
    fun renders_singleTranscriptionPluralForm_whenOne() {
        composeRule.setContent {
            PilgrimTheme {
                AIPromptsRow(transcribedRecordingsCount = 1, onClick = {})
            }
        }
        composeRule.onNodeWithText("1 transcription available").assertIsDisplayed()
    }

    @Test
    fun renders_pluralForm_whenMultiple() {
        composeRule.setContent {
            PilgrimTheme {
                AIPromptsRow(transcribedRecordingsCount = 3, onClick = {})
            }
        }
        composeRule.onNodeWithText("3 transcriptions available").assertIsDisplayed()
    }

    @Test
    fun tap_invokesOnClick() {
        var clickCount = 0
        composeRule.setContent {
            PilgrimTheme {
                AIPromptsRow(
                    transcribedRecordingsCount = 0,
                    onClick = { clickCount++ },
                )
            }
        }
        composeRule.onNodeWithText("Generate AI Prompts").performClick()
        assertEquals(1, clickCount)
    }
}
