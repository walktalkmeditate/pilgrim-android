// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Voice intention dictation. Android-idiomatic substitution for iOS
 * `IntentionVoiceRecorder` (`IntentionVoiceRecorder.swift`): iOS records
 * an m4a then runs WhisperKit; Android uses the platform
 * [SpeechRecognizer] (same iOS-native→Android substitution policy as
 * WhisperKit→whisper.cpp / WeatherKit→Open-Meteo in CLAUDE.md). The
 * user-facing capability — speak your intention, it fills the field,
 * 30 s cap with a live countdown + level — is preserved.
 *
 * The pure pieces (countdown text, transcript cap, state reduction) are
 * unit-tested; the [SpeechRecognizer] wiring is thin platform glue.
 */

private const val INTENTION_VOICE_MAX_SECONDS = 30

private const val FINALIZE_WATCHDOG_MS = 2_000L

/** Port of iOS `formatCountdown` (`IntentionSettingView.swift:337`). */
internal fun formatIntentionCountdown(secondsRemaining: Int): String =
    "${if (secondsRemaining < 0) 0 else secondsRemaining}s"

/** Port of iOS `String(transcribed.prefix(maxCharacters))`. */
internal fun cappedIntention(transcript: String, maxChars: Int): String =
    transcript.take(maxChars)

sealed interface IntentionVoiceState {
    data object Idle : IntentionVoiceState
    data class Listening(val level: Float, val secondsRemaining: Int) : IntentionVoiceState
    data object MicDenied : IntentionVoiceState
}

internal sealed interface IntentionVoiceEvent {
    data object Started : IntentionVoiceEvent
    data class Rms(val level: Float) : IntentionVoiceEvent
    data object Tick : IntentionVoiceEvent
    data object Denied : IntentionVoiceEvent
    data object Finished : IntentionVoiceEvent
}

/**
 * Pure transition. Started → 30 s listening; Rms updates the clamped
 * level; Tick decrements the countdown and ends at 0; Denied/Finished
 * return to Idle.
 */
internal fun reduceIntentionVoice(
    state: IntentionVoiceState,
    event: IntentionVoiceEvent,
): IntentionVoiceState = when (event) {
    IntentionVoiceEvent.Started -> IntentionVoiceState.Listening(0f, INTENTION_VOICE_MAX_SECONDS)
    IntentionVoiceEvent.Denied -> IntentionVoiceState.MicDenied
    IntentionVoiceEvent.Finished -> IntentionVoiceState.Idle
    is IntentionVoiceEvent.Rms ->
        (state as? IntentionVoiceState.Listening)
            ?.copy(level = event.level.coerceIn(0f, 1f))
            ?: state
    IntentionVoiceEvent.Tick -> {
        val listening = state as? IntentionVoiceState.Listening ?: return state
        val next = listening.secondsRemaining - 1
        if (next <= 0) IntentionVoiceState.Idle else listening.copy(secondsRemaining = next)
    }
}

/**
 * Owns a [SpeechRecognizer] for one dictation session. Caller wires
 * [onTranscript] (already capped) and observes [state]. Not unit-tested
 * — the reducer above is; this is the platform boundary.
 */
