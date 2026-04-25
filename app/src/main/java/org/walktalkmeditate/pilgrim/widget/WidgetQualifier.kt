// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.widget

import javax.inject.Qualifier

/**
 * Qualifier for the widget-specific DataStore. Stage 8-A's
 * `@Qualifier annotation class` pattern (avoid `@JvmInline value class`
 * Hilt-factory-visibility trap).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class WidgetDataStore
