// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.honor.pilgrimage

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlinx.coroutines.launch
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.data.weather.WeatherSnapshot
import org.walktalkmeditate.pilgrim.domain.honor.WayStage
import org.walktalkmeditate.pilgrim.ui.honor.WayMomentCopy
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimCornerRadius
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType
import org.walktalkmeditate.pilgrim.ui.walk.formatTemperature

/** iOS `StageMorningCardModel` (`StageMorningCard.swift:3-27@7c200bf`, pilgrimage-stage spec P4 §7.1). */
object StageMorningCardModel {

    /** The stage block's [WayStageFacts], the same line the route page's row for this stage prints. */
    fun factsLine(resources: Resources, stage: WayStage, units: UnitSystem): String =
        WayStageFacts.line(resources, stage.distanceKm, stage.gainMeters, stage.hours, stage.difficulty, units)

    /**
     * "clear, 9°C": the condition's label lower-cased, then the temperature
     * in the walker's distance unit, miles reading Fahrenheit. Nothing at
     * all without a snapshot; a stage's words don't need weather to stand.
     */
    fun weatherLine(resources: Resources, snapshot: WeatherSnapshot?, units: UnitSystem, locale: Locale): String? {
        snapshot ?: return null
        val condition = resources.getString(snapshot.condition.labelRes).lowercase(locale)
        val temperature = formatTemperature(snapshot.temperatureCelsius, imperial = units == UnitSystem.Imperial)
        return resources.getString(R.string.pilgrimage_morning_weather, condition, temperature)
    }
}

/**
 * The card's one button: "walk" at the overview's Begin, "close" from the
 * walk's "the day". iOS passes the face as a string and picks the label by
 * comparing it with "walk" (`StageMorningCard.swift:79@7c200bf`); the two
 * cases stand in for that comparison.
 */
enum class StageMorningCardAction(@StringRes val title: Int, @StringRes val label: Int) {
    WALK(R.string.pilgrimage_morning_walk, R.string.pilgrimage_morning_walk_a11y),
    CLOSE(R.string.pilgrimage_morning_close, R.string.pilgrimage_morning_close_a11y),
}

/**
 * iOS `StageMorningCard` (`StageMorningCard.swift:29-104@7c200bf`,
 * pilgrimage-stage spec P4 §6.2, §7.2): the stage's own words before the
 * walk, and again from the walk's options as "the day". A full-height sheet
 * with its drag handle, in parchment. The button slides the sheet down,
 * then runs [onAction]; a swipe down, Back or the scrim close it through
 * [onDismiss] and do nothing else.
 *
 * [mapsLine] is Stage 21-3's seam: every caller in Stage 21-2 passes null,
 * since there is no save for "save on wifi" to point at (P4 §9, A-8).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StageMorningCard(
    stage: WayStage,
    weather: WeatherSnapshot?,
    units: UnitSystem,
    mapsLine: String?,
    action: StageMorningCardAction,
    onAction: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val acted by rememberUpdatedState(onAction)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = pilgrimColors.parchment,
    ) {
        StageMorningCardContent(
            stage = stage,
            weather = weather,
            units = units,
            mapsLine = mapsLine,
            action = action,
            onAction = {
                scope.launch { sheetState.hide() }.invokeOnCompletion {
                    if (!sheetState.isVisible) acted()
                }
            },
            modifier = Modifier
                .fillMaxHeight()
                .navigationBarsPadding(),
        )
    }
}

/**
 * Everything but the button scrolls: the theme, the narrative, the facts,
 * each warning in its own row, the weather and the maps line. The button
 * stays pinned under them and is never disabled.
 */
@Composable
internal fun StageMorningCardContent(
    stage: WayStage,
    weather: WeatherSnapshot?,
    units: UnitSystem,
    mapsLine: String?,
    action: StageMorningCardAction,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val resources = LocalResources.current
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    Column(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(PilgrimSpacing.normal),
            verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.normal),
        ) {
            Text(text = stage.theme, style = pilgrimType.displayMedium, color = pilgrimColors.ink)
            Text(text = stage.narrative, style = pilgrimType.body, color = pilgrimColors.ink)
            Caption(StageMorningCardModel.factsLine(resources, stage, units))
            Warnings(stage.warnings)
            StageMorningCardModel.weatherLine(resources, weather, units, locale)?.let { Caption(it) }
            mapsLine?.let { Caption(it) }
        }
        val label = stringResource(action.label)
        Button(
            onClick = onAction,
            modifier = Modifier
                .fillMaxWidth()
                .padding(PilgrimSpacing.normal)
                .semantics { contentDescription = label },
            shape = RoundedCornerShape(PilgrimCornerRadius.normal),
            contentPadding = PaddingValues(vertical = 12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = pilgrimColors.stone,
                contentColor = pilgrimColors.parchment,
            ),
        ) {
            Text(
                text = stringResource(action.title),
                style = pilgrimType.button,
                color = pilgrimColors.parchment,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    }
}

/**
 * Each warning its own short paragraph behind a rust triangle, sized to
 * the caption so it scales with the text. The triangle isn't hidden: iOS's
 * VoiceOver reaches it alone and speaks its symbol's name, then the
 * warning (P4 §7.2, A-7).
 */
@Composable
private fun Warnings(warnings: List<String>) {
    if (warnings.isEmpty()) return
    val glyphSize = with(LocalDensity.current) { pilgrimType.caption.fontSize.toDp() }
    Column(verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.small)) {
        warnings.forEach { warning ->
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
            ) {
                Icon(
                    imageVector = Icons.Outlined.WarningAmber,
                    contentDescription = WayMomentCopy.spokenSymbolName(WARNING_SYMBOL),
                    tint = pilgrimColors.rust,
                    modifier = Modifier.size(glyphSize),
                )
                Text(text = warning, style = pilgrimType.caption, color = pilgrimColors.ink)
            }
        }
    }
}

@Composable
private fun Caption(text: String) {
    Text(text = text, style = pilgrimType.caption, color = pilgrimColors.fog)
}

private const val WARNING_SYMBOL = "exclamationmark.triangle"
