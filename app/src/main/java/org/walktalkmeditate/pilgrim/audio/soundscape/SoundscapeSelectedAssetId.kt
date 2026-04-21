// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio.soundscape

import javax.inject.Qualifier

/**
 * Qualifier for the read-only [kotlinx.coroutines.flow.StateFlow] of
 * the currently-selected soundscape asset id. Separate from
 * `@VoiceGuideSelectedPackId` — a user may pick voice guide A + a
 * different soundscape B, so the two selections are independent
 * DataStore keys bridged into distinct StateFlows.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SoundscapeSelectedAssetId
