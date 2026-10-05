// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The options sheet's hand-off to the sheet one of its rows opens, as
 * iOS's `asyncAfter(deadline: .now() + 0.3)` does
 * (`ActiveWalkView.swift:229-305@7c200bf`): the options sheet closes and
 * the next one opens [SHEET_HANDOFF_DELAY_MS] later, so the two
 * animations don't fight. One at a time, a second row tapped in the
 * window replacing the first. [cancel] drops one still waiting, which a
 * walk leaving Active or Paused does as it closes the in-walk sheets (an
 * Android step; iOS's `asyncAfter` can't be cancelled): a sheet opened
 * after that would show over the outgoing screen, and stay saved open for
 * the return from meditation.
 */
internal class SheetHandoff(private val scope: CoroutineScope) {

    private var pending: Job? = null

    fun open(show: () -> Unit) {
        pending?.cancel()
        pending = scope.launch {
            delay(SHEET_HANDOFF_DELAY_MS)
            show()
        }
    }

    fun cancel() {
        pending?.cancel()
        pending = null
    }
}
