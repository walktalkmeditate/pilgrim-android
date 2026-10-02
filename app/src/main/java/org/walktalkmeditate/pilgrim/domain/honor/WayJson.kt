// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain.honor

import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Reads and writes `way.json` in the shape iOS's `WayStore` does
 * (`WayStore.swift:26-36@7c200bf`: `.iso8601` dates, `[.sortedKeys]`), so
 * files cross platforms unchanged (parity spec A §2): keys sorted at every
 * level, nil optionals omitted, unknown keys ignored, and an unknown enum
 * case failing the whole Way.
 *
 * Its own [Json] rather than the injected ones: key sorting is specific to
 * this format, and the model is read in `:tracker` and in plain unit tests,
 * neither of which has the Hilt graph. The `@PilgrimJson` instance also
 * pretty-prints, which iOS does not.
 *
 * Swift spells a whole-number `Double` as `60` where this writes `60.0`,
 * and escapes `/` as `\/`; every JSON reader, iOS's included, reads both.
 */
object WayJson {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun encode(way: Way): String = sortedKeys(json.encodeToJsonElement(Way.serializer(), way)).toString()

    /** Throws [SerializationException] on anything iOS's decoder would refuse. */
    fun decode(text: String): Way = json.decodeFromString(Way.serializer(), text)

    private fun sortedKeys(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.toSortedMap().mapValues { (_, value) -> sortedKeys(value) })
        is JsonArray -> JsonArray(element.map(::sortedKeys))
        else -> element
    }
}

/**
 * Swift's `.iso8601` strategy: UTC, whole seconds, `Z`. Sub-second time is
 * dropped on write, and a string carrying fractional seconds fails to read,
 * as it does on iOS.
 */
internal object WayDateSerializer : KSerializer<Instant> {

    private val readFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssXXX", Locale.ROOT)
        .withResolverStyle(ResolverStyle.STRICT)

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("org.walktalkmeditate.pilgrim.domain.honor.WayDate", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeString(DateTimeFormatter.ISO_INSTANT.format(value.truncatedTo(ChronoUnit.SECONDS)))
    }

    override fun deserialize(decoder: Decoder): Instant = try {
        OffsetDateTime.parse(decoder.decodeString(), readFormat).toInstant()
    } catch (e: DateTimeParseException) {
        // The message would carry the input; parse positions are enough.
        throw SerializationException("way.json date is not whole-second ISO 8601 (index ${e.errorIndex})")
    }
}

/*
 * Swift synthesizes `Codable` for an enum with associated values as an
 * object with exactly one key, the case name, holding an object of the
 * labeled values (`_0` for an unlabeled one). Each serializer below reads
 * and writes through a surrogate with one nullable field per case: nulls
 * are omitted on write, an unknown case key is ignored on read like any
 * unknown key, and anything but exactly one known case fails, as Swift's
 * decoder does.
 */

internal object WayMediaSerializer : KSerializer<WayMedia> {

    @Serializable
    private class Wire(
        val file: WayMedia.File? = null,
        val recording: WayMedia.Recording? = null,
        val photoAsset: WayMedia.PhotoAsset? = null,
    )

    override val descriptor: SerialDescriptor =
        SerialDescriptor("org.walktalkmeditate.pilgrim.domain.honor.WayMedia", Wire.serializer().descriptor)

    override fun serialize(encoder: Encoder, value: WayMedia) {
        val wire = when (value) {
            is WayMedia.File -> Wire(file = value)
            is WayMedia.Recording -> Wire(recording = value)
            is WayMedia.PhotoAsset -> Wire(photoAsset = value)
        }
        encoder.encodeSerializableValue(Wire.serializer(), wire)
    }

    override fun deserialize(decoder: Decoder): WayMedia {
        val wire = decoder.decodeSerializableValue(Wire.serializer())
        return exactlyOneCase("WayMedia", wire.file, wire.recording, wire.photoAsset)
    }
}

internal object WayMomentKindSerializer : KSerializer<WayMomentKind> {

    @Serializable
    private class Wire(
        val voice: WayMomentKind.Voice? = null,
        val photo: WayMomentKind.Photo? = null,
        val waypoint: WayMomentKind.Waypoint? = null,
        val rest: WayMomentKind.Rest? = null,
        val meditation: WayMomentKind.Meditation? = null,
    )

    override val descriptor: SerialDescriptor =
        SerialDescriptor("org.walktalkmeditate.pilgrim.domain.honor.WayMomentKind", Wire.serializer().descriptor)

    override fun serialize(encoder: Encoder, value: WayMomentKind) {
        val wire = when (value) {
            is WayMomentKind.Voice -> Wire(voice = value)
            is WayMomentKind.Photo -> Wire(photo = value)
            is WayMomentKind.Waypoint -> Wire(waypoint = value)
            is WayMomentKind.Rest -> Wire(rest = value)
            is WayMomentKind.Meditation -> Wire(meditation = value)
        }
        encoder.encodeSerializableValue(Wire.serializer(), wire)
    }

    override fun deserialize(decoder: Decoder): WayMomentKind {
        val wire = decoder.decodeSerializableValue(Wire.serializer())
        return exactlyOneCase("WayMomentKind", wire.voice, wire.photo, wire.waypoint, wire.rest, wire.meditation)
    }
}

internal object WaySourceSerializer : KSerializer<WaySource> {

    @Serializable
    private class Wire(
        val ownWalk: WaySource.OwnWalk? = null,
        val share: WaySource.Share? = null,
        val pilgrimage: WaySource.Pilgrimage? = null,
    )

    override val descriptor: SerialDescriptor =
        SerialDescriptor("org.walktalkmeditate.pilgrim.domain.honor.WaySource", Wire.serializer().descriptor)

    override fun serialize(encoder: Encoder, value: WaySource) {
        val wire = when (value) {
            is WaySource.OwnWalk -> Wire(ownWalk = value)
            is WaySource.Share -> Wire(share = value)
            is WaySource.Pilgrimage -> Wire(pilgrimage = value)
        }
        encoder.encodeSerializableValue(Wire.serializer(), wire)
    }

    override fun deserialize(decoder: Decoder): WaySource {
        val wire = decoder.decodeSerializableValue(Wire.serializer())
        return exactlyOneCase("WaySource", wire.ownWalk, wire.share, wire.pilgrimage)
    }
}

private fun <T : Any> exactlyOneCase(type: String, vararg cases: T?): T =
    cases.filterNotNull().singleOrNull()
        ?: throw SerializationException("$type needs exactly one known case key")
