// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.pilgrim

import java.time.Instant
import kotlinx.serialization.Serializable

@Serializable
data class PilgrimStats(
    val distance: Double,
    val steps: Int? = null,
    val activeDuration: Double,
    val pauseDuration: Double,
    val ascent: Double,
    val descent: Double,
    val burnedEnergy: Double? = null,
    val talkDuration: Double,
    val meditateDuration: Double,
)

@Serializable
data class PilgrimWeather(
    val temperature: Double,
    val condition: String,
    val humidity: Double? = null,
    val windSpeed: Double? = null,
)

@Serializable
data class PilgrimPause(
    @Serializable(with = EpochSecondsInstantSerializer::class)
    val startDate: Instant,
    @Serializable(with = EpochSecondsInstantSerializer::class)
    val endDate: Instant,
    val type: String,
)

@Serializable
data class PilgrimActivity(
    val type: String,
    @Serializable(with = EpochSecondsInstantSerializer::class)
    val startDate: Instant,
    @Serializable(with = EpochSecondsInstantSerializer::class)
    val endDate: Instant,
)

@Serializable
data class PilgrimVoiceRecording(
    @Serializable(with = EpochSecondsInstantSerializer::class)
    val startDate: Instant,
    @Serializable(with = EpochSecondsInstantSerializer::class)
    val endDate: Instant,
    val duration: Double,
    val transcription: String? = null,
    val wordsPerMinute: Double? = null,
    val isEnhanced: Boolean,
)

@Serializable
data class PilgrimHeartRate(
    @Serializable(with = EpochSecondsInstantSerializer::class)
    val timestamp: Instant,
    val heartRate: Int,
)

@Serializable
data class PilgrimWorkoutEvent(
    @Serializable(with = EpochSecondsInstantSerializer::class)
    val timestamp: Instant,
    val type: String,
)

/**
 * Pinned reliquary photo. `localIdentifier` is the platform-native
 * resolver (iOS PHAsset id; Android `content://media/...` URI string).
 * `embeddedPhotoFilename` points at a file under the archive's
 * `photos/` directory — null when the bytes aren't embedded.
 *
 * `inlineUrl` is base64 data-URL enrichment used ONLY by the in-app
 * journey viewer; never serialized to a `.pilgrim` archive. On
 * Android, enrichment is performed by `JourneyViewerViewModel` when
 * the user's Photo Reliquary preference is enabled — otherwise the
 * field stays null.
 */
@Serializable
data class PilgrimPhoto(
    val localIdentifier: String,
    @Serializable(with = EpochSecondsInstantSerializer::class)
    val capturedAt: Instant,
    val capturedLat: Double,
    val capturedLng: Double,
    @Serializable(with = EpochSecondsInstantSerializer::class)
    val keptAt: Instant,
    val embeddedPhotoFilename: String? = null,
    val inlineUrl: String? = null,
)

@Serializable
data class PilgrimReflection(
    val style: String? = null,
    val text: String? = null,
    val celestialContext: PilgrimCelestialContext? = null,
)

@Serializable
data class PilgrimCelestialContext(
    val lunarPhase: PilgrimLunarPhase,
    val planetaryPositions: List<PilgrimPlanetaryPosition>,
    val planetaryHour: PilgrimPlanetaryHour,
    val elementBalance: PilgrimElementBalance,
    val seasonalMarker: String? = null,
    val zodiacSystem: String,
)

@Serializable
data class PilgrimLunarPhase(
    val name: String,
    val illumination: Double,
    val age: Double,
    val isWaxing: Boolean,
)

@Serializable
data class PilgrimPlanetaryPosition(
    val planet: String,
    val sign: String,
    val degree: Double,
    val isRetrograde: Boolean,
)

@Serializable
data class PilgrimPlanetaryHour(
    val planet: String,
    val planetaryDay: String,
)

@Serializable
data class PilgrimElementBalance(
    val fire: Int,
    val earth: Int,
    val air: Int,
    val water: Int,
    val dominant: String? = null,
)
