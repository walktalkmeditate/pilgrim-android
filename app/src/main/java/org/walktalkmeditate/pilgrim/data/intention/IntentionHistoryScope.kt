// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.intention

import javax.inject.Qualifier

/**
 * Qualifier for the long-lived [kotlinx.coroutines.CoroutineScope]
 * backing [IntentionHistoryRepository]'s `stateIn` collection. Same
 * pattern as `PracticePreferencesScope`.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IntentionHistoryScope
