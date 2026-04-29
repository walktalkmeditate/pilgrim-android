// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.pilgrim

import java.time.Instant
import kotlinx.serialization.Serializable

/**
 * Top-level walk entry in a `.pilgrim` archive's `walks/<uuid>.json`.
 * Field shape matches iOS `PilgrimPackageModels.swift` exactly.
 *
 * `photos` is null when the user opts out of including pinned reliquary
 * photos at export time. With `explicitNulls = false` on the project's
 * pilgrim Json instance, the key is OMITTED entirely from the output —
 * preserving byte-parity with pre-reliquary `.pilgrim` files.
 */
@Serializable
data class PilgrimWalk(
    val schemaVersion: String,
    val id: String,
    val type: String,
    @Serializable(with = EpochSecondsInstantSerializer::class)
    val startDate: Instant,
    @Serializable(with = EpochSecondsInstantSerializer::class)
    val endDate: Instant,
    val stats: PilgrimStats,
    val weather: PilgrimWeather? = null,
    val route: GeoJsonFeatureCollection,
    val pauses: List<PilgrimPause>,
    val activities: List<PilgrimActivity>,
    val voiceRecordings: List<PilgrimVoiceRecording>,
    val intention: String? = null,
    val reflection: PilgrimReflection? = null,
    val heartRates: List<PilgrimHeartRate>,
    val workoutEvents: List<PilgrimWorkoutEvent>,
    val favicon: String? = null,
    val isRace: Boolean,
    val isUserModified: Boolean,
    val finishedRecording: Boolean,
    val photos: List<PilgrimPhoto>? = null,
)
