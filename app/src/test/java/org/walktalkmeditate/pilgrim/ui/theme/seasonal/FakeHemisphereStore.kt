// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.theme.seasonal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeHemisphereStore(
    initial: Hemisphere = Hemisphere.Northern,
) : HemisphereStore {
    private val state = MutableStateFlow(initial)
    override val hemisphere: StateFlow<Hemisphere> = state.asStateFlow()
    override suspend fun resolvedHemisphere(): Hemisphere = state.value
    override suspend fun setOverride(hemisphere: Hemisphere) {
        state.value = hemisphere
    }
}
