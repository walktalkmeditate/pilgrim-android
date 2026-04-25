// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.share

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ShareServiceTest {

    private lateinit var server: MockWebServer
    private lateinit var service: ShareService

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val deviceTokenStore = DeviceTokenStore(context)
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val client = OkHttpClient.Builder()
            .callTimeout(5, TimeUnit.SECONDS)
            .build()
        service = ShareService(
            client = client,
            json = json,
            deviceTokenStore = deviceTokenStore,
            baseUrl = server.url("").toString().trimEnd('/'),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
        File(context.filesDir, "datastore/share_device_token.preferences_pb").delete()
    }

    private fun samplePayload() = SharePayload(
        stats = SharePayload.Stats(distance = 1_000.0, activeDuration = 600.0),
        route = listOf(
            SharePayload.RoutePoint(45.0, -70.0, 0.0, 1_700_000_000L),
            SharePayload.RoutePoint(45.001, -70.001, 0.0, 1_700_000_060L),
        ),
        activityIntervals = emptyList(),
        expiryDays = 90,
        units = "metric",
        startDate = "2023-11-14T22:13:20Z",
        toggledStats = listOf("distance", "duration"),
    )

    @Test
    fun `share POST returns parsed ShareResult on 201`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setBody("""{"url":"https://walk.pilgrimapp.org/abc1234xyz","id":"abc1234xyz"}""")
                .addHeader("Content-Type", "application/json"),
        )
        val result = service.share(samplePayload())
        assertEquals("https://walk.pilgrimapp.org/abc1234xyz", result.url)
        assertEquals("abc1234xyz", result.id)
    }

    @Test
    fun `share POST sets X-Device-Token header`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(201)
                .setBody("""{"url":"https://x","id":"x"}"""),
        )
        service.share(samplePayload())
        val recorded = server.takeRequest()
        val token = recorded.getHeader("X-Device-Token")
        assertNotNull("X-Device-Token missing", token)
        assertTrue(
            "token not UUID shape: '$token'",
            token!!.matches(Regex("[0-9a-fA-F-]{36}")),
        )
    }

    @Test
    fun `share POST serializes payload with snake_case fields`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(201)
                .setBody("""{"url":"https://x","id":"x"}"""),
        )
        service.share(samplePayload())
        val recorded = server.takeRequest()
        val bodyText = recorded.body.readUtf8()
        // @SerialName snake_case check on a handful of fields
        assertTrue("expected expiry_days in body", bodyText.contains("\"expiry_days\":90"))
        assertTrue("expected toggled_stats in body", bodyText.contains("\"toggled_stats\""))
        assertTrue("expected start_date in body", bodyText.contains("\"start_date\""))
    }

    @Test
    fun `share 429 throws RateLimited`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setBody("{}"))
        try {
            service.share(samplePayload())
            fail("expected RateLimited")
        } catch (e: ShareError.RateLimited) {
            // Expected; ShareError.RateLimited is the object sentinel.
            assertEquals(ShareError.RateLimited, e)
        }
    }

    @Test
    fun `share 500 with error body throws ServerError with message`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(500).setBody("""{"error":"boom"}"""),
        )
        try {
            service.share(samplePayload())
            fail("expected ServerError")
        } catch (e: ShareError.ServerError) {
            assertEquals(500, e.code)
            assertEquals("boom", e.serverMessage)
        }
    }

    @Test
    fun `share 500 with garbage body falls back to Unknown error message`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("not-json"))
        try {
            service.share(samplePayload())
            fail("expected ServerError")
        } catch (e: ShareError.ServerError) {
            assertEquals(500, e.code)
            assertEquals("Unknown error", e.serverMessage)
        }
    }

    @Test
    fun `share on DISCONNECT_AT_START throws NetworkError`() = runBlocking {
        server.enqueue(
            MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START),
        )
        try {
            service.share(samplePayload())
            fail("expected NetworkError")
        } catch (e: ShareError.NetworkError) {
            assertNotNull(e.cause)
        }
    }
}
