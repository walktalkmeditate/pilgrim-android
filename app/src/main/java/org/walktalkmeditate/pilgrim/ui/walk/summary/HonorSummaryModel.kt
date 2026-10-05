// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import java.io.File
import kotlin.math.abs
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageLedger
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.domain.WalkEventType
import org.walktalkmeditate.pilgrim.domain.honor.HonorPersistence
import org.walktalkmeditate.pilgrim.domain.honor.WayStage
import org.walktalkmeditate.pilgrim.honor.HonorWalkRecord
import org.walktalkmeditate.pilgrim.ui.honor.pilgrimage.StageFormat
import org.walktalkmeditate.pilgrim.ui.walk.map.HonorWayLine

/**
 * What a stage's route ledger holds for it, against the stage's own
 * length: iOS's `stageProgressLine` before it is printed, so the line
 * follows the walker's unit wherever it is drawn.
 */
@Immutable
data class HonorStageProgress(val kmWalked: Double, val distanceKm: Double)

/**
 * The summary's Honor story (iOS `HonorSummaryData`,
 * `HonorSummarySection.swift:3-22@7c200bf`): own walks, shared walks and
 * pilgrimage stages.
 */
@Immutable
data class HonorSummaryData(
    /** The honored Way's title; null when the Way is gone, which reads "a way that has been removed". */
    val wayTitle: String?,
    /**
     * Their time minus yours at arrival, from the link: positive when the
     * walker arrived before the companion. Null before the Honor step
     * has written the link, for a walk that never arrived, and always
     * for a stage, which has no companion to arrive before.
     */
    val arrivedBeforeTheirsSeconds: Double?,
    /** Every voice the Way carries, not the ones this walk heard. */
    val voicesAlongTheWay: Int,
    /** Every reply in the Way's `replies.json`, this walk's or not, a stage's reflection included (pilgrim-ios #99). */
    val repliesMade: Int,
    /**
     * Carried, never inferred from [stageProgress]: a stage walk that
     * earned no ledger entry (it never joined the line) is still a stage
     * walk, and must not be told it walked in someone's steps.
     */
    val isPilgrimageStage: Boolean = false,
    /** The route ledger's entry for this stage; null with none. */
    val stageProgress: HonorStageProgress? = null,
    /** The stage's closing line, only when this walk's events hold its arrival. */
    val closing: String? = null,
    /**
     * The reply to the stage's closing line filed under the reserved
     * origin, by any walk of this stage: not gated on arrival, as the
     * closing is (pilgrim-ios #123 item 2, matched as shipped).
     */
    val replyRelativePath: String? = null,
)

/** The section's text and the summary map's ghost line, built together off Main. */
@Immutable
data class HonorSummaryState(
    val data: HonorSummaryData,
    /** Null when the Way is gone: the summary map draws the walk alone. */
    val ghost: HonorWayLine?,
    /** [HonorSummaryData.replyRelativePath]'s recording while it is on the phone: "your reply" shows only then. */
    val replyFile: File?,
)

/**
 * Pure assembly for the summary's Honor section (iOS `HonorSummaryModel`,
 * `HonorSummarySection.swift:25-53,98-118@7c200bf`, parity spec G §2–§3,
 * pilgrimage-stage spec P5 §11). A walk is an honor by its `HONOR_MODE`
 * event alone, and only with the release flag on: with it off an honor
 * walk is a plain walk (AE12).
 */
object HonorSummaryModel {

    fun isHonorWalk(events: List<WalkEventType>, honorEnabled: Boolean): Boolean =
        honorEnabled && WalkEventType.HONOR_MODE in events

    /** Whether this walk reached its Way's end, which alone lets a stage's closing line show. */
    fun arrived(events: List<WalkEventType>): Boolean = WalkEventType.HONOR_ARRIVAL in events

    fun summaryState(
        events: List<WalkEventType>,
        honorEnabled: Boolean,
        record: HonorWalkRecord,
        recordingFile: (relativePath: String) -> File?,
    ): HonorSummaryState? =
        if (isHonorWalk(events, honorEnabled)) summaryState(record, arrived(events), recordingFile) else null

