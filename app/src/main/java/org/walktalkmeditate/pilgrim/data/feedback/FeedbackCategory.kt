// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.feedback

/**
 * Three iOS-faithful feedback buckets. The wire `apiValue` is what
 * the server's `/api/feedback` endpoint expects — note that
 * `Thought` posts `"feedback"`, not `"thought"`. iOS preserves this
 * legacy mapping; we follow.
 */
enum class FeedbackCategory(val apiValue: String) {
    Bug("bug"),
    Feature("feature"),
    Thought("feedback"),
}
