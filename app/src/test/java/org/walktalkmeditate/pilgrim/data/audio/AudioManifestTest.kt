// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.audio

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure parsing tests for [AudioManifest] / [AudioAsset]. Locks in
 * iOS-shape compatibility: field names, types, optional handling,
 * and forward-compatibility flags. No Android runtime needed.
 */
class AudioManifestTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Test fun `parses minimal manifest with empty assets`() {
        val body = """{"version":"2026-01-01","assets":[]}"""
        val manifest = json.decodeFromString<AudioManifest>(body)
        assertEquals("2026-01-01", manifest.version)
        assertTrue(manifest.assets.isEmpty())
    }

    @Test fun `parses a soundscape-only manifest`() {
        val body = """
            {
              "version": "2026-01-01",
              "assets": [
                {
                  "id": "forest-morning",
                  "type": "soundscape",
                  "name": "forest_morning",
                  "displayName": "Forest Morning",
                  "durationSec": 180.0,
                  "r2Key": "soundscape/forest-morning.aac",
                  "fileSizeBytes": 1048576,
                  "usageTags": []
                }
              ]
            }
        """.trimIndent()
        val manifest = json.decodeFromString<AudioManifest>(body)
        assertEquals(1, manifest.assets.size)
        val asset = manifest.assets.first()
        assertEquals("forest-morning", asset.id)
        assertEquals("soundscape", asset.type)
        assertEquals("Forest Morning", asset.displayName)
        assertEquals(180.0, asset.durationSec, 0.0)
        assertEquals(1_048_576L, asset.fileSizeBytes)
    }

    @Test fun `parses a bell-only manifest`() {
        val body = """
            {
              "version": "2026-01-01",
              "assets": [
                {
                  "id": "temple-chime",
                  "type": "bell",
                  "name": "temple_chime",
                  "displayName": "Temple Chime",
                  "durationSec": 3.0,
                  "r2Key": "bell/temple-chime.aac",
                  "fileSizeBytes": 32768
                }
              ]
            }
        """.trimIndent()
        val manifest = json.decodeFromString<AudioManifest>(body)
        assertEquals("bell", manifest.assets.first().type)
    }

    @Test fun `parses mixed bell and soundscape manifest`() {
        val body = """
            {
              "version": "2026-01-01",
              "assets": [
                {"id":"b1","type":"bell","name":"b1","displayName":"B1","durationSec":2.0,"r2Key":"bell/b1.aac","fileSizeBytes":100},
                {"id":"s1","type":"soundscape","name":"s1","displayName":"S1","durationSec":300.0,"r2Key":"soundscape/s1.aac","fileSizeBytes":5000000},
                {"id":"b2","type":"bell","name":"b2","displayName":"B2","durationSec":2.5,"r2Key":"bell/b2.aac","fileSizeBytes":150}
              ]
            }
        """.trimIndent()
        val manifest = json.decodeFromString<AudioManifest>(body)
        assertEquals(3, manifest.assets.size)
        val bells = manifest.assets.filter { it.type == AudioAssetType.BELL }
        val soundscapes = manifest.assets.filter { it.type == AudioAssetType.SOUNDSCAPE }
        assertEquals(2, bells.size)
        assertEquals(1, soundscapes.size)
        assertEquals("s1", soundscapes.first().id)
    }

    @Test fun `tolerates unknown top-level fields for forward compatibility`() {
        val body = """
            {
              "version": "2026-01-01",
              "assets": [],
              "experimentalFeature": {"foo": 42}
            }
        """.trimIndent()
        val manifest = json.decodeFromString<AudioManifest>(body)
        assertEquals("2026-01-01", manifest.version)
    }

    @Test fun `tolerates unknown fields inside asset`() {
        val body = """
            {
              "version": "2026-01-01",
              "assets": [
                {
                  "id": "s1",
                  "type": "soundscape",
                  "name": "s1",
                  "displayName": "S1",
                  "durationSec": 100.0,
                  "r2Key": "soundscape/s1.aac",
                  "fileSizeBytes": 1000,
                  "futureProperty": "ignored"
                }
              ]
            }
        """.trimIndent()
        val manifest = json.decodeFromString<AudioManifest>(body)
        assertEquals(1, manifest.assets.size)
    }

    @Test fun `round-trips through encode and decode`() {
        val original = AudioManifest(
            version = "2026-04-20",
            assets = listOf(
                AudioAsset(
                    id = "rt-1",
                    type = AudioAssetType.SOUNDSCAPE,
                    name = "rt_one",
                    displayName = "Round-Trip One",
                    durationSec = 120.5,
                    r2Key = "soundscape/rt-1.aac",
                    fileSizeBytes = 2048L,
                    usageTags = listOf("ambient", "morning"),
                ),
            ),
        )
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<AudioManifest>(encoded)
        assertEquals(original, decoded)
    }

    @Test fun `usageTags defaults to empty list when absent`() {
        val body = """
            {
              "version": "v1",
              "assets": [{
                "id": "x", "type": "soundscape", "name": "x",
                "displayName": "X", "durationSec": 1.0,
                "r2Key": "soundscape/x.aac", "fileSizeBytes": 1
              }]
            }
        """.trimIndent()
        val manifest = json.decodeFromString<AudioManifest>(body)
        assertTrue(manifest.assets.first().usageTags.isEmpty())
    }
}
