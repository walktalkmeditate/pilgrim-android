// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.walk.honor

import kotlin.math.max
import kotlin.math.roundToLong
import org.walktalkmeditate.pilgrim.data.honor.HonorEngineState
import org.walktalkmeditate.pilgrim.data.honor.HonorMomentStateEntity
import org.walktalkmeditate.pilgrim.data.honor.HonorNoticeEntity
import org.walktalkmeditate.pilgrim.data.honor.HonorNoticeKind
import org.walktalkmeditate.pilgrim.data.honor.HonorSessionEntity
import org.walktalkmeditate.pilgrim.data.honor.HonorSourceKind
import org.walktalkmeditate.pilgrim.data.honor.HonorVoiceState
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.HonorStageOutcome
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageStageIdentity
import org.walktalkmeditate.pilgrim.domain.WalkState
import org.walktalkmeditate.pilgrim.domain.honor.HonorEngine
import org.walktalkmeditate.pilgrim.domain.honor.HonorMomentTracker
import org.walktalkmeditate.pilgrim.domain.honor.HonorPersistence
import org.walktalkmeditate.pilgrim.domain.honor.HonorPhase
import org.walktalkmeditate.pilgrim.domain.honor.VoiceKind
import org.walktalkmeditate.pilgrim.domain.honor.WayMoment
import org.walktalkmeditate.pilgrim.domain.honor.WayMomentKind
import org.walktalkmeditate.pilgrim.domain.seek.SeekGlanceModel

/**
 * A walker's command from a card or the listening chip (parity spec D §6),
 * which the UI sends and `:tracker` applies once, by its sequence number.
 * A command about the voice held names the voice the walker saw held, and
 * `:tracker` drops it once another voice, or none, is: iOS judges the tap
 * one main-queue hop after it lands, Android a process hop and a Room read.
 */
sealed interface HonorCommand {

    /** Pause or resume the voice the player holds, or play another voice outside the queue (iOS `togglePlayback(of:)`). */
    data class TogglePlayback(val momentId: String) : HonorCommand

    /** `togglePlayback(of:)` on the voice held, from the chip or its own card: never starts one. */
    data class PauseResume(val momentId: String) : HonorCommand

    /** Scrub to [fraction] of a voice, starting it first if it isn't the one held (iOS `seekVoice`). */
    data class Scrub(val momentId: String, val fraction: Double) : HonorCommand

    data class Skip(val momentId: String) : HonorCommand

    /** 1× → 1.25× → 1.5× → 2× → 1× (iOS `cycleVoiceRate`). */
    data object CycleRate : HonorCommand

    /**
     * The walker's earlier reply to a voice, from a previous honoring, or
     * to a stage's closing line by [HonorPersistence.STAGE_REFLECTION_MOMENT_ID]
     * (iOS `playReply(url:)`).
     */
    data class PlayReply(val momentId: String) : HonorCommand
}

/**
 * The lock-screen glance (iOS `HonorGlanceState`, `SeekGlance.swift:21-28@7c200bf`),
 * computed from the engine after every fix. The bucket is Seek's: floored
 * to 100 m and capped at 2,000 (parity spec D §10.1).
 */
data class HonorGlanceState(
    val distanceRemainingBucketMeters: Int,
    val isOnWay: Boolean,
    val isArrived: Boolean,
)

/** What [HonorSession.start] did with a walk. */
sealed interface HonorSessionStart {

    /** The release flag is off: nothing Honor runs. */
    data object Disabled : HonorSessionStart

    /** The walk has no Honor session row: an ordinary walk. */
    data object NotHonor : HonorSessionStart

    /** The walk carries a session the session can't run; nothing started. [reason] names no id. */
    data class Refused(val reason: String) : HonorSessionStart

    /** Running: from Begin, or [revived] from Room after the process that ran it died. */
    data class Started(val revived: Boolean) : HonorSessionStart
}

internal val VOICE_RATES = listOf(1f, 1.25f, 1.5f, 2f)

/** An unknown current rate restarts the ladder at its second step, as iOS's `firstIndex(of:) ?? 0` does. */
internal fun nextVoiceRate(current: Float): Float {
    val index = VOICE_RATES.indexOf(current).coerceAtLeast(0)
    return VOICE_RATES[(index + 1) % VOICE_RATES.size]
}

/**
 * The engine clock: time since the walk's start less its pauses, with the
 * pause in progress frozen and sittings counted (parity spec B §7, D §3.5,
 * owner decision 1). Never `WalkStats.activeWalkingMillis`, which also
 * takes out meditation. Null outside a walk in progress.
 */
