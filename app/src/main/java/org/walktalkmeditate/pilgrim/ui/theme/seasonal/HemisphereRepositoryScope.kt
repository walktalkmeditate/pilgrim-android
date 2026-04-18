// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.theme.seasonal

import javax.inject.Qualifier

/**
 * Qualifier for the application-scoped [kotlinx.coroutines.CoroutineScope]
 * that backs [HemisphereRepository.hemisphere]'s `stateIn` collection.
 * Kept as a dedicated qualifier (vs a generic `@ApplicationScope`) so
 * tests can substitute a test dispatcher without affecting other
 * Hilt-provided scopes.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class HemisphereRepositoryScope
