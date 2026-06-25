// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the #43 Wander-Zoom target. The animation timing itself
 * (easeOut 0.4s) is device-QA; this pins the target value + the
 * reduce-motion bypass.
 */
class WelcomeLogoZoomTest {

    @Test
    fun `no zoom before Begin is tapped`() {
        assertEquals(1.0f, welcomeLogoExitZoom(isExiting = false, reduceMotion = false), 0f)
        assertEquals(1.0f, welcomeLogoExitZoom(isExiting = false, reduceMotion = true), 0f)
    }

    @Test
    fun `Begin tap zooms to 1_4 when motion is allowed`() {
        assertEquals(1.4f, welcomeLogoExitZoom(isExiting = true, reduceMotion = false), 0f)
    }

    @Test
    fun `reduce-motion bypasses the zoom on Begin`() {
        assertEquals(1.0f, welcomeLogoExitZoom(isExiting = true, reduceMotion = true), 0f)
    }
}
