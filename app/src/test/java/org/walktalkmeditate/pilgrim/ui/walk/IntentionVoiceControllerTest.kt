// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Bundle
import android.os.Looper
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
import org.robolectric.shadows.ShadowSpeechRecognizer

/**
 * Exercises [IntentionVoiceController] against Robolectric's
 * ShadowSpeechRecognizer + ShadowAudioManager. Per CLAUDE.md's
 * platform-object-builder rule this drives the real
 * [SpeechRecognizer] / [android.media.AudioFocusRequest] builder
 * paths under a real Android runtime — the reducer is unit-tested
 * separately ([IntentionVoiceTest]); this covers the glue + the
 * terminal latch where double-delivery and stuck-Listening live.
 *
 * The countdown loop is a perpetual `while (isActive) { delay }`, so
 * tests drive it with `runCurrent()` — never `advanceUntilIdle()`
 * (it would spin forever).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class IntentionVoiceControllerTest {

    private lateinit var context: Application
    private lateinit var audioManager: AudioManager

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        audioManager = context.getSystemService(AudioManager::class.java)
        registerRecognitionService()
    }

    /**
     * `SpeechRecognizer.isRecognitionAvailable` queries the package
     * manager for a [RecognitionService]; Robolectric ships none, so
     * register a stub component or the controller short-circuits to
     * MicDenied before any recognizer is built.
     */
    private fun registerRecognitionService() {
        val pm = shadowOf(context.packageManager)
        val component = ComponentName(context.packageName, "FakeRecognitionService")
        val serviceInfo = ServiceInfo().apply {
            name = component.className
            packageName = component.packageName
        }
        val packageInfo = PackageInfo().apply {
            packageName = context.packageName
            services = arrayOf(serviceInfo)
        }
        pm.installPackage(packageInfo)
        pm.addOrUpdateService(serviceInfo)
        val resolveInfo = ResolveInfo().apply { this.serviceInfo = serviceInfo }
        pm.addResolveInfoForIntent(Intent(RecognitionService.SERVICE_INTERFACE), resolveInfo)
    }

    private fun controller(scope: TestScope) = IntentionVoiceController(
        context = context,
        scope = scope,
        maxChars = 140,
    )

    private fun resultsBundle(text: String) = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
    }

    /**
     * `SpeechRecognizer.startListening` posts the service-connect +
     * listener-bind onto the main Looper; Robolectric's main Looper is
     * paused, so the shadow's `triggerOn*` would NPE on a null listener
     * until it runs.
     */
    private fun idleMain() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `start builds and starts a recognizer with the free-form partial intent`() = runTest {
        val c = controller(this)
        c.start()
        runCurrent()

        val sr = ShadowSpeechRecognizer.getLatestSpeechRecognizer()
        assertNotNull("recognizer was created", sr)
        val intent = shadowOf(sr).lastRecognizerIntent
        assertEquals(
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL),
        )
        assertTrue(intent.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false))
        assertTrue(c.state.value is IntentionVoiceState.Listening)

        c.release()
    }

    @Test
    fun `start acquires an audio focus request and teardown abandons it`() = runTest {
        val c = controller(this)
        c.start()
        runCurrent()

        // AudioFocusRequest.Builder is runtime-validated (CLAUDE.md
        // builder rule) — building it without crashing + the shadow
        // recording it proves the chain.
        val requested = shadowOf(audioManager).lastAudioFocusRequest
        assertNotNull("focus request was built and submitted", requested)

        c.release()
        runCurrent()
        assertNotNull(
            "focus abandoned on teardown",
            shadowOf(audioManager).lastAbandonedAudioFocusRequest,
        )
    }

    @Test
    fun `onResults delivers the transcript once and destroys the recognizer`() = runTest {
        val c = controller(this)
        var delivered: String? = null
        var deliverCount = 0
        c.onTranscript = { delivered = it; deliverCount++ }
        c.start()
        runCurrent()
        val sr = ShadowSpeechRecognizer.getLatestSpeechRecognizer()
        idleMain()

        shadowOf(sr).triggerOnResults(resultsBundle("walk gently"))
        runCurrent()

        assertEquals("walk gently", delivered)
        assertEquals(1, deliverCount)
        assertTrue(shadowOf(sr).isDestroyed)
        assertEquals(IntentionVoiceState.Idle, c.state.value)

        c.release()
    }

    @Test
    fun `a blank final result falls back to the last partial`() = runTest {
        val c = controller(this)
        var delivered: String? = null
        c.onTranscript = { delivered = it }
        c.start()
        runCurrent()
        val sr = ShadowSpeechRecognizer.getLatestSpeechRecognizer()
        idleMain()

        shadowOf(sr).triggerOnPartialResults(resultsBundle("half spoken"))
        shadowOf(sr).triggerOnResults(resultsBundle("   "))
        runCurrent()

        assertEquals("half spoken", delivered)

        c.release()
    }

    @Test
    fun `countdown tick to zero then a late onResults delivers exactly once`() = runTest {
        val shortCap = IntentionVoiceController(context, this, maxChars = 140)
        var deliverCount = 0
        var delivered: String? = null
        shortCap.onTranscript = { delivered = it; deliverCount++ }
        shortCap.start()
        runCurrent()
        val sr = ShadowSpeechRecognizer.getLatestSpeechRecognizer()
        idleMain()
        shadowOf(sr).triggerOnPartialResults(resultsBundle("from partial"))

        // Drive the 30 one-second ticks to zero — runCurrent advances
        // only ready work; pump the virtual clock one second at a time.
        repeat(31) {
            testScheduler.advanceTimeBy(1_000)
            runCurrent()
        }
        assertEquals("countdown ended the session", IntentionVoiceState.Idle, shortCap.state.value)
        assertEquals("finalizeWithLastPartial delivered once", 1, deliverCount)
        assertEquals("from partial", delivered)

        // Late OEM onResults after the latch closed must no-op.
        shadowOf(sr).triggerOnResults(resultsBundle("too late"))
        runCurrent()
        assertEquals("latch blocked the late result", 1, deliverCount)
        assertEquals("from partial", delivered)

        shortCap.release()
    }

    @Test
    fun `non-permission error surfaces TransientError and destroys the recognizer`() = runTest {
        val c = controller(this)
        c.start()
        runCurrent()
        val sr = ShadowSpeechRecognizer.getLatestSpeechRecognizer()
        idleMain()

        shadowOf(sr).triggerOnError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
        runCurrent()

        assertEquals(IntentionVoiceState.TransientError, c.state.value)
        assertTrue(shadowOf(sr).isDestroyed)

        // A trailing onResults after the error must not deliver
        // (latch-safe — the error path already closed the latch).
        var delivered = false
        c.onTranscript = { delivered = true }
        shadowOf(sr).triggerOnResults(resultsBundle("ignored"))
        runCurrent()
        assertFalse(delivered)

        c.release()
    }

    @Test
    fun `insufficient-permission error surfaces MicDenied`() = runTest {
        val c = controller(this)
        c.start()
        runCurrent()
        val sr = ShadowSpeechRecognizer.getLatestSpeechRecognizer()
        idleMain()

        shadowOf(sr).triggerOnError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
        runCurrent()

        assertEquals(IntentionVoiceState.MicDenied, c.state.value)

        c.release()
    }

    @Test
    fun `cancel destroys the recognizer and returns to Idle`() = runTest {
        val c = controller(this)
        c.start()
        runCurrent()
        val sr = ShadowSpeechRecognizer.getLatestSpeechRecognizer()

        c.cancel()
        runCurrent()

        assertTrue(shadowOf(sr).isDestroyed)
        assertEquals(IntentionVoiceState.Idle, c.state.value)
    }

    @Test
    fun `release destroys the recognizer`() = runTest {
        val c = controller(this)
        c.start()
        runCurrent()
        val sr = ShadowSpeechRecognizer.getLatestSpeechRecognizer()

        c.release()
        runCurrent()

        assertTrue(shadowOf(sr).isDestroyed)
    }

    @Test
    fun `markDenied without recognition available surfaces MicDenied`() = runTest {
        // No recognizer ever built — pure denial path.
        val c = controller(this)
        c.markDenied()
        runCurrent()
        assertEquals(IntentionVoiceState.MicDenied, c.state.value)
    }
}
