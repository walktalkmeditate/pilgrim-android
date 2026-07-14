// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.walktalkmeditate.pilgrim.domain.seek.SeekFogModel
import org.walktalkmeditate.pilgrim.domain.seek.SeekFogState
import org.walktalkmeditate.pilgrim.domain.seek.SeekPoint
import org.walktalkmeditate.pilgrim.domain.seek.SeekPulseVisual

/**
 * The renderer's state machine — equality gate, deferred queue, self-heal
 * probe, pulse-token swallow — against a fake style surface. The Mapbox
 * surface itself is device-verified (port spec smoke-check list).
 */
class SeekFogRendererTest {

    private class FakeSeekFogStyle : SeekFogStyle {
        var styleLoaded = true
        val layers = mutableMapOf<String, Installed>()
        val ops = mutableListOf<String>()
        var probeCount = 0
        var ringsFired = 0
        var lastRingColor: Int? = null
        var ringPresent = false

        data class Installed(
            val circle: SeekFogState.FogCircle,
            val tintHex: String?,
            val transitionMillis: Long,
            var opacity: Double,
        )

        override fun isStyleLoaded(): Boolean = styleLoaded

        override fun fogLayerExists(layerId: String): Boolean {
            probeCount++
            return layerId in layers
        }

        override fun installFogCircle(
            circle: SeekFogState.FogCircle,
            tintHex: String?,
            transitionMillis: Long,
        ) {
            layers[circle.id] = Installed(
                circle,
                tintHex,
                transitionMillis,
                SeekFogModel.opacity(circle.opacityBucket, circle.isHalo),
            )
            ops += "install:${circle.id}"
        }

        override fun setFogOpacity(layerId: String, opacity: Double) {
            layers[layerId]?.opacity = opacity
            ops += "opacity:$layerId=$opacity"
        }

        override fun removeFogCircle(layerId: String) {
            layers.remove(layerId)
            ops += "remove:$layerId"
        }

        override fun firePulseRing(lightColorArgb: Int) {
            ringsFired++
            lastRingColor = lightColorArgb
            ringPresent = true
            ops += "ring"
        }

        override fun removePulseRing() {
            ringPresent = false
            ops += "removeRing"
        }
    }

    private fun circle(
        index: Int,
        bucket: Int,
        isHalo: Boolean = false,
        lat: Double = 42.0 + index * 0.01,
    ) = SeekFogState.FogCircle(
        id = SeekFogModel.fogCircleId(index),
        center = SeekPoint(latitude = lat, longitude = -8.5),
        radiusMeters = 50.0,
        opacityBucket = bucket,
        isHalo = isHalo,
    )

    private fun pulse(token: Int) = SeekPulseVisual(token = token, aligned = false, closeness = 0.5)

    private val gold = 0xFFC4956A.toInt()

    private fun renderer(style: FakeSeekFogStyle) = SeekFogRenderer(style)

    private fun SeekFogRenderer.applyState(
        state: SeekFogState?,
        pulse: SeekPulseVisual = SeekPulseVisual.NONE,
        reduceMotion: Boolean = false,
    ) = apply(state, pulse, reduceMotion, gold)

    // Wander fast path

    @Test
    fun `null state on a wander walk never touches the style`() {
        val style = FakeSeekFogStyle()
        val renderer = renderer(style)
        renderer.applyState(null)
        renderer.applyState(null)
        assertTrue(style.ops.isEmpty())
        assertEquals(0, style.probeCount)
    }

    // Equality gate + self-heal

    @Test
    fun `identical consecutive states produce no style writes`() {
        val style = FakeSeekFogStyle()
        val renderer = renderer(style)
        val state = SeekFogState(circles = listOf(circle(0, bucket = 5)))
        renderer.applyState(state)
        style.ops.clear()

        renderer.applyState(state.copy())

        assertTrue("unchanged state must not write", style.ops.isEmpty())
        assertEquals("one self-heal probe per unchanged pass", 1, style.probeCount)
    }

    @Test
    fun `self-heal reinstalls when layers were stripped with no style event`() {
        val style = FakeSeekFogStyle()
        val renderer = renderer(style)
        val state = SeekFogState(
            circles = listOf(circle(0, bucket = 0, isHalo = true), circle(1, bucket = 3)),
        )
        renderer.applyState(state)
        style.layers.clear()
        style.ops.clear()

        renderer.applyState(state.copy())

        assertEquals(
            "both circles reinstall after a lock/unlock strip",
            setOf("seek-fog-0", "seek-fog-1"),
            style.layers.keys,
        )
        assertTrue(style.ops.count { it.startsWith("install:") } == 2)
    }

    // Diff behavior

