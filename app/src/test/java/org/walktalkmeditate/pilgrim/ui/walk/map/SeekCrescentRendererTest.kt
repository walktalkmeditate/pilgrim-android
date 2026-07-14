// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.walktalkmeditate.pilgrim.domain.seek.SeekFogState
import org.walktalkmeditate.pilgrim.domain.seek.SeekPoint
import org.walktalkmeditate.pilgrim.domain.seek.SeekPulseVisual
import org.walktalkmeditate.pilgrim.domain.seek.SeekSkyLight
import kotlin.math.abs

/**
 * The crescent renderer's state machine — image cache, flare generations,
 * viewport release with hysteresis, visibility throttle, reload survival —
 * against a fake style surface. The Mapbox surface itself is
 * device-verified (port spec smoke-check list, U7).
 */
class SeekCrescentRendererTest {

    private class FakeCrescentStyle : SeekCrescentStyle {
        var styleLoaded = true
        var layerPresent = false
        var sourcePresent = false
        val images = mutableSetOf<String>()
        val ops = mutableListOf<String>()
        val delayed = mutableListOf<() -> Unit>()
        var projection: ScreenPointPx? = null
        var viewport = ViewportPx(width = 400.0, height = 800.0)
        var zoom = 16.0

        override fun isStyleLoaded(): Boolean = styleLoaded
        override fun crescentLayerExists(): Boolean = layerPresent
        override fun crescentSourceExists(): Boolean = sourcePresent
        override fun hasImage(imageId: String): Boolean = imageId in images

        override fun addCrescentImage(imageId: String, spanDegrees: Double, colorArgb: Int) {
            images += imageId
            ops += "image:$imageId"
        }

        override fun installCrescentLayer(
            imageId: String,
            position: SeekPoint,
            bearingDegrees: Double,
            breathTransitionMillis: Long,
        ) {
            layerPresent = true
            sourcePresent = true
            ops += "install:$imageId:transition=$breathTransitionMillis"
        }

        override fun updateCrescentGeometry(position: SeekPoint, bearingDegrees: Double) {
            ops += "geometry:$bearingDegrees"
        }

        override fun setCrescentImage(imageId: String) {
            ops += "setImage:$imageId"
        }

        override fun setCrescentOpacity(opacity: Double) {
            ops += "opacity:$opacity"
        }

        override fun removeCrescent() {
            layerPresent = false
            sourcePresent = false
            ops += "remove"
        }

        override fun projectedFogCenter(latitude: Double, longitude: Double): ScreenPointPx? =
            projection

        override fun viewportSize(): ViewportPx = viewport
        override fun cameraZoom(): Double = zoom

        override fun postDelayed(delayMillis: Long, action: () -> Unit) {
            delayed += action
        }

        fun runDelayed() {
            val pending = delayed.toList()
            delayed.clear()
            pending.forEach { it() }
        }

        fun opacityWrites(): List<Double> =
            ops.filter { it.startsWith("opacity:") }.map { it.removePrefix("opacity:").toDouble() }
    }

    private class Harness {
        val style = FakeCrescentStyle()
        var daypart = SeekSkyLight.Daypart.GOLDEN
        var starlight = false
        var now = 0L
        val renderer = SeekCrescentRenderer(
            style = style,
            daypart = { daypart },
            starlight = { starlight },
            uptimeMillis = { now },
        )
    }

    private val walker = SeekPoint(latitude = 0.0, longitude = 0.0)
    private fun crescent(bearing: Double = 45.0, position: SeekPoint = walker) =
        SeekFogState.Crescent(position = position, bearingDegrees = bearing)

    private fun fogState(withCrescent: Boolean = true) = SeekFogState(
        circles = listOf(
            SeekFogState.FogCircle(
                id = "seek-fog-0",
                center = walker,
                radiusMeters = 50.0,
                opacityBucket = 5,
                isHalo = false,
            ),
        ),
        crescent = if (withCrescent) crescent() else null,
    )

    private fun pulse(token: Int, aligned: Boolean = false, closeness: Double = 0.5) =
        SeekPulseVisual(token = token, aligned = aligned, closeness = closeness)

    // Sync — install, update, image cache

    @Test
    fun `sync installs at rest with a span-and-light image`() {
        val h = Harness()
        h.renderer.sync(crescent(), spanDegrees = 48.0, reduceMotion = false)
        assertEquals(
            listOf(
                // Defensive same-id sweep before a from-scratch install
                // (U6 spec D8 precedent — duplicate addSource is a hard
                // error on Android).
                "remove",
                "image:seek-crescent-48-dawn-golden",
                "install:seek-crescent-48-dawn-golden:transition=1000",
                "opacity:0.55",
            ),
            h.style.ops,
        )
    }

