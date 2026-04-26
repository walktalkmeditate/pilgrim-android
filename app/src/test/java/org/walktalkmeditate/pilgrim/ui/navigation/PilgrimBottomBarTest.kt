// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.navigation

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
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
@Config(sdk = [34], application = Application::class)
class PilgrimBottomBarTest {

    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `renders all three tabs`() {
        composeRule.setContent {
            PilgrimTheme {
                PilgrimBottomBar(currentRoute = Routes.PATH, onSelectTab = {})
            }
        }
        composeRule.onNodeWithText("Path").assertIsDisplayed()
        composeRule.onNodeWithText("Journal").assertIsDisplayed()
        composeRule.onNodeWithText("Settings").assertIsDisplayed()
    }

    @Test
    fun `tap on Journal invokes onSelectTab with HOME route`() {
        val selected = mutableListOf<String>()
        composeRule.setContent {
            PilgrimTheme {
                PilgrimBottomBar(
                    currentRoute = Routes.PATH,
                    onSelectTab = { selected += it },
                )
            }
        }
        composeRule.onNodeWithText("Journal").performClick()
        assertEquals(listOf(Routes.HOME), selected)
    }

    @Test
    fun `current route is selected`() {
        composeRule.setContent {
            PilgrimTheme {
                PilgrimBottomBar(currentRoute = Routes.SETTINGS, onSelectTab = {})
            }
        }
        composeRule.onNodeWithText("Settings").assertIsSelected()
    }
}
