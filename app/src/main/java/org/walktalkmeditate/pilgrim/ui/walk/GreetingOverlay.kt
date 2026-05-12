// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.weather.WeatherCondition
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * iOS parity `ActiveWalkView.swift:711-732@db4196e` — fades a weather
 * greeting in over 0.8s (easeIn), holds 3.5s, fades out over 1.0s
 * (easeOut). Each [triggerCondition] change drives a fresh
 * appear/dismiss cycle; back-to-back triggers within the visible
 * window are debounced (iOS guards via `weatherGreeting == nil`).
 *
 * The composable owns the visibility latch internally — caller only
 * needs to forward `triggerCondition` (the weather snapshot's
 * condition, or null when no walk is active).
 */
@Composable
fun WeatherGreetingOverlay(
    triggerCondition: WeatherCondition?,
    modifier: Modifier = Modifier,
) {
    var visibleCondition by remember { mutableStateOf<WeatherCondition?>(null) }

    LaunchedEffect(triggerCondition) {
        // iOS guard: `weatherGreeting == nil else return` — back-to-back
        // condition flips within the visible window let the current
        // greeting finish on its own timer rather than restart it.
        if (triggerCondition != null && visibleCondition == null) {
            visibleCondition = triggerCondition
            delay(GREETING_VISIBLE_MS)
            visibleCondition = null
        }
    }

    AnimatedVisibility(
        visible = visibleCondition != null,
        enter = fadeIn(animationSpec = tween(durationMillis = 800)),
        exit = fadeOut(animationSpec = tween(durationMillis = 1000)),
        modifier = modifier,
    ) {
        val condition = visibleCondition ?: return@AnimatedVisibility
        val message = stringResource(greetingStringResFor(condition))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PilgrimSpacing.normal),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(pilgrimColors.parchmentSecondary.copy(alpha = 0.85f))
                    .padding(horizontal = PilgrimSpacing.normal, vertical = PilgrimSpacing.small),
            ) {
                Text(
                    text = message,
                    style = pilgrimType.body,
                    color = pilgrimColors.ink,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * Same overlay shape as [WeatherGreetingOverlay], but takes a
 * pre-resolved string and fires only when [text] transitions from
 * null → non-null. iOS uses celestial greeting with a 5s pre-delay
 * before the fade; callers handle the delay externally.
 */
@Composable
fun TextGreetingOverlay(
    text: String?,
    modifier: Modifier = Modifier,
) {
    var visibleText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(text) {
        if (text != null && visibleText == null) {
            visibleText = text
            delay(GREETING_VISIBLE_MS)
            visibleText = null
        }
    }

    AnimatedVisibility(
        visible = visibleText != null,
        enter = fadeIn(animationSpec = tween(durationMillis = 800)),
        exit = fadeOut(animationSpec = tween(durationMillis = 1000)),
        modifier = modifier,
    ) {
        val message = visibleText ?: return@AnimatedVisibility
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PilgrimSpacing.normal),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(pilgrimColors.parchmentSecondary.copy(alpha = 0.85f))
                    .padding(horizontal = PilgrimSpacing.normal, vertical = PilgrimSpacing.small),
            ) {
                Text(
                    text = message,
                    style = pilgrimType.body,
                    color = pilgrimColors.ink,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private fun greetingStringResFor(condition: WeatherCondition): Int = when (condition) {
    WeatherCondition.CLEAR -> R.string.walk_greeting_weather_clear
    WeatherCondition.PARTLY_CLOUDY -> R.string.walk_greeting_weather_partly_cloudy
    WeatherCondition.OVERCAST -> R.string.walk_greeting_weather_overcast
    WeatherCondition.LIGHT_RAIN -> R.string.walk_greeting_weather_light_rain
    WeatherCondition.HEAVY_RAIN -> R.string.walk_greeting_weather_heavy_rain
    WeatherCondition.THUNDERSTORM -> R.string.walk_greeting_weather_thunderstorm
    WeatherCondition.SNOW -> R.string.walk_greeting_weather_snow
    WeatherCondition.FOG -> R.string.walk_greeting_weather_fog
    WeatherCondition.WIND -> R.string.walk_greeting_weather_wind
    WeatherCondition.HAZE -> R.string.walk_greeting_weather_haze
}

private const val GREETING_VISIBLE_MS = 3_500L
