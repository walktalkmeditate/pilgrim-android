// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.honor

import javax.inject.Qualifier
import kotlinx.coroutines.flow.StateFlow

/**
 * The Honor preferences the UI owns. iOS `UserPreferences.honorVoicesEnabled`
 * (`UserPreferences.swift:77@7c200bf`, default on): the overview's "walk with
 * their voice" writes it on every change, and the walk screen's Start reads it
 * once, AND-ed with the Sounds switch (`HonorSettings.atStart`). Sticky across
 * walks and overviews, never per Way. Never read in `:tracker`: the value
 * rides the start intent.
 */
interface HonorPreferencesRepository {
    val voicesEnabled: StateFlow<Boolean>
    suspend fun setVoicesEnabled(value: Boolean)

    /**
     * The stored value, once DataStore has read it from disk. The walk
     * screen's Start reads this rather than [voicesEnabled]'s `.value`,
     * which is the default until the first read lands.
     */
    suspend fun awaitVoicesEnabled(): Boolean
}

/** The long-lived scope behind [HonorPreferencesRepository]'s `stateIn`. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class HonorPreferencesScope
