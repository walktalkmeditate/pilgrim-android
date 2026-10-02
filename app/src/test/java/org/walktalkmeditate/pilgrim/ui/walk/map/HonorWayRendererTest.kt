// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.map

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.walktalkmeditate.pilgrim.domain.honor.VoiceKind
import org.walktalkmeditate.pilgrim.domain.honor.Way
import org.walktalkmeditate.pilgrim.domain.honor.WayCoordinate
import org.walktalkmeditate.pilgrim.domain.honor.WayMedia
import org.walktalkmeditate.pilgrim.domain.honor.WayMoment
import org.walktalkmeditate.pilgrim.domain.honor.WayMomentKind
import org.walktalkmeditate.pilgrim.domain.honor.WayPoint
import org.walktalkmeditate.pilgrim.domain.honor.WaySource
import org.walktalkmeditate.pilgrim.domain.honor.WaySpan
import org.walktalkmeditate.pilgrim.domain.honor.WaySpanKind
import org.walktalkmeditate.pilgrim.ui.walk.ROUTE_CASING_LAYER_ID
import org.walktalkmeditate.pilgrim.ui.walk.ROUTE_LINE_LAYER_ID

/**
 * The Way's ghost line, companion, and moment pins (parity spec E §2–§5):
 * as U21's overview draws the line and pins, and as the walk map adds the
 * companion, the layer order, and the reinstall after a style reload.
 */
class HonorWayRendererTest {

    private val route = (0..10).map { i -> WayPoint(lat = 0.0, lon = i * 0.001, alt = null, t = i * 60.0) }

    private fun way(
        moments: List<WayMoment> = emptyList(),
        spans: List<WaySpan>? = null,
        id: String = "walk:0e8d6f8a-5b1c-4f1e-9a53-2f1d8c7b6a50",
    ) = Way(
        id = id,
        source = WaySource.OwnWalk("0e8d6f8a-5b1c-4f1e-9a53-2f1d8c7b6a50"),
        title = "the long way",
        departedAt = Instant.ofEpochSecond(1_700_000_000),
        tzIdentifier = "UTC",
        expires = null,
        route = route,
        totalDistanceMeters = 1_111.95,
        theirActiveSeconds = 600.0,
        moments = moments,
        weather = null,
        spans = spans,
    )

    private val here = WayCoordinate(lat = 0.0, lon = 0.002)
    private val further = WayCoordinate(lat = 0.0, lon = 0.003)

    // ---- The ghost line's geometry ----------------------------------------

    @Test
    fun `a Way with no spans is one walking line over every route point`() {
        val segments = HonorWayRendering.segments(route, emptyList())

        assertEquals(1, segments.size)
        assertEquals(HonorWayRendering.WALKING, segments.single().kind)
        assertEquals(route.first().lon, segments.single().coordinates.first().lon, 1e-12)
        assertEquals(route.last().lon, segments.single().coordinates.last().lon, 1e-12)
    }

    @Test
    fun `spans cut the line into walking, talking, and meditating pieces that share their ends`() {
        val segments = HonorWayRendering.segments(
            route,
            listOf(
                WaySpan(startFrac = 0.6, endFrac = 0.8, kind = WaySpanKind.MEDITATING),
                WaySpan(startFrac = 0.2, endFrac = 0.4, kind = WaySpanKind.TALKING),
            ),
        )

        assertEquals(
            listOf("walking", "talking", "walking", "meditating", "walking"),
            segments.map { it.kind },
        )
        segments.zipWithNext().forEach { (a, b) -> assertEquals(a.coordinates.last(), b.coordinates.first()) }
    }

    @Test
    fun `a span never reaches back over one already drawn`() {
        val segments = HonorWayRendering.segments(
            route,
            listOf(
                WaySpan(startFrac = 0.2, endFrac = 0.5, kind = WaySpanKind.TALKING),
                WaySpan(startFrac = 0.3, endFrac = 0.4, kind = WaySpanKind.MEDITATING),
            ),
        )

        assertEquals(listOf("walking", "talking", "walking"), segments.map { it.kind })
    }

