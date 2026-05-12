// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.whisper

/**
 * iOS parity `WhisperService.swift@db4196e` `WhisperError` enum. The
 * caller (ActiveWalkScreen) maps these to user-facing banner copy and
 * decides whether to retry or surface the failure.
 *
 * `RateLimited` is the only error that has a distinct user-facing
 * copy ("Too many whispers placed today"). Everything else collapses
 * to "Couldn't place whisper. Please try again."
 */
sealed class WhisperError(message: String) : Exception(message) {
    object RateLimited : WhisperError("Too many whispers placed today.")
    data class ServerError(val code: Int, val msg: String) :
        WhisperError("Server returned $code: $msg")
    data class NetworkError(val msg: String) : WhisperError(msg)
    data class EncodingFailed(val msg: String) : WhisperError(msg)
}
