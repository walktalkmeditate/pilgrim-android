// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.voiceguide

/**
 * Endpoint + asset-path constants for the voice-guide catalog.
 * Mirrors iOS's `Config.VoiceGuide` group.
 */
internal object VoiceGuideConfig {
    /** Public manifest endpoint. Same URL iOS reads from. */
    const val MANIFEST_URL = "https://cdn.pilgrimapp.org/voiceguide/manifest.json"
}
