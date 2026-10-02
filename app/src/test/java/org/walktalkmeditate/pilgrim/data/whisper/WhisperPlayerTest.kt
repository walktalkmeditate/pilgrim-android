// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.whisper

import android.app.Application
import android.media.AudioManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaPlayer
import org.walktalkmeditate.pilgrim.data.sounds.FakeSoundsPreferencesRepository

/**
 * An in-flight CDN download is the only "pending" phase a whisper has on
 * Android (there is no slot that holds one behind a voice-guide prompt).
 * These tests pin that stopping during that phase drops the whisper: it
 * never starts playing and never takes audio focus once the download
 * lands. The CDN base URL is a constant, so the request is held on a
 * latch by an application interceptor instead of a test server.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WhisperPlayerTest {

    private class HeldDownload(private val body: ByteArray) : Interceptor {
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)

        override fun intercept(chain: Interceptor.Chain): Response {
            entered.countDown()
            if (!released.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw IOException("test never released the held download")
            }
            return Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody("audio/aac".toMediaType()))
                .build()
        }
    }

    private lateinit var context: Application
    private lateinit var audioManager: AudioManager
    private lateinit var download: HeldDownload
    private lateinit var httpClient: OkHttpClient
    private lateinit var player: WhisperPlayer

    private val definition = WhisperDefinition(
        id = "held-whisper",
        title = "Held whisper",
        category = WhisperCategory.Presence,
        audioFileName = "held-whisper",
        durationSec = 4.0,
    )

    private val cachedFile: File
        get() = File(File(context.filesDir, "whispers"), "${definition.audioFileName}.aac")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        audioManager = context.getSystemService(AudioManager::class.java)
        ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo() }
        File(context.filesDir, "whispers").deleteRecursively()
        download = HeldDownload(body = ByteArray(256) { 1 })
        httpClient = OkHttpClient.Builder().addInterceptor(download).build()
        player = WhisperPlayer(
            context = context,
            httpClient = httpClient,
            soundsPreferences = FakeSoundsPreferencesRepository(initialSoundsEnabled = true),
        )
    }

    @After
    fun tearDown() {
        download.released.countDown()
        player.stop()
        httpClient.dispatcher.cancelAll()
        ShadowMediaPlayer.resetStaticState()
    }

    @Test
    fun `a download that lands starts the whisper and takes focus`() {
        player.play(definition)
        awaitDownloadInFlight()

        download.released.countDown()
        awaitCondition("the whisper starts once its download lands") {
            player.isAnyChannelPlaying.value
        }

        assertNotNull(
            "a started whisper requests duck focus",
            shadowOf(audioManager).lastAudioFocusRequest,
        )
    }

    @Test
    fun `stop while a whisper download is in flight never starts it`() {
        player.play(definition)
        awaitDownloadInFlight()

        player.stop()
        download.released.countDown()
        awaitDownloadSettled()

        assertFalse(
            "a whisper stopped while downloading must not start",
            player.isAnyChannelPlaying.value,
        )
        assertNull(
            "a whisper stopped while downloading must not take focus",
            shadowOf(audioManager).lastAudioFocusRequest,
        )
        assertFalse("the cancelled download is dropped", cachedFile.exists())
    }

    @Test
    fun `stopPreviewOnly while a preview download is in flight never starts it`() {
        player.preview(definition)
        awaitDownloadInFlight()

        player.stopPreviewOnly()
        download.released.countDown()
        awaitDownloadSettled()

        assertFalse(
            "a preview stopped while downloading must not start",
            player.isPlaying.value,
        )
        assertFalse(player.isAnyChannelPlaying.value)
        assertNull(
            "a preview stopped while downloading must not take focus",
            shadowOf(audioManager).lastAudioFocusRequest,
        )
    }

    private fun awaitDownloadInFlight() {
        assertTrue(
            "the whisper download never reached the network layer",
            download.entered.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS),
        )
    }

    /**
     * The cancelled call's callback has run once OkHttp reports no
     * running calls; after that nothing can resume the download
     * coroutine with a response.
     */
    private fun awaitDownloadSettled() {
        awaitCondition("the held download never settled") {
            httpClient.dispatcher.runningCallsCount() == 0
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun awaitCondition(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(LATCH_TIMEOUT_SECONDS)
        while (!condition()) {
            if (System.nanoTime() > deadline) throw AssertionError(message)
            Thread.sleep(POLL_INTERVAL_MS)
        }
    }

    private companion object {
        const val LATCH_TIMEOUT_SECONDS = 10L
        const val POLL_INTERVAL_MS = 5L
    }
}
