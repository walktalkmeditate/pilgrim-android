// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.share

/**
 * Stage 8-A: sealed error hierarchy for share-flow failures.
 *
 * Each variant maps to a distinct snackbar in the UI — kept explicit
 * (not a generic `Throwable` wrapper) so upstream VMs can reason about
 * rate-limit vs server vs network without string-matching error
 * messages (iOS `ShareError` parity).
 */
sealed class ShareError(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause) {

    class EncodingFailed(cause: Throwable? = null) :
        ShareError("Failed to prepare walk data.", cause)

    class NetworkError(cause: Throwable) :
        ShareError("Network error: ${cause.message ?: cause::class.simpleName}", cause)

    class ServerError(val code: Int, val serverMessage: String) :
        ShareError("Server error ($code): $serverMessage")

    object RateLimited :
        ShareError("You've shared too many walks today. Try again tomorrow.") {
        private fun readResolve(): Any = RateLimited
    }
}
