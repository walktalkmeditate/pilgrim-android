// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.honor.pilgrimage

import java.time.Instant
import org.walktalkmeditate.pilgrim.data.honor.WayStore
import org.walktalkmeditate.pilgrim.domain.honor.Way
import org.walktalkmeditate.pilgrim.domain.honor.WayCoordinate
import org.walktalkmeditate.pilgrim.domain.honor.WayPoint
import org.walktalkmeditate.pilgrim.domain.honor.WaySource
import org.walktalkmeditate.pilgrim.domain.honor.WayStage
import org.walktalkmeditate.pilgrim.domain.honor.WayStageHours
import org.walktalkmeditate.pilgrim.domain.honor.WayStagePlace

/**
 * iOS `PilgrimageTilesManagerTests`' stage fixture, verbatim
 * (`PilgrimageTilesManagerTests.swift:26-45@7c200bf`, spec D C1 §13.1), for
 * the corridor tests and the tiles manager's.
 */
internal object PilgrimageTilesFixtures {

    /** A 3 km straight stage east along latitude 42, 31 points 100 m apart. */
    fun stage(index: Int, count: Int = 3, routeId: String = "camino-frances", lonOffset: Double = 0.0): Way {
        val points = (0..30).map { i ->
            WayPoint(lat = 42.0, lon = lonOffset + i.toDouble() * 0.001209, alt = null, t = i.toDouble() * 60)
        }
        val stage = WayStage(
            routeId = routeId,
            index = index,
            count = count,
            name = "stage $index",
            theme = "t",
            narrative = "n",
            closing = "c",
            warnings = emptyList(),
            distanceKm = 3.0,
            gainMeters = 50.0,
            hours = WayStageHours(min = 1.0, max = 2.0),
            difficulty = "easy",
            start = WayStagePlace(name = "a", at = WayCoordinate(lat = 42.0, lon = lonOffset)),
            end = WayStagePlace(name = "b", at = WayCoordinate(lat = 42.0, lon = lonOffset + 0.03627)),
        )
        return Way(
            id = WayStore.stageWayId(routeId = routeId, stageIndex = index),
            source = WaySource.Pilgrimage(routeId = routeId, stageIndex = index),
            title = "stage $index",
            departedAt = Instant.EPOCH,
            tzIdentifier = null,
            expires = null,
            route = points,
            totalDistanceMeters = 3000.0,
            theirActiveSeconds = 1800.0,
            moments = emptyList(),
            weather = null,
            spans = null,
            marks = null,
            stage = stage,
        )
    }

    fun stages(count: Int, routeId: String = "camino-frances"): List<Way> =
        (0 until count).map { stage(it, count = count, routeId = routeId, lonOffset = it.toDouble() * 0.04) }
}
