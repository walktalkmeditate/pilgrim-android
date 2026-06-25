// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.onboarding

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.onboarding.OnboardingPreferencesRepository
import org.walktalkmeditate.pilgrim.ui.design.BreathingLogo
import org.walktalkmeditate.pilgrim.ui.design.LocalReduceMotion
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * First-launch Welcome screen. Port of iOS
 * `Setup/Welcome/WelcomeView.swift` + `WelcomeAnimationState.swift`
 * (v1.6.0). Choreographs:
 *
 * 1. Breathing logo fades in (1.5s easeInOut starting at 0.5s).
 * 2. Quote line fades in (2.0s easeInOut at 2.5s).
 * 3. Seven footprints fade up in bottom-to-top order (0.9s stride
 *    starting at 3.5s); each fades back to 0.12 opacity 1.5s after
 *    appearing (3.0s for the last one before the button reveals).
 * 4. Begin button slides up + fades in after the last footprint
 *    (≈ 10.6s elapsed).
 * 5. Privacy promise + ambient yellow drift after the button.
 *
 * Reduce-motion: every stage is set to its terminal value immediately
 * on entrance — no animation runs. Matches iOS's `UIAccessibility
 * .isReduceMotionEnabled` short-circuit.
 *
 * Tapping Begin runs the exit sequence (logo / quote / footprints fade
 * out in reverse, ≈1.2s) then calls [onBegin]. The view-model writes
 * `welcomeCompleted = true` before [onBegin] so the route swap on the
 * next composition picks Permissions instead of returning to Welcome.
 */
@Composable
fun WelcomeScreen(
    onBegin: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WelcomeViewModel = hiltViewModel(),
) {
    val reduceMotion = LocalReduceMotion.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    val showLogo = remember { mutableStateOf(reduceMotion) }
    val isBreathing = remember { mutableStateOf(reduceMotion) }
    val showQuote = remember { mutableStateOf(reduceMotion) }
    val footprintOpacities = remember {
        mutableStateListOf<Float>().apply { repeat(7) { add(if (reduceMotion) 1f else 0f) } }
    }
    val showButton = remember { mutableStateOf(reduceMotion) }
    val showAmbient = remember { mutableStateOf(reduceMotion) }
    var isExiting by remember { mutableStateOf(false) }

    LaunchedEffect(reduceMotion) {
        if (reduceMotion) return@LaunchedEffect
        delay(500)
        if (!isExiting) showLogo.value = true
        delay(1500)
        if (!isExiting) isBreathing.value = true
        delay(500)
        if (!isExiting) showQuote.value = true
        delay(1000)
        // Footprints bottom-to-top — index 6 first, index 0 last.
        for (orderIndex in 0 until 7) {
            if (isExiting) return@LaunchedEffect
            val footIndex = 6 - orderIndex
            footprintOpacities[footIndex] = 1f
            runCatching {
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
            }
            // Schedule the fade-down branch concurrently.
            scope.launch {
                val fadeDelay = if (footIndex == 0) 3000L else 1500L
                delay(fadeDelay)
                if (!isExiting) footprintOpacities[footIndex] = 0.12f
            }
            delay(900)
        }
        if (!isExiting) {
            runCatching {
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
            }
            showButton.value = true
        }
        delay(500)
        if (!isExiting) showAmbient.value = true
    }

    val onTap = {
        if (!isExiting) {
            isExiting = true
            scope.launch {
                if (reduceMotion) {
                    delay(300)
                } else {
                    isBreathing.value = false
                    showButton.value = false
                    delay(100)
                    for (i in footprintOpacities.indices) footprintOpacities[i] = 0f
                    delay(200)
                    showQuote.value = false
                    delay(200)
                    showLogo.value = false
                    delay(700)
                }
                viewModel.completeWelcome()
                onBegin()
            }
        }
    }

    val quote = remember { viewModel.pickQuote() }
    val quoteText = stringArrayResource(R.array.welcome_quote_pool).getOrElse(quote) { "" }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(pilgrimColors.parchment)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        if (showAmbient.value && !reduceMotion) {
            AmbientYellowDrift()
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            val logoAnim by animateFloatAsState(
                targetValue = if (showLogo.value) 1f else 0f,
                animationSpec = tween(durationMillis = 1500),
                label = "logo-fade",
            )
            // #43 Wander Zoom: on Begin the logo zooms 1.0 → 1.4 (easeOut,
            // 0.4s) as it fades — a "step into the journey" beat. Skipped
            // under reduce-motion (the exit is an instant 300ms hold there).
            val exitZoom by animateFloatAsState(
                targetValue = welcomeLogoExitZoom(isExiting, reduceMotion),
                animationSpec = tween(durationMillis = 400, easing = LinearOutSlowInEasing),
                label = "logo-exit-zoom",
            )
            Box(
                modifier = Modifier
                    .graphicsLayer {
                        alpha = logoAnim
                        val scale = (0.85f + 0.15f * logoAnim) * exitZoom
                        scaleX = scale
                        scaleY = scale
                    },
            ) {
                BreathingLogo(size = 120.dp, reducedMotion = reduceMotion || !isBreathing.value)
            }
            Spacer(Modifier.height(28.dp))
            val quoteAnim by animateFloatAsState(
                targetValue = if (showQuote.value) 1f else 0f,
                animationSpec = tween(durationMillis = 1500),
                label = "quote-fade",
            )
            Text(
                text = quoteText,
                style = pilgrimType.displayMedium,
                color = pilgrimColors.fog,
                textAlign = TextAlign.Center,
                // iOS parity: WelcomeView uses `.minimumScaleFactor(0.7)`
                // so a long quote shrinks to fit its two explicit lines
                // instead of soft-wrapping a word ("...thousand miles"
                // breaking after "miles"). maxLines = 2 honors the
                // single `\n` in the resource; autoSize shrinks the
                // glyphs down to ~70% before it would ever wrap.
                maxLines = 2,
                autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(
                    minFontSize = pilgrimType.displayMedium.fontSize * 0.7f,
                    maxFontSize = pilgrimType.displayMedium.fontSize,
                    stepSize = 0.5.sp,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { alpha = quoteAnim },
            )
            Spacer(Modifier.weight(1f))
            Footprints(opacities = footprintOpacities)
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.welcome_breath_cue),
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { alpha = quoteAnim * 0.85f },
            )
            Spacer(Modifier.height(16.dp))
            val buttonAnim by animateFloatAsState(
                targetValue = if (showButton.value) 1f else 0f,
                animationSpec = tween(durationMillis = 1500),
                label = "button-fade",
            )
            val buttonOffsetY by animateFloatAsState(
                targetValue = if (showButton.value) 0f else 30f,
                animationSpec = tween(durationMillis = 1500),
                label = "button-offset",
            )
            Button(
                onClick = onTap,
                enabled = !isExiting,
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha = buttonAnim
                        translationY = buttonOffsetY * density
                    }
                    .semantics { contentDescription = "Begin your journey" },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = pilgrimColors.stone,
                    contentColor = pilgrimColors.parchment,
                ),
            ) {
                Text(
                    text = stringResource(R.string.welcome_begin_button),
                    style = pilgrimType.button,
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.welcome_privacy_promise),
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { alpha = buttonAnim * 0.7f },
            )
        }
    }
}

/** #43 Wander-Zoom factor: 1.4× on Begin, 1.0× at rest or under reduce-motion. */
internal fun welcomeLogoExitZoom(isExiting: Boolean, reduceMotion: Boolean): Float =
    if (isExiting && !reduceMotion) 1.4f else 1.0f

@Composable
private fun AmbientYellowDrift() {
    // 15s ease-in-out drift between two corners — gentle yellow radial
    // brighten matching iOS WelcomeView.startAmbientDrift().
    val transition = rememberInfiniteTransition(label = "ambient")
    val offsetX by transition.animateFloat(
        initialValue = 0f,
        targetValue = 0.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 15_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ambient-x",
    )
    val offsetY by transition.animateFloat(
        initialValue = 0f,
        targetValue = 0.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 15_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ambient-y",
    )
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .semantics { contentDescription = "" },
    ) {
        val cx = size.width * (0.5f + offsetX)
        val cy = size.height * (0.5f + offsetY)
        val brush = Brush.radialGradient(
            colors = listOf(Color(0xFFFFEB3B).copy(alpha = 0.03f), Color.Transparent),
            center = Offset(cx, cy),
            radius = 300.dp.toPx(),
        )
        drawRect(brush)
    }
}

@Composable
private fun Footprints(opacities: List<Float>) {
    val printColor = pilgrimColors.ink.copy(alpha = 0.18f)
    // 7 prints kept compact (18×28 + 2dp gaps ≈ 208dp) so the trail +
    // breath cue + Begin + privacy promise all fit above the gesture
    // inset on tall + short devices alike. iOS lets the VStack flex;
    // Android needs the explicit budget since the privacy line is the
    // last child and would otherwise clip off-screen.
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        opacities.forEachIndexed { index, opacity ->
            val isLeft = index % 2 != 0
            val visible = opacity > 0.5f
            val targetAlpha by animateFloatAsState(
                targetValue = opacity,
                animationSpec = tween(durationMillis = 800),
                label = "footprint-alpha-$index",
            )
            val targetScale by animateFloatAsState(
                targetValue = if (visible) 1f else 1.12f,
                animationSpec = tween(durationMillis = 800),
                label = "footprint-scale-$index",
            )
            Box(
                modifier = Modifier
                    .size(width = 18.dp, height = 28.dp)
                    .graphicsLayer {
                        alpha = targetAlpha
                        scaleX = (if (isLeft) -1f else 1f) * targetScale
                        scaleY = targetScale
                        rotationZ = if (isLeft) -12f else 12f
                        translationX = if (isLeft) -10f * density else 10f * density
                    },
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawFootprint(printColor)
                }
            }
        }
    }
}

private fun DrawScope.drawFootprint(color: Color) {
    // Reuse the canonical 10-ellipse footprint silhouette (heel +
    // outer edge + ball + 5 toes) shared with the About screen's
    // footprint trail — same shape iOS uses for both surfaces.
    drawPath(
        org.walktalkmeditate.pilgrim.ui.settings.about.FootprintShape
            .path(size.width, size.height),
        color,
    )
}

@HiltViewModel
class WelcomeViewModel @Inject constructor(
    private val onboardingPreferences: OnboardingPreferencesRepository,
) : ViewModel() {

    /**
     * Picks a 0..5 index into the welcome_quote_pool. The view reads
     * the resource array via [stringArrayResource] and selects this
     * index. Snapshotted once per VM lifetime via `remember` at the
     * call site so rotation doesn't re-randomize.
     */
    fun pickQuote(): Int = (0..5).random()

    fun completeWelcome() {
        viewModelScope.launch {
            runCatching { onboardingPreferences.markWelcomeCompleted() }
        }
    }
}
