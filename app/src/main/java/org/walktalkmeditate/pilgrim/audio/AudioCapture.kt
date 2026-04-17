// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

/**
 * Hardware-mockable abstraction over the PCM source for voice
 * recording. Production implementation wraps [android.media.AudioRecord];
 * tests provide a fake that feeds canned PCM buffers without device
 * hardware.
 *
 * Contract:
 * - [start] must be called before [read]. Throws if the underlying
 *   recorder fails to initialize.
 * - [read] is blocking. Returns number of shorts read (0 or positive),
 *   or -1 on EOF / irrecoverable error. Matches AudioRecord.read(...)
 *   semantics.
 * - [stop] is idempotent; subsequent [read] returns -1.
 * - Not required to be thread-safe. VoiceRecorder pins the entire
 *   lifecycle to a single dedicated thread.
 */
interface AudioCapture {
    fun start()
    fun read(buffer: ShortArray): Int
    fun stop()
    val sampleRateHz: Int
    val channels: Int
}
