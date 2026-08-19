// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import org.junit.Assert.assertEquals
import org.junit.Test
import org.walktalkmeditate.pilgrim.data.walk.RouteActivity
import org.walktalkmeditate.pilgrim.data.walk.RouteSegment
import org.walktalkmeditate.pilgrim.domain.LocationPoint

/**
 * The annotation plan behind the live route line. A 90-minute walk emits
 * roughly 2700 fixes; rebuilding every polyline (and its casing mirror)
 * per fix is thousands of Mapbox annotation deletes and creates. The plan
 * has to keep settled polylines untouched and grow only the tail.
 */
class RouteSegmentDiffTest {

    private fun seg(activity: RouteActivity, vararg timestamps: Long) = RouteSegment(
        activity = activity,
        points = timestamps.map { LocationPoint(timestamp = it, latitude = 0.0, longitude = 0.0) },
    )

    @Test
    fun `first render creates everything`() {
        val next = listOf(seg(RouteActivity.Walking, 0, 1), seg(RouteActivity.Talking, 1, 2))

        assertEquals(
            RouteSegmentDiff(keepCount = 0, mutateIndex = null),
            diffRouteSegments(prev = emptyList(), next = next),
        )
    }

    @Test
    fun `an unchanged list touches nothing`() {
        val segments = listOf(seg(RouteActivity.Walking, 0, 1), seg(RouteActivity.Talking, 1, 2))

        assertEquals(
            RouteSegmentDiff(keepCount = 2, mutateIndex = null),
            diffRouteSegments(prev = segments, next = segments),
        )
    }

    // Steady state: one fix arrives, the tail grows by a point, and no
    // annotation is created or destroyed.
    @Test
    fun `a growing tail mutates in place`() {
        val prev = listOf(seg(RouteActivity.Walking, 0, 1), seg(RouteActivity.Talking, 1, 2))
        val next = listOf(seg(RouteActivity.Walking, 0, 1), seg(RouteActivity.Talking, 1, 2, 3))

        assertEquals(
            RouteSegmentDiff(keepCount = 2, mutateIndex = 1),
            diffRouteSegments(prev = prev, next = next),
        )
    }

    @Test
    fun `a single-segment walk grows without ever recreating its polyline`() {
        val prev = listOf(seg(RouteActivity.Walking, 0, 1, 2))
        val next = listOf(seg(RouteActivity.Walking, 0, 1, 2, 3))

        assertEquals(
            RouteSegmentDiff(keepCount = 1, mutateIndex = 0),
            diffRouteSegments(prev = prev, next = next),
        )
    }

    // An activity transition duplicates the boundary fix into both
    // segments, so the old tail grows by that one point and exactly one
    // new polyline is created.
    @Test
    fun `an activity transition mutates the old tail and creates one segment`() {
        val prev = listOf(seg(RouteActivity.Walking, 0, 1, 2))
        val next = listOf(seg(RouteActivity.Walking, 0, 1, 2, 3), seg(RouteActivity.Talking, 3))

        assertEquals(
            RouteSegmentDiff(keepCount = 1, mutateIndex = 0),
            diffRouteSegments(prev = prev, next = next),
        )
    }

    @Test
    fun `a tail that changed activity is rebuilt rather than mutated`() {
        val prev = listOf(seg(RouteActivity.Walking, 0, 1), seg(RouteActivity.Talking, 1, 2))
        val next = listOf(seg(RouteActivity.Walking, 0, 1), seg(RouteActivity.Meditating, 1, 2, 3))

        assertEquals(
            RouteSegmentDiff(keepCount = 1, mutateIndex = null),
            diffRouteSegments(prev = prev, next = next),
        )
    }

    @Test
    fun `a rewritten prefix rebuilds from the first divergence`() {
        val prev = listOf(seg(RouteActivity.Walking, 0, 1), seg(RouteActivity.Talking, 1, 2))
        val next = listOf(seg(RouteActivity.Meditating, 0, 1), seg(RouteActivity.Talking, 1, 2))

        assertEquals(
            RouteSegmentDiff(keepCount = 0, mutateIndex = null),
            diffRouteSegments(prev = prev, next = next),
        )
    }

    @Test
    fun `a shorter list keeps its stable prefix and drops the rest`() {
        val prev = listOf(seg(RouteActivity.Walking, 0, 1), seg(RouteActivity.Talking, 1, 2))
        val next = listOf(seg(RouteActivity.Walking, 0, 1))

        assertEquals(
            RouteSegmentDiff(keepCount = 1, mutateIndex = null),
            diffRouteSegments(prev = prev, next = next),
        )
    }

    @Test
    fun `an emptied list drops everything`() {
        val prev = listOf(seg(RouteActivity.Walking, 0, 1))

        assertEquals(
            RouteSegmentDiff(keepCount = 0, mutateIndex = null),
            diffRouteSegments(prev = prev, next = emptyList()),
        )
    }