    @Test
    fun `features carry their activity under ids honor-way-index`() {
        val features = ghostFeatures(
            HonorWayLine.of(way(spans = listOf(WaySpan(0.2, 0.4, WaySpanKind.TALKING)))),
        ).features()!!

        assertEquals(listOf("honor-way-0", "honor-way-1", "honor-way-2"), features.map { it.id() })
        assertEquals(
            listOf("walking", "talking", "walking"),
            features.map { it.getStringProperty(HonorWayRendering.ACTIVITY_PROPERTY) },
        )
    }

    // ---- The ghost style ----------------------------------------------------

    @Test
    fun `the ghost is 0_22 opaque on the light map and 0_4 on the dark`() {
        val light = HonorWayRendering.ghostStyle(dark = false)
        val dark = HonorWayRendering.ghostStyle(dark = true)

        assertEquals(0.22 to 0.4, light.lineOpacity to dark.lineOpacity)
    }

    @Test
    fun `the companion is 8A8175 at 0_6 on the light map and D9CFBF at 0_85 on the dark`() {
        assertEquals(
            listOf(GhostStyle(0xFF8A8175.toInt(), 0.22, 0.6), GhostStyle(0xFFD9CFBF.toInt(), 0.4, 0.85)),
            listOf(HonorWayRendering.ghostStyle(dark = false), HonorWayRendering.ghostStyle(dark = true)),
        )
    }

    // ---- Install order ------------------------------------------------------

    @Test
    fun `the ghost goes below the route casing and the companion directly above the route line`() {
        val style = FakeStyle()
        style.addManagerLayers()
        val renderer = HonorWayRenderer(style) { false }

        renderer.apply(HonorWayLine.of(way()), here)

        assertEquals(
            listOf(LINE, ROUTE_CASING_LAYER_ID, ROUTE_LINE_LAYER_ID, COMPANION, CIRCLES, POINTS, WAY_PINS),
            style.stack,
        )
    }

    @Test
    fun `with no casing the ghost goes below the route line`() {
        val style = FakeStyle()
        style.stack += listOf(ROUTE_LINE_LAYER_ID, POINTS)

        HonorWayRenderer(style) { false }.apply(HonorWayLine.of(way()))

        assertEquals(listOf(LINE, ROUTE_LINE_LAYER_ID, POINTS), style.stack)
    }

    @Test
    fun `with no route yet both Honor layers go on top`() {
        val style = FakeStyle()
        style.stack += POINTS

        HonorWayRenderer(style) { false }.apply(HonorWayLine.of(way()), here)

        assertEquals(listOf(POINTS, LINE, COMPANION), style.stack)
    }

    // ---- Idempotent installs -------------------------------------------------

    @Test
    fun `the line installs once per Way, with the opacity read at install time`() {
        val style = FakeStyle()
        var dark = false
        val renderer = HonorWayRenderer(style) { dark }
        val line = HonorWayLine.of(way())

        renderer.apply(line)
        renderer.apply(line)
        dark = true
        renderer.apply(line)

        assertEquals(listOf(0.22), style.lineInstalls)
    }

    @Test
    fun `a second install of both layers is a no-op`() {
        val style = FakeStyle()
        style.addManagerLayers()
        val renderer = HonorWayRenderer(style) { false }
        val line = HonorWayLine.of(way())
        renderer.apply(line, here)
        val writes = style.writes

        renderer.apply(line, here)

        assertEquals(writes, style.writes)
    }

    @Test
    fun `a companion that moves is moved in place, not reinstalled`() {
        val style = FakeStyle()
        val renderer = HonorWayRenderer(style) { false }
        renderer.apply(null, here)

        renderer.apply(null, further)

        assertEquals(1 to listOf(further), style.companionInstalls.size to style.moves)
    }

