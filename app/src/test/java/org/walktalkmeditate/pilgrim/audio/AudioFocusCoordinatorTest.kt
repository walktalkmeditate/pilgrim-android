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

    @Test
    fun `abandon releases the held request`() {
        coordinator.requestTransient(onLossListener = {})

        coordinator.abandon()

        assertNotNull(
            "focus was abandoned",
            shadowOf(audioManager).lastAbandonedAudioFocusRequest,
        )
    }

    // Capture (treatDuckAsLoss = false): only a permanent or transient LOSS
    // finalizes the recording. A CAN_DUCK loss (a notification ding) must NOT
    // — a recording can simply talk over it.
    @Test
    fun `capture treats LOSS and transient LOSS as finalizing but not CAN_DUCK`() {
        val duck = false
        assertTrue(
            AudioFocusCoordinator.isFinalizingFocusLoss(AudioManager.AUDIOFOCUS_LOSS, duck),
        )
        assertTrue(
            AudioFocusCoordinator.isFinalizingFocusLoss(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, duck),
        )
        assertFalse(
            AudioFocusCoordinator.isFinalizingFocusLoss(
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
                duck,
            ),
        )
        assertFalse(
            AudioFocusCoordinator.isFinalizingFocusLoss(AudioManager.AUDIOFOCUS_GAIN, duck),
        )
    }

    // Playback (treatDuckAsLoss = true): a CAN_DUCK loss DOES count, preserving
    // the pre-existing requestMediaPlayback pause-on-duck behavior.
    @Test
    fun `playback treats CAN_DUCK as finalizing`() {
        assertTrue(
            AudioFocusCoordinator.isFinalizingFocusLoss(
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
                treatDuckAsLoss = true,
            ),
        )
    }
}
