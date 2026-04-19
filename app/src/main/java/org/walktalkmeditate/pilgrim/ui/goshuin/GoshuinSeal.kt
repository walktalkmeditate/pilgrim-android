// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.goshuin

import java.time.LocalDate
import org.walktalkmeditate.pilgrim.ui.design.seals.SealSpec

/**
 * Per-walk row model for the goshuin grid. The VM builds one per
 * finished walk. The `@Composable` cell resolves [SealSpec.ink] via
 * [org.walktalkmeditate.pilgrim.ui.theme.seasonal.SeasonalColorEngine]
 * using [walkDate] + the current hemisphere — the VM can't do it
 * because theme reads require `@Composable` scope.
 *
 * Mirrors [org.walktalkmeditate.pilgrim.ui.home.HomeWalkRow] in
 * spirit: precompute what the VM can, defer theme-dependent work to
 * composition.
 */
data class GoshuinSeal(
    val walkId: Long,
    /** `ink = Color.Transparent` placeholder; resolved in [GoshuinScreen]. */
    val sealSpec: SealSpec,
    /** Device-local date of the walk, used for the seasonal ink shift. */
    val walkDate: LocalDate,
    /** Pre-formatted short date for the caption, e.g., `"Apr 19"`. */
    val shortDateLabel: String,
)
