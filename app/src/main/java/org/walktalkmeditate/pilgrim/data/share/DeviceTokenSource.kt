// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.share

/**
 * Test seam over the persistent per-device UUID. Backed in production
 * by [DeviceTokenStore]; tests provide a fake without DataStore.
 */
interface DeviceTokenSource {
    suspend fun getToken(): String
}