    @Test
    fun `sync with unchanged crescent and image writes nothing`() {
        val h = Harness()
        h.renderer.sync(crescent(), 48.0, reduceMotion = false)
        h.style.ops.clear()
        h.renderer.sync(crescent(), 48.0, reduceMotion = false)
        assertTrue(h.style.ops.isEmpty())
    }

    @Test
    fun `walker movement updates geometry without reinstalling`() {
        val h = Harness()
        h.renderer.sync(crescent(bearing = 45.0), 48.0, reduceMotion = false)
        h.style.ops.clear()
        h.renderer.sync(
            crescent(bearing = 50.0, position = SeekPoint(0.001, 0.0)),
            48.0,
            reduceMotion = false,
        )
        assertEquals(listOf("geometry:50.0"), h.style.ops)
    }

    @Test
    fun `span bucket change swaps the image in place`() {
        val h = Harness()
        h.renderer.sync(crescent(), 48.0, reduceMotion = false)
        h.style.ops.clear()
        h.renderer.sync(crescent(bearing = 46.0), 60.0, reduceMotion = false)
        assertEquals(
            listOf(
                "geometry:46.0",
                "image:seek-crescent-60-dawn-golden",
                "setImage:seek-crescent-60-dawn-golden",
            ),
            h.style.ops,
        )
    }

    @Test
    fun `hour change swaps the image even when the crescent is unchanged`() {
        val h = Harness()
        h.renderer.sync(crescent(), 48.0, reduceMotion = false)
        h.style.ops.clear()
        h.daypart = SeekSkyLight.Daypart.NIGHT
        h.renderer.sync(crescent(), 48.0, reduceMotion = false)
        assertTrue("setImage:seek-crescent-48-dawn-night" in h.style.ops)
    }

    @Test
    fun `image cache is reused per span and light`() {
        val h = Harness()
        h.renderer.sync(crescent(), 48.0, reduceMotion = false)
        h.renderer.sync(crescent(bearing = 46.0), 60.0, reduceMotion = false)
        h.style.ops.clear()
        h.renderer.sync(crescent(bearing = 47.0), 48.0, reduceMotion = false)
        assertTrue(
            "cached image must not re-render",
            h.style.ops.none { it.startsWith("image:") },
        )
        assertTrue("setImage:seek-crescent-48-dawn-golden" in h.style.ops)
    }

    @Test
    fun `null crescent removes the layer`() {
        val h = Harness()
        h.renderer.sync(crescent(), 48.0, reduceMotion = false)
        h.style.ops.clear()
        h.renderer.sync(null, 48.0, reduceMotion = false)
        assertEquals(listOf("remove"), h.style.ops)
    }

    @Test
    fun `reduce motion installs with a zero transition at steady opacity`() {
        val h = Harness()
        h.renderer.sync(crescent(), 48.0, reduceMotion = true)
        assertTrue("install:seek-crescent-48-dawn-golden:transition=0" in h.style.ops)
        assertEquals(listOf(0.8), h.style.opacityWrites())
    }

    // Flare

    @Test
    fun `flare swells with closeness and settles after the hold`() {
        val h = Harness()
        h.renderer.sync(crescent(), 48.0, reduceMotion = false)
        h.style.ops.clear()
        h.renderer.flare(pulse(1, closeness = 0.5), reduceMotion = false)
        assertEquals(listOf(0.825), h.style.opacityWrites())
        h.style.runDelayed()
        assertEquals(listOf(0.825, 0.55), h.style.opacityWrites())
    }

    @Test
    fun `aligned flare peaks at full brightness`() {
        val h = Harness()
        h.renderer.sync(crescent(), 48.0, reduceMotion = false)
        h.style.ops.clear()
        h.renderer.flare(pulse(1, aligned = true), reduceMotion = false)
        assertEquals(listOf(1.0), h.style.opacityWrites())
    }

    @Test
    fun `superseded settle is a no-op`() {
        val h = Harness()
        h.renderer.sync(crescent(), 48.0, reduceMotion = false)
        h.style.ops.clear()
        h.renderer.flare(pulse(1, closeness = 0.0), reduceMotion = false)
        h.renderer.flare(pulse(2, closeness = 1.0), reduceMotion = false)
        h.style.runDelayed()
        assertEquals(
            "two swells, exactly one settle (the superseded one is a no-op)",
            listOf(0.75, 0.9, 0.55),
            h.style.opacityWrites(),
        )
    }

