// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.prompt

import javax.inject.Qualifier

/**
 * Qualifier for the long-lived [kotlinx.coroutines.CoroutineScope]
 * that backs [CustomPromptStyleStore.styles]'s `stateIn` collection.
 * Same pattern as `AppearancePreferencesScope`.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CustomPromptStyleScope
