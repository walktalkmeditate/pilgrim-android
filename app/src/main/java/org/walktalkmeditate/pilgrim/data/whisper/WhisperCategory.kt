// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.whisper

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * iOS parity `WhisperDefinition.swift:13@db4196e` — the 8 contemplative
 * categories a placed whisper can carry. Wire format uses the lowercase
 * raw string (`@SerialName`); the `apiValue` accessor exposes the same
 * string for non-serializer code paths.
 *
 * Border colors are the row stroke in [WhisperPlacementSheet] and the
 * map annotation pin color (when map pins are wired in a follow-up
 * PR). Verbatim sRGB tuples from iOS `WhisperDefinition.swift:14-49`.
 */
@Serializable
enum class WhisperCategory(val apiValue: String, val borderColor: Color) {
    @SerialName("presence")
    Presence("presence", Color(red = 0.11f, green = 0.23f, blue = 0.29f)),

    @SerialName("lightness")
    Lightness("lightness", Color(red = 0.76f, green = 0.65f, blue = 0.55f)),

    @SerialName("wonder")
    Wonder("wonder", Color(red = 0.66f, green = 0.72f, blue = 0.75f)),

    @SerialName("gratitude")
    Gratitude("gratitude", Color(red = 0.78f, green = 0.63f, blue = 0.31f)),

    @SerialName("compassion")
    Compassion("compassion", Color(red = 0.66f, green = 0.85f, blue = 0.82f)),

    @SerialName("courage")
    Courage("courage", Color(red = 0.78f, green = 0.72f, blue = 0.53f)),

    @SerialName("stillness")
    Stillness("stillness", Color(red = 0.72f, green = 0.58f, blue = 0.42f)),

    @SerialName("play")
    Play("play", Color(red = 0.92f, green = 0.51f, blue = 0.32f));

    companion object {
        fun fromApiValue(value: String): WhisperCategory? =
            entries.firstOrNull { it.apiValue == value }
    }
}
