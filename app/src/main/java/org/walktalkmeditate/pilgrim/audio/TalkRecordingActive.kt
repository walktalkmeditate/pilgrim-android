// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import javax.inject.Qualifier

/**
 * Qualifier for the read-only `StateFlow<Boolean>` mirroring
 * [VoiceRecorder.isRecording] — true while a talk recording is being
 * captured. Bound in [org.walktalkmeditate.pilgrim.di.AudioModule];
 * consumed by the seek sonar's suppression gate (U9) and the
 * voice-guide scheduler's `isRecordingVoice` guard. Same
 * bind-the-flow-not-the-owner pattern as `MeditationObservedWalkState`,
 * so tests inject a plain `MutableStateFlow` without building a
 * recorder.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class TalkRecordingActive
