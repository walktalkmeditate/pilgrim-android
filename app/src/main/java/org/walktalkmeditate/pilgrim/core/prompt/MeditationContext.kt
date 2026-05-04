// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.prompt

import androidx.compose.runtime.Immutable

@Immutable
data class MeditationContext(
    val startDate: Long,
    val endDate: Long,
    val durationSeconds: Long,
)
