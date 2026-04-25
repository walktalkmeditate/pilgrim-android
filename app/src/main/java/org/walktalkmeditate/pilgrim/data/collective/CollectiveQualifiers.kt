// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.collective

import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CounterHttpClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CounterBaseUrl

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CollectiveRepoScope
