// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.goshuin

import androidx.compose.runtime.Immutable
import java.time.LocalDate
import org.walktalkmeditate.pilgrim.data.entity.WalkFavicon
import org.walktalkmeditate.pilgrim.ui.design.seals.SealSpec

/**
 * Per-walk row model for the goshuin grid. The VM builds one per
 * finished walk. The `@Composable` cell resolves [SealSpec.ink] via
 * [org.walktalkmeditate.pilgrim.ui.theme.seasonal.SeasonalColorEngine]
 * using [walkDate] + the current hemisphere — the VM can't do it
 * because theme reads require `@Composable` scope.
 *
 * Precompute what the VM can, defer theme-dependent work to
 * composition.
 *
 * `@Immutable` is required because [walkDate] is [LocalDate], an
 * external JDK type not in Compose's default stable-class set. Without
 * the annotation, the Compose compiler marks the class Unstable and
 * [GoshuinSealCell] fails its skip check on any ancestor recomposition
 * (e.g., the hemisphere StateFlow re-emitting the same value). Matches
 * the `@Immutable` precedent on [SealSpec].
 */
@Immutable
data class GoshuinSeal(
    val walkId: Long,
    /** `ink = Color.Transparent` placeholder; resolved in [GoshuinScreen]. */
    val sealSpec: SealSpec,
    /** Device-local date of the walk, used for the seasonal ink shift. */
    val walkDate: LocalDate,
    /** Pre-formatted short date for the caption, e.g., `"Apr 19"`. */
    val shortDateLabel: String,
    /**
     * Stage 4-D: highest-precedence milestone for this walk (or `null`
     * when no milestone applies). Computed by
     * [GoshuinMilestones.detect] in the VM. The cell renders a halo
     * ring when non-null and uses [GoshuinMilestones.label] in place
     * of [shortDateLabel].
     */
    val milestone: GoshuinMilestone? = null,
    /**
     * v1.6.0 iOS-parity: the walk's mood tag (`Walk.favicon`), used by
     * [GoshuinScreen]'s filter bar (All / Transformative / Peaceful /
     * Extraordinary) and the favicon tint circle in
     * [GoshuinShareRenderer]. `null` = untagged (always shown under
     * "All"). Mirrors iOS `GoshuinView.filteredWalks` /
     * `GoshuinShareRenderer.tintColor(for:)`.
     */
    val favicon: WalkFavicon? = null,
    /** Walk UUID — share-renderer milestone map key. */
    val uuid: String = "",
    /** GPS distance in meters — share header stats + per-seal tint. */
    val distanceMeters: Double = 0.0,
    /** Walk start epoch millis — share seal-selection ordering + season. */
    val startMillis: Long = 0L,
)
