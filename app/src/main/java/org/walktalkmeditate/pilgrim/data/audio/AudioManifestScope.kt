// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.audio

import javax.inject.Qualifier

/**
 * Qualifier for the long-lived [kotlinx.coroutines.CoroutineScope]
 * that backs [AudioManifestService]'s sync coroutine. Separate from
 * `viewModelScope` / other short-lived scopes because a sync
 * request may outlive the screen that triggered it. `SupervisorJob`
 * so one failed fetch doesn't tear the whole scope down.
 * `Dispatchers.Default`; I/O hops via explicit `withContext` blocks.
 * Same pattern as `VoiceGuideManifestScope` (Stage 5-C).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AudioManifestScope

/**
 * Qualifier for the audio manifest URL string. Lets tests inject a
 * MockWebServer URL without patching [AudioConfig].
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AudioManifestUrl