    @Test
    fun `a map handed no Way never touches the style, and a removed Way comes off`() {
        val style = FakeStyle()
        val renderer = HonorWayRenderer(style) { false }

        renderer.apply(null)
        assertEquals(0, style.writes)

        renderer.apply(HonorWayLine.of(way()))
        renderer.apply(null)
        assertEquals(1, style.lineInstalls.size)
        assertFalse(style.ghostLineExists())
    }

    @Test
    fun `a companion handed null comes off`() {
        val style = FakeStyle()
        val renderer = HonorWayRenderer(style) { false }
        renderer.apply(null, here)

        renderer.apply(null, null)

        assertFalse(style.companionExists())
    }

    @Test
    fun `before the style loads the layers wait, and the reload installs them`() {
        val style = FakeStyle(loaded = false)
        val renderer = HonorWayRenderer(style) { true }

        renderer.apply(HonorWayLine.of(way()), here)
        assertTrue(style.lineInstalls.isEmpty() && style.companionInstalls.isEmpty())

        style.loaded = true
        renderer.onStyleReloaded()
        assertEquals(listOf(0.4) to here, style.lineInstalls to style.companionAt)
    }

    @Test
    fun `the reload installs the layers while new sources leave the style reading not loaded`() {
        // The annotation managers' GeoJSON sources, added in the same
        // callback, make isStyleLoaded read false while they settle.
        val style = FakeStyle(loaded = false)
        style.addManagerLayers()
        val renderer = HonorWayRenderer(style) { false }
        renderer.apply(HonorWayLine.of(way()), here)

        renderer.onStyleReloaded()

        assertTrue(style.ghostLineExists() && style.companionExists())
    }

    @Test
    fun `after the style's callback a pass installs while the style reads not loaded`() {
        val style = FakeStyle(loaded = false)
        val renderer = HonorWayRenderer(style) { false }
        renderer.onStyleReloaded()

        renderer.apply(HonorWayLine.of(way()))

        assertTrue(style.ghostLineExists())
    }

    @Test
    fun `a style load that starts holds the layers until its callback`() {
        val style = FakeStyle(loaded = false)
        val renderer = HonorWayRenderer(style) { false }
        renderer.onStyleReloaded()

        renderer.onStyleLoadStarted()
        renderer.apply(HonorWayLine.of(way()), here)

        assertTrue(style.lineInstalls.isEmpty() && style.companionInstalls.isEmpty())
    }

    @Test
    fun `a layer lost without a style event is reinstalled on the next pass`() {
        val style = FakeStyle()
        val renderer = HonorWayRenderer(style) { false }
        val line = HonorWayLine.of(way())
        renderer.apply(line, here)

        style.stack.clear()
        renderer.apply(line, here)

        assertEquals(2 to 2, style.lineInstalls.size to style.companionInstalls.size)
    }

    @Test
    fun `a failed install is retried by the next pass`() {
        val style = FakeStyle(failNextInstall = true)
        val renderer = HonorWayRenderer(style) { false }
        val line = HonorWayLine.of(way())

        renderer.apply(line)
        renderer.apply(line)

        assertEquals(2, style.lineInstalls.size)
        assertTrue(style.ghostLineExists())
    }

    // ---- Style reloads and theme flips ----------------------------------------

    @Test
    fun `after a style reload both layers reinstall where the companion last stood`() {
        val style = FakeStyle()
        style.addManagerLayers()
        val renderer = HonorWayRenderer(style) { false }
        renderer.apply(HonorWayLine.of(way()), here)
        renderer.apply(HonorWayLine.of(way()), further)

        style.reload()
        style.addManagerLayers()
        renderer.onStyleReloaded()

        assertEquals(
            Triple(2, 2, further),
            Triple(style.lineInstalls.size, style.companionInstalls.size, style.companionAt),
        )
    }

