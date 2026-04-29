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
 */
@Serializable
internal data class FeedbackRequest(
    @SerialName("category") val category: String,
    @SerialName("message") val message: String,
    @SerialName("deviceInfo") val deviceInfo: String? = null,
)
