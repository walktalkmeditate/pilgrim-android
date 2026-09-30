// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio.walk

import android.app.Application
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.core.flags.FixedReleaseFlags
import org.walktalkmeditate.pilgrim.data.honor.HonorVoiceState
import org.walktalkmeditate.pilgrim.walk.honor.HonorHarness

/**
 * The UI's whisper slot (plan U18; parity spec C §5, resolution 3): a pin
 * tap, the placement confirmation, and Seek's reveal wait behind the
 * guide's prompt and the Way voice `:tracker` persisted, as every in-walk
 * whisper does on iOS.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UiWhisperQueueTest {

    @get:Rule val folder = TemporaryFolder()

    private val log = audioLog()
    private val whispers = AudibleWhisperPlayer(log)
    private val prompt = MutableStateFlow(false)
    private val wayVoice = MutableStateFlow(false)

    private fun TestScope.queue(honorEnabled: Boolean = true) = UiWhisperQueue(
        whisperPlayer = whispers,
        releaseFlags = FixedReleaseFlags(honor = honorEnabled),
        promptSounding = prompt,
        wayVoiceLoaded = wayVoice,
        scope = backgroundScope,
    )

    @Test
    fun `a whisper started during a prompt waits, and plays when the prompt ends`() = runTest(UnconfinedTestDispatcher()) {
        val queue = queue()
        prompt.value = true

        queue.play(whisper("w1"))
        assertEquals(emptyList<String>(), whispers.played)

        prompt.value = false
        assertEquals(listOf("w1"), whispers.played)
    }

    @Test
    fun `a prompt starting cuts the whisper sounding and drops the one waiting`() = runTest(UnconfinedTestDispatcher()) {
        val queue = queue()
        queue.play(whisper("w1"))
        whispers.audible.value = true
        wayVoice.value = true
        queue.play(whisper("w2"))

        prompt.value = true
        prompt.value = false
        wayVoice.value = false

        assertFalse(whispers.audible.value)
        assertEquals("w2 was dropped, not played later", listOf("w1"), whispers.played)
    }

    @Test
    fun `a Way voice starting cuts the whisper sounding and keeps the one waiting, newest first`() =
        runTest(UnconfinedTestDispatcher()) {
            val queue = queue()
            queue.play(whisper("w1"))
            whispers.audible.value = true

            wayVoice.value = true
            assertFalse(whispers.audible.value)
            queue.play(whisper("w2"))
            queue.play(whisper("w3"))

            wayVoice.value = false
            assertEquals(listOf("w1", "w3"), whispers.played)
        }

    @Test
    fun `a whisper waits for both the prompt and the voice to go quiet`() = runTest(UnconfinedTestDispatcher()) {
        val queue = queue()
        wayVoice.value = true
        prompt.value = true
        queue.play(whisper("w1"))

        prompt.value = false
        assertEquals("the voice still holds it", emptyList<String>(), whispers.played)

        wayVoice.value = false
        assertEquals(listOf("w1"), whispers.played)
    }

    @Test
    fun `a whisper still downloading when a Way voice starts parks once it lands`() = runTest(UnconfinedTestDispatcher()) {
        val queue = queue()
        whispers.holdFetches = true
        queue.play(whisper("w1"))

        wayVoice.value = true
        whispers.landHeldFetches()
        assertEquals(emptyList<String>(), whispers.played)

        wayVoice.value = false
        assertEquals(listOf("w1"), whispers.played)
    }

    @Test
    fun `with the release flag off a whisper plays at once, over anything`() = runTest(UnconfinedTestDispatcher()) {
        val queue = queue(honorEnabled = false)
        prompt.value = true
        wayVoice.value = true
        whispers.holdFetches = true

        queue.play(whisper("w1"))

        assertEquals("straight to the player, no fetch first", listOf("w1"), whispers.played)
        assertEquals(0, whispers.cutCalls)
    }

    @Test
    fun `the Way voice the session persisted holds a pin tap until its row clears`() = runBlocking {
        val h = HonorHarness(folder.root)
        val queueThread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + queueThread)
        try {
            val walk = h.startHonorWalk()
            val dao = h.db.honorDao()
            val queue = UiWhisperQueue(
                whisperPlayer = whispers,
                releaseFlags = FixedReleaseFlags(honor = true),
                promptSounding = prompt,
                wayVoiceLoaded = dao.wayVoiceLoaded(),
                scope = scope,
            )

            dao.updateVoiceState(voiceState(walk.id, playing = "voice-1"))
            awaitCondition("the queue never saw the voice start") { whispers.cutCalls == 1 }
            queue.play(whisper("w1"))
            withContext(queueThread) {}
            assertEquals("parked behind the voice", emptyList<String>(), whispers.played)

            dao.updateVoiceState(voiceState(walk.id, playing = null))
            awaitCondition("the parked whisper never played") { whispers.played == listOf("w1") }
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            queueThread.close()
            h.close()
        }
    }

    private suspend fun awaitCondition(message: String, condition: () -> Boolean) {
        withTimeoutOrNull(WAIT_MS) {
            while (!condition()) delay(POLL_MS)
        } ?: throw AssertionError(message)
    }

    private fun voiceState(walkId: Long, playing: String?) = HonorVoiceState(
        walkId = walkId,
        playingMomentId = playing,
        voicePaused = false,
        voiceStartedAt = playing?.let { 1L },
        voiceStartOffsetMillis = playing?.let { 0L },
        voicePauseOffsetMillis = null,
        voiceRate = 1.0,
    )

    private companion object {
        /** A failsafe, not a grace window: each wait returns the moment Room's row lands. */
        const val WAIT_MS = 30_000L
        const val POLL_MS = 5L
    }
}
