// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.audio

/**
 * Endpoint constants for the unified audio catalog. Mirrors iOS's
 * `Config.Audio` — same CDN host, same path layout.
 */
internal object AudioConfig {
    /** Public audio-manifest endpoint. Unified bell + soundscape catalog. */
    const val MANIFEST_URL = "https://cdn.pilgrimapp.org/audio/manifest.json"

    /**
     * Base URL for per-asset downloads. iOS builds
     * `<base>/<type>/<id>.aac` — e.g.,
     * `https://cdn.pilgrimapp.org/audio/soundscape/forest-morning.aac`.
     * Android reconstructs the same path rather than using `r2Key`
     * verbatim, matching iOS's convention.
     */
    const val BASE_URL = "https://cdn.pilgrimapp.org/audio"
}
