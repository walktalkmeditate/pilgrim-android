// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.pilgrim

/**
 * Embedded JSON Schema doc shipped at the root of every `.pilgrim`
 * archive as `schema.json`. Verbatim port of the iOS
 * `PilgrimPackageSchema.json` constant — same content + same
 * semantics so cross-platform schema awareness is preserved.
 */
object PilgrimSchema {

    const val VERSION = "1.0"

    val JSON: String = """
        {
          "${'$'}schema": "https://json-schema.org/draft/2020-12/schema",
          "title": "Pilgrim Walk Export",
          "description": "Schema for .pilgrim walk data files. Dates are seconds since 1970-01-01T00:00:00Z. Coordinates are [longitude, latitude, altitude].",
          "type": "object",
          "properties": {
            "schemaVersion": { "type": "string", "const": "1.0" },
            "id": { "type": "string", "format": "uuid" },
            "type": { "type": "string", "enum": ["walking", "unknown"] },
            "startDate": { "type": "number", "description": "seconds since epoch" },
            "endDate": { "type": "number", "description": "seconds since epoch" },
            "stats": {
              "type": "object",
              "properties": {
                "distance": { "type": "number", "description": "meters" },
                "steps": { "type": ["integer", "null"] },
                "activeDuration": { "type": "number", "description": "seconds" },
                "pauseDuration": { "type": "number", "description": "seconds" },
                "ascent": { "type": "number", "description": "meters" },
                "descent": { "type": "number", "description": "meters" },
                "burnedEnergy": { "type": ["number", "null"], "description": "kcal" },
                "talkDuration": { "type": "number", "description": "seconds" },
                "meditateDuration": { "type": "number", "description": "seconds" }
              },
              "required": ["distance", "activeDuration", "pauseDuration", "ascent", "descent", "talkDuration", "meditateDuration"]
            },
            "weather": { "type": ["object", "null"] },
            "route": {
              "type": "object",
              "description": "GeoJSON FeatureCollection. Coordinates are [longitude, latitude, altitude]."
            },
            "pauses": { "type": "array" },
            "activities": { "type": "array" },
            "voiceRecordings": { "type": "array" },
            "heartRates": { "type": "array" },
            "workoutEvents": { "type": "array" },
            "photos": {
              "type": ["array", "null"],
              "description": "Reliquary photos the user opted to include at export time. Each entry carries its PHAsset localIdentifier, GPS coordinates, captured/kept timestamps, and an optional embeddedPhotoFilename pointing at a file under the archive's photos/ directory. Absent entirely when the user opts out — older files and opted-out exports stay byte-identical."
            },
            "intention": { "type": ["string", "null"] },
            "reflection": { "type": ["object", "null"] },
            "favicon": { "type": ["string", "null"] },
            "isRace": { "type": "boolean" },
            "isUserModified": { "type": "boolean" },
            "finishedRecording": { "type": "boolean" }
          },
          "required": ["schemaVersion", "id", "type", "startDate", "endDate", "stats", "route", "pauses", "activities", "voiceRecordings", "heartRates", "workoutEvents"]
        }
    """.trimIndent()
}
