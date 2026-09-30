// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio.soundscape

import android.app.Application
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The soundscape's volume decisions: the may-duck dip as it always was,
 * and a Way voice's duck, iOS's absolute level ramped over 0.5 s (parity
 * spec C §6, correction 12), on the main looper's clock.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SoundscapeLevelTest {

    private val knob = FakeKnob()
    private val level = SoundscapeLevel(Handler(Looper.getMainLooper()), knob)

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun idleFor(millis: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))

    private fun walkerAt(volume: Float) {
        level.setUserVolume(volume)
        idle()
    }

    @Test
    fun `with no Way voice the may-duck dip is as before, to three tenths of the walker's level at once`() {
        walkerAt(0.4f)

        level.onMayDuck(true)
        assertEquals(0.12f, knob.volume!!, 1e-6f)
        level.onMayDuck(false)
        assertEquals(0.4f, knob.volume!!, 1e-6f)
    }

    @Test
    fun `a Way voice's duck ramps the soundscape down over half a second`() {
        walkerAt(0.4f)

        level.holdDuck(0.15f)
        idle()
        assertEquals("the ramp starts from where it is", 0.4f, knob.volume!!, 1e-6f)
        idleFor(250)
        assertTrue("halfway down, got ${knob.volume}", knob.volume!! in 0.2f..0.35f)
        idleFor(300)

        assertEquals(0.15f, knob.volume!!, 1e-6f)
        assertEquals(0.15f, level.targetVolume, 0f)
    }

    @Test
    fun `while a Way voice holds the duck, the may-duck dip stands aside`() {
        walkerAt(0.4f)
        level.holdDuck(0.15f)
        idleFor(600)

        level.onMayDuck(true)
        assertEquals(0.15f, knob.volume!!, 1e-6f)
        level.onMayDuck(false)
        assertEquals(0.15f, knob.volume!!, 1e-6f)
    }

    @Test
    fun `a release ramps back up, and the regain after the voice gives up its focus re-aims it rather than jumping`() {
        walkerAt(0.4f)
        level.holdDuck(0.15f)
        idleFor(600)
        // The Way voice's own focus request dipped us, then its abandon regains.
        level.onMayDuck(true)

        level.releaseDuck(0.4f)
        idle()
        level.onMayDuck(false)
        assertEquals("no jump on the regain", 0.15f, knob.volume!!, 1e-6f)
        idleFor(250)
        assertTrue("on the way up, got ${knob.volume}", knob.volume!! in 0.2f..0.35f)
        idleFor(300)

        assertEquals(0.4f, knob.volume!!, 1e-6f)
    }

    @Test
    fun `a release straight after a hold never lifts the soundscape, as when a prompt hands the duck back`() {
        walkerAt(0.4f)
        level.holdDuck(0.15f)
        idleFor(600)

        level.releaseDuck(0.4f)
        level.holdDuck(0.15f)
        repeat(30) {
            idleFor(20)
            assertTrue("rose to ${knob.volume}", knob.volume!! <= 0.15f + 1e-6f)
        }
        assertEquals(0.15f, knob.volume!!, 1e-6f)
    }

    @Test
    fun `a soundscape started while a Way voice holds the duck plays at the walker's level (iOS C-D4, pilgrim-ios #104)`() {
        walkerAt(0.4f)
        knob.player = false
        level.holdDuck(0.15f)
        idle()
        assertEquals("with nothing playing only the target moves", 0.15f, level.targetVolume, 0f)

        knob.player = true
        level.onPlay()

        assertEquals(0.4f, knob.volume!!, 1e-6f)
        assertEquals(0.4f, level.targetVolume, 0f)
        level.onMayDuck(true)
        assertEquals("the ordinary dip applies again", 0.12f, knob.volume!!, 1e-6f)
    }

    @Test
    fun `a new walker level lands at once, over a ramp in progress`() {
        walkerAt(0.4f)
        level.holdDuck(0.15f)
        idleFor(100)

        walkerAt(0.5f)
        idleFor(600)

        assertEquals(0.5f, knob.volume!!, 1e-6f)
    }

    @Test
    fun `the soundscape player's target follows a Way voice's duck, and a soundscape start overwrites it (iOS C-D4)`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val player = ExoPlayerSoundscapePlayer(context, context.getSystemService(AudioManager::class.java))
        val file = File(context.cacheDir, "soundscape-duck.aac").apply { writeBytes(ByteArray(256)) }
        try {
            player.setVolume(0.4f)
            player.holdDuck(0.15f)
            assertEquals(0.15f, player.targetVolume, 0f)

            player.play(file)
            idle()

            assertEquals(0.4f, player.targetVolume, 0f)
        } finally {
            player.release()
            idle()
            file.delete()
        }
    }

    private class FakeKnob : SoundscapeLevel.Knob {
        var player = true
        private var level = 1f
        val volume: Float? get() = level.takeIf { player }

        override fun get(): Float? = volume

        override fun set(volume: Float) {
            if (player) level = volume
        }
    }
}