    @Test
    fun `a theme flip reinstalls after the managers, so the companion stays above the route, in the dark palette`() {
        val style = FakeStyle()
        style.addManagerLayers()
        var dark = false
        val renderer = HonorWayRenderer(style) { dark }
        renderer.apply(HonorWayLine.of(way()), here)

        // PilgrimMap's style-load callback: the fresh style, the managers
        // recreated (casing, route line, circles, points), then the Honor
        // layers. iOS reinstalls them first, which leaves its companion
        // under the route (owner decision 7, pilgrim-ios #111).
        dark = true
        style.reload()
        style.addManagerLayers()
        renderer.onStyleReloaded()

        assertEquals(
            listOf(LINE, ROUTE_CASING_LAYER_ID, ROUTE_LINE_LAYER_ID, COMPANION, CIRCLES, POINTS, WAY_PINS) to
                HonorWayRendering.ghostStyle(dark = true),
            style.stack to style.companionInstalls.last(),
        )
    }

    @Test
    fun `a theme flip reinstalls the line at the dark map's opacity`() {
        val style = FakeStyle()
        var dark = false
        val renderer = HonorWayRenderer(style) { dark }
        renderer.apply(HonorWayLine.of(way()))

        dark = true
        style.reload()
        renderer.onStyleReloaded()

        assertEquals(listOf(0.22, 0.4), style.lineInstalls)
    }

    // ---- Pins -----------------------------------------------------------------

    @Test
    fun `pins stand at the place itself, else its projection, else the line at its frac`() {
        val pinned = WayMoment(
            id = "waypoint-1", frac = 0.5, at = WayCoordinate(1.0, 1.0),
            kind = WayMomentKind.Waypoint("rest", "leaf"), pin = WayCoordinate(2.0, 2.0),
        )
        val projected = WayMoment(
            id = "photo-1", frac = 0.5, at = WayCoordinate(1.0, 1.0),
            kind = WayMomentKind.Photo(WayMedia.PhotoAsset("content://p")),
        )
        val onTheLine = WayMoment(id = "rest-1", frac = 0.5, at = null, kind = WayMomentKind.Rest(4))

        val pins = wayPins(way(listOf(pinned, projected, onTheLine)), heardVoiceIds = emptySet())

        assertEquals(WayCoordinate(2.0, 2.0), pins[0].at)
        assertEquals(WayCoordinate(1.0, 1.0), pins[1].at)
        assertEquals(0.005, pins[2].at.lon, 1e-9)
    }

    @Test
    fun `a voice pin is fog until heard, a sitting dawn, and an icon Android can't draw is mappin`() {
        val voice = WayMoment(
            id = "voice-1", frac = 0.1, at = null,
            kind = WayMomentKind.Voice(
                endFrac = 0.2, duration = 30.0, kind = VoiceKind.AMBIENT, media = WayMedia.Recording("r.wav"),
            ),
        )
        val sit = WayMoment(id = "sit-1", frac = 0.3, at = null, kind = WayMomentKind.Meditation(5, isEstimate = false))
        val odd = WayMoment(
            id = "waypoint-1", frac = 0.4, at = null, kind = WayMomentKind.Waypoint("", "sun.haze.fill"),
        )
        val w = way(listOf(voice, sit, odd))

        val unheard = wayPins(w, heardVoiceIds = emptySet())
        val heard = wayPins(w, heardVoiceIds = setOf("voice-1"))

        assertEquals(WayGlyph.Waveform to WayPinTint.FOG, unheard[0].glyph to unheard[0].tint)
        assertEquals(WayPinTint.STONE, heard[0].tint)
        assertEquals(WayGlyph.Sitting to WayPinTint.DAWN, unheard[1].glyph to unheard[1].tint)
        assertEquals(WayGlyph.Waypoint("mappin"), unheard[2].glyph)
    }

    // ---- The map tap ------------------------------------------------------------

    /** 0.0001° of longitude at the equator is 11.13 m. */
    private fun east(meters: Double) = WayCoordinate(lat = 0.0, lon = meters / 111_319.49)

