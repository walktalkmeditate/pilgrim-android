// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain.honor

import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * The Way model: a port of iOS `Pilgrim/Models/Honor/Way.swift@7c200bf`.
 * Property and case names are the Swift ones, because the wire keys of
 * `way.json` are Swift's synthesized `Codable` keys (parity spec A §2);
 * [WayJson] is the one reader and writer. As in Swift, a `let` optional has
 * no default and the `var` optionals added later default to null.
 */

@Serializable
data class WayCoordinate(
    val lat: Double,
    val lon: Double,
)

@Serializable
data class WayPoint(
    val lat: Double,
    val lon: Double,
    val alt: Double?,
    /** Seconds since the first route point, wall clock: the walker's pauses are inside it. */
    val t: Double,
)

@Serializable
enum class VoiceKind {
    @SerialName("spoken")
    SPOKEN,

    @SerialName("ambient")
    AMBIENT,
}

/** Where a moment's file lives. Wire form: a one-key object named for the case. */
@Serializable(with = WayMediaSerializer::class)
sealed interface WayMedia {

    /** Shared walks: relative to the Way's `media/` folder. */
    @Serializable
    data class File(
        @SerialName("_0") val path: String,
    ) : WayMedia

    /** Own walk: the recording's `fileRelativePath`. */
    @Serializable
    data class Recording(
        val relativePath: String,
    ) : WayMedia

    /** Own walk: the photo's `WalkPhoto.photoUri`, the Android stand-in for a PhotoKit identifier. */
    @Serializable
    data class PhotoAsset(
        val localIdentifier: String,
    ) : WayMedia
}

@Serializable(with = WayMomentKindSerializer::class)
sealed interface WayMomentKind {

    @Serializable
    data class Voice(
        val endFrac: Double,
        val duration: Double,
        val kind: VoiceKind,
        val media: WayMedia,
    ) : WayMomentKind

    @Serializable
    data class Photo(
        val media: WayMedia,
    ) : WayMomentKind

    @Serializable
    data class Waypoint(
        val label: String,
        val icon: String,
    ) : WayMomentKind

    @Serializable
    data class Rest(
        val minutes: Int,
    ) : WayMomentKind

    @Serializable
    data class Meditation(
        val minutes: Int,
        val isEstimate: Boolean,
    ) : WayMomentKind
}

@Serializable
data class WayMoment(
    val id: String,
    /** Distance fraction along the route: orders moments and gates progress. */
    val frac: Double,
    /** The true place when the source knows it; null falls back to [WayGeometry.coordinate]. */
    val at: WayCoordinate?,
    val kind: WayMomentKind,
    val place: String? = null,
    val transcript: String? = null,
    val text: String? = null,
    val names: Map<String, String>? = null,
    val sitMinutes: Int? = null,
    val pin: WayCoordinate? = null,
) {
    /** The transcript's first sentence, for a card with one line to spare. */
    val transcriptLine: String? get() = firstSentence(transcript, maxCharacters = TRANSCRIPT_LINE_CHARACTERS)

    val isVoice: Boolean get() = kind is WayMomentKind.Voice

    /** The file behind a voice or photo moment; null for the other kinds. */
    val media: WayMedia?
        get() = when (kind) {
            is WayMomentKind.Voice -> kind.media
            is WayMomentKind.Photo -> kind.media
            is WayMomentKind.Waypoint, is WayMomentKind.Rest, is WayMomentKind.Meditation -> null
        }

    companion object {
        const val MAX_TRANSCRIPT_CHARACTERS = 600
        private const val TRANSCRIPT_LINE_CHARACTERS = 120
        private const val SENTENCE_ENDS = ".!?"

        /**
         * Trims Swift's whitespace set, drops the empty, and caps at
         * [MAX_TRANSCRIPT_CHARACTERS]. Characters are counted as Swift
         * counts them, by grapheme cluster (`String.prefix`,
         * `Way.swift:71-74@7c200bf`; shared-walk spec S1 §9 trap 2).
         */
        fun trimmedTranscript(raw: String?): String? = raw.trimmedOrNull(MAX_TRANSCRIPT_CHARACTERS)

        /**
         * The text up to and including the first `.`, `!`, or `?`; past
         * [maxCharacters] it is cut back to the last space and ends in `…`
         * (`Way.swift:76-88@7c200bf`). A decimal point ends a sentence too.
         */
        fun firstSentence(transcript: String?, maxCharacters: Int): String? {
            val text = trimmedTranscript(transcript) ?: return null
            val end = text.indexOfFirst { it in SENTENCE_ENDS }
            val sentence = if (end >= 0) text.substring(0, end + 1) else text
            if (sentence.characterCount() <= maxCharacters) return sentence
            val cut = sentence.prefixCharacters(maxCharacters)
            val lastSpace = cut.lastIndexOf(' ')
            return (if (lastSpace >= 0) cut.substring(0, lastSpace) else cut) + "…"
        }
    }
}

