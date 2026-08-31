// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Test double for [AudioCapture]. Feeds a pre-configured list of PCM
 * bursts on [read], cycling through the list for as long as [start]
 * has been called — this matches AudioRecord's real behavior (a
 * microphone is a continuous source, not a one-shot). Returns -1
 * only after [stop]. Optionally throws from [start] to drive the
 * AudioCaptureInitFailed path.
 *
 * [read] blocks briefly, because the real `AudioRecord.read()` does:
 * it returns only once the requested samples have been captured, which
 * `VoiceRecorder.stop`'s KDoc pins at ~100 ms per cycle on nominal
 * hardware. Returning instantly instead turns `runCaptureLoop` into a
 * spin — a dedicated thread at 100% CPU streaming megabytes/second to
 * disk for as long as a recording is live. On a 2-vCPU CI runner with
 * two test forks that starves whatever else is waiting, which is how a
 * recorder-driven test manufactures its own timeout flake.
 *
 * [readBlockMillis] is deliberately far below the real ~100 ms: enough
 * to yield the CPU and stop the spin, still ~50x faster than real time
 * so tests reach "enough audio captured" without waiting for it. Raise
 * it in a test that needs faithful capture pacing.
 */
class FakeAudioCapture(
    override val sampleRateHz: Int = 16_000,
    override val channels: Int = 1,
    private val bursts: List<ShortArray> = listOf(ShortArray(1_600) { 500 }),
    var startThrowable: Throwable? = null,
    private val readBlockMillis: Long = 2L,
) : AudioCapture {

    private val started = AtomicBoolean(false)
    private val cursor = AtomicInteger(0)
    val stopCallCount = AtomicInteger(0)

    override fun start() {
        startThrowable?.let { throw it }
        started.set(true)
    }

    override fun read(buffer: ShortArray): Int {
        if (!started.get()) return -1
        if (bursts.isEmpty()) return -1
        if (readBlockMillis > 0) Thread.sleep(readBlockMillis)
        // Re-check: stop() can land while this call was blocked, exactly
        // as it can mid-read on real hardware.
        if (!started.get()) return -1
        val idx = cursor.getAndIncrement() % bursts.size
        val src = bursts[idx]
        val n = minOf(src.size, buffer.size)
        System.arraycopy(src, 0, buffer, 0, n)
        return n
    }

    override fun stop() {
        started.set(false)
        stopCallCount.incrementAndGet()
    }
}
