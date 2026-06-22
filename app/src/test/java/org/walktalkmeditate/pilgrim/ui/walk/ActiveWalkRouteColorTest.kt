// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.core.celestial.SeasonalMarker
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimLightColors
import org.walktalkmeditate.pilgrim.ui.theme.turningAccentColor
import org.walktalkmeditate.pilgrim.ui.walk.summary.RouteSegmentColors

/**
 * iOS parity `ActiveWalkView.swift:597@v1.6.0` —
 * `walkingColor: activeTurning?.uiColor ?? .moss`. On a solstice/equinox
 * the live route's walking segments take the turning's cardinal accent;
 * every other day they stay the fixed walking moss. The accent must be
 * the SAME color the celestial-vignette halo wears so the route and the
 * chip corona read as one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ActiveWalkRouteColorTest {

    private val colors = pilgrimLightColors()

    @Test fun `spring equinox routes in turning jade`() {
        assertEquals(colors.turningJade, activeWalkRouteColor(SeasonalMarker.SpringEquinox, colors))
    }

    @Test fun `summer solstice routes in turning gold`() {
        assertEquals(colors.turningGold, activeWalkRouteColor(SeasonalMarker.SummerSolstice, colors))
    }

    @Test fun `autumn equinox routes in turning claret`() {
        assertEquals(colors.turningClaret, activeWalkRouteColor(SeasonalMarker.AutumnEquinox, colors))
    }

    @Test fun `winter solstice routes in turning indigo`() {
        assertEquals(colors.turningIndigo, activeWalkRouteColor(SeasonalMarker.WinterSolstice, colors))
    }

    @Test fun `non-turning day falls back to fixed walking moss`() {
        assertEquals(RouteSegmentColors.Fixed.walking, activeWalkRouteColor(null, colors))
    }

    @Test fun `cross-quarter day falls back to fixed walking moss`() {
        // iOS assigns colors only to the four cardinals; imbolc/beltane/
        // lughnasadh/samhain have no uiColor, so they degrade to moss.
        assertEquals(RouteSegmentColors.Fixed.walking, activeWalkRouteColor(SeasonalMarker.Imbolc, colors))
    }

    @Test fun `route accent matches the vignette halo accent on a turning day`() {
        SeasonalMarker.entries.forEach { marker ->
            val haloAccent = turningAccentColor(marker, colors)
            if (haloAccent != null) {
                assertEquals(
                    "route + halo must share one color on $marker",
                    haloAccent,
                    activeWalkRouteColor(marker, colors),
                )
            }
        }
    }
}