internal fun honorEngineSeconds(state: WalkState, nowMillis: Long): Double? {
    val (walk, until) = when (state) {
        is WalkState.Active -> state.walk to nowMillis
        is WalkState.Meditating -> state.walk to nowMillis
        is WalkState.Paused -> state.walk to state.pausedAt
        WalkState.Idle, is WalkState.Finished -> return null
    }
    return max(0L, until - walk.startedAt - walk.totalPausedMillis) / MILLIS_PER_SECOND
}

internal fun HonorEngine.glance(): HonorGlanceState = HonorGlanceState(
    distanceRemainingBucketMeters = SeekGlanceModel.distanceBucket(distanceRemainingMeters),
    isOnWay = isOnWay,
    isArrived = phase == HonorPhase.ARRIVED,
)

/** The source a Way id names, by its prefix; null for an id the store's allow-list would refuse anyway. */
internal fun honorSourceKind(wayId: String): HonorSourceKind? = when {
    wayId.startsWith("walk:") -> HonorSourceKind.OWN_WALK
    wayId.startsWith("share:") -> HonorSourceKind.SHARE
    wayId.startsWith("pilgrimage:") -> HonorSourceKind.PILGRIMAGE
    else -> null
}

/**
 * The index a reply is filed under (iOS `originIndex(of:)`,
 * `ActiveWalkViewModel+Replies.swift:79-88@7c200bf`): the `n` of a
 * `voice-n` moment, and the reserved [HonorPersistence.STAGE_REFLECTION_ORIGIN]
 * for the stage reflection's id on any Way, as iOS maps it. Whether the
 * Way is a stage is for the callers that look a reply up to decide.
 */
internal fun voiceOriginIndex(momentId: String): Int? {
    if (momentId == HonorPersistence.STAGE_REFLECTION_MOMENT_ID) return HonorPersistence.STAGE_REFLECTION_ORIGIN
    return momentId.takeIf { it.startsWith(VOICE_ID_PREFIX) }?.removePrefix(VOICE_ID_PREFIX)?.toIntOrNull()
}

/**
 * The stage walked, as this row took it at Start: the ledger's record
 * reads it here, never from the stage's `way.json` (pilgrimage-stage spec
 * P2 A-7). Null on a walk that isn't a stage.
 */
internal fun HonorSessionEntity.stageIdentity(): PilgrimageStageIdentity? {
    val routeId = stageRouteId ?: return null
    val index = stageIndex ?: return null
    val name = stageName ?: return null
    val distanceKm = stageDistanceKm ?: return null
    return PilgrimageStageIdentity(routeId = routeId, index = index, name = name, distanceKm = distanceKm)
}

/**
 * What the engine had to say about the stage (iOS `teardownHonor`'s
 * outcome, `ActiveWalkViewModel+Honor.swift:128-133@7c200bf`), read from
 * the row its every step commits (P3 §10.2): the walker's current place,
 * not the farthest (pilgrim-ios #120, matched as shipped), and whether
 * arrival fired. Null unless the engine anchored on the Way, as
 * `HonorEngine.isAnchoredOnWay` reads it: no fix yet, or Begin's frac-0
 * fallback still standing, is an approach, not a stage walked.
 */
internal fun HonorSessionEntity.stageOutcome(): HonorStageOutcome? =
    if (startFrac == null || anchoredByFallback) {
        null
    } else {
        HonorStageOutcome(progressFrac = progressFrac, arrived = phase == HonorPhase.ARRIVED)
    }

internal fun WayMoment.voiceGain(): Float =
    if ((kind as? WayMomentKind.Voice)?.kind == VoiceKind.AMBIENT) AMBIENT_GAIN else 1f

/**
 * The engine where this row, its moment rows, and its [notices] left it:
 * the water spoken is the walk's water notices, and an unknown kind's row
 * is ignored.
 */
internal fun HonorSessionEntity.engineSnapshot(
    rows: Collection<HonorMomentStateEntity>,
    notices: Collection<HonorNoticeEntity>,
) = HonorEngine.Snapshot(
    phase = phase,
    startFrac = startFrac,
    anchoredByFallback = anchoredByFallback,
    anchorActiveSeconds = anchorActiveSeconds,
    companionT0 = companionT0Seconds,
    progressFrac = progressFrac,
    progressHighWater = progressHighWater,
    walkedFrac = walkedFrac,
    offWaySinceMillis = offWaySince,
    offWayActiveSeconds = offWayActiveSeconds,
    lastReacquireAttemptMillis = lastReacquireAttempt,
    softTapSinceMillis = softTapSince,
    softTapArmed = softTapArmed,
    arrivalInsideFixes = arrivalInsideFixes,
    tracker = HonorMomentTracker.Snapshot(
        reached = rows.filter { it.reachedAt != null }.mapTo(mutableSetOf()) { it.momentId },
        queue = rows.filter { it.queuePosition != null }.sortedBy { it.queuePosition }.map { it.momentId },
        firedMarks = notices.filter { it.kind == HonorNoticeKind.WATER }.mapTo(mutableSetOf()) { it.refId },
        lastNoticeSeconds = lastNoticeSeconds,
    ),
)

