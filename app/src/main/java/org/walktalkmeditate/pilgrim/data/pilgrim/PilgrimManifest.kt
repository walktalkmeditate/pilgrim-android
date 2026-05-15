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
    /**
     * iOS v1.6.0: walks the user marked as "released" via the web
     * editor. Heavy payload (route / photos / audio / transcripts) is
     * stripped from these — only surface stats remain. `null` for
     * pre-1.6 files. iOS parity `PilgrimManifest.archived@v1.6.0`.
     */
    val archived: List<PilgrimArchivedWalk>? = null,
    /**
     * Edits the user applied via the web editor. Schema is loose — we
     * only check `isNotEmpty()` to detect a tended file vs a fresh
     * export. Tended files trigger overwrite-by-UUID in the importer
     * so the user's edits land on this device. iOS parity
     * `PilgrimManifest.modifications@v1.6.0`.
     */
    val modifications: List<PilgrimModification>? = null,
) {
    /** True when this file carries edits OR archives from the web editor. */
    val isTended: Boolean
        get() = (modifications?.isNotEmpty() == true) || (archived?.isNotEmpty() == true)
}

@Serializable
data class PilgrimArchivedWalk(
    val id: String,
    /** Epoch seconds. iOS encodes archived walk timestamps as Double. */
    val startDate: Double,
    val endDate: Double,
    val archivedAt: Double,
    val stats: Stats,
) {
    @Serializable
    data class Stats(
        val distance: Double,
        val activeDuration: Double,
        val talkDuration: Double,
        val meditateDuration: Double,
        val steps: Int? = null,
    )
}

/**
 * Placeholder for entries in `manifest.modifications[]`. iOS doesn't
 * replay individual ops on import — the editor already applied them
 * before writing the file. Schema is loose so the web editor can grow
 * new op types without breaking import.
 */
@Serializable
data class PilgrimModification(
    val op: String? = null,
    val walkId: String? = null,
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
