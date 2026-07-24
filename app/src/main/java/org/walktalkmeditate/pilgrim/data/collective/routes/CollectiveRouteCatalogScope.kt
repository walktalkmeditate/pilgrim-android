// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.collective.routes

import javax.inject.Qualifier

/**
 * Qualifier for the long-lived [kotlinx.coroutines.CoroutineScope] backing
 * [CollectiveRouteCatalogService]'s initial load + sync coroutines. Same
 * shape as `VoiceGuideManifestScope`: `SupervisorJob` so a failed fetch
 * doesn't tear the scope down, `Dispatchers.Default` with explicit
 * `withContext(Dispatchers.IO)` hops at the file/network seams. Production
 * binding lives in `CollectiveModule`.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CollectiveRouteCatalogScope

/**
 * Qualifier for the catalog URL string. Lets tests inject a MockWebServer
 * URL without patching the production `const val` in [CollectiveRoutesConfig].
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CollectiveRouteCatalogUrl

/**
 * Qualifier for the bundled-bootstrap asset path — the injectable stand-in
 * for iOS's `bootstrapCatalogURL` closure, so tests can exercise the
 * missing-bootstrap path against a nonexistent asset.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CollectiveRouteBootstrapAsset
