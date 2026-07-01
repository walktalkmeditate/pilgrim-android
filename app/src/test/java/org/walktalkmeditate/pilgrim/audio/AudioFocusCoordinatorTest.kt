// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import android.app.Application
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AudioFocusCoordinatorTest {

    private lateinit var audioManager: AudioManager
    private lateinit var coordinator: AudioFocusCoordinator

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        audioManager = context.getSystemService(AudioManager::class.java)
        coordinator = AudioFocusCoordinator(audioManager)
    }

    // CLAUDE.md builder rule: AudioFocusRequest.Builder is runtime-validated,
    // so exercise the real .build() + submit path, not just a fake.
    @Test
    fun `requestTransient builds and submits a transient-gain focus request`() {
        val granted = coordinator.requestTransient(onLossListener = {})

        assertTrue("Robolectric grants focus by default", granted)
        val req = shadowOf(audioManager).lastAudioFocusRequest
        assertNotNull("focus request was built and submitted", req)
        assertEquals(
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            req.audioFocusRequest.focusGain,
        )
    }

    // requestMediaPlayback's signature also changed (treatDuckAsLoss=true);
    // exercise its real .build()/submit path too (CLAUDE.md builder rule).
    @Test
    fun `requestMediaPlayback builds and submits a transient-gain focus request`() {
        val granted = coordinator.requestMediaPlayback(onLossListener = {})

        assertTrue("Robolectric grants focus by default", granted)
        val req = shadowOf(audioManager).lastAudioFocusRequest
        assertNotNull("focus request was built and submitted", req)
        assertEquals(
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            req.audioFocusRequest.focusGain,
        )
    }

    // End-to-end (iOS PR #47): the listener wired by requestTransient must
    // ignore a transient interruption (notification / nav / assistant / ring)
    // and fire only on a permanent takeover — proving the flags are threaded
    // through, not just the isFinalizingFocusLoss decision in isolation.
    @Test
    fun `requestTransient loss listener ignores transient loss, fires on permanent loss`() {
        var lossCount = 0
        coordinator.requestTransient(onLossListener = { lossCount++ })
        val listener = shadowOf(audioManager).lastAudioFocusRequest.listener

        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertEquals("a transient interruption must not cut the talk", 0, lossCount)

        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        assertEquals("a permanent takeover finalizes the recording", 1, lossCount)
    }

    // Symmetric guard: playback's listener DOES fire on a transient loss, so a
    // future accidental flag flip on requestMediaPlayback can't silently pass.
    @Test
    fun `requestMediaPlayback loss listener fires on transient loss`() {
        var lossCount = 0
        coordinator.requestMediaPlayback(onLossListener = { lossCount++ })
        val listener = shadowOf(audioManager).lastAudioFocusRequest.listener

        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertEquals("playback pauses on a transient interruption", 1, lossCount)
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertEquals("playback also pauses on a duckable loss", 2, lossCount)
    }

    @Test
    fun `abandon releases the held request`() {
        coordinator.requestTransient(onLossListener = {})

        coordinator.abandon()

        assertNotNull(
            "focus was abandoned",
            shadowOf(audioManager).lastAbandonedAudioFocusRequest,
        )
    }

    // Capture (requestTransient → treatTransientAsLoss = false, treatDuckAsLoss
    // = false): ONLY a permanent LOSS finalizes the recording. A transient LOSS
    // or CAN_DUCK (a notification, nav prompt, assistant, unanswered ring) must
    // NOT — AudioRecord keeps capturing, so a talk shouldn't split for a blip
    // (iOS PR #47 parity).
    @Test
    fun `capture finalizes only on permanent LOSS, not transient or duck`() {
        assertTrue(
            AudioFocusCoordinator.isFinalizingFocusLoss(
                AudioManager.AUDIOFOCUS_LOSS,
                treatTransientAsLoss = false,
                treatDuckAsLoss = false,
            ),
        )
        assertFalse(
            "a transient interruption must not cut the talk",
            AudioFocusCoordinator.isFinalizingFocusLoss(
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                treatTransientAsLoss = false,
                treatDuckAsLoss = false,
            ),
        )
        assertFalse(
            AudioFocusCoordinator.isFinalizingFocusLoss(
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
                treatTransientAsLoss = false,
                treatDuckAsLoss = false,
            ),
        )
        assertFalse(
            AudioFocusCoordinator.isFinalizingFocusLoss(
                AudioManager.AUDIOFOCUS_GAIN,
                treatTransientAsLoss = false,
                treatDuckAsLoss = false,
            ),
        )
    }

    // Playback (requestMediaPlayback → both true): a transient LOSS and a
    // CAN_DUCK loss both pause playback, preserving the pre-existing behavior —
    // only capture changed for iOS PR #47.
    @Test
    fun `playback finalizes on transient and CAN_DUCK loss`() {
        assertTrue(
            AudioFocusCoordinator.isFinalizingFocusLoss(
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                treatTransientAsLoss = true,
                treatDuckAsLoss = true,
            ),
        )
        assertTrue(
            AudioFocusCoordinator.isFinalizingFocusLoss(
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
                treatTransientAsLoss = true,
                treatDuckAsLoss = true,
            ),
        )
    }
}
