// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.core.celestial.CelestialSnapshot
import org.walktalkmeditate.pilgrim.core.celestial.Planet
import org.walktalkmeditate.pilgrim.data.practice.ZodiacSystem
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * Stage 13-Cel: "Moon in {sign}, Hour of {planet}, {element} predominates"
 * inline row. iOS reference: `WalkSummaryView.celestialLine`
 * (`WalkSummaryView.swift:511-533`).
 *
 * Caller-side gate: render only when `snapshot != null` (the VM's
 * `celestialSnapshotDisplay` flow handles the celestialAwareness
 * preference toggle; this composable just renders what it's given).
 *
 * Three Texts; first and third are conditional on data availability
 * (no moon position → no moon Text; element tied → no element Text).
 * Middle (planetary hour) is always present per iOS.
 */
@Composable
fun CelestialLineRow(
    snapshot: CelestialSnapshot,
    modifier: Modifier = Modifier,
) {
    val moonPos = snapshot.position(Planet.Moon)
    val moonZodiac = moonPos?.let {
        if (snapshot.system == ZodiacSystem.Tropical) it.tropical else it.sidereal
    }
    val dominant = snapshot.elementBalance.dominant
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
    ) {
        moonZodiac?.let { zp ->
            Text(
                text = stringResource(R.string.summary_celestial_moon_in, zp.sign.displayName),
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = stringResource(R.string.summary_celestial_hour_of, snapshot.planetaryHour.planet.displayName),
            style = pilgrimType.caption,
            color = pilgrimColors.fog,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        dominant?.let { el ->
            Text(
                text = stringResource(R.string.summary_celestial_predominates, el.displayName),
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
