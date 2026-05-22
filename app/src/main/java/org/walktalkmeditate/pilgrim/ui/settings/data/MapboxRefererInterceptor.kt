// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.data

import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The Journey Viewer/Editor web apps render walks on a Mapbox-GL map
 * whose access token is **referrer-restricted** to the pilgrimapp.org
 * origins. iOS WKWebView sends the page `Referer` on the tile/style
 * `fetch()` requests, so Mapbox returns 200. Android System WebView
 * does NOT send it for these cross-origin sub-resource fetches, so
 * Mapbox returns 403 — surfaced to the page as `net::ERR_FAILED` (a
 * 403 without CORS headers reads as a CORS failure), leaving the map
 * blank ("white screen") while the page chrome renders fine.
 *
 * Verified 2026-05-21:
 *   curl …vector.pbf?access_token=pk… (no Referer)            → 403
 *   curl …vector.pbf … -H 'Referer: https://view.pilgrimapp.org/' → 200
 *
 * This re-issues every `*.mapbox.com` GET through OkHttp with the
 * page's own origin as `Referer`, then hands the bytes back to the
 * WebView with `Access-Control-Allow-Origin: *` so the page's `fetch`
 * accepts the cross-origin response. OkHttp transparently handles
 * gzip, so the returned stream is the decoded body.
 */
internal object MapboxRefererInterceptor {

    private val client: OkHttpClient by lazy { OkHttpClient() }

    /**
     * @param pageOrigin the loading page's origin with trailing slash
     *   (e.g. `https://view.pilgrimapp.org/`) — used as the `Referer`
     *   Mapbox's token restriction requires.
     * @return a re-fetched response for Mapbox hosts, or null to let
     *   the WebView load the request normally.
     */
    fun maybeIntercept(request: WebResourceRequest, pageOrigin: String): WebResourceResponse? {
        if (!request.method.equals("GET", ignoreCase = true)) return null
        val host = request.url.host ?: return null
        if (host != "api.mapbox.com" && !host.endsWith(".mapbox.com")) return null

        return try {
            val builder = Request.Builder().url(request.url.toString())
            request.requestHeaders.forEach { (key, value) ->
                // Drop headers we set ourselves / that OkHttp owns.
                if (!key.equals("Referer", ignoreCase = true) &&
                    !key.equals("Host", ignoreCase = true) &&
                    !key.equals("Accept-Encoding", ignoreCase = true)
                ) {
                    builder.addHeader(key, value)
                }
            }
            builder.header("Referer", pageOrigin)

            val response = client.newCall(builder.get().build()).execute()
            Log.d("MapboxReferer", "intercepted ${request.url.host}${request.url.encodedPath} -> ${response.code}")
            val body = response.body ?: run { response.close(); return null }
            val contentType = response.header("Content-Type") ?: "application/octet-stream"
            val mime = contentType.substringBefore(';').trim().ifEmpty { "application/octet-stream" }
            val charset = CHARSET_REGEX.find(contentType)?.groupValues?.getOrNull(1)?.trim()

            val headers = linkedMapOf(
                "Access-Control-Allow-Origin" to "*",
                "Access-Control-Allow-Headers" to "*",
            )
            response.header("Cache-Control")?.let { headers["Cache-Control"] = it }

            WebResourceResponse(
                mime,
                charset,
                response.code,
                response.message.ifEmpty { "OK" },
                headers,
                // WebView reads this stream; closing it closes the OkHttp
                // response. byteStream() stays valid until fully read.
                body.byteStream(),
            )
        } catch (_: Throwable) {
            // Fall back to the WebView's own load on any failure — no
            // worse than the current (failing) behavior.
            null
        }
    }

    private val CHARSET_REGEX = Regex("charset=([^;]+)", RegexOption.IGNORE_CASE)
}
