// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.cairn

import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.walktalkmeditate.pilgrim.data.share.DeviceTokenSource

open class FakeCairnService : CairnService(
    httpClient = OkHttpClient(),
    deviceTokenStore = object : DeviceTokenSource {
        override suspend fun getToken(): String = "fake-token"
    },
    json = Json,
) {
    var placeCalls: Int = 0

    override suspend fun placeStone(
        latitude: Double,
        longitude: Double,
    ): PlaceStoneResult {
        placeCalls += 1
        return PlaceStoneResult(id = "fake-cairn-id", stoneCount = 1)
    }
}
