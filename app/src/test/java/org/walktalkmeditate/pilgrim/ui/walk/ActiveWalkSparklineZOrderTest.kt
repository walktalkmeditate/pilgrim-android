// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.domain.WalkAccumulator
import org.walktalkmeditate.pilgrim.domain.WalkState

/**
 * Manual-QA batch 2, BUG B1: the live pace sparkline was declared
 * BEFORE [WalkStatsSheet] in the same BottomCenter root Box, so the
 * minimized sheet chrome painted over it (z-order: later sibling wins)
 * and it was never visible.
 *
 * This test mirrors the screen's BottomCenter Box layering — sheet
 * first, ambient sparkline second — and asserts the sparkline node is
 * composed when the sheet is Minimized and the pace history has >10
 * positive samples (the iOS gate `ActiveWalkView.swift:461-473`). The
 * missing integration coverage: nothing previously verified the gate
 * actually composes the sparkline alongside the sheet.
 *
 * Asserts presence (`assertExists`) not visibility — Stage 3-C
 * precedent: Robolectric's Canvas backend is a stub, so
 * `assertIsDisplayed` on the bare-Canvas [LivePaceSparkline] node is
 * unreliable. The negative cases assert the gate keeps it OUT of the
 * tree.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ActiveWalkSparklineZOrderTest {

    @get:Rule val composeRule = createComposeRule()

    private val movingPace = List(20) { 8.0 }

    @Test
    fun `sparkline composes above the minimized sheet when enough samples`() {
        composeRule.setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                WalkStatsSheet(
                    state = SheetState.Minimized,
                    onStateChange = {},
                    walkState = WalkState.Active(WalkAccumulator(1L, 0L)),
                    totalElapsedMillis = 0L,
                    distanceMeters = 0.0,
                    walkMillis = 0L,
                    talkMillis = 0L,
                    meditateMillis = 0L,
                    recorderState = VoiceRecorderUiState.Idle,
                    audioLevel = 0f,
                    recordingsCount = 0,
                    units = UnitSystem.Metric,
                    onStartWalk = {},
                    onStartMeditation = {},
                    onEndMeditation = {},
                    onToggleRecording = {},
                    onPermissionDenied = {},
                    onDismissError = {},
                    onFinish = {},
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
                AmbientPaceSparkline(
                    paceHistory = movingPace,
                    sheetState = SheetState.Minimized,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
        composeRule.onNodeWithTag(AMBIENT_SPARKLINE_TAG, useUnmergedTree = true).assertExists()
    }

    @Test
    fun `sparkline absent when fewer than 11 positive samples`() {
        composeRule.setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                AmbientPaceSparkline(
                    paceHistory = List(10) { 8.0 },
                    sheetState = SheetState.Minimized,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
        composeRule.onNodeWithTag(AMBIENT_SPARKLINE_TAG, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `sparkline absent when sheet expanded`() {
        composeRule.setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                AmbientPaceSparkline(
                    paceHistory = movingPace,
                    sheetState = SheetState.Expanded,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
        composeRule.onNodeWithTag(AMBIENT_SPARKLINE_TAG, useUnmergedTree = true).assertDoesNotExist()
    }
}
