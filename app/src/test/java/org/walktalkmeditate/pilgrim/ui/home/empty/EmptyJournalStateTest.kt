// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.empty

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EmptyJournalStateTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun renders_begin_caption() {
        composeRule.setContent {
            PilgrimTheme { EmptyJournalState() }
        }
        composeRule.onNodeWithText("Begin").assertIsDisplayed()
    }
}