    @Test
    fun `bucket change on unchanged geometry writes a single opacity update`() {
        val style = FakeSeekFogStyle()
        val renderer = renderer(style)
        renderer.applyState(SeekFogState(circles = listOf(circle(0, bucket = 5))))
        style.ops.clear()

        renderer.applyState(SeekFogState(circles = listOf(circle(0, bucket = 4))))

        assertEquals(listOf("opacity:seek-fog-0=0.55"), style.ops)
    }

    @Test
    fun `reveal dissolves to zero then persists as a halo`() {
        val style = FakeSeekFogStyle()
        val renderer = renderer(style)
        renderer.applyState(SeekFogState(circles = listOf(circle(0, bucket = 1))))
        style.ops.clear()

        // Arrival: same geometry, bucket 0 — the dissolve IS the moment.
        renderer.applyState(SeekFogState(circles = listOf(circle(0, bucket = 0))))
        assertEquals(listOf("opacity:seek-fog-0=0.0"), style.ops)
        style.ops.clear()

        // Reveal committed: same clearing returns as a halo (role change →
        // recreate so the entrance fade-in plays), next clearing veils.
        val haloState = SeekFogState(
            circles = listOf(circle(0, bucket = 0, isHalo = true), circle(1, bucket = 5)),
        )
        renderer.applyState(haloState)
        assertEquals(
            listOf("remove:seek-fog-0", "install:seek-fog-0", "install:seek-fog-1"),
            style.ops,
        )
        assertEquals(SeekFogModel.HALO_OPACITY, style.layers.getValue("seek-fog-0").opacity, 0.0)
        style.ops.clear()

        // The halo persists untouched while the active fog thins.
        renderer.applyState(
            SeekFogState(
                circles = listOf(circle(0, bucket = 0, isHalo = true), circle(1, bucket = 4)),
            ),
        )
        assertEquals(listOf("opacity:seek-fog-1=0.55"), style.ops)
        assertTrue("seek-fog-0" in style.layers)
    }

    @Test
    fun `geometry change recreates the circle`() {
        val style = FakeSeekFogStyle()
        val renderer = renderer(style)
        renderer.applyState(SeekFogState(circles = listOf(circle(0, bucket = 5, lat = 42.0))))
        style.ops.clear()

        // Reroll moved the active clearing: same id, new center.
        renderer.applyState(SeekFogState(circles = listOf(circle(0, bucket = 5, lat = 43.0))))

        assertEquals(listOf("remove:seek-fog-0", "install:seek-fog-0"), style.ops)
    }

    @Test
    fun `circles absent from the new state are removed`() {
        val style = FakeSeekFogStyle()
        val renderer = renderer(style)
        renderer.applyState(
            SeekFogState(circles = listOf(circle(0, bucket = 0, isHalo = true), circle(1, bucket = 3))),
        )
        style.ops.clear()

        renderer.applyState(SeekFogState(circles = listOf(circle(0, bucket = 0, isHalo = true))))

        assertEquals(listOf("remove:seek-fog-1"), style.ops)
    }

    @Test
    fun `null state after fog removes every circle and the ring`() {
        val style = FakeSeekFogStyle()
        val renderer = renderer(style)
        renderer.applyState(
            SeekFogState(circles = listOf(circle(0, bucket = 0, isHalo = true), circle(1, bucket = 3))),
        )
        style.ops.clear()

        renderer.applyState(null)

        assertTrue(style.layers.isEmpty())
        assertTrue("removeRing" in style.ops)
        style.ops.clear()
        renderer.applyState(null)
        assertTrue("null == null fast path after teardown", style.ops.isEmpty())
    }

    // Deferred queue

    @Test
    fun `state emitted while style not ready is applied once on flush and pulses are swallowed`() {
        val style = FakeSeekFogStyle()
        val renderer = renderer(style)
        style.styleLoaded = false
        val state = SeekFogState(circles = listOf(circle(0, bucket = 5)))

        renderer.applyState(state, pulse(3))
        assertTrue("no writes while the style is not ready", style.ops.isEmpty())

        // Flush before the style is ready keeps the flag (silent-drop guard).
        renderer.flushDeferred(reduceMotion = false)
        assertTrue(style.ops.isEmpty())

        style.styleLoaded = true
        renderer.flushDeferred(reduceMotion = false)
        assertEquals(listOf("install:seek-fog-0"), style.ops)
        style.ops.clear()

        // The flag cleared: a second flush is a no-op.
        renderer.flushDeferred(reduceMotion = false)
        assertTrue(style.ops.isEmpty())

        // The pulse token seen while paused was swallowed — moments, not state.
        renderer.applyState(state, pulse(3))
        assertEquals(0, style.ringsFired)
    }

