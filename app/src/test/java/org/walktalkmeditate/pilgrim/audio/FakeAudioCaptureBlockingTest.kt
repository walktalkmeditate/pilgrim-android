// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [FakeAudioCapture]'s blocking-read contract.
 *
 * This exists because the absence of that contract was invisible: an
 * instantly-returning `read` is indistinguishable from a correct one in
 * every assertion any recorder test makes, while turning
 * `VoiceRecorder.runCaptureLoop` into a CPU spin that starved unrelated
 * tests on CI. Deleting the sleep as a micro-optimisation would restore
 * that silently, so the contract is asserted rather than trusted.
 */
class FakeAudioCaptureBlockingTest {

    @Test
    fun `read blocks for roughly the configured interval instead of returning instantly`() {
        val capture = FakeAudioCapture(readBlockMillis = 20L)
        capture.start()
        val buffer = ShortArray(1_600)

        val elapsed = System.nanoTime().let { started ->
            repeat(5) { capture.read(buffer) }
            (System.nanoTime() - started) / 1_000_000
        }

        // Five reads at 20ms cannot legitimately finish in under 50ms.
        // Deliberately loose on the upper side: this asserts "does not
        // spin", not scheduler precision on a loaded machine.
        assertTrue("five 20ms reads took ${elapsed}ms", elapsed >= 50)
    }

    @Test
    fun `read that was blocked when stop landed reports end of stream`() {
        // Real hardware can stop mid-read; the fake must not hand back a
        // burst captured after the caller asked it to stop.
        val capture = FakeAudioCapture(readBlockMillis = 200L)
        capture.start()
        val buffer = ShortArray(1_600)

        val stopper = Thread {
            Thread.sleep(50)
            capture.stop()
        }
        stopper.start()
        val result = capture.read(buffer)
        stopper.join()

        assertEquals(-1, result)
    }

    @Test
    fun `a zero interval keeps the instant-return behaviour available`() {
        // Tests that genuinely need throughput over fidelity can opt out.
        val capture = FakeAudioCapture(readBlockMillis = 0L)
        capture.start()

        assertEquals(1_600, capture.read(ShortArray(1_600)))
    }
}
