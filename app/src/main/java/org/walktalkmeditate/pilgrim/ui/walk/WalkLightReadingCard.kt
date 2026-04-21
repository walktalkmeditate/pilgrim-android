// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.ZoneId
import org.walktalkmeditate.pilgrim.core.celestial.LightReading
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * Post-walk "Light Reading" card — the contemplative payoff on Walk
 * Summary. Shows moon-phase emoji, the koan, optional attribution,
 * and a compact celestial-context section (moon phase + illumination,
 * planetary hour with a muted dot, sun times when available), with a
 * "— a light reading" footer.
 *
 * Long-press copies the koan text to the clipboard. The haptic pulse
 * is the confirmation — no toast, no snackbar.
 *
 * Stateless. Takes a fully-computed [LightReading]; see
 * [WalkSummaryViewModel]'s `buildState()` for the factory call.
 */
@Composable
fun WalkLightReadingCard(
    reading: LightReading,
    zoneId: ZoneId = ZoneId.systemDefault(),
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current

    Card(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(reading.koan.text) {
                detectTapGestures(
                    onLongPress = {
                        clipboard.setText(AnnotatedString(reading.koan.text))
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                )
            },
        colors = CardDefaults.cardColors(
            containerColor = pilgrimColors.parchmentSecondary,
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = PilgrimSpacing.normal,
                    vertical = PilgrimSpacing.big,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.normal),
        ) {
            // Moon phase emoji — the visual anchor. 48sp so it reads
            // as a glyph rather than an icon.
            Text(
                text = LightReadingPresenter.phaseEmoji(reading.moon.name),
                fontSize = 48.sp,
            )

            // Koan — the emotional payload. Serif body, centered,
            // minimally decorated.
            Text(
                text = reading.koan.text,
                style = pilgrimType.body,
                color = pilgrimColors.ink,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            // Attribution (conditional). Italic sans, dim.
            val attribution = reading.koan.attribution
            if (attribution != null) {
                Text(
                    text = "— $attribution",
                    style = pilgrimType.caption,
                    color = pilgrimColors.fog,
                    fontStyle = FontStyle.Italic,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(PilgrimSpacing.small))

            // Moon phase + illumination line — compact, stone color.
            Text(
                text = LightReadingPresenter.moonLine(reading.moon),
                style = pilgrimType.caption,
                color = pilgrimColors.stone,
                textAlign = TextAlign.Center,
            )

            // Planetary hour line with small dot.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            color = LightReadingPresenter.planetDotColor(
                                reading.planetaryHour.planet,
                            ),
                            shape = CircleShape,
                        ),
                )
                Text(
                    text = LightReadingPresenter.planetaryHourLine(
                        reading.planetaryHour,
                    ),
                    style = pilgrimType.caption,
                    color = pilgrimColors.stone,
                )
            }

            // Sun line (conditional — omitted for no-GPS walks and
            // polar regions).
            val sunLine = LightReadingPresenter.sunLine(reading.sun, zoneId)
            if (sunLine != null) {
                Text(
                    text = sunLine,
                    style = pilgrimType.caption,
                    color = pilgrimColors.stone,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(PilgrimSpacing.small))

            // Footer — matches iOS's brand caption.
            Text(
                text = "— a light reading",
                style = pilgrimType.caption,
                color = pilgrimColors.fog.copy(alpha = 0.6f),
                fontStyle = FontStyle.Italic,
                textAlign = TextAlign.Center,
            )
        }
    }
}
