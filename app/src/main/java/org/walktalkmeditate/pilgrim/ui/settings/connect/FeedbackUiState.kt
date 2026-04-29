// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.connect

import org.walktalkmeditate.pilgrim.data.feedback.FeedbackCategory

enum class FeedbackErrorMessage { RateLimited, Generic }

data class FeedbackUiState(
    val selectedCategory: FeedbackCategory? = null,
    val message: String = "",
    val includeDeviceInfo: Boolean = true,
    val isSubmitting: Boolean = false,
    val showConfirmation: Boolean = false,
    val errorMessage: FeedbackErrorMessage? = null,
) {
    val canSubmit: Boolean
        get() = selectedCategory != null &&
            message.trim().isNotEmpty() &&
            !isSubmitting
}
