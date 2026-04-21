// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio.soundscape

import javax.inject.Qualifier

/**
 * Qualifier for the long-lived [kotlinx.coroutines.CoroutineScope]
 * that hosts [SoundscapeOrchestrator]'s observer coroutine + any
 * pending-play delay jobs it spawns. Lives for the app process;
 * `SupervisorJob` so a transient failure doesn't tear it down.
 * Same shape as `VoiceGuidePlaybackScope` (Stage 5-E),
 * `MeditationBellScope` (Stage 5-B).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SoundscapePlaybackScope
