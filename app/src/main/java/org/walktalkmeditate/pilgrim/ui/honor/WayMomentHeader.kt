// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.honor

import android.content.res.Resources
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.dp
import java.time.DateTimeException
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.domain.honor.VoiceKind
import org.walktalkmeditate.pilgrim.domain.honor.Way
import org.walktalkmeditate.pilgrim.domain.honor.WayGeometry
import org.walktalkmeditate.pilgrim.domain.honor.WayMoment
import org.walktalkmeditate.pilgrim.domain.honor.WayMomentKind
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType
import org.walktalkmeditate.pilgrim.ui.walk.WalkFormat
import org.walktalkmeditate.pilgrim.ui.walk.map.WayGlyph
import org.walktalkmeditate.pilgrim.ui.walk.map.wayGlyphSymbolName
import org.walktalkmeditate.pilgrim.ui.walk.map.wayGlyphVector

/** A moment's words, as iOS's `WayMomentHeader` and `WayMomentPreview` write them (F §14.2–§14.3). */
object WayMomentCopy {

    /**
     * Null for a waypoint saved without a label, which shows no kicker
     * (owner decision 4). Minutes always read "minutes", as iOS ships them
     * (pilgrim-ios #109, matched).
     */
    fun kicker(resources: Resources, moment: WayMoment): String? = when (val kind = moment.kind) {
        is WayMomentKind.Voice -> resources.getString(
            if (kind.kind == VoiceKind.AMBIENT) {
                R.string.honor_moment_ambient_kicker
            } else {
                R.string.honor_moment_spoken_kicker
            },
        )
        is WayMomentKind.Photo -> resources.getString(R.string.honor_moment_photo_kicker)
        is WayMomentKind.Rest -> resources.getString(R.string.honor_moment_rest_kicker, count(kind.minutes))
        is WayMomentKind.Meditation -> resources.getString(
            if (kind.isEstimate) R.string.honor_moment_sit_estimate_kicker else R.string.honor_moment_sit_kicker,
            count(kind.minutes),
        )
        is WayMomentKind.Waypoint -> kind.label.takeIf { it.isNotEmpty() }
    }

    /**
     * `"1.2 km along their way · 8:41 AM"`, then the place when the Way
     * names one. The hour is in the Way's `tzIdentifier`, which an own walk
     * stamps with the zone the phone was in when the Way was built, not the
     * walk's own (pilgrim-ios #110, matched).
     */
    fun subline(resources: Resources, way: Way, moment: WayMoment, units: UnitSystem, locale: Locale): String {
        val distance = WalkFormat.distance(moment.frac * way.totalDistanceMeters, units)
        val elapsedSeconds = WayGeometry(way.route).elapsed(atFrac = moment.frac)
        val hour = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
            .withLocale(locale)
            .withZone(wayZone(way))
            .format(way.departedAt.plusMillis((elapsedSeconds * 1000).toLong()))
        val parts = buildList {
            add(resources.getString(R.string.honor_moment_along_their_way, distance))
            add(hour)
            moment.place?.takeIf { it.isNotEmpty() }?.let(::add)
        }
        return parts.joinToString(" · ")
    }

    /** iOS `placeCopy(for:isStage:)` for an own walk, which is never a stage. */
    fun placeCopy(resources: Resources, moment: WayMoment): String =
        moment.text?.takeIf { it.isNotEmpty() } ?: resources.getString(R.string.honor_moment_place_marked)

    /**
     * iOS leaves the preview's header glyph and its missing-voice glyph
     * unlabelled, so VoiceOver speaks each SF symbol's name (pilgrim-ios
     * #108, matched). The name with its dots read as spaces stands in.
     */
    fun spokenSymbolName(symbol: String): String = symbol.replace('.', ' ')

    /** `m:ss`. */
    fun clock(seconds: Double): String {
        val total = if (seconds.isNaN()) 0L else seconds.coerceAtLeast(0.0).toLong()
        return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
    }

    /** `"1x"`, `"1.5x"`, `"2x"`, as iOS's `%.0fx` / `%gx` pair prints them. */
    fun speedLabel(speed: Float): String =
        if (speed % 1f == 0f) {
            String.format(Locale.US, "%.0fx", speed)
        } else {
            String.format(Locale.US, "%sx", speed.toString())
        }

    private fun wayZone(way: Way): ZoneId =
        way.tzIdentifier?.let {
            try {
                ZoneId.of(it)
            } catch (_: DateTimeException) {
                null
            }
        } ?: ZoneId.systemDefault()

    private fun count(n: Int): String = String.format(Locale.US, "%d", n)
}

/**
 * iOS `WayMomentHeader` in its full size (`WayMomentHeader.swift:14-45@7c200bf`):
 * a 52 dp parchment disc with the glyph in stone, then the kicker and the
 * subline. The disc is parchment on a parchment sheet, so only the glyph
 * shows there. The compact size and the heading tick are U22's.
 */
@Composable
fun WayMomentHeader(
    way: Way,
    moment: WayMoment,
    units: UnitSystem,
    modifier: Modifier = Modifier,
) {
    val resources = LocalResources.current
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.normal),
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(pilgrimColors.parchment),
            contentAlignment = Alignment.Center,
        ) {
            val glyph = WayGlyph.header(moment)
            Icon(
                imageVector = wayGlyphVector(glyph),
                contentDescription = WayMomentCopy.spokenSymbolName(wayGlyphSymbolName(glyph)),
                tint = pilgrimColors.stone,
                modifier = Modifier.size(22.dp),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.xs)) {
            WayMomentCopy.kicker(resources, moment)?.let {
                Text(text = it, style = pilgrimType.heading, color = pilgrimColors.ink)
            }
            Text(
                text = WayMomentCopy.subline(resources, way, moment, units, locale),
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
            )
        }
    }
}
