// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.whisper

import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.walktalkmeditate.pilgrim.data.share.DeviceTokenSource

/**
 * Test fake for [WhisperService]. Inherits the constructor to satisfy
 * `@Inject` plumbing (test cases never exercise the HTTP path on this
 * code path), and overrides the network suspend to short-circuit. The
 * `OkHttpClient` / `DeviceTokenSource` / `Json` args go to the
 * superclass and are never used because [placeWhisper] is overridden.
 */
open class FakeWhisperService : WhisperService(
    httpClient = OkHttpClient(),
    deviceTokenStore = object : DeviceTokenSource {
        override suspend fun getToken(): String = "fake-token"
    },
    json = Json,
) {
    var placeCalls: Int = 0

    override suspend fun placeWhisper(
        latitude: Double,
        longitude: Double,
        whisperId: String,
        category: WhisperCategory,
        expiry: ExpiryDuration,
    ): PlaceWhisperResult {
        placeCalls += 1
        return PlaceWhisperResult(id = "fake-whisper-id")
    }
}
