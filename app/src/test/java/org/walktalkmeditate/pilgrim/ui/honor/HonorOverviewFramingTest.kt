// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.honor

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The overview hands its map the card's measured height as the fit's bottom inset (parity spec F §9.2). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HonorOverviewFramingTest {

    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `the map's inset is the card's height, and follows it when the card grows`() {
        var cardHeight by mutableStateOf(180.dp)
        var inset = Dp.Unspecified
        composeRule.setContent {
            Box(Modifier.size(400.dp, 800.dp)) {
                HonorOverviewFrame(
                    map = { inset = it },
                    card = { Box(Modifier.fillMaxWidth().height(cardHeight)) },
                )
            }
        }
        composeRule.waitForIdle()
        assertEquals(180f, inset.value, 0.5f)

        cardHeight = 230.dp
        composeRule.waitForIdle()
        assertEquals(230f, inset.value, 0.5f)
    }
}
