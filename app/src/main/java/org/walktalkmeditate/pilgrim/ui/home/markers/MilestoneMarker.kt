// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.markers

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.util.Locale
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.ui.design.scenery.toriiGatePath
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * Horizontal milestone bar — leading hairline + torii glyph + label +
 * trailing hairline. Constrained to 70 % canvas width via fillMaxWidth(0.7f).
 *
 * Distance text uses `Locale.US` — Stage 5-A locale lesson; numeric
 * body is always ASCII. The full localized label (with non-ASCII digits
 * where appropriate) is applied only to the content-description for
 * screen readers.
 */
@Composable
fun MilestoneMarker(
    distanceM: Double,
    units: UnitSystem,
    modifier: Modifier = Modifier,
) {
    val displayText = remember(distanceM, units) {
        if (units == UnitSystem.Imperial) {
            String.format(Locale.US, "%d mi", (distanceM / 1609.344).toInt())
        } else {
            String.format(Locale.US, "%d km", (distanceM / 1000.0).toInt())
        }
    }
    val a11yLabel = stringResource(R.string.journal_milestone_a11y, displayText)
    val hairlineColor = pilgrimColors.fog.copy(alpha = 0.15f)
    val toriiColor = pilgrimColors.stone.copy(alpha = 0.25f)
    val labelColor = pilgrimColors.fog.copy(alpha = 0.4f)
    val microStyle = pilgrimType.micro
    Row(
        modifier = modifier
            .fillMaxWidth(0.7f)
            .semantics { contentDescription = a11yLabel },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .weight(1f)
                .height(0.5.dp)
                .background(hairlineColor),
        )
        Canvas(modifier = Modifier.size(width = 16.dp, height = 14.dp)) {
            drawPath(
                path = toriiGatePath(Size(size.width, size.height)),
                color = toriiColor,
            )
        }
        Text(
            text = displayText,
            style = microStyle,
            color = labelColor,
        )
        Box(
            Modifier
                .weight(1f)
                .height(0.5.dp)
                .background(hairlineColor),
        )
    }
}
