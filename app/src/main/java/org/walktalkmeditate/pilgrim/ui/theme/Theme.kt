// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import org.walktalkmeditate.pilgrim.data.appearance.AppearanceMode
import org.walktalkmeditate.pilgrim.ui.design.LocalReduceMotion
import org.walktalkmeditate.pilgrim.ui.design.rememberReducedMotion

@Composable
fun PilgrimTheme(
    appearanceMode: AppearanceMode = AppearanceMode.System,
    hemisphere: org.walktalkmeditate.pilgrim.ui.theme.seasonal.Hemisphere =
        org.walktalkmeditate.pilgrim.ui.theme.seasonal.Hemisphere.Northern,
    today: java.time.LocalDate = java.time.LocalDate.now(),
    content: @Composable () -> Unit,
) {
    // Resolve appearance preference -> dark/light flag. `System` defers
    // to the platform via `isSystemInDarkTheme()`; `Light`/`Dark` force
    // the theme regardless of system setting. `Constellation` also
    // resolves to dark (iOS `AppearanceMode.constellation.resolvedScheme
    // == .dark`) but applies the constellation override on top.
    val darkTheme = when (appearanceMode) {
        AppearanceMode.System -> isSystemInDarkTheme()
        AppearanceMode.Light -> false
        AppearanceMode.Dark -> true
        AppearanceMode.Constellation -> true
    }
    val constellation = appearanceMode.isConstellation

    // Cache the PilgrimColors AND PilgrimTypography instances across
    // recompositions. Without `remember`, every PilgrimTheme recomposition
    // would allocate fresh instances. LocalPilgrimColors /
    // LocalPilgrimDarkTheme / LocalIsConstellation are `compositionLocalOf`
    // (NOT static): the appearance is runtime-switchable, and a static
    // local would leave back-stack screens stranded on the stale palette
    // because the static-local change only re-runs the currently-composing
    // provider subtree — a screen retained on the Navigation back stack
    // keeps its old-theme composition slots and renders with the wrong
    // colors when popped back. `compositionLocalOf` invalidates every
    // reader on an appearance flip, which is correct and cheap here:
    // [PilgrimColors] is `@Stable` and the flip is rare. (LocalPilgrim-
    // Typography stays static — typography has no runtime switch.) Key
    // the colors `remember` on `darkTheme`, `hemisphere`, `today`, AND
    // `constellation` so a constellation flip rebuilds the palette with
    // the indigo override applied. iOS parity
    // `Color.swift@db4196e` wraps every static getter in
    // `SeasonalColorEngine.seasonalColor` so the whole app picks up
    // season-driven hue/saturation/brightness shifts; we replicate that
    // here at theme-construction time via [pilgrimSeasonalColors] and
    // then layer the constellation override on top (which BYPASSES the
    // seasonal shift for the 5 pinned tokens per iOS v1.6.0
    // `constellationOverride`).
    val colors = remember(darkTheme, hemisphere, today, constellation) {
        val base = if (darkTheme) pilgrimDarkColors() else pilgrimLightColors()
        val shifted = pilgrimSeasonalColors(base, today, hemisphere)
        if (constellation) pilgrimConstellationOverride(shifted) else shifted
    }
    val type = remember { pilgrimTypography() }

    // `outline` is consumed by Material3's OutlinedButton (and TextField
    // borders). Left unmapped, it falls back to M3's default cool
    // purple-gray, which clashes with Pilgrim's warm earth palette.
    // Stone-at-40% reads as a muted secondary-button border. Memoize on
    // `colors` (which itself is keyed on `darkTheme`) so unrelated
    // PilgrimTheme recompositions don't reallocate the 30+ field
    // ColorScheme.
    val m3 = remember(colors) {
        if (darkTheme) {
            darkColorScheme(
                primary = colors.stone,
                onPrimary = colors.parchment,
                background = colors.parchment,
                onBackground = colors.ink,
                surface = colors.parchmentSecondary,
                onSurface = colors.ink,
                surfaceVariant = colors.parchmentTertiary,
                outline = colors.stone.copy(alpha = 0.4f),
                error = colors.rust,
            )
        } else {
            lightColorScheme(
                primary = colors.stone,
                onPrimary = colors.parchment,
                background = colors.parchment,
                onBackground = colors.ink,
                surface = colors.parchmentSecondary,
                onSurface = colors.ink,
                surfaceVariant = colors.parchmentTertiary,
                outline = colors.stone.copy(alpha = 0.4f),
                error = colors.rust,
            )
        }
    }

    // Build a fresh M3 Typography rather than copying MaterialTheme.typography:
    // PilgrimTheme owns the full M3 typography mapping, and a `.copy()` would
    // capture whichever Typography is in scope at this call site. If PilgrimTheme
    // is ever nested (Compose previews, tests), the outer Typography would be
    // an already-Pilgrim-customized one and `.copy()` would re-stack onto itself.
    // Constructing directly leaves the unmapped slots (displaySmall, headline*,
    // titleMedium, titleSmall, bodySmall) at the M3 default — the same values
    // a fresh root MaterialTheme would expose.
    val m3Typography = remember(type) {
        Typography(
            displayLarge = type.displayLarge,
            displayMedium = type.displayMedium,
            titleLarge = type.heading,
            bodyLarge = type.body,
            bodyMedium = type.body,
            labelLarge = type.button,
            labelMedium = type.caption,
            labelSmall = type.micro,
        )
    }

    val reducedMotion = rememberReducedMotion()

    CompositionLocalProvider(
        LocalPilgrimColors provides colors,
        LocalPilgrimDarkTheme provides darkTheme,
        LocalIsConstellation provides constellation,
        LocalPilgrimHemisphere provides hemisphere,
        LocalPilgrimTypography provides type,
        LocalReduceMotion provides reducedMotion,
        // User-directed: Pilgrim has no Material tap ripples. Two
        // pieces wire this up:
        //  - LocalRippleConfiguration provides null → M3 components
        //    (Card / Button / IconButton / NavigationBarItem) skip
        //    their built-in ripples.
        //  - LocalIndication provides NoIndication → plain
        //    `Modifier.clickable { ... }` sites that don't pass an
        //    explicit `indication = null` also stay silent (e.g.
        //    WalkDot, scenery taps, settings rows that haven't been
        //    individually patched).
        // Visual feedback comes from each surface's own state-driven
        // animation (favicon's color tween, dot's existing radial
        // gradient + opacity, etc.).
        androidx.compose.material3.LocalRippleConfiguration provides null,
        androidx.compose.foundation.LocalIndication provides NoIndication,
    ) {
        MaterialTheme(
            colorScheme = m3,
            typography = m3Typography,
            content = content,
        )
    }
}

/**
 * No-op [androidx.compose.foundation.IndicationNodeFactory] — drops the
 * rectangular tap ripple that ships by default with `Modifier.clickable`.
 * Provided via `LocalIndication` at the Pilgrim theme root so every
 * clickable inherits transparency on tap. Per-surface state animations
 * remain the visual feedback channel.
 */
private object NoIndication : androidx.compose.foundation.IndicationNodeFactory {
    override fun create(
        interactionSource: androidx.compose.foundation.interaction.InteractionSource,
    ): androidx.compose.ui.node.DelegatableNode = NoIndicationNode()

    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = System.identityHashCode(this)
}

private class NoIndicationNode :
    androidx.compose.ui.Modifier.Node(),
    androidx.compose.ui.node.DrawModifierNode {
    override fun androidx.compose.ui.graphics.drawscope.ContentDrawScope.draw() {
        drawContent()
    }
}
