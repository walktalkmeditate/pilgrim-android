// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.sensor

import android.content.Context
import org.robolectric.RuntimeEnvironment

/**
 * Tiny test helper — constructs a real [StepCounter] with the Robolectric
 * application context. Robolectric's SensorManager stub returns null for
 * TYPE_STEP_COUNTER, so start/stop are no-ops and stop() returns null.
 * Tests that don't care about steps can use this without setup.
 */
fun fakeStepCounter(context: Context = RuntimeEnvironment.getApplication()): StepCounter =
    StepCounter(context)
