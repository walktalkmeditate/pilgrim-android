// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.walktalkmeditate.pilgrim.data.share.ExpiryOption

private const val TOLERANCE = 0.0001f
private const val THIRTY_DAYS_MS = 30L * 24 * 60 * 60 * 1000

/**
 * Issue #222 / iOS parity `WalkSharingButtons.swift@2ee1185`. Pure-logic
 * coverage (no Robolectric) for the three things ported byte-for-byte
 * from the Swift: the watermark opacity formula (`:281-288`), the
 * kanji-per-expiry-option mapping (`:290-297`), and the copy-toast
 * generation guard (`:230-239`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WalkSharingBlockLogicTest {

    // -- watermarkOpacity: `:281-288@2ee1185` --
    //
    // guard let shareDate = cached.shareDate else { return 0.05 }
    // let total = cached.expiry.timeIntervalSince(shareDate)
    // guard total > 0 else { return 0.025 }
    // let elapsed = Date().timeIntervalSince(shareDate)
    // let fraction = min(max(elapsed / total, 0), 1)
    // return 0.07 - (fraction * 0.045)

    @Test
    fun freshShare_isAtTheHighEndOfTheRange() {
        val shareDate = 1_000_000L
        val expiry = shareDate + THIRTY_DAYS_MS
        val opacity = watermarkOpacity(shareDate, expiry, nowEpochMs = shareDate)
        assertEquals(0.07f, opacity, TOLERANCE)
    }

    @Test
    fun atExpiry_decaysToTheSwiftAsymptoteOf0025_not005() {
        // The task brief's prose says "floor 0.05" at expiry, but the
        // Swift source computes 0.07 - (1 * 0.045) == 0.025 here. 0.05
        // is ONLY the null-shareDate fallback (see below) — a
        // different branch entirely. This test asserts what the ported
        // formula actually does, per the "port the exact formula"
        // instruction taking precedence over the prose paraphrase.
        val shareDate = 1_000_000L
        val expiry = shareDate + THIRTY_DAYS_MS
        val opacity = watermarkOpacity(shareDate, expiry, nowEpochMs = expiry)
        assertEquals(0.025f, opacity, TOLERANCE)
    }

    @Test
    fun nullShareDate_fallsBackTo005_perSwiftGuardLet() {
        val opacity = watermarkOpacity(
            shareDateEpochMs = null,
            expiryEpochMs = 2_000_000L,
            nowEpochMs = 1_500_000L,
        )
        assertEquals(0.05f, opacity, TOLERANCE)
    }

    @Test
    fun opacityDecaysMonotonically_fromShareDateToExpiry() {
        val shareDate = 1_000_000L
        val expiry = shareDate + 90L * 24 * 60 * 60 * 1000
        val samples = (0..10).map { step ->
            val t = shareDate + (expiry - shareDate) * step / 10
            watermarkOpacity(shareDate, expiry, nowEpochMs = t)
        }
        for (i in 1 until samples.size) {
            assertTrue(
                "opacity must be non-increasing over time: ${samples[i - 1]} -> ${samples[i]}",
                samples[i] <= samples[i - 1],
            )
        }
    }

    @Test
    fun degenerateExpiryAtOrBeforeShareDate_returnsTheSwiftTotalGuardFloor() {
        // guard total > 0 else { return 0.025 }
        val shareDate = 2_000_000L
        val opacity = watermarkOpacity(
            shareDateEpochMs = shareDate,
            expiryEpochMs = shareDate,
            nowEpochMs = shareDate,
        )
        assertEquals(0.025f, opacity, TOLERANCE)
    }

    @Test
    fun opacityPastExpiry_staysClampedAtTheFloor_doesNotGoNegative() {
        val shareDate = 1_000_000L
        val expiry = shareDate + 1_000L
        val opacity = watermarkOpacity(shareDate, expiry, nowEpochMs = expiry + 999_999L)
        assertEquals(0.025f, opacity, TOLERANCE)
    }

    // -- kanji mapping: `kanjiForOption(_:)` `:290-297@2ee1185` --
    //
    // case "moon": return "\u{6708}"
    // case "season": return "\u{5B63}"
    // case "cycle": return "\u{5DE1}"
    // default: return nil

    @Test
    fun kanjiMapping_matchesSwiftKanjiForOptionExactCodepoints() {
        assertEquals("月", ExpiryOption.Moon.kanji)
        assertEquals("季", ExpiryOption.Season.kanji)
        assertEquals("巡", ExpiryOption.Cycle.kanji)
    }

    @Test
    fun labelMapping_matchesSwiftLabelForOption() {
        assertEquals("1 moon", ExpiryOption.Moon.label)
        assertEquals("1 season", ExpiryOption.Season.label)
        assertEquals("1 cycle", ExpiryOption.Cycle.label)
    }

    // -- CopyToastState generation guard: `:230-239@2ee1185` --
    //
    // copiedToastGeneration += 1
    // let gen = copiedToastGeneration
    // showCopiedToast = true
    // Task {
    //     try? await Task.sleep(for: .seconds(2))
    //     if copiedToastGeneration == gen { showCopiedToast = false }
    // }

    @Test
    fun copyToast_showsImmediately_thenClearsAfterDuration() = runTest {
        val state = CopyToastState()
        state.trigger(scope = this, durationMs = 2_000L)
        assertTrue(state.visible)

        advanceTimeBy(2_001L)
        runCurrent()
        assertFalse(state.visible)
    }

    @Test
    fun copyToast_rapidRetap_extendsTheWindowInsteadOfCuttingItShort() = runTest {
        val state = CopyToastState()
        state.trigger(scope = this, durationMs = 2_000L)

        advanceTimeBy(1_000L)
        runCurrent()
        assertTrue("toast should still be visible mid-window", state.visible)

        // Second tap at t=1000ms bumps the generation.
        state.trigger(scope = this, durationMs = 2_000L)

        // The FIRST tap's reset would have fired at t=2000ms — it must
        // now be a stale-generation no-op, not a flicker-off.
        advanceTimeBy(1_000L) // t=2000ms
        runCurrent()
        assertTrue(
            "toast must not flicker off from the stale first-tap reset",
            state.visible,
        )

        // The SECOND tap's own window ends at t=3000ms.
        advanceTimeBy(1_000L) // t=3000ms
        runCurrent()
        assertFalse(state.visible)
    }

    @Test
    fun copyToast_singleTap_doesNotClearEarly() = runTest {
        val state = CopyToastState()
        state.trigger(scope = this, durationMs = 2_000L)

        advanceTimeBy(1_999L)
        runCurrent()
        assertTrue("toast must still be visible one tick before the deadline", state.visible)
    }
}
