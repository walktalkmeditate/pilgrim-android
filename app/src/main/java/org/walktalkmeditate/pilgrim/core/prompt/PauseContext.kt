// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.prompt

import androidx.compose.runtime.Immutable

/**
 * One paused stretch of the walk (iOS `PauseContext@9a418e4`). Android
 * derives these from `PAUSED`/`RESUMED` event pairs — there is no pause
 * entity (spec D4).
 */
@Immutable
data class PauseContext(
    val startDate: Long,
    val durationSeconds: Long,
)
