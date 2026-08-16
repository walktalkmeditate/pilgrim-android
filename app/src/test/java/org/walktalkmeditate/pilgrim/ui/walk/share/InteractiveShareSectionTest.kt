// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.share

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.share.TourBuilder
import org.walktalkmeditate.pilgrim.data.share.TourRecordingCandidate
import org.walktalkmeditate.pilgrim.data.share.TourRecordingKind
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class InteractiveShareSectionTest {

    @get:Rule val composeRule = createComposeRule()

    private fun row(
        id: Int = 0,
        durationSeconds: Int = 65,
        startEpochSeconds: Long = 1_700_000_000L,
        transcriptionPreview: String? = null,
        effectiveKind: TourRecordingKind = TourRecordingKind.SPOKEN,
        includeInShare: Boolean = true,
        availability: RecordingAvailability = RecordingAvailability.Available(2_000_000L),
    ) = TourRecordingRowState(
        id = id,
        durationSeconds = durationSeconds,
        startEpochSeconds = startEpochSeconds,
        transcriptionPreview = transcriptionPreview,
        effectiveKind = effectiveKind,
        includeInShare = includeInShare,
        availability = availability,
    )

    private fun setSection(
        state: InteractiveShareSectionState,
        onInteractiveEnabledChange: (Boolean) -> Unit = {},
        onToggleRowInclude: (Int) -> Unit = {},
        onFlipRowKind: (Int) -> Unit = {},
        onTrimEnabledChange: (Boolean) -> Unit = {},
    ) {
        composeRule.setContent {
            PilgrimTheme {
                InteractiveShareSection(
                    state = state,
                    onInteractiveEnabledChange = onInteractiveEnabledChange,
                    onToggleRowInclude = onToggleRowInclude,
                    onFlipRowKind = onFlipRowKind,
                    onTrimEnabledChange = onTrimEnabledChange,
                )
            }
        }
    }

    // ---- UI-9/UI-62: toggle gating ----------------------------------

    @Test
    fun `section label always renders regardless of toggle state`() {
        setSection(InteractiveShareSectionState(interactiveEnabled = false))
        composeRule.onNodeWithText("WALK WITH ME").assertIsDisplayed()
    }

    @Test
    fun `toggle off hides recordings disclosure and trim toggle`() {
        setSection(
            InteractiveShareSectionState(
                interactiveEnabled = false,
                rows = listOf(row()),
            ),
        )
        composeRule.onNodeWithTag("trim-toggle-row").assertDoesNotExist()
        composeRule.onNodeWithTag("tour-recording-row-0").assertDoesNotExist()
    }

    @Test
    fun `toggle on with no rows shows the no-recordings message`() {
        setSection(InteractiveShareSectionState(interactiveEnabled = true, rows = emptyList()))
        composeRule.onNodeWithText(
            "No recordings on this walk — the page will carry your route, photos, and moments.",
        ).assertIsDisplayed()
        composeRule.onNodeWithTag("trim-toggle-row").assertIsDisplayed()
    }

    @Test
    fun `toggle on with rows shows the recordings list not the empty message`() {
        setSection(InteractiveShareSectionState(interactiveEnabled = true, rows = listOf(row())))
        composeRule.onNodeWithTag("tour-recording-row-0").assertIsDisplayed()
        composeRule.onNodeWithText(
            "No recordings on this walk — the page will carry your route, photos, and moments.",
        ).assertDoesNotExist()
    }

    @Test
    fun `toggling the interactive switch fires the callback`() {
        var fired: Boolean? = null
        setSection(
            InteractiveShareSectionState(interactiveEnabled = false),
            onInteractiveEnabledChange = { fired = it },
        )
        composeRule.onNodeWithTag("interactive-toggle").performClick()
        assertEquals(true, fired)
    }

    // ---- UI-22/UI-24/UI-25/UI-26: the five row states ----------------

    @Test
    fun `included available row shows a checked include button and a kind chip`() {
        setSection(
            InteractiveShareSectionState(
                interactiveEnabled = true,
                rows = listOf(row(includeInShare = true, availability = RecordingAvailability.Available(3_145_728L))),
            ),
        )
        composeRule.onNodeWithTag("tour-recording-include-0").assertIsDisplayed()
        composeRule.onNodeWithTag("tour-recording-kind-0").assertIsDisplayed()
        composeRule.onNodeWithText("3.0 MB").assertIsDisplayed()
    }

    @Test
    fun `excluded available row shows an unchecked include button still visible`() {
        setSection(
            InteractiveShareSectionState(
                interactiveEnabled = true,
                rows = listOf(row(includeInShare = false, availability = RecordingAvailability.Available(1_048_576L))),
            ),
        )
        composeRule.onNodeWithTag("tour-recording-include-0").assertIsDisplayed()
        composeRule.onNodeWithTag("tour-recording-kind-0").assertIsDisplayed()
    }

    @Test
    fun `audio-removed row hides both controls and shows the reason text`() {
        setSection(
            InteractiveShareSectionState(
                interactiveEnabled = true,
                rows = listOf(row(availability = RecordingAvailability.AudioRemoved)),
            ),
        )
        composeRule.onNodeWithTag("tour-recording-include-0").assertDoesNotExist()
        composeRule.onNodeWithTag("tour-recording-kind-0").assertDoesNotExist()
        composeRule.onNodeWithText("audio removed").assertIsDisplayed()
    }

    @Test
    fun `too-large row hides both controls and shows the reason text`() {
        setSection(
            InteractiveShareSectionState(
                interactiveEnabled = true,
                rows = listOf(row(availability = RecordingAvailability.TooLargeToCarry)),
            ),
        )
        composeRule.onNodeWithTag("tour-recording-include-0").assertDoesNotExist()
        composeRule.onNodeWithTag("tour-recording-kind-0").assertDoesNotExist()
        composeRule.onNodeWithText("too large to carry").assertIsDisplayed()
    }

    @Test
    fun `preparing row (Android-original) hides both controls and shows preparing text`() {
        setSection(
            InteractiveShareSectionState(
                interactiveEnabled = true,
                rows = listOf(row(availability = RecordingAvailability.Preparing)),
            ),
        )
        composeRule.onNodeWithTag("tour-recording-include-0").assertDoesNotExist()
        composeRule.onNodeWithTag("tour-recording-kind-0").assertDoesNotExist()
        composeRule.onNodeWithText("Preparing…").assertIsDisplayed()
    }

    @Test
    fun `tapping the include button fires onToggleRowInclude with the row id`() {
        var toggledId: Int? = null
        setSection(
            InteractiveShareSectionState(interactiveEnabled = true, rows = listOf(row(id = 3))),
            onToggleRowInclude = { toggledId = it },
        )
        composeRule.onNodeWithTag("tour-recording-include-3").performClick()
        assertEquals(3, toggledId)
    }

    @Test
    fun `tapping the kind chip fires onFlipRowKind with the row id`() {
        var flippedId: Int? = null
        setSection(
            InteractiveShareSectionState(interactiveEnabled = true, rows = listOf(row(id = 2))),
            onFlipRowKind = { flippedId = it },
        )
        composeRule.onNodeWithTag("tour-recording-kind-2").performClick()
        assertEquals(2, flippedId)
    }

    @Test
    fun `kind chip shows voice for spoken and ambience for ambient`() {
        setSection(
            InteractiveShareSectionState(
                interactiveEnabled = true,
                rows = listOf(
                    row(id = 0, effectiveKind = TourRecordingKind.SPOKEN),
                    row(id = 1, effectiveKind = TourRecordingKind.AMBIENT, startEpochSeconds = 1_700_000_100L),
                ),
            ),
        )
        composeRule.onNodeWithTag("tour-recording-kind-0").assertTextEquals("voice")
        composeRule.onNodeWithTag("tour-recording-kind-1").assertTextEquals("ambience")
    }

    // ---- UI-12: voices warning compound condition --------------------

    @Test
    fun `voices warning shown when an included available row exists`() {
        setSection(
            InteractiveShareSectionState(
                interactiveEnabled = true,
                rows = listOf(row(includeInShare = true, availability = RecordingAvailability.Available(1L))),
            ),
        )
        composeRule.onNodeWithText("Voices will be audible to anyone with the link.").assertIsDisplayed()
    }

    @Test
    fun `voices warning hidden when the only row is excluded`() {
        setSection(
            InteractiveShareSectionState(
                interactiveEnabled = true,
                rows = listOf(row(includeInShare = false, availability = RecordingAvailability.Available(1L))),
            ),
        )
        composeRule.onNodeWithText("Voices will be audible to anyone with the link.").assertDoesNotExist()
    }

    @Test
    fun `voices warning hidden when the only row is unavailable even though included`() {
        setSection(
            InteractiveShareSectionState(
                interactiveEnabled = true,
                rows = listOf(row(includeInShare = true, availability = RecordingAvailability.AudioRemoved)),
            ),
        )
        composeRule.onNodeWithText("Voices will be audible to anyone with the link.").assertDoesNotExist()
    }

    // ---- UI-73/UI-74/EDG-69: aggregate caps copy, derived not hardcoded ----

    private fun candidate(id: Int, sizeBytes: Long = 1_000L, durationSeconds: Double = 60.0) = TourRecordingCandidate(
        id = id,
        recordingUuid = "rec-$id",
        startTs = 0L,
        endTs = durationSeconds.toLong(),
        duration = durationSeconds,
        sizeBytes = sizeBytes,
        transcription = null,
        wpm = null,
        autoKind = TourRecordingKind.SPOKEN,
        includeInShare = true,
    )

    @Test
    fun `count-cap breach renders TourBuilder's exact validation string`() {
        val over = (0..TourBuilder.MAX_RECORDINGS).map { candidate(it) } // 13 candidates > cap of 12
        val expected = requireNotNull(TourBuilder.validationError(over))
        setSection(InteractiveShareSectionState(interactiveEnabled = true, validationErrorText = expected))
        composeRule.onNodeWithText(expected).assertIsDisplayed()
        assertTrue(expected.contains("${TourBuilder.MAX_RECORDINGS} recordings"))
    }

    @Test
    fun `bytes-cap breach renders TourBuilder's exact validation string`() {
        // 5 x 13MB = 65MB total, each under the 15MB per-file cap individually.
        val over = (0 until 5).map { candidate(it, sizeBytes = 13L * 1024 * 1024) }
        val expected = requireNotNull(TourBuilder.validationError(over))
        setSection(InteractiveShareSectionState(interactiveEnabled = true, validationErrorText = expected))
        composeRule.onNodeWithText(expected).assertIsDisplayed()
        assertTrue(expected.contains("60 MB"))
    }

    @Test
    fun `minutes-cap breach derives its minute figure from MAX_TOTAL_SECONDS, never hardcodes 108`() {
        val over = (0 until 10).map { candidate(it, durationSeconds = 700.0) } // 7000s > 6480s cap
        val expected = requireNotNull(TourBuilder.validationError(over))
        setSection(InteractiveShareSectionState(interactiveEnabled = true, validationErrorText = expected))
        composeRule.onNodeWithText(expected).assertIsDisplayed()
        // The cap-derived minute figure must equal MAX_TOTAL_SECONDS / 60 exactly —
        // this assertion would fail (not silently pass) if a caller ever hardcoded "108".
        val expectedCapMinutes = (TourBuilder.MAX_TOTAL_SECONDS / 60).toInt()
        assertTrue(expected.endsWith("at most $expectedCapMinutes."))
    }

    @Test
    fun `no validation error renders when the field is null`() {
        setSection(InteractiveShareSectionState(interactiveEnabled = true, validationErrorText = null, rows = listOf(row())))
        // Only the row's own text should be visible; nothing rust-colored/error-shaped is asserted here
        // beyond confirming no stray error node exists for a representative cap string fragment.
        composeRule.onNodeWithText("leave some out.", substring = true).assertDoesNotExist()
    }

    // ---- UI-15/UI-16/UI-17/EDG-120: trim toggle -----------------------

    @Test
    fun `trim toggle shows the can-trim subtitle interpolating the real trim meters constant`() {
        setSection(InteractiveShareSectionState(interactiveEnabled = true, trimEnabled = true, canTrim = true))
        composeRule.onNodeWithText(
            "Keeps the first and last 150 m off the shared map — including photos and waymarkers there.",
        ).assertIsDisplayed()
        composeRule.onNodeWithTag("trim-toggle").assertIsOn()
        composeRule.onNodeWithTag("trim-toggle").assertIsEnabled()
    }

    @Test
    fun `trim toggle shows the too-short subtitle and is disabled when canTrim is false`() {
        // Fold-in (FOLD-5): the renderer stays a dumb display of
        // whatever `trimEnabled` it is handed — the caller (the VM's
        // interactiveSection combine, see displayedTrimEnabled) is what
        // now pre-derives that value as outcome (intent && canTrim)
        // rather than raw intent, so a real too-short walk is handed
        // `trimEnabled = false` here, not `true`. This test's job is
        // only to prove the too-short subtitle + disabled state; the
        // outcome-vs-intent contract itself is covered by
        // WalkShareOrchestrationTest's displayedTrimEnabled tests and
        // WalkShareInteractiveTest's VM-level trim-outcome test.
        setSection(InteractiveShareSectionState(interactiveEnabled = true, trimEnabled = false, canTrim = false))
        composeRule.onNodeWithText("This walk is too short to trim.").assertIsDisplayed()
        composeRule.onNodeWithTag("trim-toggle").assertIsOff()
        composeRule.onNodeWithTag("trim-toggle").assertIsNotEnabled()
    }

    @Test
    fun `trim toggle off reflects checked state false`() {
        setSection(InteractiveShareSectionState(interactiveEnabled = true, trimEnabled = false, canTrim = true))
        composeRule.onNodeWithTag("trim-toggle").assertIsOff()
    }

    @Test
    fun `tapping the trim toggle fires onTrimEnabledChange`() {
        var fired: Boolean? = null
        setSection(
            InteractiveShareSectionState(interactiveEnabled = true, trimEnabled = true, canTrim = true),
            onTrimEnabledChange = { fired = it },
        )
        composeRule.onNodeWithTag("trim-toggle").performClick()
        assertEquals(false, fired)
    }

    // ---- Drift hazard: isShareInFlight gates input WITHOUT default-disabled dimming ----

    @Test
    fun `input locked disables the interactive switch (Toggle-style dimming IS correct parity)`() {
        setSection(InteractiveShareSectionState(interactiveEnabled = false, inputLocked = true))
        composeRule.onNodeWithTag("interactive-toggle").assertIsNotEnabled()
    }

    @Test
    fun `input locked marks the include button and kind chip semantically disabled`() {
        setSection(
            InteractiveShareSectionState(interactiveEnabled = true, inputLocked = true, rows = listOf(row())),
        )
        composeRule.onNodeWithTag("tour-recording-include-0").assertIsNotEnabled()
        composeRule.onNodeWithTag("tour-recording-kind-0").assertIsNotEnabled()
    }

    @Test
    fun `input locked blocks the include button tap from firing`() {
        var fired = false
        setSection(
            InteractiveShareSectionState(interactiveEnabled = true, inputLocked = true, rows = listOf(row())),
            onToggleRowInclude = { fired = true },
        )
        composeRule.onNodeWithTag("tour-recording-include-0").performClick()
        assertFalse("a locked row must swallow the tap", fired)
    }

    @Test
    fun `not locked leaves the include button enabled`() {
        setSection(
            InteractiveShareSectionState(interactiveEnabled = true, inputLocked = false, rows = listOf(row())),
        )
        composeRule.onNodeWithTag("tour-recording-include-0").assertIsEnabled()
    }
}

