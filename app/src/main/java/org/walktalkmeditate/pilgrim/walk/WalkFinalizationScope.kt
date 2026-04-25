// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.walk

import javax.inject.Qualifier

/**
 * Qualifier for the long-lived [kotlinx.coroutines.CoroutineScope] that
 * backs [WalkFinalizationObserver]. Lives for the app process; uses
 * `SupervisorJob` so a single side-effect failure doesn't tear down
 * the observer + `Dispatchers.IO` because every collaborator
 * (transcriptionScheduler / hemisphere refresh / collective POST /
 * widget enqueue) is suspending I/O.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class WalkFinalizationScope

/**
 * Qualifier for the read-only `StateFlow<WalkState>` the
 * [WalkFinalizationObserver] subscribes to. Same pattern as
 * `MeditationObservedWalkState` — bind to `WalkController.state` so
 * tests can inject any flow without building a full controller.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class WalkFinalizationObservedState
