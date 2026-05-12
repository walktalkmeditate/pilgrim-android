// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.walktalkmeditate.pilgrim.data.voice.VoiceRecordingFileSystem

/**
 * In-memory cache for decoded waveform bars, keyed by
 * [org.walktalkmeditate.pilgrim.data.entity.VoiceRecording.id]. Lives
 * for the process lifetime — keeps Walk Summary re-opens cheap (the
 * 50-bar float array per recording is ~200 bytes, so even thousands of
 * recordings stay well under a single Bitmap's memory footprint).
 *
 * Concurrency: a single [Mutex] protects the map AND the in-flight
 * loader set. A second [ensure] call for the same recording while
 * decoding is in progress finds the recording in `inFlight` and
 * returns immediately without queuing a duplicate decode.
 *
 * Failure caching: a null result is stored as `EMPTY_SENTINEL` so we
 * don't re-attempt to decode a file that's already proven unreadable
 * (deleted via sweeper, malformed header, etc.). The sentinel is
 * filtered out in the [samples] read path.
 */
@Singleton
class WaveformCache @Inject constructor(
    private val fileSystem: VoiceRecordingFileSystem,
) {
    private val mutex = Mutex()
    private val cache = mutableMapOf<Long, FloatArray>()
    private val inFlight = mutableSetOf<Long>()

    fun samples(recordingId: Long): FloatArray? {
        val v = cache[recordingId]
        return if (v === EMPTY_SENTINEL) null else v
    }

    /**
     * Decode the recording at [relativePath] off the calling
     * dispatcher and store the result in the cache. Returns the
     * decoded array (or null on failure). Subsequent calls for the
     * same id are no-ops (returns the cached value immediately).
     */
    suspend fun ensure(recordingId: Long, relativePath: String): FloatArray? {
        mutex.withLock {
            cache[recordingId]?.let {
                return if (it === EMPTY_SENTINEL) null else it
            }
            if (recordingId in inFlight) return null
            inFlight += recordingId
        }
        val result = WaveformGenerator.generate(fileSystem.absolutePath(relativePath))
        mutex.withLock {
            inFlight -= recordingId
            cache[recordingId] = result ?: EMPTY_SENTINEL
        }
        return result
    }

    suspend fun invalidate(recordingId: Long) {
        mutex.withLock {
            cache.remove(recordingId)
            inFlight -= recordingId
        }
    }

    private companion object {
        /** Marker for "tried to decode, no usable samples." */
        val EMPTY_SENTINEL = FloatArray(0)
    }
}