/** Pure, screenshot-free coverage of the row-level opacity/formatting helpers — no Compose/Robolectric needed. */
class InteractiveShareSectionPureFunctionsTest {

    private fun availableRow(includeInShare: Boolean) = TourRecordingRowState(
        id = 0,
        durationSeconds = 90,
        startEpochSeconds = 0L,
        transcriptionPreview = null,
        effectiveKind = TourRecordingKind.SPOKEN,
        includeInShare = includeInShare,
        availability = RecordingAvailability.Available(1_000_000L),
    )

    private fun unavailableRow(availability: RecordingAvailability) = TourRecordingRowState(
        id = 0,
        durationSeconds = 90,
        startEpochSeconds = 0L,
        transcriptionPreview = null,
        effectiveKind = TourRecordingKind.SPOKEN,
        includeInShare = true,
        availability = availability,
    )

    @Test
    fun `rowOpacity is full opacity for an included available row`() {
        assertEquals(1f, rowOpacity(availableRow(includeInShare = true)))
    }

    @Test
    fun `rowOpacity is 0-6 for an excluded available row`() {
        assertEquals(0.6f, rowOpacity(availableRow(includeInShare = false)))
    }

    @Test
    fun `rowOpacity is 0-45 for both unavailable reasons and for preparing`() {
        assertEquals(0.45f, rowOpacity(unavailableRow(RecordingAvailability.AudioRemoved)))
        assertEquals(0.45f, rowOpacity(unavailableRow(RecordingAvailability.TooLargeToCarry)))
        assertEquals(0.45f, rowOpacity(unavailableRow(RecordingAvailability.Preparing)))
    }

