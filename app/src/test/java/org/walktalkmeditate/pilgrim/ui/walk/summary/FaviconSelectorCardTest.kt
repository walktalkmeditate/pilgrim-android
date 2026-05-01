// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import org.walktalkmeditate.pilgrim.data.entity.WalkFavicon
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FaviconSelectorCardTest {

    @get:Rule val composeRule = createComposeRule()

    @Test
    fun rendersAllThreeButtons() {
        composeRule.setContent {
            PilgrimTheme {
                FaviconSelectorCard(selected = null, onSelect = {})
            }
        }
        composeRule.onNodeWithText("Transformative").assertIsDisplayed()
        composeRule.onNodeWithText("Peaceful").assertIsDisplayed()
        composeRule.onNodeWithText("Extraordinary").assertIsDisplayed()
    }

    @Test
    fun tapsSameButtonTwice_deselects() {
        val selections = mutableListOf<WalkFavicon?>()
        composeRule.setContent {
            var selected by remember { mutableStateOf<WalkFavicon?>(null) }
            PilgrimTheme {
                FaviconSelectorCard(
                    selected = selected,
                    onSelect = {
                        selected = it
                        selections += it
                    },
                )
            }
        }
        composeRule.onNodeWithText("Peaceful").performClick()
        composeRule.onNodeWithText("Peaceful").performClick()
        assertEquals(listOf(WalkFavicon.LEAF, null), selections)
    }
}
