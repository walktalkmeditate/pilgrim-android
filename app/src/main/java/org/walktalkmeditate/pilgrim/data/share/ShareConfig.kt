// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.share

/**
 * Stage 8-A: Share Worker endpoint configuration.
 *
 * Reuses the iOS `ShareService.baseURL` exactly so both platforms POST
 * to the same Cloudflare Worker + R2 bucket.
 */
internal object ShareConfig {
    const val BASE_URL = "https://walk.pilgrimapp.org"
    const val SHARE_ENDPOINT = "/api/share"

    /**
     * Hardcoded for 8-A; a future settings stage can replace with a
     * DataStore pref. The modal's displayed stats also use km, so both
     * sides are consistently metric today.
     */
    const val DEFAULT_UNITS = "metric"
    const val JOURNAL_MAX_LEN = 140
    const val ROUTE_MIN_POINTS = 2
    const val DOWNSAMPLE_TARGET_POINTS = 200
}
