// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.collective

import kotlin.time.Duration.Companion.seconds

internal object CollectiveConfig {
    const val BASE_URL = "https://walk.pilgrimapp.org"
    const val ENDPOINT = "/api/counter"
    val FETCH_TTL = 216.seconds
    const val MAX_WALKS_PER_POST = 10
    const val MAX_DISTANCE_KM_PER_POST = 200.0
    const val MAX_DURATION_MIN_PER_POST = 480
}
