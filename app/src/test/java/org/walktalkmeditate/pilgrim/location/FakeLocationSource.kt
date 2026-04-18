// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.location

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.walktalkmeditate.pilgrim.domain.LocationPoint

/**
 * Test double for [LocationSource]. Defaults: empty flow, null last-
 * known. Set [lastKnown] explicitly when a test wants to seed the
 * map's initial camera from a cached fix.
 */
class FakeLocationSource(
    private val flow: Flow<LocationPoint> = emptyFlow(),
    var lastKnown: LocationPoint? = null,
) : LocationSource {
    override fun locationFlow(): Flow<LocationPoint> = flow
    override suspend fun lastKnownLocation(): LocationPoint? = lastKnown
}