/**
 * The phase is left out: only arrival's compare-and-set writes it. The
 * water spoken goes out as notice rows, in the same commit.
 */
internal fun HonorEngine.Snapshot.toEngineState(walkId: Long) = HonorEngineState(
    walkId = walkId,
    startFrac = startFrac,
    anchoredByFallback = anchoredByFallback,
    anchorActiveSeconds = anchorActiveSeconds,
    companionT0Seconds = companionT0,
    progressFrac = progressFrac,
    progressHighWater = progressHighWater,
    walkedFrac = walkedFrac,
    offWaySince = offWaySinceMillis,
    offWayActiveSeconds = offWayActiveSeconds,
    lastReacquireAttempt = lastReacquireAttemptMillis,
    softTapSince = softTapSinceMillis,
    softTapArmed = softTapArmed,
    arrivalInsideFixes = arrivalInsideFixes,
    lastNoticeSeconds = tracker.lastNoticeSeconds,
)

/**
 * What the player holds, as iOS's view model tracks it (`activeVoice`,
 * `isVoicePaused`, `voiceRate`, `ActiveWalkViewModel.swift:92-103@7c200bf`),
 * which is what the chip and the card draw: a replayed voice while the
 * engine still counts its own, or nothing while a reply plays. The file
 * position is what the UI needs to draw progress, estimated from when
 * the voice last started or resumed at [rate]; with no start time it is
 * held still at [startOffsetMillis], neither paused nor sounding.
 */
internal data class VoiceHold(
    val active: WayMoment? = null,
    val paused: Boolean = false,
    val startedAtMillis: Long? = null,
    val startOffsetMillis: Long? = null,
    val pauseOffsetMillis: Long? = null,
    val rate: Float = 1f,
) {
    fun positionMillis(nowMillis: Long): Long {
        pauseOffsetMillis?.let { return it }
        val started = startedAtMillis ?: return startOffsetMillis ?: 0L
        return (startOffsetMillis ?: 0L) + ((nowMillis - started).coerceAtLeast(0) * rate).roundToLong()
    }

    /**
     * Still where the voice stands while the player holds it silent with no
     * pause (a guide prompt, a call), and running on from there once it
     * sounds again. A paused voice is the walker's or the engine's, and
     * keeps its own pause.
     */
    fun heldIf(held: Boolean, nowMillis: Long): VoiceHold = when {
        active == null || paused -> this
        held && startedAtMillis != null -> copy(startedAtMillis = null, startOffsetMillis = positionMillis(nowMillis))
        !held && startedAtMillis == null -> copy(startedAtMillis = nowMillis)
        else -> this
    }

    fun started(moment: WayMoment, nowMillis: Long) =
        copy(active = moment, paused = false, startedAtMillis = nowMillis, startOffsetMillis = 0L, pauseOffsetMillis = null)

    fun pausedAt(nowMillis: Long) = copy(paused = true, pauseOffsetMillis = positionMillis(nowMillis))

    fun resumedAt(nowMillis: Long) =
        copy(paused = false, startedAtMillis = nowMillis, startOffsetMillis = positionMillis(nowMillis), pauseOffsetMillis = null)

    /** A held voice moves and stays held, as iOS's `seek` sets the clock of a paused player. */
    fun movedTo(offsetMillis: Long, nowMillis: Long) = if (paused) {
        copy(pauseOffsetMillis = offsetMillis)
    } else {
        copy(startedAtMillis = startedAtMillis?.let { nowMillis }, startOffsetMillis = offsetMillis)
    }

    fun released() = VoiceHold(rate = rate)

    fun toVoiceState(walkId: Long) = HonorVoiceState(
        walkId = walkId,
        playingMomentId = active?.id,
        voicePaused = paused,
        voiceStartedAt = startedAtMillis.takeIf { active != null },
        voiceStartOffsetMillis = startOffsetMillis.takeIf { active != null },
        voicePauseOffsetMillis = pauseOffsetMillis.takeIf { active != null },
        voiceRate = rate.toDouble(),
    )
}

private const val MILLIS_PER_SECOND = 1_000.0
private const val VOICE_ID_PREFIX = "voice-"
private const val AMBIENT_GAIN = 0.5f
