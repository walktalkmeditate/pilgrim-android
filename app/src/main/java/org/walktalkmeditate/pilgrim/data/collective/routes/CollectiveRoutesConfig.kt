// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.collective.routes

/**
 * Endpoint + asset-path constants for the collective route catalog.
 * Mirrors iOS's `Config.Collective`.
 */
internal object CollectiveRoutesConfig {
    /**
     * The route artifact `pilgrimapp.org` and both apps read, so a curator
     * edit reaches every surface from one publish. Full literal URL in one
     * place — the voice-guide double-path 404 lesson.
     */
    const val CATALOG_URL = "https://cdn.pilgrimapp.org/collective/routes.json"

    /** Verbatim copy of iOS `Pilgrim/Support Files/collective-routes-bootstrap.json` (plan R4). */
    const val BOOTSTRAP_ASSET_PATH = "collective/collective-routes-bootstrap.json"
}