    /**
     * The section shows whether or not the Way still exists. The delta is
     * the engine's own numbers from the link, never a recomputation, and
     * there is none for a stage. A stage whose Way is gone reads as a
     * shared walk's block, delta and all, as iOS builds it (P5 §11.2).
     * [recordingFile] resolves a reply's path to its recording, null
     * once the file is gone; it touches the disk.
     */
    fun summaryState(
        record: HonorWalkRecord,
        arrived: Boolean,
        recordingFile: (relativePath: String) -> File?,
    ): HonorSummaryState {
        val way = record.way
        val stage = way?.stage
        val replyRelativePath = record.replies[HonorPersistence.STAGE_REFLECTION_ORIGIN]
        return HonorSummaryState(
            data = HonorSummaryData(
                wayTitle = way?.title,
                arrivedBeforeTheirsSeconds = if (stage == null) record.arrival?.let { it.theirSeconds - it.yourSeconds } else null,
                voicesAlongTheWay = way?.voiceCount ?: 0,
                repliesMade = record.replies.size,
                isPilgrimageStage = stage != null,
                stageProgress = stage?.let { stageProgress(it, record.ledger) },
                closing = if (arrived) stage?.closing else null,
                replyRelativePath = replyRelativePath,
            ),
            ghost = way?.let(HonorWayLine::of),
            replyFile = replyRelativePath?.let(recordingFile),
        )
    }

    /**
     * The ledger's entry at the stage's index, which keeps the best of
     * every walk of it, as of this reading: an unanchored or shorter
     * re-walk shows an earlier, longer figure, and a past summary changes
     * after a later walk (pilgrim-ios #120 item 2, matched as shipped).
     * The length is the stage's own, from its `way.json` as it stands now.
     */
    fun stageProgress(stage: WayStage, ledger: PilgrimageLedger?): HonorStageProgress? {
        val entry = ledger?.stages?.get(stage.index.toString()) ?: return null
        return HonorStageProgress(kmWalked = entry.kmWalked, distanceKm = stage.distanceKm)
    }

    /** "14.04 km of 24.2 km of the stage", in the stage surfaces' numbers (owner decision 7). */
    fun stageProgressLine(resources: Resources, progress: HonorStageProgress, units: UnitSystem): String =
        resources.getString(
            R.string.honor_summary_stage_progress,
            StageFormat.distance(progress.kmWalked * 1000, units),
            StageFormat.distance(progress.distanceKm * 1000, units),
        )

    /**
     * The reply button's face: "your reply", or "pause" while it plays.
     * TalkBack reads [R.string.honor_summary_reply_a11y] for both
     * (pilgrim-ios #123 item 7, matched as shipped).
     */
    @StringRes
    fun replyTitle(isPlaying: Boolean): Int =
        if (isPlaying) R.string.honor_summary_reply_pause else R.string.honor_card_your_reply

    /** "the stage you walked" for a stage; else "in their steps", even on the walker's own earlier walk (pilgrim-ios #109). */
    fun kicker(resources: Resources, data: HonorSummaryData): String = resources.getString(
        if (data.isPilgrimageStage) R.string.honor_summary_stage_kicker else R.string.honor_summary_kicker,
    )

    fun title(resources: Resources, data: HonorSummaryData): String =
        data.wayTitle ?: resources.getString(R.string.honor_summary_way_removed)

    /**
     * Whole minutes of the delta, truncated (`Int(abs(delta) / 60)`):
     * under a minute either way is "you arrived together"; their longer
     * time reads "after you". The word is "minute" only for exactly 1.
     */
    fun deltaLine(resources: Resources, deltaSeconds: Double): String {
        val minutes = (abs(deltaSeconds) / 60).toInt()
        if (minutes == 0) return resources.getString(R.string.honor_summary_arrived_together)
        val text = minutes.toString()
        return when {
            deltaSeconds > 0 && minutes == 1 -> resources.getString(R.string.honor_summary_arrived_after_one, text)
            deltaSeconds > 0 -> resources.getString(R.string.honor_summary_arrived_after, text)
            minutes == 1 -> resources.getString(R.string.honor_summary_arrived_before_one, text)
            else -> resources.getString(R.string.honor_summary_arrived_before, text)
        }
    }

    /** "N voice(s) along the way · N reply/replies", voices first; null when both are zero. */
    fun countsLine(resources: Resources, data: HonorSummaryData): String? {
        val parts = buildList {
            val voices = data.voicesAlongTheWay
            if (voices > 0) {
                val res = if (voices == 1) R.string.honor_summary_voice_one else R.string.honor_summary_voices
                add(resources.getString(res, voices.toString()))
            }
            val replies = data.repliesMade
            if (replies > 0) {
                val res = if (replies == 1) R.string.honor_summary_reply_one else R.string.honor_summary_replies
                add(resources.getString(res, replies.toString()))
            }
        }
        return if (parts.isEmpty()) null else parts.joinToString(" · ")
    }
}
