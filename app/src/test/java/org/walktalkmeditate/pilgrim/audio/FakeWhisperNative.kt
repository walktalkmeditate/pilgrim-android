// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

/**
 * Test double for the JNI seam. Records init/transcribe/free calls so
 * tests pin [WhisperCppEngine]'s path-keyed load state; the real
 * library needs a device.
 */
internal class FakeWhisperNative(
    var resultText: String = "text from the fake native",
    var resultSegments: Array<WhisperSegment>? = null,
) : WhisperNative {

    val initPaths = mutableListOf<String>()
    val transcribedWavPaths = mutableListOf<String>()
    val transcribedHandles = mutableListOf<Long>()
    val transcribedSegmentsWavPaths = mutableListOf<String>()
    val freedHandles = mutableListOf<Long>()
    var failInit = false

    private var nextHandle = 1L

    override fun init(modelPath: String): Long {
        initPaths.add(modelPath)
        if (failInit) return 0L
        return nextHandle++
    }

    override fun transcribe(handle: Long, wavPath: String): String? {
        transcribedHandles.add(handle)
        transcribedWavPaths.add(wavPath)
        return resultText
    }

    /**
     * Falls back to a single segment built from [resultText] when
     * [resultSegments] is untouched, so existing [resultText]-only test
     * setups keep working for tests that exercise this method without
     * caring about segment shape.
     */
    override fun transcribeSegments(handle: Long, wavPath: String): Array<WhisperSegment>? {
        transcribedHandles.add(handle)
        transcribedSegmentsWavPaths.add(wavPath)
        return resultSegments ?: arrayOf(WhisperSegment(text = resultText, t0Ms = 0L, t1Ms = 0L, noSpeechProb = 0f))
    }

    override fun free(handle: Long) {
        freedHandles.add(handle)
    }
}
