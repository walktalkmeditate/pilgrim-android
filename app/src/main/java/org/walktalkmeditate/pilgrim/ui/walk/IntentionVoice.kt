// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
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

internal const val INTENTION_VOICE_MAX_SECONDS = 30

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
    private var lastPartial: String = ""

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
        lastPartial = ""
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
        recognizer?.stopListening()
    }

    fun cancel() {
        countdownJob?.cancel()
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        _state.value = IntentionVoiceState.Idle
    }

    fun release() {
        countdownJob?.cancel()
        recognizer?.destroy()
        recognizer = null
    }

    private fun finalizeWithLastPartial() {
        if (lastPartial.isNotBlank()) onTranscript(cappedIntention(lastPartial, maxChars))
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
    }

    private fun deliver(text: String?) {
        countdownJob?.cancel()
        val clean = text?.trim().orEmpty()
        if (clean.isNotBlank()) onTranscript(cappedIntention(clean, maxChars))
        recognizer?.destroy()
        recognizer = null
        emit(IntentionVoiceEvent.Finished)
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
            countdownJob?.cancel()
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
