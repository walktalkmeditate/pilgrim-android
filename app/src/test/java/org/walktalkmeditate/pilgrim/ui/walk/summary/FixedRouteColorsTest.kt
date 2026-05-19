// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * iOS parity `PilgrimMapView.swift:321-329@v1.6.0` — the walk/talk/
 * meditate route + annotation colors are FIXED `UIColor` assets,
 * constant across light/dark/constellation. Regression guard: if these
 * drift back to theme-resolved tokens the route color bug returns.
 */
class FixedRouteColorsTest {

    @Test
    fun route_segment_colors_are_the_frozen_base_palette() {
        assertEquals(Color(0xFF7A8B6F), RouteSegmentColors.Fixed.walking)
        assertEquals(Color(0xFFA0634B), RouteSegmentColors.Fixed.talking)
        assertEquals(Color(0xFFC4956A), RouteSegmentColors.Fixed.meditating)
    }

    @Test
    fun annotation_colors_are_the_frozen_base_palette() {
        assertEquals(Color(0xFF8B7355), WalkAnnotationColors.Fixed.startEnd)
        assertEquals(Color(0xFFC4956A), WalkAnnotationColors.Fixed.meditation)
        assertEquals(Color(0xFFA0634B), WalkAnnotationColors.Fixed.voice)
        assertEquals(Color(0xFF7A8B6F), WalkAnnotationColors.Fixed.photo)
    }
}
