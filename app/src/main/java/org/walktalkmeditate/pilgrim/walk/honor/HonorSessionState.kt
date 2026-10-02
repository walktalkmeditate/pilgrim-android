// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.walk.honor

import kotlin.math.max
import kotlin.math.roundToLong
import org.walktalkmeditate.pilgrim.data.honor.HonorEngineState
import org.walktalkmeditate.pilgrim.data.honor.HonorMomentStateEntity
import org.walktalkmeditate.pilgrim.data.honor.HonorSessionEntity
import org.walktalkmeditate.pilgrim.data.honor.HonorSourceKind
import org.walktalkmeditate.pilgrim.data.honor.HonorVoiceState
import org.walktalkmeditate.pilgrim.domain.WalkState
import org.walktalkmeditate.pilgrim.domain.honor.HonorEngine
import org.walktalkmeditate.pilgrim.domain.honor.HonorMomentTracker
import org.walktalkmeditate.pilgrim.domain.honor.HonorPhase
import org.walktalkmeditate.pilgrim.domain.honor.VoiceKind
import org.walktalkmeditate.pilgrim.domain.honor.WayMoment
import org.walktalkmeditate.pilgrim.domain.honor.WayMomentKind
import org.walktalkmeditate.pilgrim.domain.seek.SeekGlanceModel

/**
 * A walker's command from a card or the listening chip (parity spec D §6),
 * which the UI sends and `:tracker` applies once, by its sequence number.
 */
sealed interface HonorCommand {

    /** Pause or resume the voice the player holds, or play another voice outside the queue (iOS `togglePlayback(of:)`). */
    data class TogglePlayback(val momentId: String) : HonorCommand

    /** Scrub to [fraction] of a voice, starting it first if it isn't the one held (iOS `seekVoice`). */
    data class Scrub(val momentId: String, val fraction: Double) : HonorCommand

    data object Skip : HonorCommand

    /** 1× → 1.25× → 1.5× → 2× → 1× (iOS `cycleVoiceRate`). */
    data object CycleRate : HonorCommand

    /** The walker's earlier reply to a voice, from a previous honoring (iOS `playReply(url:)`). */
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
 * The `n` of a `voice-n` moment, the index a reply is filed under (iOS
 * `originIndex(of:)`, `ActiveWalkViewModel+Replies.swift:79-88@7c200bf`).
 * The stage reflection's reserved index is stage-only.
 */
internal fun voiceOriginIndex(momentId: String): Int? =
    momentId.takeIf { it.startsWith(VOICE_ID_PREFIX) }?.removePrefix(VOICE_ID_PREFIX)?.toIntOrNull()

internal fun WayMoment.voiceGain(): Float =
    if ((kind as? WayMomentKind.Voice)?.kind == VoiceKind.AMBIENT) AMBIENT_GAIN else 1f

internal fun HonorSessionEntity.engineSnapshot(rows: Collection<HonorMomentStateEntity>) = HonorEngine.Snapshot(
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
    ),
)

/** The phase is left out: only arrival's compare-and-set writes it. */
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
)

/**
 * What the player holds, as iOS's view model tracks it (`activeVoice`,
 * `isVoicePaused`, `voiceRate`, `ActiveWalkViewModel.swift:92-103@7c200bf`),
 * which is what the chip and the card draw: a replayed voice while the
 * engine still counts its own, or nothing while a reply plays. The file
 * position is what the UI needs to draw progress, estimated from when
 * the voice last started or resumed at [rate].
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
        val started = startedAtMillis ?: return 0L
        return (startOffsetMillis ?: 0L) + ((nowMillis - started).coerceAtLeast(0) * rate).roundToLong()
    }

    fun started(moment: WayMoment, nowMillis: Long) =
        copy(active = moment, paused = false, startedAtMillis = nowMillis, startOffsetMillis = 0L, pauseOffsetMillis = null)

    fun pausedAt(nowMillis: Long) = copy(paused = true, pauseOffsetMillis = positionMillis(nowMillis))

    fun resumedAt(nowMillis: Long) =
        copy(paused = false, startedAtMillis = nowMillis, startOffsetMillis = positionMillis(nowMillis), pauseOffsetMillis = null)

    fun movedTo(offsetMillis: Long, nowMillis: Long) = if (paused) {
        copy(pauseOffsetMillis = offsetMillis)
    } else {
        copy(startedAtMillis = nowMillis, startOffsetMillis = offsetMillis)
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
