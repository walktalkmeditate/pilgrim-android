// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.meditation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Manual-QA batch 1, BUG 3: the meditation soundscape long-press (and
 * the "Silence" tap) opened the breath/voice options sheet instead of
 * the soundscape picker.
 *
 * Covers the pure gesture-routing mapping extracted from the
 * `MeditationScreen` lambdas so the iOS-parity rule
 * (`MeditationView.swift:293-333,60-63@v1.6.0`) is verified without
 * standing up Compose + Hilt + a real gesture detector (the inline
 * lambdas now delegate to these functions).
 */
class SoundscapeGestureRoutingTest {

    @Test fun `soundscape tap with a selected soundscape toggles mute`() {
        assertEquals(
            SoundscapeGestureAction.ToggleMute,
            soundscapeTapAction(soundscapeSelected = true),
        )
    }

    @Test fun `soundscape tap with Silence opens the soundscape picker`() {
        // iOS MeditationView.swift:301-303 — nothing to mute, so the
        // tap must open the picker (NOT the breath/voice rhythm sheet).
        assertEquals(
            SoundscapeGestureAction.OpenSoundscapePicker,
            soundscapeTapAction(soundscapeSelected = false),
        )
    }

    @Test fun `soundscape long-press always opens the soundscape picker`() {
        // iOS MeditationView.swift:327-332. This is the core BUG-3
        // regression: it must NOT resolve to OpenRhythmSheet.
        assertEquals(
            SoundscapeGestureAction.OpenSoundscapePicker,
            soundscapeLongPressAction(),
        )
    }

    @Test fun `breathing-circle long-press opens the breath-rhythm sheet`() {
        // iOS MeditationView.swift:60-63 — the circle long-press is
        // the breath/voice options affordance; this one is correct and
        // must stay distinct from the soundscape gestures.
        assertEquals(
            SoundscapeGestureAction.OpenRhythmSheet,
            circleLongPressAction(),
        )
    }

    @Test fun `soundscape and circle long-press resolve to different actions (BUG 3)`() {
        assertEquals(
            SoundscapeGestureAction.OpenSoundscapePicker,
            soundscapeLongPressAction(),
        )
        assertEquals(
            SoundscapeGestureAction.OpenRhythmSheet,
            circleLongPressAction(),
        )
    }
}
