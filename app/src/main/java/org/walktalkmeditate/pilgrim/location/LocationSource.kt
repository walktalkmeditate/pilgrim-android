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

    /**
     * One-shot fetch of the system's last-known location. Returns null
     * when no cached fix is available (fresh install, GPS never
     * enabled, location services off). Used to seed the Active Walk
     * map's initial camera so the first paint lands near the user
     * rather than at Mapbox's global default.
     *
     * **Permission contract**: same as [locationFlow] — caller must
     * hold `ACCESS_FINE_LOCATION`. Without it, the underlying platform
     * call returns null (the one-shot API is more forgiving than the
     * streaming one: it doesn't throw SecurityException on a missing
     * permission, it just hands back null).
     */
    suspend fun lastKnownLocation(): LocationPoint?
}
