// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.prompt

import androidx.compose.runtime.Immutable

enum class PlaceRole { Start, End }

@Immutable
data class PlaceContext(
    val name: String,
    val coordinate: LatLng,
    val role: PlaceRole,
)
