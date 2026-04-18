// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.theme.seasonal

import androidx.compose.ui.graphics.Color
import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSeasonal

/**
 * Date-and-hemisphere-driven HSB shift applied to a base PilgrimColors
 * token. Byte-for-byte port of the iOS `SeasonalColorEngine`:
 *
 * - cos² weights around four seasonal peak days (northern 105/196/288/15,
 *   spread 91 days), so each day of the year picks up a smooth blend of
 *   the four seasonal signals
 * - linear combination of the four seasons' per-channel HSB deltas
 *   (constants already in [PilgrimSeasonal])
 * - hue applied additively with `[0, 1)` wrap, saturation and
 *   brightness applied multiplicatively with `[0, 1]` clamp
 * - southern hemisphere offsets day-of-year by 182 days so the palette
 *   feels right below the equator
 *
 * Pure object — no state, no DI. Callers provide the date + hemisphere.
 * For the typical flow, [HemisphereRepository] resolves the hemisphere
 * once per process; the date is usually `LocalDate.now()` for an
 * "about today" render or the walk's start date for retrospective
 * rendering (the calligraphy path uses the latter).
 */
object SeasonalColorEngine {

    enum class Intensity(val scale: Float) {
        /** 100% of the seasonal shift. Walk dots, seal accents. */
        Full(1.0f),
        /** 40%. Calligraphy-path segments, road lines on maps. */
        Moderate(0.4f),
        /** 10%. Map backgrounds, subtle accents. */
        Minimal(0.1f),
    }

    /**
     * @param base the base color to shift (a [PilgrimColors] token).
     * @param intensity how much of the seasonal signal to apply.
     * @param date date to evaluate against. Defaults to today in the
     *   system zone.
     * @param hemisphere northern/southern. Defaults to Northern; real
     *   callers inject from [HemisphereRepository.hemisphere].
     */
    fun applySeasonalShift(
        base: Color,
        intensity: Intensity,
        date: LocalDate = LocalDate.now(),
        hemisphere: Hemisphere = Hemisphere.Northern,
    ): Color {
        val adjustment = seasonalTransform(date, hemisphere)
        val scale = intensity.scale

        // Decompose base into HSV. Compose's Color is RGB-native; drop
        // down to android.graphics.Color for HSV conversion. Alpha is
        // preserved out-of-band.
        val hsv = FloatArray(3)
        android.graphics.Color.RGBToHSV(
            (base.red * 255f).toInt().coerceIn(0, 255),
            (base.green * 255f).toInt().coerceIn(0, 255),
            (base.blue * 255f).toInt().coerceIn(0, 255),
            hsv,
        )
        // iOS convention: hue in `[0, 1]`. android.graphics uses
        // `[0, 360]`. Normalize so the PilgrimSeasonal.*_HUE constants
        // (imported from iOS verbatim) keep their meaning.
        var h01 = hsv[0] / 360f
        var s = hsv[1]
        var v = hsv[2]

        // `%` on negative floats in Kotlin (and iOS's truncatingRemainder)
        // returns a negative result; double-mod keeps the hue in [0, 1).
        h01 = (((h01 + adjustment.hueDelta * scale) % 1f) + 1f) % 1f
        s = (s * (1f + (adjustment.saturationMultiplier - 1f) * scale)).coerceIn(0f, 1f)
        v = (v * (1f + (adjustment.brightnessMultiplier - 1f) * scale)).coerceIn(0f, 1f)

        val argb = android.graphics.Color.HSVToColor(
            (base.alpha * 255f).toInt().coerceIn(0, 255),
            floatArrayOf(h01 * 360f, s, v),
        )
        return Color(argb)
    }

    internal fun seasonalTransform(date: LocalDate, hemisphere: Hemisphere): SeasonalAdjustment {
        val dayOfYear = adjustedDayOfYear(date, hemisphere)
        val spring = seasonalWeight(dayOfYear, PilgrimSeasonal.SPRING_PEAK_DAY, PilgrimSeasonal.SPREAD)
        val summer = seasonalWeight(dayOfYear, PilgrimSeasonal.SUMMER_PEAK_DAY, PilgrimSeasonal.SPREAD)
        val autumn = seasonalWeight(dayOfYear, PilgrimSeasonal.AUTUMN_PEAK_DAY, PilgrimSeasonal.SPREAD)
        val winter = seasonalWeight(dayOfYear, PilgrimSeasonal.WINTER_PEAK_DAY, PilgrimSeasonal.SPREAD)
        return SeasonalAdjustment(
            hueDelta =
                spring * PilgrimSeasonal.SPRING_HUE +
                    summer * PilgrimSeasonal.SUMMER_HUE +
                    autumn * PilgrimSeasonal.AUTUMN_HUE +
                    winter * PilgrimSeasonal.WINTER_HUE,
            saturationMultiplier = 1f +
                spring * PilgrimSeasonal.SPRING_SAT +
                summer * PilgrimSeasonal.SUMMER_SAT +
                autumn * PilgrimSeasonal.AUTUMN_SAT +
                winter * PilgrimSeasonal.WINTER_SAT,
            brightnessMultiplier = 1f +
                spring * PilgrimSeasonal.SPRING_BRIGHT +
                summer * PilgrimSeasonal.SUMMER_BRIGHT +
                autumn * PilgrimSeasonal.AUTUMN_BRIGHT +
                winter * PilgrimSeasonal.WINTER_BRIGHT,
        )
    }

    internal fun adjustedDayOfYear(date: LocalDate, hemisphere: Hemisphere): Int {
        val doy = date.dayOfYear
        return when (hemisphere) {
            Hemisphere.Northern -> doy
            Hemisphere.Southern -> ((doy + 182) % 365) + 1
        }
    }

    internal fun seasonalWeight(dayOfYear: Int, peakDay: Int, spread: Float): Float {
        val rawDiff = abs(dayOfYear - peakDay).toFloat()
        val distance = min(rawDiff, 365f - rawDiff)
        val normalized = distance / spread
        val base = max(0f, cos(normalized * PI.toFloat() / 2f))
        return base * base
    }
}

internal data class SeasonalAdjustment(
    val hueDelta: Float,
    val saturationMultiplier: Float,
    val brightnessMultiplier: Float,
)
