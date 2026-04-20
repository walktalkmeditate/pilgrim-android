// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.voiceguide

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure parsing tests for [VoiceGuideManifest]. Locks in iOS-shape
 * compatibility: field names, types, optional handling, and
 * forward-compatibility flags. No Android runtime needed — these
 * run on the JVM-only harness.
 */
class VoiceGuideManifestTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Test fun `parses minimal manifest with empty packs`() {
        val body = """{"version":"2026-01-01","packs":[]}"""
        val manifest = json.decodeFromString<VoiceGuideManifest>(body)
        assertEquals("2026-01-01", manifest.version)
        assertTrue(manifest.packs.isEmpty())
    }

    @Test fun `parses pack with walk prompts and no meditation`() {
        val body = """
            {
              "version": "2026-01-01",
              "packs": [
                {
                  "id": "morning-walk",
                  "version": "1.0",
                  "name": "Morning Walk",
                  "tagline": "Start the day gently",
                  "description": "A slow, quiet opening.",
                  "theme": "dawn",
                  "iconName": "sunrise",
                  "type": "walk",
                  "walkTypes": ["solo", "urban"],
                  "scheduling": {
                    "densityMinSec": 120,
                    "densityMaxSec": 240,
                    "minSpacingSec": 60,
                    "initialDelaySec": 30,
                    "walkEndBufferSec": 60
                  },
                  "totalDurationSec": 180.5,
                  "totalSizeBytes": 1048576,
                  "prompts": [
                    {
                      "id": "p1",
                      "seq": 1,
                      "durationSec": 12.5,
                      "fileSizeBytes": 65536,
                      "r2Key": "morning-walk/p1.m4a",
                      "phase": "opening"
                    }
                  ]
                }
              ]
            }
        """.trimIndent()
        val manifest = json.decodeFromString<VoiceGuideManifest>(body)
        assertEquals(1, manifest.packs.size)
        val pack = manifest.packs.first()
        assertEquals("morning-walk", pack.id)
        assertEquals(listOf("solo", "urban"), pack.walkTypes)
        assertEquals(120, pack.scheduling.densityMinSec)
        assertEquals(1, pack.prompts.size)
        assertEquals("opening", pack.prompts.first().phase)
        assertNull(pack.meditationScheduling)
        assertNull(pack.meditationPrompts)
        assertFalse(pack.hasMeditationGuide)
    }

    @Test fun `parses pack with meditation prompts and computes hasMeditationGuide`() {
        val body = """
            {
              "version": "2026-01-01",
              "packs": [
                {
                  "id": "noon-sit",
                  "version": "1.0",
                  "name": "Noon Sit",
                  "tagline": "Pause at midday",
                  "description": "Guided mid-walk meditation.",
                  "theme": "sun",
                  "iconName": "sun",
                  "type": "meditate",
                  "walkTypes": [],
                  "scheduling": {
                    "densityMinSec": 0,
                    "densityMaxSec": 0,
                    "minSpacingSec": 0,
                    "initialDelaySec": 0,
                    "walkEndBufferSec": 0
                  },
                  "totalDurationSec": 0.0,
                  "totalSizeBytes": 0,
                  "prompts": [],
                  "meditationScheduling": {
                    "densityMinSec": 60,
                    "densityMaxSec": 120,
                    "minSpacingSec": 30,
                    "initialDelaySec": 15,
                    "walkEndBufferSec": 0
                  },
                  "meditationPrompts": [
                    {
                      "id": "m1",
                      "seq": 1,
                      "durationSec": 8.0,
                      "fileSizeBytes": 40960,
                      "r2Key": "noon-sit/m1.m4a"
                    }
                  ]
                }
              ]
            }
        """.trimIndent()
        val manifest = json.decodeFromString<VoiceGuideManifest>(body)
        val pack = manifest.packs.first()
        assertEquals(60, pack.meditationScheduling?.densityMinSec)
        assertEquals(1, pack.meditationPrompts?.size)
        assertNull(pack.meditationPrompts?.first()?.phase)
        assertTrue(pack.hasMeditationGuide)
    }

    @Test fun `ignores unknown top-level fields for forward compatibility`() {
        val body = """
            {
              "version": "2026-01-01",
              "packs": [],
              "experimental_feature": {"foo": 42},
              "newField": "future iOS addition"
            }
        """.trimIndent()
        val manifest = json.decodeFromString<VoiceGuideManifest>(body)
        assertEquals("2026-01-01", manifest.version)
    }

    @Test fun `ignores unknown fields inside pack and prompt`() {
        val body = """
            {
              "version": "2026-01-01",
              "packs": [
                {
                  "id": "p",
                  "version": "1.0",
                  "name": "n",
                  "tagline": "t",
                  "description": "d",
                  "theme": "x",
                  "iconName": "i",
                  "type": "walk",
                  "walkTypes": [],
                  "scheduling": {
                    "densityMinSec": 1,
                    "densityMaxSec": 2,
                    "minSpacingSec": 3,
                    "initialDelaySec": 4,
                    "walkEndBufferSec": 5
                  },
                  "totalDurationSec": 0.0,
                  "totalSizeBytes": 0,
                  "prompts": [
                    {
                      "id": "a",
                      "seq": 1,
                      "durationSec": 1.0,
                      "fileSizeBytes": 1,
                      "r2Key": "k",
                      "futureProperty": "ignored"
                    }
                  ],
                  "unknownPackField": true
                }
              ]
            }
        """.trimIndent()
        // Should not throw despite `futureProperty` and `unknownPackField`.
        val manifest = json.decodeFromString<VoiceGuideManifest>(body)
        assertEquals(1, manifest.packs.first().prompts.size)
    }

    @Test fun `round-trips through encode and decode`() {
        val original = VoiceGuideManifest(
            version = "2026-04-20",
            packs = listOf(
                VoiceGuidePack(
                    id = "rt-1",
                    version = "1.0",
                    name = "Round Trip",
                    tagline = "tagline",
                    description = "description",
                    theme = "mist",
                    iconName = "mist",
                    type = "walk",
                    walkTypes = listOf("urban"),
                    scheduling = PromptDensity(60, 120, 30, 15, 30),
                    totalDurationSec = 90.0,
                    totalSizeBytes = 2048L,
                    prompts = listOf(
                        VoiceGuidePrompt(
                            id = "x",
                            seq = 1,
                            durationSec = 5.0,
                            fileSizeBytes = 1024L,
                            r2Key = "rt-1/x.m4a",
                            phase = null,
                        ),
                    ),
                ),
            ),
        )
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<VoiceGuideManifest>(encoded)
        assertEquals(original, decoded)
    }
}
