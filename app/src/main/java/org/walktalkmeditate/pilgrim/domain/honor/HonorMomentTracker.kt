// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain.honor

/**
 * Pure moment bookkeeping for an honor walk (iOS
 * `HonorMomentTracker.swift@7c200bf`): which moments have been reached,
 * which voice is playing or waiting, and when a waiting voice is
 * abandoned. No clock and no timers; [HonorEngine] feeds it fixes and
 * gates. Parity spec B §8.
 *
 * The stage-only water caption (`waterAhead`, the `marks` filter, and the
 * `markAhead` action) is not ported: only a pilgrimage stage carries
 * marks, so for own and shared Ways it always yields nothing. Its branch
 * point is between the drops and the start in [update]
 * (`HonorMomentTracker.swift:91@7c200bf`).
 */
class HonorMomentTracker(
    moments: List<WayMoment>,
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
    }

    data class Gates(
        val paused: Boolean = false,
        val meditating: Boolean = false,
        val recording: Boolean = false,
        val externalAudio: Boolean = false,
    ) {
        val isClosed: Boolean get() = paused || meditating || recording || externalAudio
    }

    /** What a revival needs back: the moments reached, and the voices waiting, in queue order. */
    data class Snapshot(val reached: Set<String>, val queue: List<String>)

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
    private val reached = mutableSetOf<String>()
    private val queue = mutableListOf<WayMoment>()

    var playing: WayMoment? = null
        private set
    var isVoicePaused: Boolean = false
        private set

    fun update(location: WayCoordinate, progressFrac: Double, gates: Gates, isStationary: Boolean): List<Action> {
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

    fun snapshot(): Snapshot = Snapshot(reached = reached.toSet(), queue = queue.map { it.id })

    /**
     * Restores [snapshot] with nothing playing: a voice that was playing when
     * its process died is over, never replayed (plan U17). Ids this Way does
     * not carry are dropped.
     */
    fun restore(snapshot: Snapshot) {
        val byId = moments.associateBy { it.id }
        reached.clear()
        snapshot.reached.filterTo(reached) { it in byId }
        queue.clear()
        snapshot.queue.mapNotNullTo(queue) { byId[it] }
        playing = null
        isVoicePaused = false
    }

    private fun startNextIfPossible(gates: Gates): List<Action> {
        if (playing != null || gates.isClosed || queue.isEmpty()) return emptyList()
        val next = queue.removeAt(0)
        playing = next
        return listOf(Action.VoiceStart(next))
    }

    private fun place(moment: WayMoment): WayCoordinate = moment.at ?: geometry.coordinate(atFrac = moment.frac)
}
