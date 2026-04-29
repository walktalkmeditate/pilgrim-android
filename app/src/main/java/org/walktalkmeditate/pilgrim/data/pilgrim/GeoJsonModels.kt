// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.pilgrim

import java.time.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive

/**
 * GeoJSON FeatureCollection — the wrapper around all walk geometry
 * (route LineString + waypoint Points).
 */
@Serializable
data class GeoJsonFeatureCollection(
    val type: String = "FeatureCollection",
    val features: List<GeoJsonFeature>,
)

@Serializable
data class GeoJsonFeature(
    val type: String = "Feature",
    val geometry: GeoJsonGeometry,
    val properties: GeoJsonProperties,
)

@Serializable
data class GeoJsonGeometry(
    val type: String,
    @Serializable(with = GeoJsonCoordinatesSerializer::class)
    val coordinates: GeoJsonCoordinates,
)

/**
 * Polymorphic coordinates union mirroring iOS `AnyCodableCoordinates`.
 * Decoded by inspecting the JSON array shape (array-of-arrays =
 * LineString; array-of-doubles = Point). Try-decode order matches
 * iOS: LineString first.
 */
@Serializable(with = GeoJsonCoordinatesSerializer::class)
sealed class GeoJsonCoordinates {
    data class Point(val coords: List<Double>) : GeoJsonCoordinates()
    data class LineString(val coords: List<List<Double>>) : GeoJsonCoordinates()
}

@Serializable
data class GeoJsonProperties(
    val timestamps: List<@Serializable(with = EpochSecondsInstantSerializer::class) Instant>? = null,
    val speeds: List<Double>? = null,
    val directions: List<Double>? = null,
    val horizontalAccuracies: List<Double>? = null,
    val verticalAccuracies: List<Double>? = null,
    val markerType: String? = null,
    val label: String? = null,
    val icon: String? = null,
    @Serializable(with = EpochSecondsInstantSerializer::class)
    val timestamp: Instant? = null,
)

object GeoJsonCoordinatesSerializer : KSerializer<GeoJsonCoordinates> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("GeoJsonCoordinates")

    override fun serialize(encoder: Encoder, value: GeoJsonCoordinates) {
        require(encoder is JsonEncoder) { "GeoJsonCoordinates only supports JSON encoding" }
        when (value) {
            is GeoJsonCoordinates.Point ->
                encoder.encodeSerializableValue(ListSerializer(Double.serializer()), value.coords)
            is GeoJsonCoordinates.LineString ->
                encoder.encodeSerializableValue(
                    ListSerializer(ListSerializer(Double.serializer())),
                    value.coords,
                )
        }
    }

    override fun deserialize(decoder: Decoder): GeoJsonCoordinates {
        require(decoder is JsonDecoder) { "GeoJsonCoordinates only supports JSON decoding" }
        val element = decoder.decodeJsonElement()
        require(element is JsonArray) { "GeoJSON coordinates must be a JSON array" }
        if (element.isEmpty()) {
            // Empty array could be either shape. Default to Point with
            // empty coords; the converter rejects empty geometries anyway
            // so this matches the behavior of an iOS round-trip.
            return GeoJsonCoordinates.Point(emptyList())
        }
        // Try LineString first (mirror iOS try-decode order).
        return if (element.first() is JsonArray) {
            val coords = element.map { row ->
                require(row is JsonArray) { "LineString coordinates must be array-of-arrays" }
                row.map { it.jsonPrimitive.double }
            }
            GeoJsonCoordinates.LineString(coords)
        } else {
            val coords = element.map { it.jsonPrimitive.double }
            GeoJsonCoordinates.Point(coords)
        }
    }
}
