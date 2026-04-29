// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.serialization.json.Json

/**
 * Hilt-scoped `Json` for the `.pilgrim` package read/write paths.
 * Distinct from `NetworkModule.provideJson` (which omits prettyPrint
 * + uses `explicitNulls = false` for forward-compat manifest reads):
 * pilgrim files are pretty-printed for human inspection on disk +
 * encodeDefaults = false so `photos: null` collapses the key.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PilgrimJson

@Module
@InstallIn(SingletonComponent::class)
object PilgrimJsonModule {

    @Provides
    @Singleton
    @PilgrimJson
    fun providePilgrimJson(): Json = Json {
        prettyPrint = true
        encodeDefaults = false
        explicitNulls = false
        ignoreUnknownKeys = true
    }
}