    // Geometry that shrinks or diverges mid-list is not a tail growth —
    // mutating in place would leave the polyline tracing a route the
    // segment no longer claims.
    @Test
    fun `a shrinking tail is rebuilt rather than mutated`() {
        val prev = listOf(seg(RouteActivity.Walking, 0, 1, 2, 3))
        val next = listOf(seg(RouteActivity.Walking, 0, 1))

        assertEquals(
            RouteSegmentDiff(keepCount = 0, mutateIndex = null),
            diffRouteSegments(prev = prev, next = next),
        )
    }

    @Test
    fun `a tail whose earlier points changed is rebuilt rather than mutated`() {
        val prev = listOf(seg(RouteActivity.Walking, 0, 1, 2))
        val next = listOf(seg(RouteActivity.Walking, 0, 9, 2, 3))

        assertEquals(
            RouteSegmentDiff(keepCount = 0, mutateIndex = null),
            diffRouteSegments(prev = prev, next = next),
        )
    }

    // A whole walk's worth of fixes must never cost more than one create
    // per activity transition.
    @Test
    fun `a long single-activity walk never creates a second polyline`() {
        var prev = emptyList<RouteSegment>()
        var creates = 0
        var mutations = 0

        for (fixes in 2..600) {
            val next = listOf(
                seg(RouteActivity.Walking, *LongArray(fixes) { it * 1_000L }),
            )
            val diff = diffRouteSegments(prev, next)
            creates += next.size - diff.keepCount
            if (diff.mutateIndex != null) mutations++
            prev = next
        }

        assertEquals(1, creates)
        assertEquals(598, mutations)
    }

    /**
     * Replays a full walk through the exact bookkeeping [PilgrimMap]'s
     * update lambda performs, against stand-in annotation handles. What
     * this pins that the plan alone cannot: the casing mirror stays
     * one-for-one with the route line through mutations, deletions and
     * creations, and the drawn geometry never lags the segment list.
     */
    private class AnnotationLedger {
        val lines = mutableListOf<List<Long>>()
        val casings = mutableListOf<List<Long>>()
        var creates = 0
        var deletes = 0
        var mutations = 0

        fun apply(prev: List<RouteSegment>, next: List<RouteSegment>) {
            val plan = diffRouteSegments(prev, next)
            plan.mutateIndex?.let { index ->
                val grown = next[index].points.map { it.timestamp }
                lines[index] = grown
                casings[index] = grown
                mutations++
            }
            while (lines.size > plan.keepCount) {
                lines.removeAt(lines.size - 1)
                casings.removeAt(casings.size - 1)
                deletes++
            }
            for (i in plan.keepCount until next.size) {
                lines += next[i].points.map { it.timestamp }
                casings += next[i].points.map { it.timestamp }
                creates++
            }
        }
    }

    @Test
    fun `replaying a walk keeps every casing mirrored and every polyline current`() {
        // Walk, talk from fix 10 to 20, walk, meditate from 30 on.
        fun segmentsAt(fixes: Int): List<RouteSegment> {
            if (fixes < 2) return emptyList()
            val activityAt = { i: Int ->
                when {
                    i in 10..20 -> RouteActivity.Talking
                    i >= 30 -> RouteActivity.Meditating
                    else -> RouteActivity.Walking
                }
            }
            val out = mutableListOf<RouteSegment>()
            var current = activityAt(0)
            var indices = mutableListOf(0)
            for (i in 1 until fixes) {
                val activity = activityAt(i)
                indices.add(i)
                if (activity != current) {
                    out += seg(current, *indices.map { it * 1_000L }.toLongArray())
                    current = activity
                    indices = mutableListOf(i)
                }
            }
            out += seg(current, *indices.map { it * 1_000L }.toLongArray())
            return out
        }

        val ledger = AnnotationLedger()
        var prev = emptyList<RouteSegment>()
        for (fixes in 2..40) {
            val next = segmentsAt(fixes)
            ledger.apply(prev, next)

            assertEquals(
                "casing mirror desynced at $fixes fixes",
                ledger.lines,
                ledger.casings,
            )
            assertEquals(
                "drawn geometry lags the segment list at $fixes fixes",
                next.map { s -> s.points.map { it.timestamp } },
                ledger.lines,
            )
            prev = next
        }

        // Four segments across the walk, each created exactly once, and
        // nothing ever torn down. Every pass after the first moves exactly
        // one polyline's geometry — 39 passes, 38 mutations — which is the
        // whole point: 39 fixes cost 4 creates instead of 4 + 39 x 4.
        assertEquals(4, ledger.creates)
        assertEquals(0, ledger.deletes)
        assertEquals(38, ledger.mutations)
    }
}
