// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.data

import android.util.Base64
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.walktalkmeditate.pilgrim.data.pilgrim.builder.PilgrimPackageBuilder

/**
 * iOS parity v1.6.0 `JourneyEditorView`. Builds a `.pilgrim` ZIP via
 * the same path as the export flow ([PilgrimPackageBuilder]), base64-
 * encodes it, and exposes it as a [JourneyEditorState.Ready] so the
 * WebView can ship it through `window.pilgrimViewer.loadFile(...)`.
 *
 * iOS gated the editor's save flow on `originalPilgrimBuffer` (the
 * original ZIP bytes) so it can apply mods to the existing archive
 * structure rather than reconstructing from scratch. iOS originally
 * shipped only the walks JSON via `pilgrimViewer.loadData` and save
 * silently bailed on `if (!originalPilgrimBuffer) return`. The fix
 * was to build the real ZIP up-front. Android takes the same approach.
 */
sealed interface JourneyEditorState {
    object Loading : JourneyEditorState
    object NoWalks : JourneyEditorState
    data class Error(val message: String) : JourneyEditorState
    data class Ready(
        /** Suggested filename for the picker save dialog. */
        val filename: String,
        /** Base64-encoded ZIP bytes ready to ship via `loadFile`. */
        val base64Payload: String,
    ) : JourneyEditorState
}

@HiltViewModel
class JourneyEditorViewModel @Inject constructor(
    private val builder: PilgrimPackageBuilder,
) : ViewModel() {

    private val _state = MutableStateFlow<JourneyEditorState>(JourneyEditorState.Loading)
    val state: StateFlow<JourneyEditorState> = _state.asStateFlow()

    init {
        load()
    }

    fun refresh() {
        _state.value = JourneyEditorState.Loading
        load()
    }

    private fun load() {
        viewModelScope.launch {
            try {
                val built = withContext(Dispatchers.IO) {
                    builder.build(includePhotos = false)
                }
                val bytes = built.file.readBytes()
                val payload = Base64.encodeToString(bytes, Base64.NO_WRAP)
                _state.value = JourneyEditorState.Ready(
                    filename = built.file.name,
                    base64Payload = payload,
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "journey editor build failed", t)
                _state.value = JourneyEditorState.Error(
                    "Couldn't prepare your journey. Please try again.",
                )
            }
        }
    }

    private companion object {
        const val TAG = "JourneyEditorVM"
    }
}
