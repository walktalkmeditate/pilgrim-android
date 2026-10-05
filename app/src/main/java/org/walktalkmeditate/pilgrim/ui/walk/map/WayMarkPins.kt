// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.map

import android.graphics.Bitmap
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bed
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import org.walktalkmeditate.pilgrim.domain.honor.HonorDistance
import org.walktalkmeditate.pilgrim.domain.honor.HonorTuning
import org.walktalkmeditate.pilgrim.domain.honor.WGS84_HONOR_DISTANCE
import org.walktalkmeditate.pilgrim.domain.honor.WayCoordinate
import org.walktalkmeditate.pilgrim.domain.honor.WayMark
import org.walktalkmeditate.pilgrim.domain.honor.WayMarkKind
import org.walktalkmeditate.pilgrim.ui.walk.MapCameraSeed

/*
 * A pilgrimage stage's service marks on a map: iOS `WayMarkPins`
 * (`WayMarkPins.swift@7c200bf`), the walk screen's selection
 * (`ActiveWalkViewModel+MarkPins.swift@7c200bf`), and their 18 pt rasters
 * (`PilgrimMapView+HonorWay.swift:311-334@7c200bf`; pilgrimage-stage spec
 * P5 §1, §2, §5). A mark is drawn, never a moment: nothing opens from it.
 */

/** One mark a screen draws, at the mark's own place. It names no moment, so no tap or card reaches it. */
@Immutable
data class WayMarkPin(
    val markId: String,
    val kind: WayMarkKind,
    val at: WayCoordinate,
)

object WayMarkPins {

    /** At or above this zoom (13.0 draws, 12.9 doesn't). */
    const val DRAW_FROM_ZOOM = 13.0

    /** Inside a town a stage can carry over a hundred at zoom 13. */
    const val MAX_PER_SCREEN = 40

    /** The SF symbol each kind wears on iOS; [markGlyphVector] is its stand-in here. */
    fun symbol(kind: WayMarkKind): String = when (kind) {
        WayMarkKind.WATER -> "drop.fill"
        WayMarkKind.FOOD -> "fork.knife"
        WayMarkKind.BED -> "bed.double.fill"
        WayMarkKind.TRANSPORT -> "bus.fill"
        WayMarkKind.SUPPLY -> "bag.fill"
        WayMarkKind.MEDICAL -> "cross.case.fill"
    }

    /**
     * Nothing below [DRAW_FROM_ZOOM] (a NaN zoom included, as Swift's `>=`
     * reads it). With somewhere to stand, the [MAX_PER_SCREEN] nearest to
     * [near], measured as `CLLocation.distance` measures it, a tie going to
     * the lower id; without, the first ones in the Way's own order. Every
     * kind, at any distance from the line.
     */
    fun pins(marks: List<WayMark>, zoom: Double, near: WayCoordinate?): List<WayMarkPin> {
        if (!(zoom >= DRAW_FROM_ZOOM) || marks.isEmpty()) return emptyList()
        val chosen = if (near != null) {
            marks
                .map { mark -> mark to WGS84_HONOR_DISTANCE(near, mark.at) }
                .sortedWith(compareBy<Pair<WayMark, Double>> { it.second }.thenBy { it.first.id })
                .take(MAX_PER_SCREEN)
                .map { it.first }
        } else {
            marks.take(MAX_PER_SCREEN)
        }
        return chosen.map { WayMarkPin(markId = it.id, kind = it.kind, at = it.at) }
    }
}

/** The six SF symbols' Material stand-ins, filled as the `.fill` names are (P5 §5.4). */
fun markGlyphVector(kind: WayMarkKind): ImageVector = when (kind) {
    WayMarkKind.WATER -> Icons.Filled.WaterDrop
    WayMarkKind.FOOD -> Icons.Filled.Restaurant
    WayMarkKind.BED -> Icons.Filled.Bed
    WayMarkKind.TRANSPORT -> Icons.Filled.DirectionsBus
    WayMarkKind.SUPPLY -> Icons.Filled.ShoppingBag
    WayMarkKind.MEDICAL -> Icons.Filled.MedicalServices
}

/**
 * What the walk screen selects around (iOS `markPinAnchor`, `mapCameraZoom`,
 * `mapCameraCenter`, P5 §2): the walker's anchor, which moves only once a
 * fix lands 200 m from it, and the camera as the map last reported it, its
 * zoom seeded at the follow-puck's 16. The marks follow the walker, not the
 * camera: a camera report re-selects only when its zoom crosses a whole
 * level, and a pan never does. Pure; the view model holds one per screen.
 * [metersBetween] is the ruler, swapped only by a test that needs exactly
 * 200 m.
 */
