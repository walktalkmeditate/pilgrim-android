// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.location

/**
 * The debug Way replayer (plan U19) as `:tracker` sees it. Debug builds bind
 * the fused-location mock-mode replayer; release binds a no-op, so the
 * service calls this without naming a class release doesn't have.
 */
interface MockLocationReplay {

    /**
     * A `:tracker` service start. Mock mode a replay left on in an earlier,
     * dead process goes off; a replay running in this process keeps playing.
     */
    fun onTrackerStart()
}
