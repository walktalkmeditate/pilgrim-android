// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.recordings

import androidx.collection.LruCache
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Caches downsampled waveform samples per recording id. Avoids repeatedly
 * reading multi-MB WAV files when the user scrolls through the recordings
 * list. iOS uses an analogous WaveformCache in RecordingsListView.
 *
 * Capacity 64 covers a typical visible window plus prefetch on a budget
 * device without unbounded growth. Eviction is automatic LRU.
 */
@Singleton
class WaveformCache @Inject constructor() {
    private val cache = LruCache<Long, FloatArray>(64)

    fun get(recordingId: Long): FloatArray? = cache.get(recordingId)

    fun put(recordingId: Long, samples: FloatArray) {
        cache.put(recordingId, samples)
    }

    fun invalidate(recordingId: Long) {
        cache.remove(recordingId)
    }
}
