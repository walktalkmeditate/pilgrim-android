// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.meditation

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
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

    private val moss = Color(0xFF7A8B6F)

    @Test fun `renders at elapsed 0 shows 0 colon 00 timer`() {
        composeRule.setContent {
            PilgrimTheme {
                Box(Modifier.size(400.dp, 800.dp)) {
                    MeditationScreenContent(
                        elapsedSeconds = 0,
                        mossColor = moss,
                        enabled = true,
                        onDone = {},
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
                    )
                }
            }
        }
        composeRule.waitForIdle()
        // 60:00 — minute format scales past 60; no zero-padding on mins.
        composeRule.onNodeWithText("60:00").assertIsDisplayed()
        composeRule.onRoot().assertExists()
    }
}
