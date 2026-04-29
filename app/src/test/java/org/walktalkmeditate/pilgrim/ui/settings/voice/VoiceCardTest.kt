// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.voice

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

/**
 * Render + interaction tests for [VoiceCard]. Stateless composable —
 * every value is supplied by the parent (Stage 10-D Task 10 wires
 * SettingsViewModel) — so each test passes a literal [VoiceCardState]
 * + capturing lambdas to assert the right callback fires with the
 * right value.
 *
 * Toggle assertions resolve the M3 `Switch` via `isToggleable()`
 * semantics (the AtmosphereCardTest / PracticeCardTest precedent) —
 * the textual labels sit in a sibling Column without a click target.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w400dp-h1000dp")
class VoiceCardTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun render(
        state: VoiceCardState = DEFAULT_STATE,
        onSetVoiceGuideEnabled: (Boolean) -> Unit = {},
        onSetAutoTranscribe: (Boolean) -> Unit = {},
        onOpenVoiceGuides: () -> Unit = {},
        onOpenRecordings: () -> Unit = {},
    ) {
        composeRule.setContent {
            PilgrimTheme {
                Box(Modifier.size(400.dp, 1000.dp)) {
                    VoiceCard(
                        state = state,
                        onSetVoiceGuideEnabled = onSetVoiceGuideEnabled,
                        onSetAutoTranscribe = onSetAutoTranscribe,
                        onOpenVoiceGuides = onOpenVoiceGuides,
                        onOpenRecordings = onOpenRecordings,
                    )
                }
            }
        }
    }

    @Test
    fun `renders header voice-guide toggle auto-transcribe toggle and recordings row`() {
        render()
        composeRule.onNodeWithText("Voice").assertExists()
        composeRule.onNodeWithText("Speaking and listening").assertExists()
        composeRule.onNodeWithText("Voice Guide").assertExists()
        composeRule.onNodeWithText("Auto-transcribe").assertExists()
        composeRule.onNodeWithText("Recordings").assertExists()
    }

    @Test
    fun `Guide Packs row hidden when voice guide is OFF`() {
        render(state = DEFAULT_STATE.copy(voiceGuideEnabled = false))
        composeRule.onNodeWithText("Guide Packs").assertDoesNotExist()
    }

    @Test
    fun `Guide Packs row shown when voice guide is ON and tap fires onOpenVoiceGuides`() {
        var opened = false
        render(
            state = DEFAULT_STATE.copy(voiceGuideEnabled = true),
            onOpenVoiceGuides = { opened = true },
        )
        composeRule.onNodeWithText("Guide Packs").assertExists()
        composeRule.onNodeWithText("Guide Packs").performClick()
        composeRule.runOnIdle { assertEquals(true, opened) }
    }

    @Test
    fun `tapping Voice Guide toggle fires setter with negation`() {
        var lastValue: Boolean? = null
        render(
            state = DEFAULT_STATE.copy(voiceGuideEnabled = false),
            onSetVoiceGuideEnabled = { lastValue = it },
        )
        composeRule.onAllNodes(isToggleable())[VOICE_GUIDE_TOGGLE_INDEX].performClick()
        composeRule.runOnIdle { assertEquals(true, lastValue) }
    }

    @Test
    fun `tapping Auto-transcribe toggle fires setter with negation`() {
        var lastValue: Boolean? = null
        render(
            state = DEFAULT_STATE.copy(voiceGuideEnabled = false, autoTranscribe = false),
            onSetAutoTranscribe = { lastValue = it },
        )
        // With voice guide OFF the Guide Packs row is hidden, so only
        // two toggles render: Voice Guide (index 0), Auto-transcribe
        // (index 1).
        composeRule.onAllNodes(isToggleable()).assertCountEquals(2)
        composeRule.onAllNodes(isToggleable())[AUTO_TRANSCRIBE_TOGGLE_INDEX_VG_OFF].performClick()
        composeRule.runOnIdle { assertEquals(true, lastValue) }
    }

    @Test
    fun `recordings detail formats zero count`() {
        render(state = DEFAULT_STATE.copy(recordingsCount = 0, recordingsSizeBytes = 0L))
        composeRule.onNodeWithText("0 recordings • 0.0 MB").assertExists()
    }

    @Test
    fun `recordings detail formats single count`() {
        render(state = DEFAULT_STATE.copy(recordingsCount = 1, recordingsSizeBytes = 2_500_000L))
        composeRule.onNodeWithText("1 recording • 2.5 MB").assertExists()
    }

    @Test
    fun `recordings detail formats plural count`() {
        render(state = DEFAULT_STATE.copy(recordingsCount = 5, recordingsSizeBytes = 12_300_000L))
        composeRule.onNodeWithText("5 recordings • 12.3 MB").assertExists()
    }

    @Test
    fun `tapping Recordings row fires onOpenRecordings`() {
        var opened = false
        render(onOpenRecordings = { opened = true })
        composeRule.onNodeWithText("Recordings").performClick()
        composeRule.runOnIdle { assertEquals(true, opened) }
    }

    private companion object {
        val DEFAULT_STATE = VoiceCardState(
            voiceGuideEnabled = false,
            autoTranscribe = false,
            recordingsCount = 0,
            recordingsSizeBytes = 0L,
        )

        const val VOICE_GUIDE_TOGGLE_INDEX = 0
        const val AUTO_TRANSCRIBE_TOGGLE_INDEX_VG_OFF = 1
    }
}
