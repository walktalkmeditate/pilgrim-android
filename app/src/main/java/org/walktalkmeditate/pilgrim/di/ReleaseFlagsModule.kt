// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.walktalkmeditate.pilgrim.core.flags.BuildConfigReleaseFlags
import org.walktalkmeditate.pilgrim.core.flags.ReleaseFlags

@Module
@InstallIn(SingletonComponent::class)
abstract class ReleaseFlagsModule {
    @Binds
    abstract fun bindReleaseFlags(impl: BuildConfigReleaseFlags): ReleaseFlags
}
