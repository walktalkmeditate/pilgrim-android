// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.location

import kotlinx.coroutines.flow.Flow
import org.walktalkmeditate.pilgrim.domain.LocationPoint

interface LocationSource {
    /**
     * Cold flow of device location points. Collection starts location
     * updates; cancellation stops them. Permission enforcement is the
     * caller's responsibility — this flow will throw SecurityException on
     * collect if fine-location permission is not granted.
     */
    fun locationFlow(): Flow<LocationPoint>
}