    @Test
    fun `flare is suppressed under reduce motion`() {
        val h = Harness()
        h.renderer.sync(crescent(), 48.0, reduceMotion = true)
        h.style.ops.clear()
        h.renderer.flare(pulse(1), reduceMotion = true)
        assertTrue(h.style.ops.isEmpty())
    }

    @Test
    fun `flare is suppressed without a layer`() {
        val h = Harness()
        h.renderer.flare(pulse(1), reduceMotion = false)
        assertTrue(h.style.ops.isEmpty())
    }

    @Test
    fun `settle after removal writes nothing`() {
        val h = Harness()
        h.renderer.sync(crescent(), 48.0, reduceMotion = false)
        h.renderer.flare(pulse(1), reduceMotion = false)
        h.style.ops.clear()
        h.renderer.remove()
        h.style.runDelayed()
        assertEquals(listOf("remove"), h.style.ops)
    }

    // Viewport release

    private fun Harness.guidingSyncOnScreenFog() {
        renderer.sync(crescent(), 48.0, reduceMotion = false)
        style.projection = ScreenPointPx(200.0, 400.0)
        style.ops.clear()
    }

    @Test
    fun `fog entering the viewport fires one handoff exhale`() {
        val h = Harness()
        h.guidingSyncOnScreenFog()
        h.renderer.evaluateVisibility(fogState(), throttled = false, reduceMotion = false)
        assertEquals("one full flare", listOf(1.0), h.style.opacityWrites())
        h.style.runDelayed()
        assertEquals("then the dissolve", listOf(1.0, 0.0), h.style.opacityWrites())

        h.renderer.evaluateVisibility(fogState(), throttled = false, reduceMotion = false)
        assertEquals("still released — no replay", listOf(1.0, 0.0), h.style.opacityWrites())
    }

    @Test
    fun `flares stay quiet while released`() {
        val h = Harness()
        h.guidingSyncOnScreenFog()
        h.renderer.evaluateVisibility(fogState(), throttled = false, reduceMotion = false)
        h.style.runDelayed()
        h.style.ops.clear()
        h.renderer.flare(pulse(9), reduceMotion = false)
        assertTrue("a released crescent never flares", h.style.ops.isEmpty())
    }

    @Test
    fun `off-screen projection never releases`() {
        val h = Harness()
        h.renderer.sync(crescent(), 48.0, reduceMotion = false)
        h.style.projection = null
        h.style.ops.clear()
        h.renderer.evaluateVisibility(fogState(), throttled = false, reduceMotion = false)
        assertTrue(h.style.ops.isEmpty())
    }

    @Test
    fun `fog leaving past the outset hands the crescent back at rest`() {
        val h = Harness()
        h.guidingSyncOnScreenFog()
        h.renderer.evaluateVisibility(fogState(), throttled = false, reduceMotion = false)
        h.style.runDelayed()
        h.style.ops.clear()

        // Dead zone first: released state holds, no writes.
        h.style.projection = ScreenPointPx(430.0, 400.0)
        h.renderer.evaluateVisibility(fogState(), throttled = false, reduceMotion = false)
        assertTrue("dead zone must not flap", h.style.ops.isEmpty())

        // Beyond the outset: the crescent returns at rest.
        h.style.projection = null
        h.renderer.evaluateVisibility(fogState(), throttled = false, reduceMotion = false)
        assertEquals(listOf(0.55), h.style.opacityWrites())
    }

    @Test
    fun `throttled checks are rate limited to the visibility window`() {
        val h = Harness()
        h.renderer.sync(crescent(), 48.0, reduceMotion = false)
        h.style.projection = ScreenPointPx(3000.0, 400.0)
        h.style.ops.clear()

        h.now = 1_000L
        h.renderer.evaluateVisibility(fogState(), throttled = true, reduceMotion = false)
        h.style.projection = ScreenPointPx(200.0, 400.0)
        h.now = 1_100L
        h.renderer.evaluateVisibility(fogState(), throttled = true, reduceMotion = false)
        assertTrue("within the 120 ms window: skipped", h.style.ops.isEmpty())

        h.now = 1_130L
        h.renderer.evaluateVisibility(fogState(), throttled = true, reduceMotion = false)
        assertEquals("past the window: the release lands", listOf(1.0), h.style.opacityWrites())
    }

