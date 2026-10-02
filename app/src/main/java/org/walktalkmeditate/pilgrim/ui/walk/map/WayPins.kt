// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.map

import android.graphics.Bitmap
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Adjust
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.Coffee
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.roundToInt
import org.walktalkmeditate.pilgrim.domain.honor.Way
import org.walktalkmeditate.pilgrim.domain.honor.WayCoordinate
import org.walktalkmeditate.pilgrim.domain.honor.WayGeometry
import org.walktalkmeditate.pilgrim.domain.honor.WayMoment
import org.walktalkmeditate.pilgrim.domain.honor.WayMomentKind
import org.walktalkmeditate.pilgrim.domain.honor.VoiceKind
import org.walktalkmeditate.pilgrim.ui.walk.PRESET_CHIPS
import org.walktalkmeditate.pilgrim.ui.walk.WAYPOINT_CUSTOM_ICON_KEY
import org.walktalkmeditate.pilgrim.ui.walk.iconKeyToVector

/*
 * A Way's moment pins: iOS `wayPins(for:heardVoiceIDs:)` and its rasters
 * (`PilgrimMapView+HonorWay.swift:120-138,299-334@7c200bf`,
 * `MapGlyphImageBuilder.swift:85-153@7c200bf`; parity spec E §4). The
 * overview shows them before Begin, none heard; U22's walk map reuses them.
 */

/** The glyph a moment wears, on its pin and in its header. */
sealed interface WayGlyph {
    data object Waveform : WayGlyph

    /** An ambient voice's header glyph; its pin still wears [Waveform]. */
    data object Wind : WayGlyph
    data object Photo : WayGlyph
    data object Rest : WayGlyph
    data object Sitting : WayGlyph

    /** [iconKey] is resolved: an icon Android cannot draw is iOS's own fallback, `mappin`. */
    data class Waypoint(val iconKey: String) : WayGlyph

    companion object {
        /** iOS `WayMomentHeader.glyph(for:)` (`WayMomentHeader.swift:48-56@7c200bf`). */
        fun header(moment: WayMoment): WayGlyph = when (val kind = moment.kind) {
            is WayMomentKind.Voice -> if (kind.kind == VoiceKind.AMBIENT) Wind else Waveform
            is WayMomentKind.Photo -> Photo
            is WayMomentKind.Rest -> Rest
            is WayMomentKind.Meditation -> Sitting
            is WayMomentKind.Waypoint -> Waypoint(resolvedWaypointIcon(kind.icon))
        }

        fun resolvedWaypointIcon(icon: String): String =
            if (icon in DRAWABLE_WAYPOINT_ICONS) icon else WAYPOINT_CUSTOM_ICON_KEY

        private val DRAWABLE_WAYPOINT_ICONS: Set<String> =
            PRESET_CHIPS.map { it.iconKey }.toSet() + WAYPOINT_CUSTOM_ICON_KEY
    }
}

/** The SF symbols' Material stand-ins. */
fun wayGlyphVector(glyph: WayGlyph): ImageVector = when (glyph) {
    WayGlyph.Waveform -> Icons.Outlined.GraphicEq
    WayGlyph.Wind -> Icons.Outlined.Air
    WayGlyph.Photo -> Icons.Outlined.Photo
    WayGlyph.Rest -> Icons.Outlined.Coffee
    WayGlyph.Sitting -> Icons.Outlined.Adjust
    is WayGlyph.Waypoint -> iconKeyToVector(glyph.iconKey)
}

/** The SF symbol each glyph stands in for, as `WayMomentHeader.glyph(for:)` names it. */
fun wayGlyphSymbolName(glyph: WayGlyph): String = when (glyph) {
    WayGlyph.Waveform -> "waveform"
    WayGlyph.Wind -> "wind"
    WayGlyph.Photo -> "photo"
    WayGlyph.Rest -> "cup.and.saucer"
    WayGlyph.Sitting -> "circle.circle"
    is WayGlyph.Waypoint -> glyph.iconKey
}

/**
 * The light-palette tints every Way pin is drawn in, in both appearances,
 * so a dark map still shows a light disc (`MapGlyphImageBuilder.swift:85-89@7c200bf`).
 */
enum class WayPinTint(val argb: Long) {
    STONE(0xFF8B7355),
    FOG(0xFF8A8175),
    DAWN(0xFFC4956A),
}

@Immutable
data class WayPin(
    val momentId: String,
    val at: WayCoordinate,
    val glyph: WayGlyph,
    val tint: WayPinTint,
)

