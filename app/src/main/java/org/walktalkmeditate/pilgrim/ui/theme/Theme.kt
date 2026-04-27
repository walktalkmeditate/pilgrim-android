// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import org.walktalkmeditate.pilgrim.data.appearance.AppearanceMode

@Composable
fun PilgrimTheme(
    appearanceMode: AppearanceMode = AppearanceMode.System,
    content: @Composable () -> Unit,
) {
    // Resolve appearance preference -> dark/light flag. `System` defers
    // to the platform via `isSystemInDarkTheme()`; `Light`/`Dark` force
    // the theme regardless of system setting.
    val darkTheme = when (appearanceMode) {
        AppearanceMode.System -> isSystemInDarkTheme()
        AppearanceMode.Light -> false
        AppearanceMode.Dark -> true
    }

    // Cache the PilgrimColors AND PilgrimTypography instances across
    // recompositions. Without `remember`, every PilgrimTheme recomposition
    // would allocate fresh instances and — because LocalPilgrimColors and
    // LocalPilgrimTypography are both staticCompositionLocalOf
    // (reference-equality) — invalidate every consumer in the entire app
    // tree. Key the colors `remember` on `darkTheme` so a flip between
    // light and dark still produces a fresh palette.
    val colors = remember(darkTheme) {
        if (darkTheme) pilgrimDarkColors() else pilgrimLightColors()
    }
    val type = remember { pilgrimTypography() }

    // `outline` is consumed by Material3's OutlinedButton (and TextField
    // borders). Left unmapped, it falls back to M3's default cool
    // purple-gray, which clashes with Pilgrim's warm earth palette.
    // Stone-at-40% reads as a muted secondary-button border. Memoize on
    // `colors` (which itself is keyed on `darkTheme`) so unrelated
    // PilgrimTheme recompositions don't reallocate the 30+ field
    // ColorScheme.
    val m3 = remember(colors, darkTheme) {
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

    val baseTypography = MaterialTheme.typography
    val m3Typography = remember(type, baseTypography) {
        baseTypography.copy(
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

    CompositionLocalProvider(
        LocalPilgrimColors provides colors,
        LocalPilgrimTypography provides type,
    ) {
        MaterialTheme(
            colorScheme = m3,
            typography = m3Typography,
            content = content,
        )
    }
}
