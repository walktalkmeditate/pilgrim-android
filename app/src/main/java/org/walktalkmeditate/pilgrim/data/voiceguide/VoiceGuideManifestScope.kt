// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.voiceguide

import javax.inject.Qualifier

/**
 * Qualifier for the long-lived [kotlinx.coroutines.CoroutineScope]
 * that backs [VoiceGuideManifestService]'s sync coroutine. Separate
 * from `viewModelScope` / other short-lived scopes because a sync
 * request may outlive the screen that triggered it (user navigates
 * away mid-fetch; the fetch completes and updates the cache
 * regardless). `SupervisorJob` so one failed emission doesn't tear
 * the whole scope down. `Dispatchers.Default` since the work is
 * Flow state updates + file I/O hops via explicit
 * `withContext(Dispatchers.IO)` blocks. Same shape as
 * `MeditationBellScope` (Stage 5-B) and `HemisphereRepositoryScope`
 * (Stage 3-D).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class VoiceGuideManifestScope

/**
 * Qualifier for the manifest URL string. Lets tests inject a
 * MockWebServer URL without patching the production `const val` in
 * [VoiceGuideConfig]. Production binding lives in `NetworkModule`.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class VoiceGuideManifestUrl
