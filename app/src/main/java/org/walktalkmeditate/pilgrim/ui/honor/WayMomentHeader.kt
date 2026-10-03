// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.honor

import android.content.res.Resources
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import org.walktalkmeditate.pilgrim.domain.honor.WaySource
import org.walktalkmeditate.pilgrim.domain.honor.WayStage
import org.walktalkmeditate.pilgrim.ui.honor.pilgrimage.STAGE_SEPARATOR
import org.walktalkmeditate.pilgrim.ui.honor.pilgrimage.StageFormat
import org.walktalkmeditate.pilgrim.ui.honor.pilgrimage.digits
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
     * Null for a waypoint the walker saved without a label, which shows no
     * kicker (owner decision 4); with [keepsEmpty], on any other Way, it is
     * iOS's empty kicker line (shared spec S4 §10.2). Minutes always read
     * "minutes", as iOS ships them (pilgrim-ios #109, matched).
     */
    fun kicker(resources: Resources, moment: WayMoment, keepsEmpty: Boolean = false): String? = when (val kind = moment.kind) {
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
        is WayMomentKind.Waypoint -> kind.label.takeIf { it.isNotEmpty() || keepsEmpty }
    }

    /**
     * `"1.2 km along their way · 8:41 AM"`, then the place when the Way
     * names one. The hour is in the Way's `tzIdentifier`, which an own walk
     * stamps with the zone the phone was in when the Way was built, not the
     * walk's own (pilgrim-ios #110, matched).
     *
     * A stage drops the hour, its clock being the build's, and reads
     * `"1.2 km along the stage"` in the stage surfaces' numbers
     * (`WayMomentPreview.swift:49-64@7c200bf`, pilgrimage-stage spec P4 §8.1,
     * owner decision 7). Its moments carry no place.
     */
    fun subline(resources: Resources, way: Way, moment: WayMoment, units: UnitSystem, locale: Locale): String {
        val meters = moment.frac * way.totalDistanceMeters
        val parts = buildList {
            if (way.isPilgrimageStage) {
                add(resources.getString(R.string.honor_moment_along_the_stage, StageFormat.distance(meters, units)))
            } else {
                val elapsedSeconds = WayGeometry(way.route).elapsed(atFrac = moment.frac)
                val hour = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
                    .withLocale(locale)
                    .withZone(wayZone(way))
                    .format(way.departedAt.plusMillis((elapsedSeconds * 1000).toLong()))
                add(resources.getString(R.string.honor_moment_along_their_way, WalkFormat.distance(meters, units)))
                add(hour)
            }
            moment.place?.takeIf { it.isNotEmpty() }?.let(::add)
        }
        return parts.joinToString(" · ")
    }

    /**
     * iOS `placeCopy(for:isStage:)` (`WayMomentHeader.swift:86-89@7c200bf`):
     * the dataset's or the walker's own words when there are any, else the
     * shortest true thing. A stage has no "they".
     */
    fun placeCopy(resources: Resources, moment: WayMoment, isStage: Boolean): String =
        moment.text?.takeIf { it.isNotEmpty() } ?: resources.getString(
            if (isStage) R.string.honor_moment_place_on_the_way else R.string.honor_moment_place_marked,
        )

    /**
     * iOS `localName(for:)` (`WayMomentHeader.swift:72-82@7c200bf`): the
     * first non-empty name, in iOS's language order, that isn't the kicker
     * itself. Only stage data carries names.
     */
    fun localName(resources: Resources, moment: WayMoment): String? {
        val names = moment.names ?: return null
        val label = kicker(resources, moment).orEmpty()
        return LOCAL_NAME_ORDER.firstNotNullOfOrNull { code -> names[code]?.takeIf { it.isNotEmpty() && it != label } }
    }

    private val LOCAL_NAME_ORDER = listOf("eu", "gl", "es", "fr", "ja", "pt", "it", "de")

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
 * iOS `WayStageLine` (`WayMomentHeader.swift:123-135@7c200bf`,
 * pilgrimage-stage spec P4 §5.2): "stage 1 of 33 · 24.2 km · hard", what
 * stands where any other Way shows the day it was walked, a stage's own
 * date being the build's. Its one live caller is the overview's date slot.
 */
object WayStageLine {

    /** Null for a Way that isn't a stage, which keeps its date. */
    fun line(resources: Resources, way: Way, units: UnitSystem): String? =
        way.stage?.let { line(resources, it, units) }

    /** The dataset's distance and difficulty; an empty difficulty adds no part. */
    fun line(resources: Resources, stage: WayStage, units: UnitSystem): String {
        val parts = mutableListOf(
            resources.getString(R.string.honor_overview_stage_of, digits(stage.index + 1), digits(stage.count)),
            StageFormat.distance(stage.distanceKm * 1000, units),
        )
        if (stage.difficulty.isNotEmpty()) parts += stage.difficulty
        return parts.joinToString(STAGE_SEPARATOR)
    }
}

/**
 * iOS `WayMomentHeader` in its full size (`WayMomentHeader.swift:14-45@7c200bf`):
 * a 52 dp parchment disc with the glyph in stone, then the kicker, a
 * stage's local name on one line (pilgrimage-stage spec P4 §8.2), and the
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
            WayMomentCopy.kicker(resources, moment, keepsEmpty = way.source !is WaySource.OwnWalk)?.let {
                Text(text = it, style = pilgrimType.heading, color = pilgrimColors.ink)
            }
            WayMomentCopy.localName(resources, moment)?.let {
                Text(text = it, style = pilgrimType.caption, color = pilgrimColors.fog, maxLines = 1)
            }
            Text(
                text = WayMomentCopy.subline(resources, way, moment, units, locale),
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
            )
        }
    }
}

/**
 * The header in its compact size, on the walk's place card
 * (`WayMomentHeader.swift:13-46@7c200bf`, parity spec E §9): a 36 dp
 * parchment disc with the glyph in stone, then the kicker, a stage's local
 * name, and the [subline] beside the heading [tick]. The tick points from
 * the walker to the place, eased over 0.25 s on the raw angle, so crossing
 * straight ahead spins it the long way (pilgrim-ios #111, matched); it
 * shows only with a subline, and TalkBack never reads it.
 */
@Composable
fun WayMomentCompactHeader(
    moment: WayMoment,
    subline: String?,
    tick: Double?,
    keepsEmptyKicker: Boolean,
    modifier: Modifier = Modifier,
) {
    val resources = LocalResources.current
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(pilgrimColors.parchment),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = wayGlyphVector(WayGlyph.header(moment)),
                contentDescription = null,
                tint = pilgrimColors.stone,
                modifier = Modifier.size(18.dp),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.xs)) {
            WayMomentCopy.kicker(resources, moment, keepsEmpty = keepsEmptyKicker)?.let {
                Text(text = it, style = pilgrimType.body, color = pilgrimColors.ink)
            }
            WayMomentCopy.localName(resources, moment)?.let {
                Text(text = it, style = pilgrimType.caption, color = pilgrimColors.fog, maxLines = 1)
            }
            if (subline != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.xs),
                ) {
                    if (tick != null) {
                        val angle by animateFloatAsState(
                            targetValue = tick.toFloat(),
                            animationSpec = tween(durationMillis = TICK_EASE_MS, easing = EaseOut),
                            label = "heading-tick",
                        )
                        Icon(
                            imageVector = Icons.Filled.Navigation,
                            contentDescription = null,
                            tint = pilgrimColors.stone,
                            modifier = Modifier
                                .size(12.dp)
                                .rotate(angle)
                                .clearAndSetSemantics {},
                        )
                    }
                    Text(text = subline, style = pilgrimType.caption, color = pilgrimColors.fog)
                }
            }
        }
    }
}

/** iOS `.animation(.easeOut(duration: 0.25), value: tick)`. */
private const val TICK_EASE_MS = 250
