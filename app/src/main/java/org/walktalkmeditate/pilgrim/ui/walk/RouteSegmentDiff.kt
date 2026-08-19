// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import org.walktalkmeditate.pilgrim.data.walk.RouteSegment

/**
 * What [PilgrimMap] must do to its polyline annotations to move from one
 * rendered segment list to the next.
 *
 * @param keepCount how many leading annotations survive, mutated ones
 *        included. Annotations at `keepCount` and beyond are deleted;
 *        segments at `keepCount` and beyond are created.
 * @param mutateIndex the one annotation whose geometry grew — always
 *        `keepCount - 1` when set. Its polyline (and casing mirror) takes
 *        the new point list in place instead of being recreated.
 */
data class RouteSegmentDiff(
    val keepCount: Int,
    val mutateIndex: Int?,
)

/**
 * Plan the annotation work between [prev] and [next].
 *
 * The live route grows by one fix roughly every two seconds for up to 90
 * minutes. Treating "the segment list changed" as "delete and recreate
 * every polyline" — which is all the summary map ever needed, since its
 * list is fixed — would churn thousands of Mapbox annotations plus their
 * casing mirrors. iOS avoids the problem by holding the whole route in one
 * GeoJSON source and rewriting only the tail feature
 * (`PilgrimMapView+RouteSource.swift:53-80@2ee1185`, the `.incremental`
 * plan with its `addedChunks` + `tailAction`); Android's annotation API
 * has no equivalent, so the tail mutation is planned here.
 *
 * Two properties of the segmenter make this safe:
 *  - a closed segment never changes again, so structural equality finds
 *    the untouched prefix;
 *  - an activity transition duplicates the boundary fix into both
 *    segments, so the outgoing tail grows by exactly that point (a
 *    mutation) and the incoming segment is the only creation.
 *
 * Steady state is therefore one geometry update per fix and one creation
 * per activity transition. Anything the planner cannot prove is a pure
 * tail growth — a shrunken segment, a rewritten interior point, a tail
 * that changed activity — falls back to rebuilding from the first
 * divergence, because mutating there would leave a polyline tracing a
 * route its segment no longer claims.
 */
fun diffRouteSegments(prev: List<RouteSegment>, next: List<RouteSegment>): RouteSegmentDiff {
    val shared = minOf(prev.size, next.size)
    var stable = 0
    while (stable < shared && prev[stable] == next[stable]) stable++

    val growsInPlace = stable < shared && extendsInPlace(prev[stable], next[stable])
    return RouteSegmentDiff(
        keepCount = if (growsInPlace) stable + 1 else stable,
        mutateIndex = if (growsInPlace) stable else null,
    )
}

private fun extendsInPlace(prev: RouteSegment, next: RouteSegment): Boolean =
    prev.activity == next.activity &&
        next.points.size > prev.points.size &&
        next.points.subList(0, prev.points.size) == prev.points
