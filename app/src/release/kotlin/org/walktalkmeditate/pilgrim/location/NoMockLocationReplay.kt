// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.location

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Release has no replayer, so mock mode is never on and there is nothing to turn off. */
object NoMockLocationReplay : MockLocationReplay {
    override fun onTrackerStart() = Unit
}

@Module
@InstallIn(SingletonComponent::class)
object NoMockLocationReplayModule {
    @Provides
    fun provideMockLocationReplay(): MockLocationReplay = NoMockLocationReplay
}
