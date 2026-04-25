// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.collective

sealed interface PostResult {
    data object Success : PostResult
    data object RateLimited : PostResult
    data class Failed(val cause: Throwable) : PostResult
}
