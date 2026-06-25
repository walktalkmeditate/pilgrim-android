// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

/**
 * Incremental list mapping for the live walk route (PR #45 AF9 / AF46).
 *
 * The active-walk route grows by one GPS sample per fix, and both the
 * view-model's `routePoints` (Room samples → domain points) and the map's
 * polyline (domain points → Mapbox points) previously re-ran the full list
 * through their transform on every fix — O(n) work per fix, O(n²) over a
 * 45–90 minute walk.
 *
 * [incrementalMap] reuses [prevMapped] and runs [transform] over ONLY the new
 * tail when [newSource] extends [prevSource]; otherwise it falls back to a full
 * remap. The unavoidable O(n) cost that remains is the list copy (immutable
 * output) / the map upload — this removes the redundant re-creation of the
 * prefix's mapped objects, not the asymptotics.
 *
 * **Soundness.** The fast path is taken only when the size grew (or held) AND
 * [sameElement] holds at BOTH the head (index 0) and the boundary (index
 * `prevSize - 1`). This is an O(1) proxy for "the shared prefix is unchanged",
 * and it is sound for a caller whose source is produced by a STABLE TOTAL
 * ORDER: a stable sort preserves the relative order of existing elements, so
 * any inserted, removed, or re-ordered element within the prefix shifts the
 * element at the boundary index (or the head), failing the check and forcing a
 * full remap. The route callers satisfy this — Room returns rows under a stable
 * `(timestamp, id)` order ([RouteDataSampleDao.observeForWalk]) and samples are
 * insert-only — so even an out-of-order GPS fix (whose wall-clock timestamp
 * lands mid-list) degrades to a correct full remap rather than a bad splice.
 *
 * What it does NOT detect: an interior-only permutation that leaves both the
 * head and the boundary fixed (e.g. swapping two middle elements). A stable
 * total order cannot produce one, so this is a precondition on the caller, not
 * a guarantee of the function. Do not reuse this for a source that can reorder
 * its interior without moving its endpoints.
 */
internal fun <S, T> incrementalMap(
    prevSource: List<S>,
    prevMapped: List<T>,
    newSource: List<S>,
    sameElement: (a: S, b: S) -> Boolean,
    transform: (S) -> T,
): List<T> {
    val prevSize = prevSource.size
    val isAppend = prevSize > 0 &&
        prevMapped.size == prevSize &&
        newSource.size >= prevSize &&
        sameElement(prevSource[0], newSource[0]) &&
        sameElement(prevSource[prevSize - 1], newSource[prevSize - 1])

    if (!isAppend) return newSource.map(transform)
    if (newSource.size == prevSize) return prevMapped
    return prevMapped + newSource.subList(prevSize, newSource.size).map(transform)
}
