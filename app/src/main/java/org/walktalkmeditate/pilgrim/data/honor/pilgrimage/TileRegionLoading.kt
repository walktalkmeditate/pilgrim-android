// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.honor.pilgrimage

import java.util.Objects
import org.walktalkmeditate.pilgrim.domain.honor.WayCoordinate

/**
 * What a region load asks for, with no Mapbox type in it (iOS
 * `TileRegionRequest`, `TileRegionLoading.swift:7-20@7c200bf`, spec D
 * §C2.1): the tiles manager builds one, the production loader turns it
 * into its load options, the fake records it.
 *
 * Equality is iOS's hand-written one: [id], [corridorHash] and
 * [acceptExpired], never [rings], which [corridorHash] already stands for.
 */
class TileRegionRequest(
    val id: String,
    /** Closed WGS84 rings, one per convex part of the corridor, each with its first coordinate repeated last. */
    val rings: List<List<WayCoordinate>>,
    /** The rings' hash, so a resumed save can tell a redrawn stage from an unchanged one. */
    val corridorHash: String,
    val acceptExpired: Boolean,
) {
    override fun equals(other: Any?): Boolean = this === other ||
        other is TileRegionRequest &&
        id == other.id &&
        corridorHash == other.corridorHash &&
        acceptExpired == other.acceptExpired

    override fun hashCode(): Int = Objects.hash(id, corridorHash, acceptExpired)

    override fun toString(): String =
        "TileRegionRequest(id=$id, parts=${rings.size}, corridorHash=$corridorHash, acceptExpired=$acceptExpired)"
}

/** iOS `StylePackRequest`: the map's two styles, loaded light first; `entries.size` is the save's pack count. */
enum class StylePackRequest { LIGHT, DARK }

/**
 * Our own view of a stored region (iOS `TileRegionSummary`,
 * `TileRegionLoading.swift:28-37@7c200bf`). Counts and sizes are `Long`, as
 * Mapbox reports them and as Swift's 64-bit `Int` holds them: a byte sum
 * over a full store passes 2 GiB. [metadata] is the JSON object the load
 * was given.
 */
data class TileRegionSummary(
    val id: String,
    val completedResourceCount: Long,
    val requiredResourceCount: Long,
    val completedResourceSize: Long,
    val metadata: Map<String, String>,
) {
    /** Zero required is never complete, and an over-count still is. */
    val isComplete: Boolean get() = requiredResourceCount > 0 && completedResourceCount >= requiredResourceCount

    /** Null when the metadata lacks the key; the production loader writes `""` when the store couldn't say. */
    val corridorHash: String? get() = metadata[CORRIDOR_HASH_KEY]

    companion object {
        const val CORRIDOR_HASH_KEY = "corridorHash"
    }
}

/** iOS `TileRegionLoadingError`: delivered in a [TileLoadResult], never thrown. */
enum class TileRegionLoadingError {
    FAILED,
    DISK_FULL,
    CANCELLED,

    /** The store's 750-unique-pack ceiling: the SDK refuses before downloading anything, so a retry refuses the same way. */
    TILE_COUNT_EXCEEDED,
}

/** A load's answer: Kotlin's `Result` can't carry a typed failure. */
sealed interface TileLoadResult<out T> {
    data class Success<out T>(val value: T) : TileLoadResult<T>

    data class Failure(val error: TileRegionLoadingError) : TileLoadResult<Nothing>
}

/** A load the tiles manager can cancel: the production loader wraps Mapbox's `Cancelable`, the fake flips a flag. */
fun interface TileLoadHandle {
    fun cancel()
}

/**
 * Which of the store's two answers arrived (iOS `TileStoreChange`). They
 * are separate round trips: the style packs from one call, the regions
 * from a list plus a metadata read each, and on a phone with saved maps
 * the packs answer lands first. A reader waiting for what is on disk must
 * not be woken by the packs.
 */
enum class TileStoreChange { REGIONS, PACKS }

/**
 * The store's first answer after the process started (spec D C3 §7, an
 * Android addition): [READ] when the first current regions answer and the
 * first current packs answer both succeeded and were written to the cache,
 * [FAILED] when either failed, so the cache is no snapshot.
 */
enum class TileStoreRead { READ, FAILED }

/**
 * The one seam between the tiles manager and Mapbox (iOS
 * `TileRegionLoading`, `TileRegionLoading.swift:64-88@7c200bf`, spec D
 * §C2.1). What every implementation promises:
 *
 * 1. Every method is synchronous to call and never blocks, and every
 *    callback ([onChange], the completions, `progress`) runs on the
 *    manager's thread, the main thread.
 * 2. [regions] is a cache read. It returns what the loader last learned,
 *    `[]` before the store's first answer, as a fresh list.
 * 3. [refreshRegions] calls back once, on the store's next current
 *    answer, success or failure, after the cache is written, and never
 *    before it returns. [onChange] with [TileStoreChange.REGIONS] fires
 *    on a difference only, so an empty store answering an empty cache
 *    signals nothing.
 * 4. [removeRegion] is fire-and-forget: it drops the id from the cache at
 *    once, signals [TileStoreChange.REGIONS], and never fails to the caller.
 * 5. [hasStylePack] is a cache read of complete packs only.
 */
interface TileRegionLoading {

    /** How a synchronous reader learns an answer arrived and is worth asking again, and which one it was. */
    var onChange: ((TileStoreChange) -> Unit)?

    fun hasStylePack(pack: StylePackRequest): Boolean

    fun loadStylePack(pack: StylePackRequest, completion: (TileLoadResult<Unit>) -> Unit): TileLoadHandle

    fun loadRegion(
        request: TileRegionRequest,
        progress: (completed: Long, required: Long) -> Unit,
        completion: (TileLoadResult<TileRegionSummary>) -> Unit,
    ): TileLoadHandle

    fun regions(): List<TileRegionSummary>

    /**
     * Asks the store for its regions and calls back when that answer has
     * landed, even when it changed nothing, so a launch-time reader acts
     * on a real snapshot rather than on an empty cache.
     */
    fun refreshRegions(completion: () -> Unit)

    fun removeRegion(id: String)

    /**
     * Calls back once with the store's first answer after the process
     * started, starting the store's first read if none has. A stale answer
     * doesn't count: while the first answer is pending, the loader reads
     * again rather than drain on it. Once known, it answers at once, on
     * every later call (spec D C3 §7).
     */
    fun firstAnswer(completion: (TileStoreRead) -> Unit)
}
