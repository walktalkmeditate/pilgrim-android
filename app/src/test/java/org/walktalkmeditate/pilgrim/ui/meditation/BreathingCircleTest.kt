// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.meditation

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Composition smoke test for [BreathingCircle]. Animation timing + the
 * gradient draw aren't asserted — Robolectric's Canvas is a stub
 * (Stage 3-C lesson); this test confirms the composable reaches the
 * draw pipeline without throwing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BreathingCircleTest {

    @get:Rule val composeRule = createComposeRule()

    @Test fun `circle composes without crashing`() {
        composeRule.setContent {
            Box(Modifier.size(400.dp, 800.dp)) {
                BreathingCircle(moss = Color(0xFF7A8B6F))
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().assertExists()
    }
}
