// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.prompt

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Provides the [CustomPromptStyleScope] CoroutineScope binding for
 * [CustomPromptStyleStore]. Same shape as `AppearanceModule`'s scope
 * provider — long-lived `SupervisorJob` on `Dispatchers.Default`.
 *
 * The broader prompt module (Stage 13-XZ Task 10) will live alongside
 * this file once the coordinator + assembler + generator land. Keeping
 * the scope binding in its own module here lets [CustomPromptStyleStore]
 * be exercised in isolation by the unit-test harness without dragging
 * the rest of the prompts pipeline into existence.
 */
@Module
@InstallIn(SingletonComponent::class)
object CustomPromptStyleScopeModule {

    @Provides
    @Singleton
    @CustomPromptStyleScope
    fun provideCustomPromptStyleScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
