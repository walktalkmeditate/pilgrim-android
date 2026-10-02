// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.map

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

/** The Way's ghost line and moment pins (parity spec E §2, §4), as U21's overview draws them. */
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
    fun `the ghost is 0_22 opaque on the light map and 0_4 on the dark`() {
        assertEquals(0.22, HonorWayRendering.lineOpacity(dark = false), 0.0)
        assertEquals(0.4, HonorWayRendering.lineOpacity(dark = true), 0.0)
    }

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

        assertEquals(listOf(0.22), style.installs)
    }

    @Test
    fun `a map handed no Way never touches the style, and a removed Way comes off`() {
        val style = FakeStyle()
        val renderer = HonorWayRenderer(style) { false }

        renderer.apply(null)
        assertEquals(0, style.removals)

        renderer.apply(HonorWayLine.of(way()))
        renderer.apply(null)
        assertEquals(1, style.installs.size)
        assertFalse(style.installed)
    }

    @Test
    fun `before the style loads the line waits, and the reload installs it`() {
        val style = FakeStyle(loaded = false)
        val renderer = HonorWayRenderer(style) { true }

        renderer.apply(HonorWayLine.of(way()))
        assertTrue(style.installs.isEmpty())

        style.loaded = true
        renderer.onStyleReloaded()
        assertEquals(listOf(0.4), style.installs)
    }

    @Test
    fun `a layer lost without a style event is reinstalled on the next pass`() {
        val style = FakeStyle()
        val renderer = HonorWayRenderer(style) { false }
        val line = HonorWayLine.of(way())
        renderer.apply(line)

        style.installed = false
        renderer.apply(line)

        assertEquals(2, style.installs.size)
    }

    @Test
    fun `a failed install is retried by the next pass`() {
        val style = FakeStyle(failNextInstall = true)
        val renderer = HonorWayRenderer(style) { false }
        val line = HonorWayLine.of(way())

        renderer.apply(line)
        renderer.apply(line)

        assertEquals(2, style.installs.size)
        assertTrue(style.installed)
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

    private class FakeStyle(
        var loaded: Boolean = true,
        private var failNextInstall: Boolean = false,
    ) : HonorWayStyle {
        val installs = mutableListOf<Double>()
        var installed = false
        var removals = 0

        override fun isStyleLoaded(): Boolean = loaded
        override fun ghostLineExists(): Boolean = installed

        override fun installGhostLine(line: HonorWayLine, opacity: Double): Boolean {
            installs += opacity
            if (failNextInstall) {
                failNextInstall = false
                return false
            }
            installed = true
            return true
        }

        override fun removeGhostLine() {
            removals++
            installed = false
        }
    }
}
