// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.honor

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.honor.HonorLinkToast
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

/**
 * The link toast inside an Honor sheet's own window (stage-links spec Q6;
 * iOS's `LinkToastOverlay` in each sheet, `MainTabView.swift:64,77,91,178-193@3b78728`):
 * it shows over the sheet's content, and taps pass through it to the sheet,
 * as iOS's `.allowsHitTesting(false)` lets them (`MainCoordinatorView.swift:548@3b78728`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HonorSheetToastTest {

    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `a link toast set while an Honor sheet is up shows inside it, and taps reach the sheet beneath`() {
        val toast = MutableStateFlow<HonorLinkToast?>(null)
        var tapped = 0
        composeRule.setContent {
            PilgrimTheme {
                CompositionLocalProvider(LocalHonorLinkToast provides toast) {
                    HonorSheetHost(onDismissed = {}) {
                        Box(Modifier.fillMaxWidth().height(240.dp).clickable { tapped++ })
                    }
                }
            }
        }

        composeRule.runOnIdle { toast.value = HonorLinkToast.NeedsNewerPilgrim }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(NEWER).assertIsDisplayed().performClick()

        composeRule.runOnIdle { assertEquals(1, tapped) }
    }

    @Test
    fun `with no toast provided, as with Honor off, a sheet draws none`() {
        composeRule.setContent {
            PilgrimTheme {
                HonorSheetHost(onDismissed = {}) { Box(Modifier.fillMaxWidth().height(240.dp)) }
            }
        }

        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodesWithText(NEWER).fetchSemanticsNodes().size)
    }

    private companion object {
        const val NEWER = "this link needs a newer Pilgrim"
    }
}
