// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Test double for [WalkSoundscapeUiController]. Records the last command. */
class FakeWalkSoundscapeUiController(
    initialName: String? = null,
    initialId: String? = null,
    initialChoices: List<SoundscapeChoice> = emptyList(),
) : WalkSoundscapeUiController {
    private val name = MutableStateFlow(initialName)
    private val id = MutableStateFlow(initialId)
    private val choices = MutableStateFlow(initialChoices)
    override val selectedName: StateFlow<String?> = name.asStateFlow()
    override val selectedId: StateFlow<String?> = id.asStateFlow()
    override val available: StateFlow<List<SoundscapeChoice>> = choices.asStateFlow()

    var lastEnabled: Boolean? = null
    var lastSelectedId: String? = null

    override fun setEnabled(on: Boolean) { lastEnabled = on }
    override fun select(assetId: String) { lastSelectedId = assetId }
}
