// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.soundscape

import javax.inject.Qualifier

/**
 * Qualifier for the CDN base URL used to resolve per-asset
 * soundscape downloads: `<base>/soundscape/<asset.id>.aac`.
 * Qualified so tests can substitute a MockWebServer URL.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SoundscapeBaseUrl
