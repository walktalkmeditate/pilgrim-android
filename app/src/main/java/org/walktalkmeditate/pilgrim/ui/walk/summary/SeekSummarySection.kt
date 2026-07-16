// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import android.content.res.Resources
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WbTwilight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DecimalStyle
import java.util.Locale
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.domain.seek.SeekSkyLight
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimCornerRadius
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * "The Seeking" card on the walk summary — the seek story between the
 * intention card and the elevation profile. Renders the unknowns-found
 * note, one row per reached clearing (label, arrival time, found-under
 * caption, signs line), the "Along the way" strays group, and the
 * provenance keepsake line. Stateless; the caller guards on a non-null
 * [SeekSummaryData] (wander walks and zero-arrival seeks have none).
 * Port spec `docs/parity/2026-07-14-port-seek-summary-u11.md`
 * (iOS `SeekSummarySection`, `SeekSummarySection.swift:246-373@c1745e8`).
 */
@Composable
fun SeekSummarySection(
    data: SeekSummaryData,
    modifier: Modifier = Modifier,
) {
    val resources = LocalContext.current.resources
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PilgrimCornerRadius.normal))
            .background(pilgrimColors.parchmentSecondary)
            .padding(PilgrimSpacing.normal),
        verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
        ) {
            // iOS renders the reserved arrival icon ("sun.haze"); Material's
            // WbTwilight is the closest low-sun-with-haze glyph (spec D4).
            Icon(
                imageVector = Icons.Outlined.WbTwilight,
                contentDescription = null,
                tint = pilgrimColors.stone,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = resources.getString(R.string.seek_summary_header),
                style = pilgrimType.heading,
                color = pilgrimColors.ink,
            )
        }

        Text(
            text = SeekSummaryModel.unknownsFoundText(resources, data.groups.size),
            style = pilgrimType.body,
            color = pilgrimColors.fog,
        )

        data.groups.forEach { group ->
            ClearingRow(group = group, resources = resources)
        }

        if (!data.alongTheWay.isEmpty) {
            AlongTheWayRow(alongTheWay = data.alongTheWay, resources = resources)
        }

        data.seededAtEpochMs?.let { seededAt ->
            Text(
                text = seededLine(
                    resources = resources,
                    seededAtEpochMs = seededAt,
                    intentionWasVoiced = data.intentionWasVoiced,
                ),
                style = pilgrimType.caption,
                fontStyle = FontStyle.Italic,
                color = pilgrimColors.fog,
                modifier = Modifier.padding(top = PilgrimSpacing.small),
            )
        }
    }
}

@Composable
private fun ClearingRow(
    group: SeekSummaryData.ClearingGroup,
    resources: Resources,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = PilgrimSpacing.xs),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = group.label,
                style = pilgrimType.body,
                color = pilgrimColors.ink,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = formatArrivalTime(group.arrivedAtEpochMs),
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
            )
        }
        Text(
            text = foundUnderText(resources, group.foundUnder),
            style = pilgrimType.caption,
            color = pilgrimColors.fog,
        )
        signsLine(
            resources = resources,
            photos = group.photoIds.size,
            voices = group.voiceRecordingIds.size,
            marks = group.waypointIds.size,
        )?.let { signs ->
            Text(
                text = signs,
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
            )
        }
    }
}

@Composable
private fun AlongTheWayRow(
    alongTheWay: SeekSummaryData.AlongTheWay,
    resources: Resources,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = PilgrimSpacing.xs),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = resources.getString(R.string.seek_summary_along_the_way),
            style = pilgrimType.body,
            color = pilgrimColors.ink,
        )
        signsLine(
            resources = resources,
            photos = alongTheWay.photoIds.size,
            voices = alongTheWay.voiceRecordingIds.size,
            marks = alongTheWay.waypointIds.size,
        )?.let { signs ->
            Text(
                text = signs,
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
            )
        }
    }
}

/**
 * Same short-time pattern as the summary card neighbors
 * (`WalkActivityListCard.kt:185`); ASCII digits forced via
 * `DecimalStyle.STANDARD` (Stage 6-B rule).
 */
private val arrivalTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
        .withDecimalStyle(DecimalStyle.STANDARD)

internal fun formatArrivalTime(
    epochMs: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String = arrivalTimeFormatter
    .withZone(zoneId)
    .format(Instant.ofEpochMilli(epochMs))

/**
 * The found-under caption for the hour's light (iOS `foundUnderText`,
 * `SeekSummarySection.swift:346-352@c1745e8`).
 */
internal fun foundUnderText(resources: Resources, daypart: SeekSkyLight.Daypart): String =
    when (daypart) {
        SeekSkyLight.Daypart.GOLDEN ->
            resources.getString(R.string.seek_summary_found_under_golden)
        SeekSkyLight.Daypart.MIDDAY ->
            resources.getString(R.string.seek_summary_found_under_midday)
        SeekSkyLight.Daypart.NIGHT ->
            resources.getString(R.string.seek_summary_found_under_night)
    }

/**
 * "a photo · 2 voice notes · a mark" — photos-voices-marks order, zero
 * counts omitted, " · " separator, null when nothing was marked (iOS
 * `signsLine`, `SeekSummarySection.swift:354-372@c1745e8`).
 */
internal fun signsLine(
    resources: Resources,
    photos: Int,
    voices: Int,
    marks: Int,
): String? {
    val parts = buildList {
        when {
            photos == 1 -> add(resources.getString(R.string.seek_summary_sign_photo_one))
            photos > 1 -> add(resources.getString(R.string.seek_summary_sign_photo_many, photos))
        }
        when {
            voices == 1 -> add(resources.getString(R.string.seek_summary_sign_voice_one))
            voices > 1 -> add(resources.getString(R.string.seek_summary_sign_voice_many, voices))
        }
        when {
            marks == 1 -> add(resources.getString(R.string.seek_summary_sign_mark_one))
            marks > 1 -> add(resources.getString(R.string.seek_summary_sign_mark_many, marks))
        }
    }
    return if (parts.isEmpty()) null else parts.joinToString(" · ")
}

/**
 * The provenance keepsake — phrased for a voiced intention or a quiet
 * seek, stamped with the gateway moment (iOS `seededLine`,
 * `SeekSummarySection.swift:337-344@c1745e8`).
 */
internal fun seededLine(
    resources: Resources,
    seededAtEpochMs: Long,
    intentionWasVoiced: Boolean,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String {
    val time = formatArrivalTime(seededAtEpochMs, zoneId)
    return if (intentionWasVoiced) {
        resources.getString(R.string.seek_summary_seeded, time)
    } else {
        resources.getString(R.string.seek_summary_seeded_quiet, time)
    }
}
