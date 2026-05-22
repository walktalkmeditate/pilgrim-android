// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.data

import android.app.Application
import android.net.Uri
import android.webkit.WebResourceRequest
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers the cheap early-return filtering. The re-fetch + WebResourceResponse
 * construction path is exercised against the live referrer-restricted Mapbox
 * endpoint and verified on-device (16 tile/style/font requests → HTTP 200,
 * map renders) — it requires a real `*.mapbox.com` host the hardcoded
 * predicate matches, so it isn't reproduced here with a localhost mock.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MapboxRefererInterceptorTest {

    private fun request(url: String, method: String = "GET") = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame(): Boolean = false
        override fun isRedirect(): Boolean = false
        override fun hasGesture(): Boolean = false
        override fun getMethod(): String = method
        override fun getRequestHeaders(): MutableMap<String, String> = mutableMapOf()
    }

    @Test
    fun `passes through non-mapbox hosts`() {
        assertNull(
            MapboxRefererInterceptor.maybeIntercept(
                request("https://example.com/tiles/1.pbf"),
                "https://view.pilgrimapp.org/",
            ),
        )
    }

    @Test
    fun `passes through non-GET mapbox requests`() {
        assertNull(
            MapboxRefererInterceptor.maybeIntercept(
                request("https://api.mapbox.com/map-sessions/v1", method = "POST"),
                "https://view.pilgrimapp.org/",
            ),
        )
    }
}
