// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.walk

/**
 * User-intent bell events emitted by [WalkController] on every
 * user-initiated transition. Mirrors iOS's `SoundManagement` entry
 * points (`onWalkStart` / `onWalkEnd` / `onMeditationStart` /
 * `onMeditationEnd`). Observers subscribe to
 * [WalkController.bellTriggers] rather than the state flow so the
 * restore path — which writes directly to `_state` from
 * `restoreActiveWalk` — does NOT replay bells that already rang
 * during the original session.
 */
enum class BellTrigger {
    WalkStart,
    WalkEnd,
    MeditationStart,
    MeditationEnd,
}
