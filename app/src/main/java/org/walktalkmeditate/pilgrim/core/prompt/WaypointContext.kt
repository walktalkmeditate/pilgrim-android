// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.prompt

import androidx.compose.runtime.Immutable

@Immutable
data class WaypointContext(
    val label: String,
    val icon: String?,
    val timestamp: Long,
    val coordinate: LatLng,
)
