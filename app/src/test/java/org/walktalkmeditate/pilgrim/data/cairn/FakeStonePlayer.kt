// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.cairn

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.walktalkmeditate.pilgrim.data.sounds.FakeSoundsPreferencesRepository
import org.walktalkmeditate.pilgrim.data.sounds.SoundsPreferencesRepository

open class FakeStonePlayer(
    context: Context = ApplicationProvider.getApplicationContext(),
    soundsPreferences: SoundsPreferencesRepository = FakeSoundsPreferencesRepository(),
) : StonePlayer(
    context = context,
    soundsPreferences = soundsPreferences,
) {
    var playCalls = mutableListOf<Int>()

    override fun playForCount(stoneCount: Int) {
        playCalls += stoneCount
    }
}
