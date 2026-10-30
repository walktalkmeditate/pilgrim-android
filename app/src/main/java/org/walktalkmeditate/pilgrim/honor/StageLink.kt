// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.honor

import org.walktalkmeditate.pilgrim.data.honor.WayStore
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageCatalog
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageCatalogEntry

/**
 * A route page opened from a stage link, and the stage it brings into
 * view: iOS `HonorRouteLink` (`StageLink.swift:3-12@3b78728`). Its [id]
 * carries the focus, so a second link to another stage of the same route
 * opens the page again rather than leaving it where it was. [focus] is
 * 0-based, and null for a stage past the route's end.
 */
data class HonorRouteLink(val entry: PilgrimageCatalogEntry, val release: String, val focus: Int?) {
    val id: String get() = "${entry.id}:${focus?.toString() ?: "-"}"
}

/** iOS `StageLinkOutcome` (`StageLink.swift:14-19@3b78728`). */
sealed interface StageLinkOutcome {
    data class Overview(val wayId: String) : StageLinkOutcome

    data class RouteSheet(val link: HonorRouteLink) : StageLinkOutcome

    data object NotInCatalog : StageLinkOutcome

    data object NeedsCatalog : StageLinkOutcome
}

/**
 * What a stage link does, decided with no disk and no network: iOS
 * `StageLinkResolver` (`StageLink.swift:21-37@3b78728`). It is what tapping
 * that stage on the route page does, so a downloaded stage never waits on
 * the catalog. An installed route whose stage Way is missing (an older
 * release), a route not installed, or another route installed all open the
 * route page; Replace is that page's question.
 */
object StageLinkResolver {

    fun resolve(
        routeId: String,
        index: Int,
        installedRouteId: String?,
        stageWayExists: Boolean,
        catalog: PilgrimageCatalog?,
    ): StageLinkOutcome {
        if (installedRouteId == routeId && stageWayExists) {
            return StageLinkOutcome.Overview(WayStore.stageWayId(routeId, index))
        }
        catalog ?: return StageLinkOutcome.NeedsCatalog
        val entry = catalog.routes.firstOrNull { it.id == routeId } ?: return StageLinkOutcome.NotInCatalog
        val focus = index.takeIf { it in 0 until entry.stageCount }
        return StageLinkOutcome.RouteSheet(HonorRouteLink(entry, catalog.release, focus))
    }
}
