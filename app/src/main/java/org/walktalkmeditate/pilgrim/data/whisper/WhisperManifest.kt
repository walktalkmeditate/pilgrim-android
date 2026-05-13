// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.whisper

import kotlinx.serialization.Serializable

/**
 * iOS parity `WhisperManifest.swift@db4196e` — the JSON shape served at
 * `cdn.pilgrimapp.org/whispers/manifest.json`. Wire format:
 * ```
 * { "version": 3, "whispers": [ { "id": "...", ... }, ... ] }
 * ```
 *
 * iOS uses a lossy `init(from decoder:)` that drops individual whisper
 * entries that fail to decode while still succeeding on the outer
 * manifest. Android's `NetworkModule.provideJson` already sets
 * `ignoreUnknownKeys = true`; individual decode failures throw —
 * caller can wrap in `runCatching` if forward compat with unknown
 * `category` values matters. Today, [WhisperCategory] has all 8
 * iOS-v1.5.0 cases so manifest parses cleanly.
 */
@Serializable
data class WhisperManifest(
    val version: Int,
    val whispers: List<WhisperDefinition>,
)
