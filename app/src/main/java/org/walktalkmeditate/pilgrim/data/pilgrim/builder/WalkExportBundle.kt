// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.pilgrim.builder

import org.walktalkmeditate.pilgrim.data.entity.ActivityInterval
import org.walktalkmeditate.pilgrim.data.entity.AltitudeSample
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.entity.WalkEvent
import org.walktalkmeditate.pilgrim.data.entity.WalkPhoto
import org.walktalkmeditate.pilgrim.data.entity.Waypoint

/**
 * All Room rows the [PilgrimPackageConverter] needs for a single
 * walk. Builder loads these once per walk via [WalkRepository] reads
 * + the walk-photo dao, then passes the bundle to the converter.
 */
data class WalkExportBundle(
    val walk: Walk,
    val routeSamples: List<RouteDataSample>,
    val altitudeSamples: List<AltitudeSample>,
    val walkEvents: List<WalkEvent>,
    val activityIntervals: List<ActivityInterval>,
    val waypoints: List<Waypoint>,
    val voiceRecordings: List<VoiceRecording>,
    val walkPhotos: List<WalkPhoto>,
)