class IntentionVoiceController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val maxChars: Int,
) {
    private val _state = MutableStateFlow<IntentionVoiceState>(IntentionVoiceState.Idle)
    val state: StateFlow<IntentionVoiceState> = _state.asStateFlow()

    var onTranscript: (String) -> Unit = {}

    private var recognizer: SpeechRecognizer? = null
    private var countdownJob: Job? = null
    private var watchdogJob: Job? = null
    private var lastPartial: String = ""

    /**
     * Single terminal latch. The OEM recognition service can race the
     * countdown / watchdog: a late `onResults` after a tick-to-zero, or
     * an `onError` after `stopListening`. Only the FIRST finalize path
     * delivers [onTranscript] + emits Finished; the rest no-op so the
     * spoken intention can't be delivered twice (or clobbered by a
     * trailing empty result).
     */
    private val finalized = AtomicBoolean(false)

    private val audioManager: AudioManager?
        get() = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var focusRequest: AudioFocusRequest? = null

    val isAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    private fun emit(event: IntentionVoiceEvent) {
        _state.value = reduceIntentionVoice(_state.value, event)
    }

    /** Runtime RECORD_AUDIO denial — surface the "Mic access needed" label. */
    fun markDenied() = emit(IntentionVoiceEvent.Denied)

    fun start() {
        if (_state.value is IntentionVoiceState.Listening) return
        if (!isAvailable) {
            emit(IntentionVoiceEvent.Denied)
            return
        }
        finalized.set(false)
        lastPartial = ""
        requestFocus()
        val sr = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = sr
        sr.setRecognitionListener(listener)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        sr.startListening(intent)
        emit(IntentionVoiceEvent.Started)
        countdownJob?.cancel()
        countdownJob = scope.launch {
            while (isActive && _state.value is IntentionVoiceState.Listening) {
                delay(1_000)
                emit(IntentionVoiceEvent.Tick)
            }
            if (_state.value !is IntentionVoiceState.Listening) finalizeWithLastPartial()
        }
    }

    /** "Done" tap — stop capture, keep whatever was recognized. */
    fun stopAndFinalize() {
        countdownJob?.cancel()
        recognizer?.stopListening()
        // Some OEM recognition services never call back after
        // stopListening() — arm a short watchdog so the mic releases
        // promptly. Latch-guarded: a real onResults/onError in the
        // interim still wins and cancels this job.
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            delay(FINALIZE_WATCHDOG_MS)
            if (!finalized.get() && _state.value is IntentionVoiceState.Listening) {
                finalizeWithLastPartial()
            }
        }
    }

    fun cancel() {
        countdownJob?.cancel()
        watchdogJob?.cancel()
        abandonFocus()
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        _state.value = IntentionVoiceState.Idle
    }

    fun release() {
        countdownJob?.cancel()
        watchdogJob?.cancel()
        abandonFocus()
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
    }

    private fun finalizeWithLastPartial() {
        if (!finalized.compareAndSet(false, true)) return
        countdownJob?.cancel()
        watchdogJob?.cancel()
        if (lastPartial.isNotBlank()) onTranscript(cappedIntention(lastPartial, maxChars))
        abandonFocus()
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        emit(IntentionVoiceEvent.Finished)
    }

    private fun deliver(text: String?) {
        if (!finalized.compareAndSet(false, true)) return
        countdownJob?.cancel()
        watchdogJob?.cancel()
        val clean = text?.trim().orEmpty()
        // iOS always transcribes the recorded audio; an empty
        // endpointed onResults must not silently lose a spoken
        // intention when partials did arrive — fall back to them.
        val resolved = clean.ifBlank { lastPartial.trim() }
        if (resolved.isNotBlank()) onTranscript(cappedIntention(resolved, maxChars))
        abandonFocus()
        recognizer?.destroy()
        recognizer = null
        emit(IntentionVoiceEvent.Finished)
    }

    private fun requestFocus() {
        val am = audioManager ?: return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .setWillPauseWhenDucked(false)
            .setAcceptsDelayedFocusGain(false)
            .build()
        focusRequest = request
        am.requestAudioFocus(request)
    }

    private fun abandonFocus() {
        val req = focusRequest ?: return
        focusRequest = null
        audioManager?.abandonAudioFocusRequest(req)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {
            // SpeechRecognizer rms is roughly -2..10 dB; map to 0..1.
            emit(IntentionVoiceEvent.Rms(((rmsdB + 2f) / 12f)))
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onError(error: Int) {
            // First terminal path wins (a late onResults after an error
            // must not double-deliver). Any non-permission error —
            // including ERROR_RECOGNIZER_BUSY — deterministically tears
            // down the recognizer and returns to Idle: no stuck
            // Listening.
            if (!finalized.compareAndSet(false, true)) return
            countdownJob?.cancel()
            watchdogJob?.cancel()
            abandonFocus()
            recognizer?.cancel()
            recognizer?.destroy()
            recognizer = null
            val denied = error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
            emit(if (denied) IntentionVoiceEvent.Denied else IntentionVoiceEvent.Finished)
        }
        override fun onResults(results: Bundle?) {
            deliver(
                results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull(),
            )
        }
        override fun onPartialResults(partialResults: Bundle?) {
            partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.let { if (it.isNotBlank()) lastPartial = it }
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
