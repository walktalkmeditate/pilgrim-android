// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test
import org.walktalkmeditate.pilgrim.ui.home.scenery.SceneryGenerator
import org.walktalkmeditate.pilgrim.ui.home.scenery.SceneryType
import org.walktalkmeditate.pilgrim.ui.home.scenery.WalkThreshold
import org.walktalkmeditate.pilgrim.ui.home.scroll.DotHapticKind

/**
 * HomeScreen's [dotHapticKind] duplicates SceneryGenerator.pick's
 * deterministic branch (iOS duplicates it the same way in
 * configureHaptics). This fixture sweep pins the two sites in
 * lockstep: a one-sided edit to either condition ships a gate haptic
 * with no gate on screen, or a silent gate — and fails here first.
 */
class DotHapticKindLockstepTest {

    private fun snap(
        uuid: String,
        startMs: Long,
        distanceM: Double,
        durationSec: Double,
        isSeek: Boolean,
        foundPlaces: Int,
        threshold: WalkThreshold?,
    ) = WalkSnapshot(
        id = 1L,
        uuid = uuid,
        startMs = startMs,
        distanceM = distanceM,
        durationSec = durationSec,
        averagePaceSecPerKm = 360.0,
        cumulativeDistanceM = distanceM,
        talkDurationSec = 0L,
        meditateDurationSec = 0L,
        favicon = null,
        isShared = false,
        weatherCondition = null,
        isSeek = isSeek,
        foundPlaces = foundPlaces,
        threshold = threshold,
    )

    @Test
    fun `haptic kind agrees with the scenery pick across varied snapshots`() {
        val seen = mutableSetOf<DotHapticKind>()
        var i = 0
        for (threshold in listOf(null, WalkThreshold.Practice, WalkThreshold.Seeking)) {
            for (isSeek in listOf(false, true)) {
                for (foundPlaces in listOf(0, 1, 4)) {
                    // Varied seeds so the lottery branch rolls both
                    // hits and misses behind the deterministic one.
                    repeat(40) {
                        i++
                        val snapshot = snap(
                            uuid = "00000000-0000-0000-0000-" +
                                i.toString().padStart(12, '0'),
                            startMs = 1_700_000_000_000L + i * 3_600_000L,
                            distanceM = 1_000.0 + i,
                            durationSec = 600.0 + i,
                            isSeek = isSeek,
                            foundPlaces = foundPlaces,
                            threshold = threshold,
                        )
                        val kind = dotHapticKind(snapshot)
                        val sceneryType = SceneryGenerator.pick(snapshot)?.type
                        seen += kind
                        assertEquals(
                            "gate kind must match a torii for $snapshot",
                            kind == DotHapticKind.Gate,
                            sceneryType == SceneryType.Torii,
                        )
                        assertEquals(
                            "cairn kind must match a cairn for $snapshot",
                            kind == DotHapticKind.Cairn,
                            sceneryType == SceneryType.Cairn,
                        )
                    }
                }
            }
        }
        assertEquals(
            "sweep must exercise every kind",
            setOf(DotHapticKind.Plain, DotHapticKind.Gate, DotHapticKind.Cairn),
            seen,
        )
    }
}
