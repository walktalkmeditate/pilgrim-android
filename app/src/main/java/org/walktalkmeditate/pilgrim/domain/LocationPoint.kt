// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain

import androidx.compose.runtime.Immutable

@Immutable
data class LocationPoint(
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
    val horizontalAccuracyMeters: Float? = null,
    val speedMetersPerSecond: Float? = null,
)
