// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.collective

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
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
import org.walktalkmeditate.pilgrim.data.share.DeviceTokenStore

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CollectiveCounterServiceTest {

    private lateinit var server: MockWebServer
    private lateinit var service: CollectiveCounterService

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val deviceTokenStore = DeviceTokenStore(context)
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val client = OkHttpClient.Builder()
            .callTimeout(5, TimeUnit.SECONDS)
            .build()
        service = CollectiveCounterService(
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

    @Test
    fun `fetch GET returns parsed CollectiveStats on 200`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"total_walks":42,"total_distance_km":12.5,
                       "total_meditation_min":30,"total_talk_min":15,
                       "last_walk_at":"2026-04-25T01:00:00Z",
                       "streak_days":3,"streak_date":"2026-04-25"}""".trimIndent(),
                ),
        )
        val stats = service.fetch()
        assertEquals(42, stats.totalWalks)
        assertEquals(12.5, stats.totalDistanceKm, 0.001)
        assertEquals(3, stats.streakDays)
    }

    @Test
    fun `fetch GET hits the counter endpoint`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"total_walks":0,"total_distance_km":0,
                   "total_meditation_min":0,"total_talk_min":0}""".trimIndent(),
            ),
        )
        service.fetch()
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals(CollectiveConfig.ENDPOINT, recorded.path)
    }

    @Test
    fun `fetch GET on 500 throws IOException`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody(""))
        try {
            service.fetch()
            fail("expected IOException")
        } catch (e: IOException) {
            assertNotNull(e)
        }
    }

    @Test
    fun `fetch GET on DISCONNECT_AT_START throws IOException`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        try {
            service.fetch()
            fail("expected IOException")
        } catch (e: IOException) {
            assertNotNull(e)
        }
    }

    @Test
    fun `post returns Success on 200`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val result = service.post(
            CollectiveCounterDelta(walks = 1, distanceKm = 2.5, meditationMin = 5, talkMin = 1),
        )
        assertEquals(PostResult.Success, result)
    }

    @Test
    fun `post sets X-Device-Token header`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        service.post(CollectiveCounterDelta(walks = 1, distanceKm = 1.0))
        val recorded = server.takeRequest()
        val token = recorded.getHeader("X-Device-Token")
        assertNotNull("X-Device-Token missing", token)
        assertTrue(
            "token not UUID shape: '$token'",
            token!!.matches(Regex("[0-9a-fA-F-]{36}")),
        )
    }

    @Test
    fun `post serializes delta with snake_case fields`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        service.post(
            CollectiveCounterDelta(
                walks = 2,
                distanceKm = 3.5,
                meditationMin = 7,
                talkMin = 4,
            ),
        )
        val recorded = server.takeRequest()
        val body = recorded.body.readUtf8()
        assertTrue("expected walks in body: $body", body.contains("\"walks\":2"))
        assertTrue("expected distance_km", body.contains("\"distance_km\":3.5"))
        assertTrue("expected meditation_min", body.contains("\"meditation_min\":7"))
        assertTrue("expected talk_min", body.contains("\"talk_min\":4"))
    }

    @Test
    fun `post hits the counter endpoint with POST`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        service.post(CollectiveCounterDelta(walks = 1, distanceKm = 1.0))
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals(CollectiveConfig.ENDPOINT, recorded.path)
    }

    @Test
    fun `post returns RateLimited on 429`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setBody("{}"))
        val result = service.post(CollectiveCounterDelta(walks = 1, distanceKm = 1.0))
        assertEquals(PostResult.RateLimited, result)
    }

    @Test
    fun `post returns Failed on 401`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        val result = service.post(CollectiveCounterDelta(walks = 1, distanceKm = 1.0))
        assertTrue("expected Failed, got $result", result is PostResult.Failed)
    }

    @Test
    fun `post returns Failed on 400`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("{}"))
        val result = service.post(CollectiveCounterDelta(walks = 1, distanceKm = 1.0))
        assertTrue("expected Failed, got $result", result is PostResult.Failed)
    }

    @Test
    fun `post returns Failed on disconnect`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val result = service.post(CollectiveCounterDelta(walks = 1, distanceKm = 1.0))
        assertTrue("expected Failed, got $result", result is PostResult.Failed)
    }
}
