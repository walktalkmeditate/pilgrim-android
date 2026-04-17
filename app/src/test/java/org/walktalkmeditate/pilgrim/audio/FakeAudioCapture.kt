// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Test double for [AudioCapture]. Feeds a pre-configured list of PCM
 * bursts on [read] (one burst per call, then -1 for EOF). Optionally
 * throws from [start] to drive the AudioCaptureInitFailed path.
 */
class FakeAudioCapture(
    override val sampleRateHz: Int = 16_000,
    override val channels: Int = 1,
    private val bursts: List<ShortArray> = listOf(ShortArray(1_600) { 500 }),
    var startThrowable: Throwable? = null,
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
        val idx = cursor.getAndIncrement()
        if (idx >= bursts.size) return -1
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
