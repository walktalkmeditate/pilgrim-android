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
import androidx.compose.runtime.saveable.rememberSaveable
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
 * (easeOut). Per-walk one-shot: once shown for a given [walkId],
 * subsequent re-entries (screen recompositions, navigation back/
 * forth) on the SAME walk don't re-fire.
 *
 * Reset signal is a fresh [walkId] (next walk) OR null (walk
 * ended). The `shownForWalk` token uses `rememberSaveable` so a
 * rotation mid-walk doesn't make the greeting fire again.
 *
 * Caller passes:
 *   - [triggerCondition] = non-null while the walk is Active AND a
 *     weather snapshot exists; null otherwise.
 *   - [walkId] = currently-active walk's uuid (or null when Idle).
 *
 * The previous design keyed `LaunchedEffect` on the condition value
 * directly, which had two failure modes: (a) cancel mid-delay left
 * the overlay stuck visible, and (b) local `remember` state reset
 * on nav-back re-entry caused the greeting to fire every return.
 * Per-walk token + try/finally clear fixes both.
 */
@Composable
fun WeatherGreetingOverlay(
    triggerCondition: WeatherCondition?,
    walkId: Long?,
    modifier: Modifier = Modifier,
    /**
     * iOS parity `ActiveWalkView.swift:691-693@c1745e8` — seek speaks
     * its own weather: `if viewModel.mode == .seek { greeting =
     * SeekVoice.greeting(for: condition) }`. Sourced from the walk
     * accumulator's mode (not the nav argument) so a recovered seek
     * walk still greets in seek language.
     */
    isSeek: Boolean = false,
) {
    var visibleCondition by rememberSaveable { mutableStateOf<WeatherCondition?>(null) }
    var shownForWalk by rememberSaveable { mutableStateOf<Long?>(null) }

    LaunchedEffect(triggerCondition, walkId) {
        if (triggerCondition != null && walkId != null && shownForWalk != walkId) {
            shownForWalk = walkId
            visibleCondition = triggerCondition
            // try/finally so cancellation (terminal walk transition,
            // composition leaving the tree) still clears the visible
            // state instead of pinning the overlay.
            try {
                delay(GREETING_VISIBLE_MS)
            } finally {
                visibleCondition = null
            }
        } else if (walkId == null) {
            // Walk ended: reset the token so the NEXT walk gets its
            // own greeting even if it happens to share the condition.
            shownForWalk = null
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
        val message = stringResource(
            if (isSeek) {
                org.walktalkmeditate.pilgrim.domain.seek.SeekVoice.greetingRes(condition)
            } else {
                greetingStringResFor(condition)
            },
        )
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
 * iOS parity `ActiveWalkView.swift:735-764@db4196e` celestial
 * greeting variant — per-walk one-shot, 5s pre-delay before the
 * 800ms fade-in, 3500ms hold, 1000ms fade-out. Cancelled by walk
 * teardown (text or walkId flipping to null), with try/finally
 * cleanup so the overlay doesn't pin stuck visible on cancellation.
 */
@Composable
fun CelestialGreetingOverlay(
    text: String?,
    walkId: Long?,
    modifier: Modifier = Modifier,
) {
    var visibleText by rememberSaveable { mutableStateOf<String?>(null) }
    var shownForWalk by rememberSaveable { mutableStateOf<Long?>(null) }

    LaunchedEffect(text, walkId) {
        if (text != null && walkId != null && shownForWalk != walkId) {
            shownForWalk = walkId
            try {
                delay(CELESTIAL_PRE_DELAY_MS)
                visibleText = text
                delay(GREETING_VISIBLE_MS)
            } finally {
                visibleText = null
            }
        } else if (walkId == null) {
            shownForWalk = null
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

/**
 * iOS parity `ActiveWalkView.swift:735-748@db4196e` — picks one of
 * three celestial greeting forms in priority order, falling back to
 * the planetary-hour greeting. Resolves through Android string
 * resources so a future translation pass can localize the text.
 *
 * Note: iOS also has a "${planet} turns inward" form for retrograde
 * planets, but `CelestialSnapshot` on Android doesn't expose the
 * retrograde list yet, so that fallback is skipped here.
 */
fun celestialGreetingText(
    snapshot: org.walktalkmeditate.pilgrim.core.celestial.CelestialSnapshot,
    resources: android.content.res.Resources,
): String {
    val marker = snapshot.seasonalMarker
    val sun = snapshot.position(org.walktalkmeditate.pilgrim.core.celestial.Planet.Sun)
    val moon = snapshot.position(org.walktalkmeditate.pilgrim.core.celestial.Planet.Moon)
    val isTropical = snapshot.system ==
        org.walktalkmeditate.pilgrim.data.practice.ZodiacSystem.Tropical
    fun signOf(pos: org.walktalkmeditate.pilgrim.core.celestial.PlanetaryPosition?) =
        if (isTropical) pos?.tropical?.sign else pos?.sidereal?.sign

    if (marker != null) {
        val sign = signOf(sun)
        if (sign != null) {
            return resources.getString(
                R.string.walk_greeting_celestial_sun_enters,
                sign.displayName,
                marker.displayName,
            )
        }
    }
    val moonSign = signOf(moon)
    if (moonSign != null) {
        return resources.getString(
            R.string.walk_greeting_celestial_moon_through,
            moonSign.displayName,
        )
    }
    return resources.getString(
        R.string.walk_greeting_celestial_hour,
        snapshot.planetaryHour.planet.displayName,
    )
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
private const val CELESTIAL_PRE_DELAY_MS = 5_000L
