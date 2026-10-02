// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain.seek

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * A chain as one line of JSON, the form it takes on the start intent and in
 * the seek session row (plan U25). Built from JSON elements rather than
 * serializable classes, so R8 needs no keep rules for it. Doubles keep
 * every bit across the round trip, so `:tracker` guides to exactly the
 * clearings the walker's ready screen showed.
 */
object SeekChainCodec {

    /** Far more than any duration generates; a longer chain is refused, never truncated. */
    const val MAX_CLEARINGS = 64

    fun encode(chain: SeekChain): String = buildJsonObject {
        put(KEY_BUDGET, chain.budgetMeters)
        putJsonArray(KEY_CLEARINGS) {
            for (clearing in chain.clearings) {
                addJsonObject {
                    put(KEY_LATITUDE, clearing.center.latitude)
                    put(KEY_LONGITUDE, clearing.center.longitude)
                    put(KEY_RADIUS, clearing.radiusMeters)
                }
            }
        }
    }.toString()

    /**
     * Null for text that isn't a chain: malformed JSON, a missing field, a
     * coordinate off the globe, a radius that isn't positive, a non-finite
     * number, or too many clearings. Callers never log the text, which holds
     * places near the walker.
     */
    fun decode(text: String): SeekChain? {
        val root = try {
            Json.parseToJsonElement(text).jsonObject
        } catch (e: IllegalArgumentException) {
            return null
        }
        return try {
            val budget = root.number(KEY_BUDGET).takeIf { it >= 0.0 } ?: return null
            val entries = root[KEY_CLEARINGS]?.jsonArray ?: return null
            if (entries.size > MAX_CLEARINGS) return null
            val clearings = entries.map { entry -> entry.jsonObject.clearing() ?: return null }
            SeekChain(clearings = clearings, budgetMeters = budget)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun JsonObject.clearing(): SeekClearing? {
        val latitude = number(KEY_LATITUDE).takeIf { it in -90.0..90.0 } ?: return null
        val longitude = number(KEY_LONGITUDE).takeIf { it in -180.0..180.0 } ?: return null
        val radius = number(KEY_RADIUS).takeIf { it > 0.0 } ?: return null
        return SeekClearing(center = SeekPoint(latitude, longitude), radiusMeters = radius)
    }

    /** A finite number under [key]; anything else is the caller's refusal. */
    private fun JsonObject.number(key: String): Double {
        val value = requireNotNull(this[key]) { "missing $key" }.jsonPrimitive.double
        require(value.isFinite()) { "non-finite $key" }
        return value
    }

    private const val KEY_BUDGET = "budget"
    private const val KEY_CLEARINGS = "clearings"
    private const val KEY_LATITUDE = "lat"
    private const val KEY_LONGITUDE = "lon"
    private const val KEY_RADIUS = "radius"
}