@Serializable
enum class WayMarkKind {
    @SerialName("water")
    WATER,

    @SerialName("food")
    FOOD,

    @SerialName("bed")
    BED,

    @SerialName("transport")
    TRANSPORT,

    @SerialName("supply")
    SUPPLY,

    @SerialName("medical")
    MEDICAL,
}

/** A pilgrimage stage's service point: drawn, never a moment. Stage-only. */
@Serializable
data class WayMark(
    val id: String,
    val kind: WayMarkKind,
    val name: String,
    val at: WayCoordinate,
    val frac: Double,
    val offLineMeters: Double,
)

@Serializable
data class WayStageHours(
    val min: Double,
    val max: Double,
)

@Serializable
data class WayStagePlace(
    val name: String,
    val at: WayCoordinate,
)

/** The stage block a pilgrimage Way carries. Stage-only. */
@Serializable
data class WayStage(
    val routeId: String,
    /** Zero-based, as the dataset numbers stages. */
    val index: Int,
    val count: Int,
    val name: String,
    val theme: String,
    val narrative: String,
    val closing: String,
    val warnings: List<String>,
    val distanceKm: Double,
    val gainMeters: Double,
    val hours: WayStageHours,
    val difficulty: String,
    val start: WayStagePlace,
    val end: WayStagePlace,
)

@Serializable(with = WaySourceSerializer::class)
sealed interface WaySource {

    /** The walk's uuid string, verbatim: lowercase on Android walks, uppercase on iOS imports. */
    @Serializable
    data class OwnWalk(
        @SerialName("_0") val uuid: String,
    ) : WaySource

    @Serializable
    data class Share(
        val id: String,
        @SerialName("pageURL") val pageUrl: String,
    ) : WaySource

    @Serializable
    data class Pilgrimage(
        val routeId: String,
        val stageIndex: Int,
    ) : WaySource

    /** Only the pilgrimage package writes a stage's `way.json`; nothing else may save over it. */
    val isPackageOwned: Boolean get() = this is Pilgrimage
}

@Serializable
data class WayWeather(
    val condition: String,
    val temperatureC: Double?,
)

@Serializable
enum class WaySpanKind {
    @SerialName("meditating")
    MEDITATING,

    @SerialName("talking")
    TALKING,
}

/** A stretch walked in another practice, by distance fraction. The ghost line colors these. */
@Serializable
data class WaySpan(
    val startFrac: Double,
    val endFrac: Double,
    val kind: WaySpanKind,
)

@Serializable
data class Way(
    val id: String,
    val source: WaySource,
    val title: String,
    @Serializable(with = WayDateSerializer::class) val departedAt: Instant,
    val tzIdentifier: String?,
    @Serializable(with = WayDateSerializer::class) val expires: Instant?,
    val route: List<WayPoint>,
    val totalDistanceMeters: Double,
    val theirActiveSeconds: Double,
    val moments: List<WayMoment>,
    val weather: WayWeather?,
    /** Null on a `way.json` written before spans existed: an all-walking Way. */
    val spans: List<WaySpan>? = null,
    val marks: List<WayMark>? = null,
    val stage: WayStage? = null,
) {
    val voiceCount: Int get() = moments.count { it.isVoice }

    val photoCount: Int get() = moments.count { it.kind is WayMomentKind.Photo }

    val isPilgrimageStage: Boolean get() = stage != null
}
