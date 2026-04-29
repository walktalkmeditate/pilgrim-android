// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.feedback

sealed class FeedbackError : Exception() {
    object RateLimited : FeedbackError()
    data class ServerError(val statusCode: Int) : FeedbackError() {
        override val message: String get() = "Server error ($statusCode)"
    }
    data class NetworkError(override val message: String) : FeedbackError()
}