internal class WalkMarkSelection(
    private val metersBetween: HonorDistance = WGS84_HONOR_DISTANCE,
) {

    var anchor: WayCoordinate? = null
        private set
    var cameraZoom: Double = MapCameraSeed.CURRENT_LOCATION_ZOOM
        private set
    var cameraCenter: WayCoordinate? = null
        private set

    /** The anchor, else the camera's centre: before Start, and between Start and the first fix. */
    val near: WayCoordinate? get() = anchor ?: cameraCenter

    /** True when [at] moves the anchor: the first fix always, then one at least 200 m from it. */
    fun onFix(at: WayCoordinate): Boolean {
        anchor?.let { from ->
            if (!(metersBetween(from, at) >= HonorTuning.MARK_PIN_REFRESH_METERS)) return false
        }
        anchor = at
        return true
    }

    /**
     * Keeps the centre always, and the zoom when it is finite; true when the
     * zoom's whole level (truncated, as Swift's `Int(_:)`) changed.
     */
    fun onCamera(center: WayCoordinate, zoom: Double): Boolean {
        cameraCenter = center
        if (!zoom.isFinite()) return false
        val wasLevel = cameraZoom.toInt()
        cameraZoom = zoom
        return zoom.toInt() != wasLevel
    }

    /** iOS `bindMarkPins()` at Start and `teardownHonor` at the end; the camera survives both. */
    fun resetAnchor() {
        anchor = null
    }

    fun pins(marks: List<WayMark>): List<WayMarkPin> = WayMarkPins.pins(marks, cameraZoom, near)
}

/** A mark ready for the map: [image] is its 18 dp raster. */
@Immutable
data class WayMarkMapPin(
    val markId: String,
    val latitude: Double,
    val longitude: Double,
    val image: Bitmap,
)

/** iOS's mark size, 18 pt against a moment pin's 22 (`PilgrimMapView+HonorWay.swift:311-313@7c200bf`). */
internal const val WAY_MARK_SIZE_DP = 18f

/**
 * Rasters [marks] as the Way's moment pins are drawn, at 18 dp, in stone
 * from the light palette in both appearances. Every kind's painter is
 * taken on every composition, so the slot table never changes shape; one
 * raster per kind in use, per density.
 */
@Composable
fun rememberWayMarkMapPins(marks: List<WayMarkPin>): List<WayMarkMapPin> {
    val density = LocalDensity.current.density
    val painters: Map<WayMarkKind, Painter> =
        WayMarkKind.entries.associateWith { rememberVectorPainter(markGlyphVector(it)) }
    val kinds = remember(marks) { marks.map { it.kind }.toSet() }
    val rasters = remember(kinds, density, painters) {
        kinds.associateWith { kind ->
            renderWayPin(painters.getValue(kind), Color(WayPinTint.STONE.argb), density, WAY_MARK_SIZE_DP)
        }
    }
    return remember(marks, rasters) {
        marks.map { mark ->
            WayMarkMapPin(
                markId = mark.markId,
                latitude = mark.at.lat,
                longitude = mark.at.lon,
                image = rasters.getValue(mark.kind),
            )
        }
    }
}

/** One point the Way pin manager draws. */
internal data class WayPinPoint(val latitude: Double, val longitude: Double, val image: Bitmap)

/**
 * The Way pin manager's one layer (owner decision 9, P5 §5.3): the marks
 * first, then the moments, as iOS lists `honorMarkPins + honorPins` in its
 * single point manager. With icon overlap on and no sort key, Mapbox sorts
 * overlapping icons by screen height, so a mark lower on screen draws over
 * a moment pin, as on iOS (pilgrim-ios #123); the order only breaks ties.
 */
@Immutable
internal data class WayPinLayer(
    val marks: List<WayMarkMapPin>,
    val moments: List<WayMapPin>,
) {
    val isEmpty: Boolean get() = marks.isEmpty() && moments.isEmpty()

    val points: List<WayPinPoint>
        get() = marks.map { WayPinPoint(it.latitude, it.longitude, it.image) } +
            moments.map { WayPinPoint(it.latitude, it.longitude, it.image) }

    /** The map tap's targets after [others] (the whisper and cairn pins): the moments, never a mark. */
    fun tapTargets(others: List<MapTapTarget>): List<MapTapTarget> =
        others + moments.map { MapTapTarget(it.momentId, WayCoordinate(it.latitude, it.longitude)) }
}
