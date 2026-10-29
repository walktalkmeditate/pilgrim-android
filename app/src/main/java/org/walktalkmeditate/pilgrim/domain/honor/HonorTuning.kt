// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain.honor

import org.walktalkmeditate.pilgrim.domain.seek.SeekEngineTuning

/**
 * Every Honor threshold in one place (iOS `HonorTuning.swift@7c200bf`),
 * in iOS's units: metres, fractions of the Way, and seconds. Parity spec
 * B §15.1 lists the comparison operator each one is used with.
 */
object HonorTuning {
    const val FIX_ACCURACY_METERS = 50.0
    const val ON_WAY_METERS = 60.0
    const val WINDOW_METERS = 300.0
    const val BACKWARD_TOLERANCE = 0.02
    const val MOMENT_FRAC_TOLERANCE = 0.05
    const val REACQUIRE_SECONDS = 120.0
    const val REACQUIRE_RETRY_SECONDS = 10.0
    const val VOICE_RADIUS_METERS = 42.0
    const val MOMENT_RADIUS_METERS = 60.0
    const val VOICE_DROP_METERS = 300.0
    const val STATIONARY_SPEED = 0.4
    const val SOFT_TAP_METERS = 200.0
    const val SOFT_TAP_SECONDS = 120.0
    const val ARRIVAL_RADIUS_METERS = 30.0
    const val ARRIVAL_MIN_FRAC = 0.9
    const val ARRIVAL_MIN_DISTANCE_RATIO = 0.5
    const val ARRIVAL_FIX_COUNT = SeekEngineTuning.ARRIVAL_FIX_COUNT
    const val ARRIVAL_ACCURACY_METERS = SeekEngineTuning.ARRIVAL_ACCURACY_METERS

    /** Stage-only: how far the walker moves before the mark pins are reselected. */
    const val MARK_PIN_REFRESH_METERS = 200.0

    /** Stage-only: how far before an on-way water source the caption rises. */
    const val MARK_AHEAD_METERS = 300.0

    /** Stage-only: at most one water caption per this many seconds of the engine clock. */
    const val MARK_QUIET_SECONDS = 3600.0

    /**
     * The least walking between any two notices on the caption line, water
     * or temple (`HonorTuning.swift:33-36@e551b11`): the line holds a notice
     * for 20 s, and a minute keeps one from replacing another still being
     * read, or two taps landing together.
     */
    const val NOTICE_GAP_SECONDS = 60.0

    /**
     * How long before the stamp office shuts the notice is worth saying, from
     * 15:00 for a 17:00 close (`HonorTuning.swift:37-40@e551b11`).
     */
    const val STAMP_NOTICE_MINUTES = 120

    /** Nearer than this and the temple has stopped being a decision: the walker is already arriving (`:41-43`). */
    const val STAMP_MIN_AHEAD_METERS = 1000.0

    /**
     * The least walking between two temple notices (`:44-50`): Shikoku's
     * temples can stand barely a kilometre apart, and water's hour would
     * hold a 16:10 notice's successor past 17:00.
     */
    const val STAMP_QUIET_SECONDS = 600.0
}
