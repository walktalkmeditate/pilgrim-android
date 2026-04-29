// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.pilgrim

import java.time.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Encodes [Instant] as seconds-since-epoch `Double` to match iOS
 * `JSONEncoder.dateEncodingStrategy = .secondsSince1970`.
 *
 * Fractional precision: an iOS `Date` is `Double` seconds; we
 * preserve nanoseconds via the same Double representation. Round-trip
 * tolerance is bounded by IEEE-754 mantissa precision (~1ns at
 * post-2000 timestamps).
 */
object EpochSecondsInstantSerializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor(
        "EpochSecondsInstant", PrimitiveKind.DOUBLE,
    )

    override fun serialize(encoder: Encoder, value: Instant) {
        val seconds = value.epochSecond + value.nano / 1_000_000_000.0
        encoder.encodeDouble(seconds)
    }

    override fun deserialize(decoder: Decoder): Instant {
        val seconds = decoder.decodeDouble()
        val whole = seconds.toLong()
        val nanos = ((seconds - whole) * 1_000_000_000).toLong()
        return Instant.ofEpochSecond(whole, nanos)
    }
}
