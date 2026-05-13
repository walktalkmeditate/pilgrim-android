// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.cairn

/**
 * iOS parity `CairnService.swift@db4196e` `CairnError` enum. Note iOS
 * has NO `rateLimited` case — stone placement is not rate-limited
 * server-side because the per-walk cap (1 stone) already throttles.
 */
sealed class CairnError(message: String) : Exception(message) {
    data class ServerError(val code: Int, val msg: String) :
        CairnError("Server returned $code: $msg")
    data class NetworkError(val msg: String) : CairnError(msg)
    data class EncodingFailed(val msg: String) : CairnError(msg)
}
