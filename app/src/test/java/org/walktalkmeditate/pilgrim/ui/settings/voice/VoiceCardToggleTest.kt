// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.voice

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.audio.model.WhisperModelState
import org.walktalkmeditate.pilgrim.audio.model.WhisperModelVariant
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

/**
 * [VoiceCard]'s Thought Threads toggle (U10 — parity spec
 * `docs/parity/2026-08-25-threads-engine-port.md` UI-17/UI-18/BEH-84).
 * The composable never knows about `ThreadsBackfillScheduler` — it only
 * forwards taps through a plain `(Boolean) -> Unit` callback; the "routes
 * through setEnabled, never the pref directly" contract lives one layer
 * up in `SettingsViewModel` and is covered by that class's own tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w400dp-h1000dp")
class VoiceCardToggleTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun render(
        state: VoiceCardState = DEFAULT_STATE,
        onSetThreadsEnabled: (Boolean) -> Unit = {},
    ) {
        composeRule.setContent {
            PilgrimTheme {
                Box(Modifier.size(400.dp, 1000.dp)) {
                    VoiceCard(
                        state = state,
                        modelState = WhisperModelState.Ready(WhisperModelVariant.Base),
                        onSetVoiceGuideEnabled = {},
                        onSetAutoTranscribe = {},
                        onSetThreadsEnabled = onSetThreadsEnabled,
                        onOpenVoiceGuides = {},
                        onOpenRecordings = {},
                        onOpenModelDownload = {},
                    )
                }
            }
        }
    }

    @Test
    fun `label and description render`() {
        render()
        composeRule.onNodeWithText("Thought Threads").assertExists()
        composeRule.onNodeWithText(
            "Weave recurring themes from your recordings into AI prompts. English recordings for now.",
        ).assertExists()
    }

    @Test
    fun `tapping the toggle fires the setter with negation`() {
        var lastValue: Boolean? = null
        render(
            state = DEFAULT_STATE.copy(voiceGuideEnabled = false, autoTranscribe = false, threadsEnabled = false),
            onSetThreadsEnabled = { lastValue = it },
        )
        // Voice Guide OFF hides the Guide Packs row, so only two toggles
        // precede Thought Threads: Voice Guide (0), Auto-transcribe (1),
        // Thought Threads (2).
        composeRule.onAllNodes(isToggleable())[2].performClick()
        composeRule.runOnIdle { assertEquals(true, lastValue) }
    }

    @Test
    fun `toggle checked state reflects threadsEnabled`() {
        render(state = DEFAULT_STATE.copy(threadsEnabled = true))
        composeRule.onAllNodes(isToggleable())[2].assertIsOn()
    }

    @Test
    fun `renders immediately after Auto-transcribe and before the Recordings row`() {
        render()
        val autoTranscribeTop = composeRule.onNodeWithText("Auto-transcribe").getUnclippedBoundsInRoot().top
        val threadsTop = composeRule.onNodeWithText("Thought Threads").getUnclippedBoundsInRoot().top
        val recordingsTop = composeRule.onNodeWithText("Recordings").getUnclippedBoundsInRoot().top
        assertTrue(autoTranscribeTop < threadsTop)
        assertTrue(threadsTop < recordingsTop)
    }

    private companion object {
        val DEFAULT_STATE = VoiceCardState(
            voiceGuideEnabled = false,
            autoTranscribe = false,
            threadsEnabled = false,
            recordingsCount = 0,
            recordingsSizeBytes = 0L,
        )
    }
}
