// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.cairn.CachedCairn

/**
 * iOS parity `CairnDetailView.swift@9a418e4` — the hero carries the
 * current tier's art and names its tier (U16 glyph spec L4).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CairnDetailSheetGlyphTest {

    @get:Rule val composeRule = createComposeRule()

    private fun cairn(stones: Int) = CachedCairn(
        id = "test",
        latitude = 0.0,
        longitude = 0.0,
        stoneCount = stones,
        lastPlacedAt = "2026-07-01T00:00:00Z",
    )

    @Test
    fun `hero names a faint cairn at one stone`() {
        composeRule.setContent {
            CairnDetailSheet(cairn = cairn(stones = 1), onDismiss = {})
        }
        composeRule.onNodeWithContentDescription("a faint cairn").assertIsDisplayed()
    }

    @Test
    fun `hero names an eternal cairn at one hundred eight stones`() {
        composeRule.setContent {
            CairnDetailSheet(cairn = cairn(stones = 108), onDismiss = {})
        }
        composeRule.onNodeWithContentDescription("an eternal cairn").assertIsDisplayed()
    }
}
