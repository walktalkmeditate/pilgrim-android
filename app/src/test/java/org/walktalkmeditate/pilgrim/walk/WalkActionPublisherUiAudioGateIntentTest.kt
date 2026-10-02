// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.walk

import android.app.Application
import android.content.Intent
import android.os.Binder
import android.os.Parcel
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.audio.walk.UiAudioGateKind
import org.walktalkmeditate.pilgrim.audio.walk.UiAudioGateSignal
import org.walktalkmeditate.pilgrim.service.WalkTrackingService
import org.walktalkmeditate.pilgrim.service.WalkTrackingService.UiAudioGateAction

/**
 * The real gate intents, built by the production [WalkActionPublisher] with
 * the Binder in a Bundle and read back through the service's own decoder
 * (the house platform-object builder rule), and the service's rule that a
 * redelivered gate is never applied.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkActionPublisherUiAudioGateIntentTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `a started gate reaches the walk service with its Binder, and decodes to the same signal`() {
        val token = Binder()
        WalkActionPublisher(context).publishUiAudioGate(UiAudioGateKind.RECORDING, held = true, seq = 42L, token = token)

        val intent = shadowOf(context).nextStartedService
        assertNotNull("expected a service start", intent)
        assertEquals(WalkTrackingService.ACTION_UI_AUDIO_GATE, intent!!.action)
        assertEquals(WalkTrackingService::class.java.name, intent.component?.className)
        val signal = WalkTrackingService.uiAudioGateSignalFromExtras(intent)
        assertEquals(UiAudioGateSignal(UiAudioGateKind.RECORDING, held = true, seq = 42L, token = token), signal)
        assertSame(token, signal!!.token)
    }

    @Test
    fun `the Binder survives the parcel an intent crosses processes in`() {
        val token = Binder()
        val built = WalkActionPublisher(context).uiAudioGateIntent(UiAudioGateKind.PROMPT, held = true, seq = 7L, token = token)

        val parcel = Parcel.obtain()
        val read = try {
            built.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            Intent.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }

        assertSame(token, WalkTrackingService.uiAudioGateSignalFromExtras(read)?.token)
    }

    @Test
    fun `an ended gate carries no Binder`() {
        val intent = WalkActionPublisher(context).uiAudioGateIntent(UiAudioGateKind.PROMPT, held = false, seq = 8L, token = null)

        assertFalse(intent.hasExtra(WalkTrackingService.EXTRA_UI_AUDIO_GATE_TOKEN))
        assertEquals(
            UiAudioGateSignal(UiAudioGateKind.PROMPT, held = false, seq = 8L, token = null),
            WalkTrackingService.uiAudioGateSignalFromExtras(intent),
        )
    }

    @Test
    fun `a whisper the UI plays crosses as its own gate`() {
        val token = Binder()
        val intent = WalkActionPublisher(context).uiAudioGateIntent(UiAudioGateKind.WHISPER, held = true, seq = 9L, token = token)

        assertEquals(
            UiAudioGateSignal(UiAudioGateKind.WHISPER, held = true, seq = 9L, token = token),
            WalkTrackingService.uiAudioGateSignalFromExtras(intent),
        )
    }

    @Test
    fun `an unknown gate or an unnumbered one decodes to nothing`() {
        val publisher = WalkActionPublisher(context)
        val unknown = publisher.uiAudioGateIntent(UiAudioGateKind.PROMPT, held = true, seq = 3L, token = Binder())
            .putExtra(WalkTrackingService.EXTRA_UI_AUDIO_GATE, "soundscape")
        val unnumbered = publisher.uiAudioGateIntent(UiAudioGateKind.PROMPT, held = true, seq = 0L, token = Binder())

        assertNull(WalkTrackingService.uiAudioGateSignalFromExtras(unknown))
        assertNull(WalkTrackingService.uiAudioGateSignalFromExtras(unnumbered))
        assertNull(WalkTrackingService.uiAudioGateSignalFromExtras(null))
    }

    @Test
    fun `a redelivered gate is never applied, and one with no walk stops the service`() {
        assertEquals(
            UiAudioGateAction.Apply,
            WalkTrackingService.decideUiAudioGateAction(honorEnabled = true, redelivered = false, pipelineActive = true),
        )
        assertEquals(
            UiAudioGateAction.Ignore,
            WalkTrackingService.decideUiAudioGateAction(honorEnabled = true, redelivered = true, pipelineActive = true),
        )
        assertEquals(
            UiAudioGateAction.Ignore,
            WalkTrackingService.decideUiAudioGateAction(honorEnabled = false, redelivered = false, pipelineActive = true),
        )
        assertEquals(
            UiAudioGateAction.StopNoPipeline,
            WalkTrackingService.decideUiAudioGateAction(honorEnabled = true, redelivered = false, pipelineActive = false),
        )
    }
}
