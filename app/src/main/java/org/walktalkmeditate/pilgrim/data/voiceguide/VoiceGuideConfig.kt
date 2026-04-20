// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.voiceguide

/**
 * Endpoint + asset-path constants for the voice-guide catalog.
 * Mirrors iOS's `Config.VoiceGuide` group.
 */
internal object VoiceGuideConfig {
    /** Public manifest endpoint. Same URL iOS reads from. */
    const val MANIFEST_URL = "https://cdn.pilgrimapp.org/voiceguide/manifest.json"

    /**
     * Base URL for per-prompt downloads. Resolution: `<base><r2Key>`
     * where `r2Key` comes from the manifest (e.g. "morning-walk/p1.aac").
     * iOS's equivalent builds `<base>/<packId>/<promptId>.aac`, but
     * we use `r2Key` verbatim — the manifest is authoritative about
     * the path, so if iOS ever ships `.opus` prompts or a new
     * directory scheme, `r2Key` reflects it automatically.
     */
    const val PROMPT_BASE_URL = "https://cdn.pilgrimapp.org/voiceguide/"
}
