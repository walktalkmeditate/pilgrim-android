// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.connect

/**
 * Test seam over [org.walktalkmeditate.pilgrim.data.feedback.FeedbackService.submit].
 * Lets unit tests drive the VM without spinning up Hilt or OkHttp.
 */
interface FeedbackSubmitter {
    suspend fun submit(category: String, message: String, deviceInfo: String?)
}
