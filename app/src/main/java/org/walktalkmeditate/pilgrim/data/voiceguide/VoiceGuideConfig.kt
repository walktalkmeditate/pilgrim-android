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
     * Base URL for per-prompt downloads. Resolution: `<base>/<r2Key>`
     * where `r2Key` is a full CDN path from the root (e.g.
     * `voiceguide/breeze/breeze_01.aac`). **Must NOT include the
     * `voiceguide/` segment** — it's already in `r2Key`. The worker's
     * URL assembly (`baseUrl.trimEnd('/') + "/" + r2Key.trimStart('/')`)
     * would otherwise produce `…/voiceguide/voiceguide/…` → 404.
     * Device QA caught this — Robolectric MockWebServer tests
     * served any path and hid the collision.
     */
    const val PROMPT_BASE_URL = "https://cdn.pilgrimapp.org/"
}
