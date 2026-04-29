// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.feedback

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.walktalkmeditate.pilgrim.data.share.DeviceTokenSource

class FeedbackServiceTest {

    private lateinit var server: MockWebServer
    private lateinit var service: FeedbackService

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val client = OkHttpClient.Builder().build()
        val tokenStore = FakeDeviceTokenSource(token = "test-token-uuid")
        // explicitNulls=false matches the project-wide Json config in NetworkModule.
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        service = FeedbackService(
            httpClient = client,
            deviceTokenStore = tokenStore,
            json = json,
            baseUrl = server.url("/").toString().trimEnd('/'),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `success path posts JSON body with X-Device-Token header`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        service.submit(
            category = "bug",
            message = "Hello from Android",
            deviceInfo = "Android 14 · Pixel 8 · v0.1.0",
        )

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/feedback", recorded.path)
        assertEquals("test-token-uuid", recorded.getHeader("X-Device-Token"))
        assertEquals("application/json; charset=utf-8", recorded.getHeader("Content-Type"))
        val body = recorded.body.readUtf8()
        assertTrue("body should contain category: $body", body.contains("\"category\":\"bug\""))
        assertTrue("body should contain message: $body", body.contains("\"message\":\"Hello from Android\""))
        assertTrue("body should contain deviceInfo: $body", body.contains("\"deviceInfo\":"))
    }

    @Test
    fun `omits deviceInfo when null`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        service.submit(category = "feature", message = "msg", deviceInfo = null)

        val body = server.takeRequest().body.readUtf8()
        assertFalse("body should not contain deviceInfo: $body", body.contains("deviceInfo"))
    }

    @Test(expected = FeedbackError.RateLimited::class)
    fun `429 maps to RateLimited`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429))
        service.submit(category = "bug", message = "m", deviceInfo = null)
    }

    @Test
    fun `5xx maps to ServerError with status code`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        try {
            service.submit(category = "bug", message = "m", deviceInfo = null)
            error("should have thrown")
        } catch (e: FeedbackError.ServerError) {
            assertEquals(503, e.statusCode)
        }
    }

    @Test(expected = FeedbackError.NetworkError::class)
    fun `IOException maps to NetworkError`() = runTest {
        // Shutting down the server forces a connection failure on the next call.
        server.shutdown()
        service.submit(category = "bug", message = "m", deviceInfo = null)
    }
}

private class FakeDeviceTokenSource(private val token: String) : DeviceTokenSource {
    override suspend fun getToken(): String = token
}
