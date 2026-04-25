// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteDownsamplerTest {

    private fun pt(lat: Double, lon: Double, alt: Double = 0.0, ts: Long = 0L) =
        SharePayload.RoutePoint(lat, lon, alt, ts)

    @Test
    fun `downsample returns input unchanged when already under cap`() {
        val input = (0 until 50).map { pt(it * 0.001, 0.0) }
        val out = RouteDownsampler.downsample(input, maxPoints = 200)
        assertEquals(input, out)
    }

    @Test
    fun `downsample reduces a 1000-point straight line to near-2 points`() {
        // A perfectly straight line collapses maximally under RDP.
        val input = (0 until 1000).map { pt(it * 0.0001, 0.0) }
        val out = RouteDownsampler.downsample(input, maxPoints = 200)
        assertTrue("expected <= 10 points on a straight line, got ${out.size}", out.size <= 10)
    }

    @Test
    fun `downsample keeps a noisy 1000-point route under cap`() {
        val input = (0 until 1000).map { i ->
            pt(i * 0.0001 + (if (i % 2 == 0) 1e-5 else -1e-5), 0.0)
        }
        val out = RouteDownsampler.downsample(input, maxPoints = 200)
        assertTrue("expected <= 200 points, got ${out.size}", out.size <= 200)
    }

    @Test
    fun `downsample preserves first and last points`() {
        val input = (0 until 1000).map { pt(it * 0.0001, it * 0.0001) }
        val out = RouteDownsampler.downsample(input, maxPoints = 200)
        assertEquals(input.first(), out.first())
        assertEquals(input.last(), out.last())
    }

    @Test
    fun `downsample handles exactly 2 points`() {
        val input = listOf(pt(0.0, 0.0), pt(1.0, 1.0))
        val out = RouteDownsampler.downsample(input, maxPoints = 200)
        assertEquals(input, out)
    }

    @Test
    fun `downsample handles a degenerate zero-distance segment`() {
        // All points at same lat/lon — perpendicular distance is always
        // 0, so RDP collapses to first+last. No divide-by-zero.
        val input = (0 until 500).map { pt(45.0, -70.0) }
        val out = RouteDownsampler.downsample(input, maxPoints = 200)
        assertEquals(2, out.size)
    }
}
