// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.location

import kotlinx.coroutines.flow.Flow
import org.walktalkmeditate.pilgrim.domain.LocationPoint

interface LocationSource {
    /**
     * Cold flow of device location points. Collection starts location
     * updates; cancellation stops them.
     *
     * **Permission contract**: the caller must hold `ACCESS_FINE_LOCATION`
     * (and `ACCESS_BACKGROUND_LOCATION` when collecting from a non-visible
     * process) before collecting this flow. Without those permissions,
     * collect will raise [SecurityException] at the platform layer — this
     * interface does not defensively check, because doing so would mask
     * the real call site in stack traces.
     */
    fun locationFlow(): Flow<LocationPoint>
}
