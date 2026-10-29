// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain.honor

import java.time.Instant
import java.time.ZoneId
import org.walktalkmeditate.pilgrim.domain.Clock

/**
 * Pure moment bookkeeping for an honor walk (iOS
 * `HonorMomentTracker.swift@7c200bf`): which moments have been reached,
 * which voice is playing or waiting, and when a waiting voice is
 * abandoned. No clock of its own and no timers; [HonorEngine] feeds it
 * fixes and gates. Parity spec B §8.
 *
 * It also watches a pilgrimage stage's water (pilgrimage-stage spec P3
 * §2): only on-way water marks, each spoken once, within
 * [HonorTuning.MARK_AHEAD_METERS] ahead along the line while the walker is
 * on the Way. Clocks of engine seconds keep the caption line quiet (iOS PR
 * #91, `HonorMomentTracker.swift:62-81@e551b11`): water's own hour
 * ([HonorTuning.MARK_QUIET_SECONDS]) and a minute between any two notices
 * ([HonorTuning.NOTICE_GAP_SECONDS]), each first notice free. It reads no
 * gate, and runs only on a fix. Own and shared Ways carry no marks, so for
 * them it never speaks.
 *
 * A Shikoku stage also has a [StampOffice] (iOS PR #91, `:193-223@e551b11`):
 * in the office's last [HonorTuning.STAMP_NOTICE_MINUTES], the nearest
 * numbered temple at least [HonorTuning.STAMP_MIN_AHEAD_METERS] ahead is
 * named, once, with [HonorTuning.STAMP_QUIET_SECONDS] of walking between
 * temples. A temple already named holds every later one until it is
 * inside the kilometre. The temple goes before the fountain on a fix, and
 * the shared minute then lets only the first speak.
 */