    @Test
    fun `style reload reinstalls from the pending state`() {
        val style = FakeSeekFogStyle()
        val renderer = renderer(style)
        val state = SeekFogState(
            circles = listOf(circle(0, bucket = 0, isHalo = true), circle(1, bucket = 2)),
        )
        renderer.applyState(state)
        // Theme flip: the new style has no seek layers.
        style.layers.clear()
        style.ops.clear()

        renderer.onStyleReloaded(reduceMotion = false)

        assertEquals(setOf("seek-fog-0", "seek-fog-1"), style.layers.keys)
    }

    // Pulse ring

    @Test
    fun `pulse fires exactly once per token advance with a non-null state`() {
        val style = FakeSeekFogStyle()
        val renderer = renderer(style)
        val state = SeekFogState(circles = listOf(circle(0, bucket = 5)))

        renderer.applyState(state, pulse(1))
        assertEquals(1, style.ringsFired)
        assertEquals(gold, style.lastRingColor)

        renderer.applyState(state, pulse(1))
        assertEquals("repeat token must not re-fire", 1, style.ringsFired)

        renderer.applyState(state, pulse(2))
        assertEquals(2, style.ringsFired)
    }

    @Test
    fun `pulse is suppressed entirely under reduce motion`() {
        val style = FakeSeekFogStyle()
        val renderer = renderer(style)
        val state = SeekFogState(circles = listOf(circle(0, bucket = 5)))

        renderer.applyState(state, pulse(1), reduceMotion = true)

        assertEquals(0, style.ringsFired)
        // The token is consumed — motion returning must not replay it.
        renderer.applyState(state, pulse(1), reduceMotion = false)
        assertEquals(0, style.ringsFired)
    }

    @Test
    fun `pulse with a null state does not fire but consumes the token`() {
        val style = FakeSeekFogStyle()
        val renderer = renderer(style)

        renderer.applyState(null, pulse(1))
        assertEquals(0, style.ringsFired)

        renderer.applyState(SeekFogState(circles = listOf(circle(0, bucket = 5))), pulse(1))
        assertEquals("token 1 was already handled", 0, style.ringsFired)
    }

    // Reduce motion + tint plumbing

    @Test
    fun `reduce motion zeroes the fog transitions`() {
        val style = FakeSeekFogStyle()
        renderer(style).applyState(
            SeekFogState(circles = listOf(circle(0, bucket = 5))),
            reduceMotion = true,
        )
        assertEquals(0L, style.layers.getValue("seek-fog-0").transitionMillis)

        val animated = FakeSeekFogStyle()
        renderer(animated).applyState(SeekFogState(circles = listOf(circle(0, bucket = 5))))
        assertEquals(
            SeekFogRendering.FOG_TRANSITION_MILLIS,
            animated.layers.getValue("seek-fog-0").transitionMillis,
        )
    }

    @Test
    fun `celestial tint override reaches the install`() {
        val style = FakeSeekFogStyle()
        renderer(style).applyState(
            SeekFogState(circles = listOf(circle(0, bucket = 5)), tintHex = "#2377A4"),
        )
        assertEquals("#2377A4", style.layers.getValue("seek-fog-0").tintHex)
    }

    // Pure geometry + naming helpers

    @Test
    fun `radius pixels at zoom zero matches the equator scale`() {
        val onePixelWorth = fogRadiusPixelsAtZoomZero(
            radiusMeters = SeekFogRendering.METERS_PER_PIXEL_EQUATOR_Z0,
            latitudeDegrees = 0.0,
        )
        assertEquals(1.0, onePixelWorth, 1e-9)
    }

    @Test
    fun `radius pixels scale with the latitude cosine`() {
        val equator = fogRadiusPixelsAtZoomZero(radiusMeters = 50.0, latitudeDegrees = 0.0)
        val sixty = fogRadiusPixelsAtZoomZero(radiusMeters = 50.0, latitudeDegrees = 60.0)
        assertEquals("cos(60°) halves the scale, doubling the pixel radius", equator * 2.0, sixty, 1e-9)
        val south = fogRadiusPixelsAtZoomZero(radiusMeters = 50.0, latitudeDegrees = -60.0)
        assertEquals("hemisphere-symmetric", sixty, south, 1e-12)
    }

    @Test
    fun `hex parsing and id naming`() {
        assertEquals(0xFF8A8175.toInt(), hexToColorArgb("#8A8175"))
        assertEquals(0xFFC4956A.toInt(), hexToColorArgb(SeekFogRendering.HALO_COLOR_HEX))
        assertEquals("seek-fog-2-source", fogSourceId("seek-fog-2"))
        assertNotNull(SeekFogRendering.DEFAULT_LIGHT_COLOR_ARGB)
        assertEquals(0xFFC4956A.toInt(), SeekFogRendering.DEFAULT_LIGHT_COLOR_ARGB)
    }
}
