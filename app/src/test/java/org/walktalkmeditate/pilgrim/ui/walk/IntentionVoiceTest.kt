// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import org.junit.Assert.assertEquals
import org.junit.Test

class IntentionVoiceTest {

    @Test
    fun `countdown formats whole seconds with s suffix`() {
        assertEquals("30s", formatIntentionCountdown(30))
        assertEquals("0s", formatIntentionCountdown(0))
    }

    @Test
    fun `countdown floors negatives at zero`() {
        assertEquals("0s", formatIntentionCountdown(-3))
    }

    @Test
    fun `transcript is capped to the intention char limit`() {
        assertEquals("abcd", cappedIntention("abcdefghij", 4))
        assertEquals("short", cappedIntention("short", 140))
    }

    @Test
    fun `started moves to listening at the 30s cap with zero level`() {
        val s = reduceIntentionVoice(IntentionVoiceState.Idle, IntentionVoiceEvent.Started)
        assertEquals(IntentionVoiceState.Listening(0f, 30), s)
    }

    @Test
    fun `rms updates and clamps the level only while listening`() {
        val listening = IntentionVoiceState.Listening(0f, 30)
        assertEquals(
            IntentionVoiceState.Listening(1f, 30),
            reduceIntentionVoice(listening, IntentionVoiceEvent.Rms(5f)),
        )
        assertEquals(
            IntentionVoiceState.Listening(0f, 30),
            reduceIntentionVoice(listening, IntentionVoiceEvent.Rms(-9f)),
        )
        // Rms while idle is ignored.
        assertEquals(
            IntentionVoiceState.Idle,
            reduceIntentionVoice(IntentionVoiceState.Idle, IntentionVoiceEvent.Rms(0.5f)),
        )
    }

    @Test
    fun `tick decrements the countdown and ends the session at zero`() {
        assertEquals(
            IntentionVoiceState.Listening(0.2f, 29),
            reduceIntentionVoice(
                IntentionVoiceState.Listening(0.2f, 30),
                IntentionVoiceEvent.Tick,
            ),
        )
        assertEquals(
            IntentionVoiceState.Idle,
            reduceIntentionVoice(
                IntentionVoiceState.Listening(0.2f, 1),
                IntentionVoiceEvent.Tick,
            ),
        )
    }

    @Test
    fun `denied and finished return to a terminal state`() {
        assertEquals(
            IntentionVoiceState.MicDenied,
            reduceIntentionVoice(
                IntentionVoiceState.Listening(0f, 10),
                IntentionVoiceEvent.Denied,
            ),
        )
        assertEquals(
            IntentionVoiceState.Idle,
            reduceIntentionVoice(
                IntentionVoiceState.Listening(0f, 10),
                IntentionVoiceEvent.Finished,
            ),
        )
    }

    @Test
    fun `transient error moves to the recoverable error state from any state`() {
        assertEquals(
            IntentionVoiceState.TransientError,
            reduceIntentionVoice(
                IntentionVoiceState.Listening(0f, 10),
                IntentionVoiceEvent.TransientError,
            ),
        )
        assertEquals(
            IntentionVoiceState.TransientError,
            reduceIntentionVoice(IntentionVoiceState.Idle, IntentionVoiceEvent.TransientError),
        )
    }

    @Test
    fun `started from a transient error retries into listening`() {
        assertEquals(
            IntentionVoiceState.Listening(0f, 30),
            reduceIntentionVoice(
                IntentionVoiceState.TransientError,
                IntentionVoiceEvent.Started,
            ),
        )
    }
}