class HonorMomentTracker(
    moments: List<WayMoment>,
    marks: List<WayMark> = emptyList(),
    private val geometry: WayGeometry,
    private val voicesEnabled: Boolean,
    private val distance: HonorDistance = WGS84_HONOR_DISTANCE,
    /** The route's stamp office; null on every Way but a Shikoku stage's, and then no temple is ever named. */
    private val stamp: StampOffice? = null,
) {

    sealed class Action {
        data class Reached(val moment: WayMoment) : Action()
        data class VoiceStart(val moment: WayMoment) : Action()
        data object VoicePause : Action()
        data object VoiceResume : Action()
        data class VoiceDropped(val moment: WayMoment) : Action()

        /** A water source [meters] ahead on the line, unrounded. */
        data class MarkAhead(val mark: WayMark, val meters: Double) : Action()

        /** A stamp temple [meters] ahead on the line, unrounded, with its office soon to shut. */
        data class StampAhead(val temple: WayMoment, val meters: Double) : Action()
    }

    /**
     * iOS `StampOffice` (`HonorMomentTracker.swift:21-45@e551b11`): when the
     * route's stamp office shuts, in minutes since midnight, and the wall
     * clock and zone to read that against, both fixed when the engine is
     * built.
     */
    class StampOffice(val closesMinutes: Int, val zone: ZoneId, private val clock: Clock) {

        /**
         * True from [HonorTuning.STAMP_NOTICE_MINUTES] before the office
         * shuts until it does, on the local minute of the day. Deliberately
         * reads the closing time alone, on the same day: every value the
         * dataset carries is 08:00–17:00, so `opens` never falls inside the
         * window and no close wraps past midnight.
         */
        val isClosingSoon: Boolean
            get() {
                val local = Instant.ofEpochMilli(clock.now()).atZone(zone)
                val minutesNow = local.hour * 60 + local.minute
                return minutesNow >= closesMinutes - HonorTuning.STAMP_NOTICE_MINUTES && minutesNow < closesMinutes
            }
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
     * queue order, the water marks and temples spoken, and the notice
     * clocks, each null while its first notice is still free (a null
     * restored stays null, never 0).
     */
    data class Snapshot(
        val reached: Set<String>,
        val queue: List<String>,
        val firedMarks: Set<String>,
        val lastNoticeSeconds: Double?,
        val lastWaterSeconds: Double?,
        val lastStampSeconds: Double?,
        /** The temples named, by moment id. */
        val firedStamps: Set<String>,
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

    /**
     * The stamp temples among the moments, in the order they are passed (the
     * frac-then-id sort above), empty whenever the route states no hours, so
     * the scan has nothing to scan on every Way but Shikoku's (`:97@e551b11`).
     */
    private val fudasho: List<WayMoment> =
        if (stamp == null) emptyList() else this.moments.filter { it.templeNumber != null }
    private val firedStamps = mutableSetOf<String>()
    private val reached = mutableSetOf<String>()
    private val queue = mutableListOf<WayMoment>()
    private val firedMarks = mutableSetOf<String>()

    /**
     * The engine clock at the last notice of either kind, read only for the
     * minute that keeps two notices off the caption line at once; null means
     * the line is free (iOS `lastNoticeSeconds`, `HonorMomentTracker.swift:68-73@e551b11`).
     * That minute can cost one of them, as iOS documents and intends: a
     * fountain under about 80 m ahead when another notice speaks is passed
     * before the minute is up, and a temple 1.00-1.08 km ahead is inside the
     * kilometre by then.
     */
    private var lastNoticeSeconds: Double? = null

    /** Water's own hour: the engine clock at the last water notice; null means the first is free (`:64-67`). */
    private var lastWaterSeconds: Double? = null

    /** The temples' ten minutes: the engine clock at the last temple notice; null means the first is free (`:80-81`). */
    private var lastStampSeconds: Double? = null

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

        // The temple before the fountain: when both are due on one fix the
        // minute lets only the first speak, and of the two an office about to
        // shut is the one still worth a walker's afternoon (`:138-143@e551b11`).
        actions += stampAhead(progressFrac, activeSeconds, isOnWay)
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
        lastWaterSeconds = lastWaterSeconds,
        lastStampSeconds = lastStampSeconds,
        firedStamps = firedStamps.toSet(),
    )

    /**
     * Restores [snapshot] with nothing playing: a voice that was playing when
     * its process died is over, never replayed (plan U17). Moment ids this
     * Way does not carry, and mark and temple ids it does not watch, are
     * dropped.
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
        val temples = fudasho.mapTo(HashSet()) { it.id }
        firedStamps.clear()
        snapshot.firedStamps.filterTo(firedStamps) { it in temples }
        lastNoticeSeconds = snapshot.lastNoticeSeconds
        lastWaterSeconds = snapshot.lastWaterSeconds
        lastStampSeconds = snapshot.lastStampSeconds
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
     * iOS `waterAhead` (`HonorMomentTracker.swift:175-191@e551b11`): the
     * nearest unfired water the walker is about to reach. Never one already
     * behind them, never off the Way, never inside the minute after another
     * notice, and at most one an hour of walking; the marks skipped inside
     * the quiet hour stay silent pins. A water notice writes its own hour
     * and the shared minute.
     */
    private fun waterAhead(progressFrac: Double, activeSeconds: Double, isOnWay: Boolean): List<Action> {
        if (!isOnWay || marks.isEmpty() || !(geometry.totalMeters > 0) || !isLineFree(activeSeconds)) return emptyList()
        val last = lastWaterSeconds
        if (last != null && activeSeconds - last < HonorTuning.MARK_QUIET_SECONDS) return emptyList()
        for (mark in marks) {
            if (mark.id in firedMarks) continue
            val ahead = (mark.frac - progressFrac) * geometry.totalMeters
            if (!(ahead >= 0)) continue
            if (!(ahead <= HonorTuning.MARK_AHEAD_METERS)) break
            firedMarks += mark.id
            lastWaterSeconds = activeSeconds
            lastNoticeSeconds = activeSeconds
            return listOf(Action.MarkAhead(mark, meters = ahead))
        }
        return emptyList()
    }

    /**
     * iOS `stampAhead` (`HonorMomentTracker.swift:193-223@e551b11`): the
     * nearest stamp temple still far enough ahead to be a choice, in the last
     * two hours its office is open. Once per temple, never one behind, never
     * off the way, never inside the minute after another notice, and ten
     * minutes of walking after the last temple. A temple behind or inside
     * the kilometre is passed over; the first one past it either speaks or,
     * if it was named already, ends the scan. A fact and never a forecast:
     * what the office does and how far it is.
     */
    private fun stampAhead(progressFrac: Double, activeSeconds: Double, isOnWay: Boolean): List<Action> {
        val office = stamp
        if (!isOnWay || fudasho.isEmpty() || !(geometry.totalMeters > 0) || office == null ||
            !isLineFree(activeSeconds) || !office.isClosingSoon
        ) {
            return emptyList()
        }
        val last = lastStampSeconds
        if (last != null && activeSeconds - last < HonorTuning.STAMP_QUIET_SECONDS) return emptyList()
        for (temple in fudasho) {
            val ahead = (temple.frac - progressFrac) * geometry.totalMeters
            // Behind them, or close enough that the walk has already decided.
            if (!(ahead >= HonorTuning.STAMP_MIN_AHEAD_METERS)) continue
            // The nearest choice, already named: the next is not news yet.
            if (temple.id in firedStamps) return emptyList()
            firedStamps += temple.id
            lastStampSeconds = activeSeconds
            lastNoticeSeconds = activeSeconds
            return listOf(Action.StampAhead(temple, meters = ahead))
        }
        return emptyList()
    }

    /** One notice on the caption line at a time, whatever its kind (`:225-229`): exactly a minute is free. */
    private fun isLineFree(activeSeconds: Double): Boolean {
        val last = lastNoticeSeconds ?: return true
        return activeSeconds - last >= HonorTuning.NOTICE_GAP_SECONDS
    }

    private fun place(moment: WayMoment): WayCoordinate = moment.at ?: geometry.coordinate(atFrac = moment.frac)
}