    @Test
    fun `wander and crescent-less states exit before any projection`() {
        val h = Harness()
        h.renderer.evaluateVisibility(null, throttled = false, reduceMotion = false)
        h.renderer.evaluateVisibility(fogState(withCrescent = false), throttled = false, reduceMotion = false)
        assertTrue(h.style.ops.isEmpty())
    }

    @Test
    fun `reduce motion release goes straight to zero`() {
        val h = Harness()
        h.renderer.sync(crescent(), 48.0, reduceMotion = true)
        h.style.projection = ScreenPointPx(200.0, 400.0)
        h.style.ops.clear()
        h.renderer.evaluateVisibility(fogState(), throttled = false, reduceMotion = true)
        assertEquals(listOf(0.0), h.style.opacityWrites())
        assertTrue("no scheduled dissolve", h.style.delayed.isEmpty())
    }

    // Style reload — released survives (spec D8)

    @Test
    fun `style reload keeps released and reinstalls at zero without replaying the exhale`() {
        val h = Harness()
        h.guidingSyncOnScreenFog()
        h.renderer.evaluateVisibility(fogState(), throttled = false, reduceMotion = false)
        h.style.runDelayed()

        // Theme flip: the new style has no crescent layer or images.
        h.style.layerPresent = false
        h.style.sourcePresent = false
        h.style.images.clear()
        h.renderer.onStyleReloaded()
        h.style.ops.clear()

        h.renderer.sync(crescent(), 48.0, reduceMotion = false)
        assertEquals(
            "released crescent reinstalls invisible",
            listOf(0.0),
            h.style.opacityWrites(),
        )
        h.style.ops.clear()
        h.renderer.evaluateVisibility(fogState(), throttled = false, reduceMotion = false)
        assertTrue("fog still on screen: no exhale replay", h.style.ops.isEmpty())
    }

    @Test
    fun `remove resets released so the next seek starts shown`() {
        val h = Harness()
        h.guidingSyncOnScreenFog()
        h.renderer.evaluateVisibility(fogState(), throttled = false, reduceMotion = false)
        h.style.runDelayed()
        h.renderer.remove()
        h.style.ops.clear()

        h.renderer.sync(crescent(), 48.0, reduceMotion = false)
        assertEquals("fresh install rests visible", listOf(0.55), h.style.opacityWrites())
    }

    // Pre-rendered arc geometry (pure)

    @Test
    fun `crescent segments stack three passes of twenty-four`() {
        assertEquals(72, crescentSegments(96.0).size)
    }

    @Test
    fun `width and alpha peak at the apex and taper at the tips`() {
        val brightPass = crescentSegments(96.0).takeLast(24)
        val apex = brightPass[11]
        val tip = brightPass[0]
        assertTrue("apex wider than tip", apex.widthDp > tip.widthDp)
        assertTrue("apex brighter than tip", apex.alpha > tip.alpha)
        assertEquals("symmetric taper", brightPass[0].alpha, brightPass[23].alpha, 1e-9)
        assertEquals("symmetric width", brightPass[0].widthDp, brightPass[23].widthDp, 1e-9)
        assertTrue("alpha never exceeds one", crescentSegments(96.0).all { it.alpha <= 1.0 })
    }

    @Test
    fun `segments span the arc centered on north`() {
        val span = 72.0
        val segments = crescentSegments(span).takeLast(24)
        assertEquals(-90.0 - span / 2, segments.first().startAngleDegrees, 1e-9)
        val lastEnd = segments.last().startAngleDegrees + segments.last().sweepAngleDegrees
        // Last segment overshoots by the seam cover only.
        assertTrue(abs(lastEnd - (-90.0 + span / 2)) < 0.5)
    }

    @Test
    fun `passes stack wide-faint under narrow-bright`() {
        val segments = crescentSegments(96.0)
        val wideApex = segments[11]
        val brightApex = segments[48 + 11]
        assertTrue("under-pass is wider", wideApex.widthDp > brightApex.widthDp)
        assertTrue("under-pass is fainter", wideApex.alpha < brightApex.alpha)
    }

    @Test
    fun `image id carries the rounded span and the light token`() {
        assertEquals(
            "seek-crescent-72-star-night",
            SeekCrescentRendering.imageId(72.4, SeekSkyLight.token(SeekSkyLight.Daypart.NIGHT, starlight = true)),
        )
    }
}