    @Test
    fun `a tap finds the nearest Way pin within 25 m on the ground`() {
        val targets = listOf(MapTapTarget("voice-1", east(20.0)), MapTapTarget("rest-1", east(8.0)))

        assertEquals("rest-1", nearestTapTarget(WayCoordinate(0.0, 0.0), targets)?.wayMomentId)
    }

    @Test
    fun `a pin 25 m away or more is out of reach`() {
        assertNull(nearestTapTarget(WayCoordinate(0.0, 0.0), listOf(MapTapTarget("voice-1", east(25.5)))))
    }

    @Test
    fun `a nearer whisper or cairn takes the tap from a Way pin`() {
        val targets = listOf(MapTapTarget(null, east(5.0)), MapTapTarget("voice-1", east(10.0)))

        assertNull(nearestTapTarget(WayCoordinate(0.0, 0.0), targets)?.wayMomentId)
    }

    @Test
    fun `two pins equally near go to the one listed first`() {
        val targets = listOf(MapTapTarget("voice-1", east(10.0)), MapTapTarget("voice-2", east(10.0)))

        assertEquals("voice-1", nearestTapTarget(WayCoordinate(0.0, 0.0), targets)?.wayMomentId)
    }

    /**
     * A style as a layer stack, bottom first. Installs place their layer by
     * the renderer's own slots, as [MapboxHonorWayStyle] does, so the stack
     * is the order a real map would draw in.
     */
    private class FakeStyle(
        var loaded: Boolean = true,
        private var failNextInstall: Boolean = false,
    ) : HonorWayStyle {
        val stack = mutableListOf<String>()
        val lineInstalls = mutableListOf<Double>()
        val companionInstalls = mutableListOf<GhostStyle>()
        val moves = mutableListOf<WayCoordinate>()
        var companionAt: WayCoordinate? = null

        /** Every style write, removals included. */
        var writes = 0

        /** What PilgrimMap's style-load callback creates first, in its order. */
        fun addManagerLayers() {
            stack += listOf(ROUTE_CASING_LAYER_ID, ROUTE_LINE_LAYER_ID, CIRCLES, POINTS, WAY_PINS)
        }

        /** A style load: every layer gone. */
        fun reload() {
            stack.clear()
            companionAt = null
        }

        override fun isStyleLoaded(): Boolean = loaded
        override fun ghostLineExists(): Boolean = LINE in stack

        override fun installGhostLine(line: HonorWayLine, opacity: Double): Boolean {
            writes++
            lineInstalls += opacity
            if (failNextInstall) {
                failNextInstall = false
                return false
            }
            insert(LINE, HonorWayRendering.ghostLineSlot(stack::contains))
            return true
        }

        override fun removeGhostLine() {
            writes++
            stack.remove(LINE)
        }

        override fun companionExists(): Boolean = COMPANION in stack

        override fun installCompanion(at: WayCoordinate, style: GhostStyle): Boolean {
            writes++
            companionInstalls += style
            companionAt = at
            insert(COMPANION, HonorWayRendering.companionSlot(stack::contains))
            return true
        }

        override fun moveCompanion(to: WayCoordinate) {
            writes++
            moves += to
            companionAt = to
        }

        override fun removeCompanion() {
            writes++
            stack.remove(COMPANION)
            companionAt = null
        }

        private fun insert(layerId: String, slot: LayerSlot) {
            when (slot) {
                is LayerSlot.Below -> stack.add(stack.indexOf(slot.layerId), layerId)
                is LayerSlot.Above -> stack.add(stack.indexOf(slot.layerId) + 1, layerId)
                LayerSlot.Top -> stack.add(layerId)
            }
        }
    }

    private companion object {
        const val LINE = HonorWayRendering.LINE_LAYER_ID
        const val COMPANION = HonorWayRendering.COMPANION_LAYER_ID
        const val CIRCLES = "circle-manager"
        const val POINTS = "point-manager"
        const val WAY_PINS = "way-pin-manager"
    }
}
