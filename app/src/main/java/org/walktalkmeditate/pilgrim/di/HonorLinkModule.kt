// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.walktalkmeditate.pilgrim.honor.DataStoreInstallReferrerStore
import org.walktalkmeditate.pilgrim.honor.InstallReferrerClientAdapter
import org.walktalkmeditate.pilgrim.honor.InstallReferrerStore
import org.walktalkmeditate.pilgrim.honor.PlayInstallReferrerClientAdapter

/** The install referrer's seams (Phase 21 U27): the Play client and the record it leaves. */
@Module
@InstallIn(SingletonComponent::class)
abstract class HonorLinkModule {

    @Binds
    abstract fun bindInstallReferrerClient(impl: PlayInstallReferrerClientAdapter): InstallReferrerClientAdapter

    @Binds
    abstract fun bindInstallReferrerStore(impl: DataStoreInstallReferrerStore): InstallReferrerStore
}
