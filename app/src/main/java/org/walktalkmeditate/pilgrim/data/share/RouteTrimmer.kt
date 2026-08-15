// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.share

import org.walktalkmeditate.pilgrim.domain.haversineMeters

/**
 * Port of iOS `RouteTrimmer.swift` (pin `3f9f9e8`): shaves `meters` of
 * walked distance off each end of a route so a shared page never
 * reveals a doorstep. Walks shorter than 4x the trim distance share
 * untrimmed — mid-walk geometry is all they have.
 */
internal object RouteTrimmer {

    fun trim(route: List<SharePayload.RoutePoint>, meters: Double): List<SharePayload.RoutePoint> {
        if (meters <= 0.0 || route.size <= 3) return route

        val cumulative = DoubleArray(route.size)
        for (i in 1 until route.size) {
            cumulative[i] = cumulative[i - 1] + haversineMeters(
                route[i - 1].lat, route[i - 1].lon, route[i].lat, route[i].lon,
            )
        }
        val total = cumulative[route.size - 1]
        if (total < meters * 4) return route

        var start = 0
        while (start < route.size - 1 && cumulative[start] < meters) start++
        var end = route.size - 1
        while (end > 0 && total - cumulative[end] < meters) end--
        if (end <= start) return route

        return route.subList(start, end + 1).toList()
    }

    /**
     * Whether trim can actually apply to the route — the UI uses this
     * to show "too short to trim" instead of silently promising
     * protection. Defined purely in terms of [trim]'s output length so
     * it can never disagree with what `trim` actually does.
     */
    fun canTrim(route: List<SharePayload.RoutePoint>, meters: Double): Boolean =
        trim(route, meters).size < route.size
}
