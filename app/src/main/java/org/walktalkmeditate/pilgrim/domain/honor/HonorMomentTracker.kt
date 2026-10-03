// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain.honor

/**
 * Pure moment bookkeeping for an honor walk (iOS
 * `HonorMomentTracker.swift@7c200bf`): which moments have been reached,
 * which voice is playing or waiting, and when a waiting voice is
 * abandoned. No clock and no timers; [HonorEngine] feeds it fixes and
 * gates. Parity spec B §8.
 *
 * It also watches a pilgrimage stage's water (pilgrimage-stage spec P3
 * §2): only on-way water marks, each spoken once, at most one per
 * [HonorTuning.MARK_QUIET_SECONDS] of the engine clock with the first
 * free, and only within [HonorTuning.MARK_AHEAD_METERS] ahead along the
 * line while the walker is on the Way. It reads no gate, and runs only on
 * a fix. Own and shared Ways carry no marks, so for them it never speaks.
 */
class HonorMomentTracker(
    moments: List<WayMoment>,
    marks: List<WayMark> = emptyList(),
    private val geometry: WayGeometry,
    private val voicesEnabled: Boolean,
    private val distance: HonorDistance = WGS84_HONOR_DISTANCE,
) {

    sealed class Action {
        data class Reached(val moment: WayMoment) : Action()
        data class VoiceStart(val moment: WayMoment) : Action()
        data object VoicePause : Action()
        data object VoiceResume : Action()
        data class VoiceDropped(val moment: WayMoment) : Action()

        /** A water source [meters] ahead on the line, unrounded. */
        data class MarkAhead(val mark: WayMark, val meters: Double) : Action()
    }

    data class Gates(
        val paused: Boolean = false,
        val meditating: Boolean = false,
        val recording: Boolean = false,
        val externalAudio: Boolean = false,
    ) {
        val isClosed: Boolean get() = paused || meditating || recording || externalAudio
    }

    /**
     * What a revival needs back: the moments reached, the voices waiting in
     * queue order, the water marks spoken, and the engine clock at the last
     * notice (null while the first is still free).
     */
    data class Snapshot(
        val reached: Set<String>,
        val queue: List<String>,
        val firedMarks: Set<String>,
        val lastNoticeSeconds: Double?,
    )

    /**
     * By frac, ties by id, with Swift's `==` and `<` on the frac, so -0.0
     * ties 0.0, and Swift's `<` on the id (`HonorMomentTracker.swift:42@7c200bf`).
     */
    private val moments: List<WayMoment> = moments.sortedWith { a, b ->
        when {
            a.frac == b.frac -> a.id.swiftCompareTo(b.id)
            a.frac < b.frac -> -1
            else -> 1
        }
    }

    /**
     * On-way water only, a fountain 250 m off the trail being a detour, in
     * a stable sort on Swift's `<` with no tiebreak, so -0.0 ties 0.0 and
     * equal fracs keep the package's order (`HonorMomentTracker.swift:45-48@7c200bf`).
     */
    private val marks: List<WayMark> = marks
        .filter { it.kind == WayMarkKind.WATER && it.offLineMeters <= HonorTuning.ON_WAY_METERS }
        .sortedWith { a, b ->
            when {
                a.frac < b.frac -> -1
                b.frac < a.frac -> 1
                else -> 0
            }
        }
    private val reached = mutableSetOf<String>()
    private val queue = mutableListOf<WayMoment>()
    private val firedMarks = mutableSetOf<String>()

    /**
     * The engine clock at the last notice; null means the first is free. iOS
     * names it `lastMarkSeconds` (`HonorMomentTracker.swift:37@7c200bf`); the
     * name is the one iOS PR #91 gives it, a clock that notice kinds share.
     */
    private var lastNoticeSeconds: Double? = null

    var playing: WayMoment? = null
        private set
    var isVoicePaused: Boolean = false
        private set

    /**
     * One accepted fix. [activeSeconds] is the engine clock and [isOnWay]
     * the engine's own reading after this fix; their defaults are iOS's.
     */
    fun update(
        location: WayCoordinate,
        progressFrac: Double,
        gates: Gates,
        isStationary: Boolean,
        activeSeconds: Double = 0.0,
        isOnWay: Boolean = true,
    ): List<Action> {
        val actions = mutableListOf<Action>()

        for (moment in moments) {
            if (moment.id in reached) continue
            if (!(progressFrac >= moment.frac - HonorTuning.MOMENT_FRAC_TOLERANCE)) continue
            val radius = if (moment.isVoice) HonorTuning.VOICE_RADIUS_METERS else HonorTuning.MOMENT_RADIUS_METERS
            if (!(distance(location, place(moment)) <= radius)) continue
            reached += moment.id
            if (moment.isVoice) {
                if (voicesEnabled) queue += moment
            } else {
                actions += Action.Reached(moment)
            }
        }

        // Abandon voices the walker has left far behind, but never while a
        // voice is playing: listening to a long musing carries the walker
        // hundreds of metres, and the next voice must still be waiting.
        if (!isStationary && (playing == null || isVoicePaused)) {
            val dropped = queue.filter { distance(location, place(it)) > HonorTuning.VOICE_DROP_METERS }
            queue.removeAll { it in dropped }
            dropped.mapTo(actions) { Action.VoiceDropped(it) }
            val current = playing
            if (current != null && isVoicePaused &&
                distance(location, place(current)) > HonorTuning.VOICE_DROP_METERS
            ) {
                playing = null
                isVoicePaused = false
                actions += Action.VoiceDropped(current)
            }
        }

        actions += waterAhead(progressFrac, activeSeconds, isOnWay)
        actions += startNextIfPossible(gates)
        return actions
    }

    fun gatesDidChange(gates: Gates): List<Action> {
        if (playing != null) {
            if (gates.isClosed && !isVoicePaused) {
                isVoicePaused = true
                return listOf(Action.VoicePause)
            }
            if (!gates.isClosed && isVoicePaused) {
                isVoicePaused = false
                return listOf(Action.VoiceResume)
            }
            return emptyList()
        }
        return startNextIfPossible(gates)
    }

    fun voiceDidFinish(gates: Gates): List<Action> {
        playing = null
        isVoicePaused = false
        return startNextIfPossible(gates)
    }

    fun snapshot(): Snapshot = Snapshot(
        reached = reached.toSet(),
        queue = queue.map { it.id },
        firedMarks = firedMarks.toSet(),
        lastNoticeSeconds = lastNoticeSeconds,
    )

    /**
     * Restores [snapshot] with nothing playing: a voice that was playing when
     * its process died is over, never replayed (plan U17). Moment ids this
     * Way does not carry, and mark ids it does not watch, are dropped.
     */
    fun restore(snapshot: Snapshot) {
        val byId = moments.associateBy { it.id }
        reached.clear()
        snapshot.reached.filterTo(reached) { it in byId }
        queue.clear()
        snapshot.queue.mapNotNullTo(queue) { byId[it] }
        val watched = marks.mapTo(HashSet()) { it.id }
        firedMarks.clear()
        snapshot.firedMarks.filterTo(firedMarks) { it in watched }
        lastNoticeSeconds = snapshot.lastNoticeSeconds
        playing = null
        isVoicePaused = false
    }

    private fun startNextIfPossible(gates: Gates): List<Action> {
        if (playing != null || gates.isClosed || queue.isEmpty()) return emptyList()
        val next = queue.removeAt(0)
        playing = next
        return listOf(Action.VoiceStart(next))
    }

    /**
     * iOS `waterAhead` (`HonorMomentTracker.swift:124-139@7c200bf`): the
     * nearest unfired water the walker is about to reach. Never one already
     * behind them, never off the Way, and at most one an hour of walking;
     * the marks skipped inside the quiet hour stay silent pins.
     */
    private fun waterAhead(progressFrac: Double, activeSeconds: Double, isOnWay: Boolean): List<Action> {
        if (!isOnWay || marks.isEmpty() || !(geometry.totalMeters > 0)) return emptyList()
        val last = lastNoticeSeconds
        if (last != null && activeSeconds - last < HonorTuning.MARK_QUIET_SECONDS) return emptyList()
        for (mark in marks) {
            if (mark.id in firedMarks) continue
            val ahead = (mark.frac - progressFrac) * geometry.totalMeters
            if (!(ahead >= 0)) continue
            if (!(ahead <= HonorTuning.MARK_AHEAD_METERS)) break
            firedMarks += mark.id
            lastNoticeSeconds = activeSeconds
            return listOf(Action.MarkAhead(mark, meters = ahead))
        }
        return emptyList()
    }

    private fun place(moment: WayMoment): WayCoordinate = moment.at ?: geometry.coordinate(atFrac = moment.frac)
}
