// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.honor

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.domain.honor.HonorPhase

/** Where the honored Way came from, as its id's prefix and `WaySource` say. */
enum class HonorSourceKind { OWN_WALK, SHARE, PILGRIMAGE }

/** How a walk with Honor ended: a Finish, or launch recovery after both processes died. */
enum class HonorFinishKind { CLEAN, RECOVERED }

/**
 * The live Honor session of one walk, written only by `:tracker` and
 * deleted by the finalize step. It holds everything a revival needs to
 * rebuild the engine where it stood (parity spec B §2.2; iOS persists
 * none of it and never resumes a walk), plus what the UI draws from.
 *
 * The engine's `activeDuration` is not here: it is the walk's elapsed
 * time minus pauses, which the walk's own events already give. Nor are
 * the published `isOnWay`, `offWayMeters`, `distanceRemainingMeters`, and
 * `companionFrac`, which the next fix or tick recomputes. The stage-only
 * water marks (`firedMarks`, `lastMarkSeconds`) wait for Stage 21-2.
 *
 * Times are epoch milliseconds on the wall clock the engine's timers use;
 * the `_seconds` columns are active-duration seconds, as in the engine.
 */
@Entity(
    tableName = "honor_sessions",
    foreignKeys = [
        ForeignKey(
            entity = Walk::class,
            parentColumns = ["id"],
            childColumns = ["walk_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class HonorSessionEntity(
    @PrimaryKey
    @ColumnInfo(name = "walk_id")
    val walkId: Long,
    @ColumnInfo(name = "way_id")
    val wayId: String,
    @ColumnInfo(name = "source_kind")
    val sourceKind: HonorSourceKind,
    /** `honorVoicesEnabled && soundsEnabled`, frozen at Start (iOS reads both once, at engine start). */
    @ColumnInfo(name = "voices_enabled")
    val voicesEnabled: Boolean,
    /** `honorSoftTapEnabled`, frozen at Start; false for every user, as nothing writes it (pilgrim-ios #109). */
    @ColumnInfo(name = "soft_tap_enabled")
    val softTapEnabled: Boolean,
    val phase: HonorPhase = HonorPhase.WALKING,
    /** The anchor; null until Begin's first fix anchors the walker. */
    @ColumnInfo(name = "start_frac")
    val startFrac: Double? = null,
    @ColumnInfo(name = "anchored_by_fallback")
    val anchoredByFallback: Boolean = false,
    @ColumnInfo(name = "anchor_active_seconds")
    val anchorActiveSeconds: Double = 0.0,
    @ColumnInfo(name = "companion_t0_seconds")
    val companionT0Seconds: Double = 0.0,
    @ColumnInfo(name = "progress_frac")
    val progressFrac: Double = 0.0,
    @ColumnInfo(name = "progress_high_water")
    val progressHighWater: Double = 0.0,
    @ColumnInfo(name = "walked_frac")
    val walkedFrac: Double = 0.0,
    @ColumnInfo(name = "off_way_since")
    val offWaySince: Long? = null,
    @ColumnInfo(name = "off_way_active_seconds")
    val offWayActiveSeconds: Double = 0.0,
    @ColumnInfo(name = "last_reacquire_attempt")
    val lastReacquireAttempt: Long? = null,
    @ColumnInfo(name = "soft_tap_since")
    val softTapSince: Long? = null,
    @ColumnInfo(name = "soft_tap_armed")
    val softTapArmed: Boolean = true,
    /** The arrival debounce's run of consecutive fixes inside the radius. */
    @ColumnInfo(name = "arrival_inside_fixes")
    val arrivalInsideFixes: Int = 0,
    /**
     * The voice the player holds, by moment id, as iOS's `activeVoice`: a
     * replayed voice while the engine still counts its own, and null while
     * a reply plays. The engine's own voice isn't kept: a revival never
     * replays it, so the tracker revives with nothing playing.
     */
    @ColumnInfo(name = "playing_moment_id")
    val playingMomentId: String? = null,
    @ColumnInfo(name = "voice_paused")
    val voicePaused: Boolean = false,
    /** When the playing voice last started or resumed. */
    @ColumnInfo(name = "voice_started_at")
    val voiceStartedAt: Long? = null,
    /** Where in its file the playing voice last started or resumed. */
    @ColumnInfo(name = "voice_start_offset_millis")
    val voiceStartOffsetMillis: Long? = null,
    /** Where in its file the voice stood when it paused; null while it plays. */
    @ColumnInfo(name = "voice_pause_offset_millis")
    val voicePauseOffsetMillis: Long? = null,
    @ColumnInfo(name = "voice_rate")
    val voiceRate: Double = 1.0,
    /** The engine's arrival numbers, held until the finalize step writes them into the link. */
    @ColumnInfo(name = "arrival_their_seconds")
    val arrivalTheirSeconds: Double? = null,
    @ColumnInfo(name = "arrival_your_seconds")
    val arrivalYourSeconds: Double? = null,
    /** The highest command sequence id applied, so a replayed command is refused. */
    @ColumnInfo(name = "last_command_seq")
    val lastCommandSeq: Long = 0,
    /** Bumped at each session start and revival; the UI re-sends its gates when it changes. */
    @ColumnInfo(name = "gate_generation")
    val gateGeneration: Long = 0,
    /** Set inside `finishWalkAtomic`'s transaction; null while the walk is on. */
    @ColumnInfo(name = "finish_kind")
    val finishKind: HonorFinishKind? = null,
)
