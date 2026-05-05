// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.design

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Single source of truth for Composition-time animation gating across
 * Stage 14-A → 14-D animation entry points. Provided in `PilgrimTheme`
 * via `rememberReducedMotion()`.
 *
 * `JournalHapticDispatcher` deliberately does NOT consume this Local —
 * it reads `Settings.Global` at handler-time so Quick-Settings flips
 * mid-scroll take effect on the next dispatch.
 */
val LocalReduceMotion: ProvidableCompositionLocal<Boolean> =
    staticCompositionLocalOf { false }
