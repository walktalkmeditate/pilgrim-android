// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio.voiceguide

import javax.inject.Qualifier

/**
 * Qualifier for the long-lived [kotlinx.coroutines.CoroutineScope]
 * that hosts [VoiceGuideOrchestrator]'s state-observer coroutine +
 * any per-session scheduler coroutines it spawns. Lives for the
 * app process; `SupervisorJob` so one scheduler's failure doesn't
 * tear the whole scope down. Same shape as `MeditationBellScope`
 * (5-B), `VoiceGuideCatalogScope` (5-D).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class VoiceGuidePlaybackScope
