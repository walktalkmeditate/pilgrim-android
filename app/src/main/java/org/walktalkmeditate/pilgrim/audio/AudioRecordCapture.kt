// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import javax.inject.Inject

/**
 * Production [AudioCapture] — 16 kHz mono 16-bit PCM from
 * [AudioRecord]. Not thread-safe; VoiceRecorder pins the entire
 * lifecycle to a single dedicated Executor thread.
 *
 * Sample rate is Whisper-native so downstream transcription (Stage
 * 2-D) can read the WAV file without a resample pipeline.
 */
@SuppressLint("MissingPermission")
class AudioRecordCapture @Inject constructor() : AudioCapture {

    override val sampleRateHz: Int = SAMPLE_RATE_HZ
    override val channels: Int = 1

    private var recorder: AudioRecord? = null

    override fun start() {
        val minBufferBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            CHANNEL_CONFIG,
            ENCODING,
        )
        check(minBufferBytes > 0) {
            "AudioRecord.getMinBufferSize returned $minBufferBytes"
        }
        val bufferBytes = maxOf(minBufferBytes, BUFFER_BYTES_MIN)
        val r = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE_HZ,
            CHANNEL_CONFIG,
            ENCODING,
            bufferBytes,
        )
        check(r.state == AudioRecord.STATE_INITIALIZED) {
            "AudioRecord state=${r.state} (uninitialized)"
        }
        r.startRecording()
        recorder = r
    }

    override fun read(buffer: ShortArray): Int =
        recorder?.read(buffer, 0, buffer.size) ?: -1

    override fun stop() {
        val r = recorder ?: return
        recorder = null
        try {
            r.stop()
        } catch (_: IllegalStateException) {
            // AudioRecord.stop throws if never startRecording'd. Tolerate.
        }
        r.release()
    }

    private companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        // 100 ms @ 16 kHz mono 16-bit = 3200 bytes. Floor at 4096 for
        // slack against OEM minimum-buffer quirks.
        const val BUFFER_BYTES_MIN = 4_096
    }
}
