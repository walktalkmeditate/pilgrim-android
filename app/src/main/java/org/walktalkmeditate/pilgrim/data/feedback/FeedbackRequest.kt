// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.feedback

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire format for the POST body. iOS uses raw `JSONSerialization` over
 * a `[String: String]` dict — we use `kotlinx.serialization` against a
 * matched-shape data class. `deviceInfo` is omitted (not nulled) when
 * the user opts out, mirroring iOS's conditional-key behavior. The
 * project-wide `Json` config (NetworkModule) sets `explicitNulls =
 * false`, so a null `deviceInfo` here will be omitted from the JSON.
 *
 * `platform` lets the share worker route new issues to the pilgrim-android
 * repo instead of pilgrim-ios. It is intentionally NOT defaulted: the
 * NetworkModule `Json` uses `encodeDefaults = false`, so a defaulted value
 * would be dropped from the payload — and unlike `deviceInfo` this signal
 * must survive the user opting out of the device-info toggle.
 */
@Serializable
internal data class FeedbackRequest(
    @SerialName("category") val category: String,
    @SerialName("message") val message: String,
    @SerialName("deviceInfo") val deviceInfo: String? = null,
    @SerialName("platform") val platform: String,
)
