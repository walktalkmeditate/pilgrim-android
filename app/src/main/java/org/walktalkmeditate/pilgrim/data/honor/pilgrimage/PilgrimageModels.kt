// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.honor.pilgrimage

import androidx.annotation.StringRes
import kotlinx.serialization.Serializable
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.domain.honor.WayStageHours

/**
 * iOS `PilgrimageError` (`PilgrimageWayImporter.swift:5-13@7c200bf`): what
 * can go wrong on the way to a walkable route, each with its own line on
 * screen (P1 §1). [MAP_TOO_LARGE] is the tile store's (Stage 21-3); nothing
 * in Stage 21-2 raises it.
 */
enum class PilgrimageError { NOT_WALKABLE, INCOMPLETE, DISK_FULL, WALK_IN_PROGRESS, CATALOG_UNREACHABLE, MAP_TOO_LARGE }

/** A [PilgrimageError], thrown. It carries no message: nothing from a package may reach a log. */
class PilgrimageException(val error: PilgrimageError) : Exception()

/**
 * iOS `PilgrimageCopy` (`PilgrimageWayImporter.swift:15-26@7c200bf`): the
 * one line each error speaks, on every screen that shows one. Disk full
 * borrows the share importer's line, voices and all, as iOS ships it
 * (pilgrim-ios #122 item 5, matched); iOS's `?? "not enough space on this phone"`
 * fallback can never run, so it has no string here.
 */
object PilgrimageCopy {
    @StringRes
    fun line(error: PilgrimageError): Int = when (error) {
        PilgrimageError.NOT_WALKABLE -> R.string.pilgrimage_error_not_walkable
        PilgrimageError.INCOMPLETE -> R.string.pilgrimage_error_incomplete
        PilgrimageError.DISK_FULL -> R.string.honor_import_disk_full
        PilgrimageError.WALK_IN_PROGRESS -> R.string.pilgrimage_error_walk_in_progress
        PilgrimageError.CATALOG_UNREACHABLE -> R.string.pilgrimage_error_catalog_unreachable
        PilgrimageError.MAP_TOO_LARGE -> R.string.pilgrimage_error_map_too_large
    }
}

/** iOS `PilgrimageRouteStage` (`PilgrimageWayImporter.swift:28-35@7c200bf`): one row of `route.json`, checked and cut. */
data class PilgrimageRouteStage(
    val index: Int,
    val name: String,
    val distanceKm: Double,
    val gainMeters: Double,
    val hours: WayStageHours,
    val difficulty: String,
)

/**
 * iOS `PilgrimageRoute` (`PilgrimageWayImporter.swift:37-48@7c200bf`): a
 * package's `route.json`, checked and cut, its [stages] in index order.
 * [names] is empty, never null, when no local name survives (P1 §6).
 */
data class PilgrimageRoute(
    val id: String,
    val name: String,
    val names: Map<String, String>,
    val country: String?,
    val region: String?,
    val distanceKm: Double,
    val stageCount: Int,
    val tradition: String?,
    val summary: String?,
    val stages: List<PilgrimageRouteStage>,
)

/*
 * The wire files, as iOS's private `StageFile` and `RouteFile` declare them
 * (`PilgrimageWayImporter.swift:77-164@7c200bf`, P1 §4.2). A required field
 * has no default, so a missing key or a JSON `null` fails the whole decode,
 * as Swift's synthesized `Decodable` does; unknown keys (`schemaVersion`,
 * `stampHours`, `cover`) are ignored. Swift's `Int` is 64-bit, so every
 * integer is a [Long], narrowed only once its bound has passed (P1 §4.3, A7).
 * Moment and mark kinds are plain strings, so a kind the dataset adds later
 * decodes and is then skipped.
 */

@Serializable
internal data class StageFile(
    val id: String,
    val title: String,
    val departedAt: String,
    val tzIdentifier: String? = null,
    val route: List<Point>,
    val totalDistanceMeters: Double,
    val theirActiveSeconds: Double,
    val moments: List<Moment>,
    val marks: List<Mark>,
    val stage: Stage,
) {

    @Serializable
    data class Coordinate(
        val lat: Double,
        val lon: Double,
    )

    @Serializable
    data class Point(
        val lat: Double,
        val lon: Double,
        val alt: Double? = null,
        val t: Double,
    )

    @Serializable
    data class Moment(
        val id: String,
        val frac: Double,
        val kind: String,
        val label: String? = null,
        val icon: String? = null,
        val text: String? = null,
        val names: Map<String, String>? = null,
        val sitMinutes: Long? = null,
        val at: Coordinate? = null,
        val pin: Coordinate? = null,
    )

    @Serializable
    data class Mark(
        val id: String,
        val kind: String,
        val name: String,
        val at: Coordinate,
        val frac: Double,
        val offLineMeters: Double,
    )

    @Serializable
    data class Hours(
        val min: Double,
        val max: Double,
    )

    @Serializable
    data class Place(
        val name: String,
        val at: Coordinate,
    )

    @Serializable
    data class Stage(
        val routeId: String,
        val index: Long,
        val count: Long,
        val name: String,
        val theme: String,
        val narrative: String,
        val closing: String,
        val warnings: List<String>,
        val distanceKm: Double,
        val gainMeters: Double,
        val hours: Hours,
        val difficulty: String,
        val start: Place,
        val end: Place,
    )
}

@Serializable
internal data class RouteFile(
    val id: String,
    val name: String,
    val names: Map<String, String>? = null,
    val country: String? = null,
    val region: String? = null,
    val distanceKm: Double,
    val stageCount: Long,
    val tradition: String? = null,
    val summary: String? = null,
    val stages: List<Stage>,
) {

    @Serializable
    data class Stage(
        val index: Long,
        val name: String,
        val distanceKm: Double,
        val gainMeters: Double,
        val hours: StageFile.Hours,
        val difficulty: String,
    )
}
