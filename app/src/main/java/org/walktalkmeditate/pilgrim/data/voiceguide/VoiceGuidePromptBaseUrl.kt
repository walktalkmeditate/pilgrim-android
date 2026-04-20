// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.voiceguide

import javax.inject.Qualifier

/**
 * Qualifier for the base URL used to resolve per-prompt download
 * URLs: `<base>/<r2Key>`. Separate from [VoiceGuideManifestUrl]
 * because the manifest and the prompts live on the same CDN but
 * at different path roots (`<host>/voiceguide/manifest.json` vs
 * `<host>/voiceguide/<r2Key>`). Qualified so tests can substitute
 * a MockWebServer URL without patching the production `const val`
 * in [VoiceGuideConfig].
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class VoiceGuidePromptBaseUrl
