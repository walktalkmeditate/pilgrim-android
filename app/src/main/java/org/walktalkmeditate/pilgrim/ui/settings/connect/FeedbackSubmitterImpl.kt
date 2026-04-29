// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.connect

import javax.inject.Inject
import javax.inject.Singleton
import org.walktalkmeditate.pilgrim.data.feedback.FeedbackService

@Singleton
class FeedbackSubmitterImpl @Inject constructor(
    private val service: FeedbackService,
) : FeedbackSubmitter {
    override suspend fun submit(category: String, message: String, deviceInfo: String?) {
        service.submit(category, message, deviceInfo)
    }
}
