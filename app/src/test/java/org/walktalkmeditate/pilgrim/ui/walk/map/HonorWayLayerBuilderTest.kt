// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.map

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.domain.honor.WayCoordinate
import org.walktalkmeditate.pilgrim.ui.walk.summary.RouteSegmentColors

/**
 * CLAUDE.md platform-object-builder rule: the ghost line and the companion
 * ride Mapbox's runtime-validated style DSL. These build the production
 * layers and source so a construction-time rejection surfaces in CI; what a
 * live style accepts stays for the device pass.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HonorWayLayerBuilderTest {

    @Test
    fun `the ghost line layer builds on its source`() {
        val layer = ghostLineLayer(opacity = 0.22, colors = RouteSegmentColors.Fixed)

        assertEquals(
            HonorWayRendering.LINE_LAYER_ID to HonorWayRendering.SOURCE_ID,
            layer.layerId to layer.sourceId,
        )
    }

    @Test
    fun `the companion layer builds on its source`() {
        val layer = companionLayer(HonorWayRendering.ghostStyle(dark = true))

        assertEquals(
            HonorWayRendering.COMPANION_LAYER_ID to HonorWayRendering.COMPANION_SOURCE_ID,
            layer.layerId to layer.sourceId,
        )
    }

    @Test
    fun `the companion source builds at its point`() {
        val source = companionSource(WayCoordinate(lat = 42.8782, lon = -8.5448))

        assertEquals(HonorWayRendering.COMPANION_SOURCE_ID, source.sourceId)
    }
}
