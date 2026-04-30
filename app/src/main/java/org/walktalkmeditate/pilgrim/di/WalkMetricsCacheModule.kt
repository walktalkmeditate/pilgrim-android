// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import org.walktalkmeditate.pilgrim.data.walk.WalkMetricsCache
import org.walktalkmeditate.pilgrim.data.walk.WalkMetricsCaching

/**
 * Stage 11-A binding for the walk-metrics cache. Lives in its own
 * abstract module because [WalkModule] is a `@Provides`-only object —
 * Dagger forbids mixing `@Binds` and `@Provides` in the same `object`.
 * Tests can replace this binding via Hilt's `TestInstallIn` to inject a
 * fake [WalkMetricsCaching] without subclassing the `@Singleton` impl.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class WalkMetricsCacheModule {

    @Binds
    @Singleton
    abstract fun bindWalkMetricsCaching(
        impl: WalkMetricsCache,
    ): WalkMetricsCaching
}
