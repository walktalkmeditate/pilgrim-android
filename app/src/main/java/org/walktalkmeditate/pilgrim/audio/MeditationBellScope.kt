// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import javax.inject.Qualifier

/**
 * Qualifier for the long-lived [kotlinx.coroutines.CoroutineScope]
 * that backs [MeditationBellObserver]'s state-transition collection.
 *
 * Separate from `viewModelScope` / other short-lived scopes because
 * the observer must live for the entire app process — it subscribes
 * to the walk-state flow on app creation and fires bells on every
 * Meditating transition across the app's lifetime. Provided with
 * `SupervisorJob()` so one failed emission doesn't cancel the whole
 * scope, and `Dispatchers.Default` since the work is pure Flow
 * collection + occasional MediaPlayer creation (no Main-thread
 * requirement). Same shape as `HemisphereRepositoryScope` (Stage 3-D).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MeditationBellScope

/**
 * Qualifier for the `StateFlow<WalkState>` the [MeditationBellObserver]
 * subscribes to. Provided in the Hilt graph by extracting
 * `WalkController.state` so the observer depends on the narrow
 * read-only flow interface — tests can inject any
 * `MutableStateFlow<WalkState>` without constructing a whole
 * `WalkController` with all its Room + clock dependencies.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MeditationObservedWalkState
