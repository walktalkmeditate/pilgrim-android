// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.pilgrim

import java.time.Instant
import kotlinx.serialization.Serializable

/**
 * `manifest.json` at the root of every `.pilgrim` archive. Carries
 * the schema version, export metadata, user preferences, custom
 * prompt styles, intention history, and event groupings. Field shape
 * matches iOS `PilgrimManifest` in `PilgrimPackageModels.swift`.
 *
 * Stage 10-I emits `customPromptStyles`, `intentions`, and `events`
 * as empty arrays — Android has no equivalent storage for these
 * iOS-specific surfaces. Imports drop them silently.
 */
@Serializable
data class PilgrimManifest(
    val schemaVersion: String,
    @Serializable(with = EpochSecondsInstantSerializer::class)
    val exportDate: Instant,
    val appVersion: String,
    val walkCount: Int,
    val preferences: PilgrimPreferences,
    val customPromptStyles: List<PilgrimCustomPromptStyle>,
    val intentions: List<String>,
    val events: List<PilgrimEvent>,
)

@Serializable
data class PilgrimPreferences(
    val distanceUnit: String,
    val altitudeUnit: String,
    val speedUnit: String,
    val energyUnit: String,
    val celestialAwareness: Boolean,
    val zodiacSystem: String,
    val beginWithIntention: Boolean,
)

@Serializable
data class PilgrimCustomPromptStyle(
    val id: String,
    val title: String,
    val icon: String,
    val instruction: String,
)

@Serializable
data class PilgrimEvent(
    val id: String,
    val title: String,
    val comment: String? = null,
    @Serializable(with = EpochSecondsInstantSerializer::class)
    val startDate: Instant? = null,
    @Serializable(with = EpochSecondsInstantSerializer::class)
    val endDate: Instant? = null,
    val walkIds: List<String>,
)
