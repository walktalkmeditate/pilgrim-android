// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.di

import javax.inject.Qualifier

/**
 * Qualifier for the process-lifetime [kotlinx.coroutines.CoroutineScope]
 * used by ViewModels to launch user-intent persistence writes (DAO
 * upserts, URI grant/release, DataStore.edit) that MUST survive the
 * ViewModel being cleared.
 *
 * `viewModelScope.launch(Dispatchers.IO) { repository.write() }` is
 * the obvious shape for fire-and-forget writes, but ViewModel
 * cancellation on back-nav (or composable disposal) cancels the
 * coroutine mid-flight when the user taps and immediately navigates
 * away. The optimistic UI flip already happened; the DB write is
 * lost; reload shows stale state. iOS doesn't have this hazard
 * because CoreStore's `dataStack.perform(asynchronous:)` runs on a
 * framework-managed background queue independent of any view's
 * lifecycle.
 *
 * Use this scope ONLY for writes whose semantics are "user committed
 * to this; persist regardless of UI lifecycle." Do NOT use it for:
 *   - VM-internal observers (`collect { ... }`) — those should die
 *     with the VM.
 *   - Event-back-to-UI flows (e.g. share dispatch, save snackbar) —
 *     those have no consumer once the VM is gone.
 *   - Best-effort cleanup (e.g. orphan sweepers) — partial work loss
 *     is acceptable; cancel-on-nav saves CPU.
 *
 * Provided by [WalkModule] as a `Singleton CoroutineScope(SupervisorJob() +
 * Dispatchers.IO)` — single scope for the whole process, so one failed
 * launch can't kill siblings, and IO dispatcher keeps DAO calls off
 * the Default pool.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PersistenceScope
