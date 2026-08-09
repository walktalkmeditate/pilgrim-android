// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.di

import android.app.Application
import android.content.Context
import android.util.Log
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import org.walktalkmeditate.pilgrim.domain.WalkState
import org.walktalkmeditate.pilgrim.walk.UiWalkController
import org.walktalkmeditate.pilgrim.walk.WalkController
import org.walktalkmeditate.pilgrim.walk.WalkControllerImpl
import org.walktalkmeditate.pilgrim.walk.WalkFinalizationObservedState
import org.walktalkmeditate.pilgrim.walk.WalkFinalizationScope

@Module
@InstallIn(SingletonComponent::class)
object WalkModule {

    /**
     * Resolve [WalkController] per process:
     *  - UI process: [UiWalkController] — state from Room, mutations
     *    fire intents to the `:tracker` service.
     *  - `:tracker` process: [WalkControllerImpl] — in-memory state
     *    machine + Room writes + GPS pipeline.
     *
     * Both impls are `dagger.Lazy` so the unused one in each process
     * is never instantiated (its dependency graph never runs). The
     * UI process never builds [WalkControllerImpl]'s StepCounter /
     * LocationSource dependencies; the tracker process never builds
     * [UiWalkController]'s [WalkActionPublisher].
     */
    @Provides
    @Singleton
    fun provideWalkController(
        @ApplicationContext context: Context,
        trackerImpl: Lazy<WalkControllerImpl>,
        uiImpl: Lazy<UiWalkController>,
    ): WalkController = if (isMainProcess(context)) {
        uiImpl.get()
    } else {
        trackerImpl.get()
    }

    private fun isMainProcess(context: Context): Boolean =
        Application.getProcessName() == context.packageName

    /**
     * Hosts [org.walktalkmeditate.pilgrim.walk.WalkFinalizationObserver],
     * [org.walktalkmeditate.pilgrim.walk.WalkLifecycleObserver] and
     * [UiWalkController]'s Room-derived state collector.
     *
     * The [CoroutineExceptionHandler] is not optional. `SupervisorJob`
     * stops one failed child from cancelling its siblings, but it does
     * NOT stop the throw itself — without a handler it reaches
     * `Thread.UncaughtExceptionHandler` and takes the process down.
     * Every child here does DataStore / Room / network I/O at the exact
     * moment a walk ends, so the crash would land on the user's finish
     * tap. Same policy as `CollectiveModule.provideCollectiveRepoScope`.
     */
    @Provides
    @Singleton
    @WalkFinalizationScope
    fun provideWalkFinalizationScope(): CoroutineScope {
        val handler = CoroutineExceptionHandler { _, t ->
            Log.w("WalkFinalizationScope", "uncaught in walk-finalization scope", t)
        }
        return CoroutineScope(SupervisorJob() + Dispatchers.IO + handler)
    }

    // PersistenceScope is generic (not walk-specific); housed here for now
    // because WalkSummaryViewModel is the first consumer. Move to a
    // dedicated `CoroutineScopesModule` when a second VM injects it
    // (Settings VM toggles + WalkVM dwell-screen writes are the obvious
    // next candidates per the sweep PR triage).
    @Provides
    @Singleton
    @PersistenceScope
    fun providePersistenceScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Provides
    @Singleton
    @WalkFinalizationObservedState
    fun provideWalkFinalizationObservedState(
        controller: WalkController,
    ): StateFlow<WalkState> = controller.state
}
