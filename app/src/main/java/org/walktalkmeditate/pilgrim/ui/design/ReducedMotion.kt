// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.design

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Reads the OS-level reduced-motion / animation-disabled preference.
 * Returns true when the user has disabled animations via
 * Settings → Accessibility → Remove animations (or
 * Developer options → Animation scale = Off).
 *
 * Used by [BreathingLogo] and [MoonPhaseGlyph] to skip continuous
 * animations that can cause vestibular discomfort. The check is
 * remembered for the composition's lifetime — toggling the setting
 * mid-session won't update until the screen recomposes from
 * elsewhere, which matches Android conventions.
 */
@Composable
internal fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        try {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.TRANSITION_ANIMATION_SCALE,
                1.0f,
            ) == 0.0f
        } catch (_: Settings.SettingNotFoundException) {
            false
        } catch (_: SecurityException) {
            // Some OEMs/test environments deny Settings.Global reads.
            false
        }
    }
}
