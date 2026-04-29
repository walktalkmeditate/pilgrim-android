// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.pilgrim

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoJsonModelsTest {

    private val json = Json { encodeDefaults = false; explicitNulls = false; ignoreUnknownKeys = true }

    @Test
    fun `decodes Point coordinates`() {
        val payload = """{"type":"Point","coordinates":[-122.3,47.6]}"""
        val geom = json.decodeFromString(GeoJsonGeometry.serializer(), payload)
        assertEquals("Point", geom.type)
        val coords = geom.coordinates
        assertTrue("expected Point, got $coords", coords is GeoJsonCoordinates.Point)
        assertEquals(listOf(-122.3, 47.6), (coords as GeoJsonCoordinates.Point).coords)
    }

    @Test
    fun `decodes LineString coordinates`() {
        val payload = """{"type":"LineString","coordinates":[[-122.3,47.6],[-122.4,47.7]]}"""
        val geom = json.decodeFromString(GeoJsonGeometry.serializer(), payload)
        assertEquals("LineString", geom.type)
        val coords = geom.coordinates
        assertTrue("expected LineString, got $coords", coords is GeoJsonCoordinates.LineString)
        assertEquals(2, (coords as GeoJsonCoordinates.LineString).coords.size)
        assertEquals(listOf(-122.3, 47.6), coords.coords[0])
    }

    @Test
    fun `round trips Point geometry`() {
        val original = GeoJsonGeometry(
            type = "Point",
            coordinates = GeoJsonCoordinates.Point(listOf(-122.3, 47.6, 50.0)),
        )
        val encoded = json.encodeToString(GeoJsonGeometry.serializer(), original)
        val decoded = json.decodeFromString(GeoJsonGeometry.serializer(), encoded)
        assertEquals(original.type, decoded.type)
        assertTrue(decoded.coordinates is GeoJsonCoordinates.Point)
        assertEquals(
            (original.coordinates as GeoJsonCoordinates.Point).coords,
            (decoded.coordinates as GeoJsonCoordinates.Point).coords,
        )
    }

    @Test
    fun `round trips LineString geometry with 3-tuple coords`() {
        val original = GeoJsonGeometry(
            type = "LineString",
            coordinates = GeoJsonCoordinates.LineString(
                listOf(
                    listOf(-122.3, 47.6, 50.0),
                    listOf(-122.4, 47.7, 55.0),
                ),
            ),
        )
        val encoded = json.encodeToString(GeoJsonGeometry.serializer(), original)
        val decoded = json.decodeFromString(GeoJsonGeometry.serializer(), encoded)
        assertTrue(decoded.coordinates is GeoJsonCoordinates.LineString)
        assertEquals(
            (original.coordinates as GeoJsonCoordinates.LineString).coords,
            (decoded.coordinates as GeoJsonCoordinates.LineString).coords,
        )
    }

    @Test
    fun `feature collection round trips with mixed feature types`() {
        val collection = GeoJsonFeatureCollection(
            features = listOf(
                GeoJsonFeature(
                    geometry = GeoJsonGeometry(
                        type = "LineString",
                        coordinates = GeoJsonCoordinates.LineString(
                            listOf(listOf(-122.3, 47.6, 50.0)),
                        ),
                    ),
                    properties = GeoJsonProperties(speeds = listOf(1.5, 2.0)),
                ),
                GeoJsonFeature(
                    geometry = GeoJsonGeometry(
                        type = "Point",
                        coordinates = GeoJsonCoordinates.Point(listOf(-122.3, 47.6)),
                    ),
                    properties = GeoJsonProperties(markerType = "waypoint", label = "Test"),
                ),
            ),
        )
        val encoded = json.encodeToString(GeoJsonFeatureCollection.serializer(), collection)
        val decoded = json.decodeFromString(GeoJsonFeatureCollection.serializer(), encoded)
        assertEquals(collection.features.size, decoded.features.size)
        assertTrue(decoded.features[0].geometry.coordinates is GeoJsonCoordinates.LineString)
        assertTrue(decoded.features[1].geometry.coordinates is GeoJsonCoordinates.Point)
    }
}
