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
 * iOS parity `StonePlacementSheet.swift@9a418e4` — both sections carry
 * the becoming tier's art, surfaced here through the accessibility
 * labels the glyphs own (U16 glyph spec L2/L3).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StonePlacementSheetGlyphTest {

    @get:Rule val composeRule = createComposeRule()

    private fun cairn(stones: Int) = CachedCairn(
        id = "test",
        latitude = 0.0,
        longitude = 0.0,
        stoneCount = stones,
        lastPlacedAt = "2026-07-01T00:00:00Z",
    )

    @Test
    fun `existing cairn shows becoming-tier art when the stone crosses a threshold`() {
        composeRule.setContent {
            StonePlacementSheet(onPlace = {}, onDismiss = {}, nearbyCairn = cairn(stones = 6))
        }
        composeRule.onNodeWithContentDescription("Becomes a medium cairn").assertIsDisplayed()
    }

    @Test
    fun `existing cairn at the eternal crossing reads with the an article`() {
        composeRule.setContent {
            StonePlacementSheet(onPlace = {}, onDismiss = {}, nearbyCairn = cairn(stones = 107))
        }
        composeRule.onNodeWithContentDescription("Becomes an eternal cairn").assertIsDisplayed()
    }

    @Test
    fun `existing cairn below a crossing still names the becoming tier`() {
        composeRule.setContent {
            StonePlacementSheet(onPlace = {}, onDismiss = {}, nearbyCairn = cairn(stones = 2))
        }
        composeRule.onNodeWithContentDescription("Becomes a small cairn").assertIsDisplayed()
    }

    @Test
    fun `new cairn shows the ghosted faint art`() {
        composeRule.setContent {
            StonePlacementSheet(onPlace = {}, onDismiss = {}, nearbyCairn = null)
        }
        composeRule.onNodeWithContentDescription("Begins a faint cairn").assertIsDisplayed()
    }
}
