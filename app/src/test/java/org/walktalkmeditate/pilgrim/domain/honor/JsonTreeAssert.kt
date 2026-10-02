// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain.honor

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * Asserts two JSON documents are the same tree: the same keys in the same
 * order at every level, the same strings and booleans, and numbers equal
 * by value within [tolerance]. Numbers compare by value because Swift
 * writes a whole-number `Double` as `60` where kotlinx writes `60.0`.
 */
internal fun assertSameJsonTree(
    expected: JsonElement,
    actual: JsonElement,
    tolerance: Double = 0.0,
    path: String = "$",
) {
    when (expected) {
        is JsonObject -> {
            assertTrue("$path is an object", actual is JsonObject)
            val actualObject = actual as JsonObject
            assertEquals("keys at $path", expected.keys.toList(), actualObject.keys.toList())
            expected.forEach { (key, value) ->
                assertSameJsonTree(value, actualObject.getValue(key), tolerance, "$path.$key")
            }
        }
        is JsonArray -> {
            assertTrue("$path is an array", actual is JsonArray)
            val actualArray = actual as JsonArray
            assertEquals("size of $path", expected.size, actualArray.size)
            expected.forEachIndexed { index, value ->
                assertSameJsonTree(value, actualArray[index], tolerance, "$path[$index]")
            }
        }
        JsonNull -> assertEquals(path, JsonNull, actual)
        is JsonPrimitive -> {
            assertTrue("$path is a primitive", actual is JsonPrimitive)
            val actualPrimitive = actual as JsonPrimitive
            when {
                expected.isString -> {
                    assertTrue("$path is a string", actualPrimitive.isString)
                    assertEquals(path, expected.content, actualPrimitive.content)
                }
                expected.booleanOrNull != null ->
                    assertEquals(path, expected.booleanOrNull, actualPrimitive.booleanOrNull)
                else -> assertEquals(path, expected.double, actualPrimitive.double, tolerance)
            }
        }
    }
}
