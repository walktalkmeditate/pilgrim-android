// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.walktalkmeditate.pilgrim.data.feedback.FeedbackCategory
import org.walktalkmeditate.pilgrim.data.feedback.FeedbackError

@HiltViewModel
class FeedbackViewModel @Inject constructor(
    private val submitter: FeedbackSubmitter,
    private val deviceInfoProvider: DeviceInfoProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(FeedbackUiState())
    val state: StateFlow<FeedbackUiState> = _state.asStateFlow()

    fun selectCategory(category: FeedbackCategory) {
        _state.update { it.copy(selectedCategory = category, errorMessage = null) }
    }

    fun updateMessage(text: String) {
        _state.update { it.copy(message = text, errorMessage = null) }
    }

    fun toggleIncludeDeviceInfo(include: Boolean) {
        _state.update { it.copy(includeDeviceInfo = include) }
    }

    fun submit() {
        val snapshot = _state.value
        val category = snapshot.selectedCategory ?: return
        if (snapshot.message.trim().isEmpty() || snapshot.isSubmitting) return

        _state.update { it.copy(isSubmitting = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                submitter.submit(
                    category = category.apiValue,
                    message = snapshot.message.trim(),
                    deviceInfo = if (snapshot.includeDeviceInfo) deviceInfoProvider.deviceInfo() else null,
                )
                _state.update { it.copy(isSubmitting = false, showConfirmation = true) }
            } catch (e: FeedbackError.RateLimited) {
                _state.update { it.copy(isSubmitting = false, errorMessage = "Too many submissions today.") }
            } catch (e: FeedbackError.ServerError) {
                _state.update { it.copy(isSubmitting = false, errorMessage = "Couldn't send — please try again") }
            } catch (e: FeedbackError.NetworkError) {
                _state.update { it.copy(isSubmitting = false, errorMessage = "Couldn't send — please try again") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update { it.copy(isSubmitting = false, errorMessage = "Couldn't send — please try again") }
            }
        }
    }
}