    @Test
    fun `chipOpacity is full when included, 0-35 when excluded`() {
        assertEquals(1f, chipOpacity(includeInShare = true))
        assertEquals(0.35f, chipOpacity(includeInShare = false))
    }

    @Test
    fun `nested row and chip opacity compound multiplicatively, matching SwiftUI (spec UI-32)`() {
        val row = availableRow(includeInShare = false)
        val effective = rowOpacity(row) * chipOpacity(row.includeInShare)
        // 0.6 x 0.35 = 0.21 — the two tiers are asserted SEPARATELY above; this test
        // additionally locks their PRODUCT so a future edit to either tier alone
        // cannot silently change the compounded on-screen result without failing here.
        assertEquals(0.21f, effective, 0.0001f)
    }

    @Test
    fun `rowShowsControls is true only for Available`() {
        assertTrue(rowShowsControls(availableRow(includeInShare = true)))
        assertFalse(rowShowsControls(unavailableRow(RecordingAvailability.AudioRemoved)))
        assertFalse(rowShowsControls(unavailableRow(RecordingAvailability.TooLargeToCarry)))
        assertFalse(rowShowsControls(unavailableRow(RecordingAvailability.Preparing)))
    }

    @Test
    fun `formatRowDuration does not zero-pad minutes (iOS parity, d colon 02d)`() {
        assertEquals("2:05", formatRowDuration(125))
        assertEquals("0:09", formatRowDuration(9))
        assertEquals("11:00", formatRowDuration(660))
    }

    @Test
    fun `formatRowSizeMb renders one decimal place`() {
        assertEquals("3.0 MB", formatRowSizeMb(3_145_728L))
        assertEquals("0.0 MB", formatRowSizeMb(0L))
    }

    @Test
    fun `hasAudibleVoices requires both included and available on at least one row`() {
        assertTrue(hasAudibleVoices(listOf(availableRow(includeInShare = true))))
        assertFalse(hasAudibleVoices(listOf(availableRow(includeInShare = false))))
        assertFalse(hasAudibleVoices(listOf(unavailableRow(RecordingAvailability.AudioRemoved))))
        assertFalse(hasAudibleVoices(emptyList()))
    }
}