/** A pin ready for the map: [image] is its raster. */
@Immutable
data class WayMapPin(
    val momentId: String,
    val latitude: Double,
    val longitude: Double,
    val image: Bitmap,
)

/**
 * One pin per moment, in the Way's order, standing at the place itself
 * (`pin`), else its `at`, else the line at its frac. A voice is fog until
 * heard, then stone; sittings are dawn; the rest stone.
 */
fun wayPins(way: Way, heardVoiceIds: Set<String>): List<WayPin> {
    val geometry = WayGeometry(way.route)
    return way.moments.map { moment ->
        val at = moment.pin ?: moment.at ?: geometry.coordinate(atFrac = moment.frac)
        val (glyph, tint) = when (val kind = moment.kind) {
            is WayMomentKind.Voice ->
                WayGlyph.Waveform to if (moment.id in heardVoiceIds) WayPinTint.STONE else WayPinTint.FOG
            is WayMomentKind.Photo -> WayGlyph.Photo to WayPinTint.STONE
            is WayMomentKind.Rest -> WayGlyph.Rest to WayPinTint.STONE
            is WayMomentKind.Meditation -> WayGlyph.Sitting to WayPinTint.DAWN
            is WayMomentKind.Waypoint ->
                WayGlyph.Waypoint(WayGlyph.resolvedWaypointIcon(kind.icon)) to WayPinTint.STONE
        }
        WayPin(momentId = moment.id, at = at, glyph = glyph, tint = tint)
    }
}

/** iOS's Way pin size, 22 pt (`PilgrimMapView+HonorWay.swift:323@7c200bf`). */
internal const val WAY_PIN_SIZE_DP = 22f

/**
 * Rasters [pins] for the map: a light-parchment disc at 0.9, the glyph at
 * 0.55 of the pin's size and 0.55 alpha. Every pin glyph's painter is taken
 * on every composition, so the slot table never changes shape; only the
 * looks in use are drawn, once per density.
 */
@Composable
fun rememberWayMapPins(pins: List<WayPin>): List<WayMapPin> {
    val density = LocalDensity.current.density
    val painters: Map<WayGlyph, Painter> = PIN_GLYPHS.associateWith { rememberVectorPainter(wayGlyphVector(it)) }
    val looks = remember(pins) { pins.map { it.glyph to it.tint }.toSet() }
    val rasters = remember(looks, density, painters) {
        looks.associateWith { (glyph, tint) ->
            renderWayPin(painters.getValue(glyph), Color(tint.argb), density)
        }
    }
    return remember(pins, rasters) {
        pins.map { pin ->
            WayMapPin(
                momentId = pin.momentId,
                latitude = pin.at.lat,
                longitude = pin.at.lon,
                image = rasters.getValue(pin.glyph to pin.tint),
            )
        }
    }
}

/** Every glyph a pin can wear; ambient voices wear the waveform on the map. */
private val PIN_GLYPHS: List<WayGlyph> = listOf(
    WayGlyph.Waveform,
    WayGlyph.Photo,
    WayGlyph.Rest,
    WayGlyph.Sitting,
) + (PRESET_CHIPS.map { it.iconKey } + WAYPOINT_CUSTOM_ICON_KEY).map { WayGlyph.Waypoint(it) }

private const val PIN_DISC_ARGB = 0xFFF5F0E8
private const val PIN_DISC_ALPHA = 0.9f
private const val PIN_GLYPH_SCALE = 0.55f
private const val PIN_GLYPH_ALPHA = 0.55f

private fun renderWayPin(painter: Painter, tint: Color, density: Float): Bitmap {
    val sizePx = (WAY_PIN_SIZE_DP * density).roundToInt().coerceAtLeast(1)
    val image = ImageBitmap(sizePx, sizePx)
    val glyphPx = sizePx * PIN_GLYPH_SCALE
    CanvasDrawScope().draw(
        density = Density(density),
        layoutDirection = LayoutDirection.Ltr,
        canvas = androidx.compose.ui.graphics.Canvas(image),
        size = Size(sizePx.toFloat(), sizePx.toFloat()),
    ) {
        drawCircle(
            color = Color(PIN_DISC_ARGB).copy(alpha = PIN_DISC_ALPHA),
            radius = sizePx / 2f,
            center = Offset(sizePx / 2f, sizePx / 2f),
        )
        val inset = (sizePx - glyphPx) / 2f
        translate(left = inset, top = inset) {
            with(painter) {
                draw(Size(glyphPx, glyphPx), alpha = PIN_GLYPH_ALPHA, colorFilter = ColorFilter.tint(tint))
            }
        }
    }
    return image.asAndroidBitmap()
}
