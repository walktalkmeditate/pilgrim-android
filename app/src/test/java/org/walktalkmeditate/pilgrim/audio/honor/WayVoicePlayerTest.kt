// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio.honor

import android.app.Application
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.net.Uri
import android.os.Looper
import androidx.media3.common.C
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * [ExoPlayerWayVoicePlayer]'s focus, noisy receiver, rate, and identity
 * guards, with the real `AudioFocusRequest` under Robolectric and a fake
 * track in place of ExoPlayer; the real track and its `MediaItem` are
 * built once too (the house builder rule).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WayVoicePlayerTest {

    private lateinit var context: Application
    private lateinit var audioManager: AudioManager
    private lateinit var player: ExoPlayerWayVoicePlayer
    private lateinit var file: File
    private val tracks = mutableListOf<FakeTrack>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        audioManager = context.getSystemService(AudioManager::class.java)
        player = ExoPlayerWayVoicePlayer(context, audioManager) { file, volume, rate, events ->
            FakeTrack(file, volume, rate, events).also { tracks += it }
        }
        file = File(context.cacheDir, "way-voice.wav").apply { writeBytes(ByteArray(64) { 1 }) }
    }

    @After
    fun tearDown() {
        player.stop()
        idle()
        file.delete()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun focus() = shadowOf(audioManager).lastAudioFocusRequest

    // Builders

    @Test
    fun `its focus request is its own may-duck request for spoken media`() {
        player.play(file, 0.8f, Reports())

        val request = focus().audioFocusRequest
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, request.focusGain)
        assertEquals(AudioAttributes.USAGE_MEDIA, request.audioAttributes.usage)
        assertEquals(AudioAttributes.CONTENT_TYPE_SPEECH, request.audioAttributes.contentType)
    }

    @Test
    fun `the real track builds its MediaItem and spoken-media attributes, and starts and stops without a crash`() {
        assertEquals(Uri.fromFile(file), ExoPlayerWayVoiceTrack.wayVoiceMediaItem(file).localConfiguration!!.uri)
        val attributes = ExoPlayerWayVoiceTrack.playerAttributes()
        assertEquals(C.USAGE_MEDIA, attributes.usage)
        assertEquals(C.AUDIO_CONTENT_TYPE_SPEECH, attributes.contentType)

        val real = ExoPlayerWayVoicePlayer(context, audioManager)
        real.setRate(1.5f)
        real.play(file, 0.8f, Reports())
        real.seek(0.5)
        real.pause()
        real.resume()
        real.stop()
        idle()
        assertFalse(real.isPlaying)
    }

    // Platform handlers (owner decision 2)

    @Test
    fun `a transient focus loss pauses the voice, and the regain resumes it`() {
        player.play(file, 0.8f, Reports())

        focus().listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertTrue(tracks.single().paused)
        assertFalse(player.isPlaying)

        focus().listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertFalse(tracks.single().paused)
        assertTrue(player.isPlaying)
    }

    @Test
    fun `a pause during a transient loss is the caller's word, so the regain leaves it paused`() {
        player.play(file, 0.8f, Reports())
        focus().listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)

        player.pause()
        focus().listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)

        assertTrue(tracks.single().paused)
    }

    @Test
    fun `a permanent focus loss stops the voice, reports it ended, and the next play asks for focus again`() {
        val reports = Reports()
        player.play(file, 0.8f, reports)
        val first = focus()

        first.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)

        assertTrue(tracks.single().released)
        assertEquals(1, reports.ended)
        assertSame(first.audioFocusRequest, shadowOf(audioManager).lastAbandonedAudioFocusRequest)

        player.play(file, 0.8f, Reports())
        assertNotSame(first.audioFocusRequest, focus().audioFocusRequest)
    }

    @Test
    fun `a callback from a focus request given up changes nothing`() {
        player.play(file, 0.8f, Reports())
        val stale = focus().listener
        stale.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        player.play(file, 0.8f, Reports())

        stale.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)

        assertFalse(tracks.last().paused)
    }

    @Test
    fun `becoming noisy pauses the voice, and nothing but a resume starts it again`() {
        player.play(file, 0.8f, Reports())

        context.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        idle()
        assertTrue(tracks.single().paused)

        focus().listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertTrue(tracks.single().paused)

        player.resume()
        assertFalse(tracks.single().paused)
    }

    @Test
    fun `a may-duck loss leaves the voice playing, as iOS mixes it with other audio`() {
        player.play(file, 0.8f, Reports())

        focus().listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)

        assertFalse(tracks.single().paused)
        assertTrue(player.isPlaying)
    }

    // Plays, ends, identity

    @Test
    fun `a replacing play keeps the focus, and the replaced play never reports`() {
        val replaced = Reports()
        player.play(file, 0.8f, replaced)
        val held = focus()

        player.play(file, 0.4f, Reports())

        assertSame(held, focus())
        assertTrue(tracks.first().released)
        tracks.first().events.onEnded()
        assertEquals(0, replaced.ended + replaced.failed)
        assertEquals(0.4f, tracks.last().volume, 0f)
    }

    @Test
    fun `a natural end reports once and gives the focus back`() {
        val reports = Reports()
        player.play(file, 0.8f, reports)

        tracks.single().events.onEnded()
        tracks.single().events.onEnded()

        assertEquals(1, reports.ended)
        assertNotNull(shadowOf(audioManager).lastAbandonedAudioFocusRequest)
        assertFalse(player.isPlaying)
    }

    @Test
    fun `stop reports nothing and gives the focus back`() {
        val reports = Reports()
        player.play(file, 0.8f, reports)

        player.stop()
        idle()

        assertTrue(tracks.single().released)
        assertEquals(0, reports.ended + reports.failed)
        assertNotNull(shadowOf(audioManager).lastAbandonedAudioFocusRequest)
    }

    @Test
    fun `a missing file reports failed on a later turn`() {
        val reports = Reports()

        player.play(File(context.cacheDir, "gone.wav"), 0.8f, reports)
        assertEquals("never inside play", 0, reports.failed)
        idle()

        assertEquals(1, reports.failed)
        assertTrue(tracks.isEmpty())
        assertFalse(player.isPlaying)
    }

    @Test
    fun `a denied focus fails the play`() {
        shadowOf(audioManager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        val reports = Reports()

        player.play(file, 0.8f, reports)
        idle()

        assertEquals(1, reports.failed)
        assertTrue(tracks.isEmpty())
    }

    @Test
    fun `the rate outlives the walk, so the next walk's first voice starts at the last one (iOS C-D5, pilgrim-ios #105)`() {
        player.play(file, 0.8f, Reports())
        player.setRate(2f)
        assertEquals(2f, tracks.single().speed, 0f)
        player.stop()

        player.play(file, 0.8f, Reports())

        assertEquals(2f, player.rate, 0f)
        assertEquals(2f, tracks.last().speed, 0f)
    }

    @Test
    fun `a scrub is clamped short of the end`() {
        player.play(file, 0.8f, Reports())

        player.seek(1.0)
        assertEquals(ExoPlayerWayVoicePlayer.MAX_SEEK_FRACTION, tracks.single().seekedTo!!, 0.0)
        player.seek(-1.0)
        assertEquals(0.0, tracks.single().seekedTo!!, 0.0)
    }

    private class Reports : WayVoicePlaybackListener {
        var ended = 0
        var failed = 0

        override fun onEnded() {
            ended += 1
        }

        override fun onFailed() {
            failed += 1
        }
    }

    private class FakeTrack(
        val file: File,
        val volume: Float,
        var speed: Float,
        val events: WayVoiceTrack.Events,
    ) : WayVoiceTrack {
        var paused = false
        var released = false
        var seekedTo: Double? = null

        override fun pause() {
            paused = true
        }

        override fun resume() {
            paused = false
        }

        override fun seekTo(fraction: Double) {
            seekedTo = fraction
        }

        override fun setRate(rate: Float) {
            speed = rate
        }

        override fun positionMillis(): Long = 0L

        override fun release() {
            released = true
        }
    }
}
