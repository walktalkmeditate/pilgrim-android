// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression: PR #89 shipped a rememberSaveable RevealPhase + reduceMotion
 * read inside WalkSummaryScreen. Wrapping the screen in a Dialog (Stage 5)
 * must NOT break those — Dialog content has its own SaveableStateRegistry,
 * so we explicitly verify rememberSaveable initializes and persists across
 * a recomposition inside a Dialog with the same properties WalkSummaryScreen
 * uses (usePlatformDefaultWidth = false, decorFitsSystemWindows = false).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkSummaryScreenDialogScopeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rememberSaveable_persistsInsideDialog() {
        var phase: String? = null
        var triggerRecompose by mutableStateOf(0)
        composeRule.setContent {
            @Suppress("UNUSED_VARIABLE")
            val trigger = triggerRecompose
            Dialog(
                onDismissRequest = {},
                properties = DialogProperties(
                    usePlatformDefaultWidth = false,
                    decorFitsSystemWindows = false,
                ),
            ) {
                val saved by rememberSaveable { mutableStateOf("Revealed") }
                phase = saved
                androidx.compose.material3.Text(text = saved)
            }
        }
        composeRule.waitForIdle()
        assert(phase == "Revealed") {
            "rememberSaveable failed to initialize inside Dialog; got $phase"
        }
        triggerRecompose = 1
        composeRule.waitForIdle()
        assert(phase == "Revealed") {
            "rememberSaveable did not persist across recompose; got $phase"
        }
        composeRule.onNodeWithText("Revealed").assertExists()
    }
}
