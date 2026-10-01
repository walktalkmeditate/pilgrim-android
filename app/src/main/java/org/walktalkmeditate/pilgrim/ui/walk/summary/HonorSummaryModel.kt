// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import android.content.res.Resources
import androidx.compose.runtime.Immutable
import kotlin.math.abs
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.domain.WalkEventType
import org.walktalkmeditate.pilgrim.honor.HonorWalkRecord
import org.walktalkmeditate.pilgrim.ui.walk.map.HonorWayLine

/**
 * The summary's Honor story (iOS `HonorSummaryData`,
 * `HonorSummarySection.swift:3-22@7c200bf`), own and shared walks alike.
 * The pilgrimage-stage fields wait for Stage 21-2.
 */
@Immutable
data class HonorSummaryData(
    /** The honored Way's title; null when the Way is gone, which reads "a way that has been removed". */
    val wayTitle: String?,
    /**
     * Their time minus yours at arrival, from the link: positive when the
     * walker arrived before the companion. Null before the Honor step
     * has written the link, and for a walk that never arrived.
     */
    val arrivedBeforeTheirsSeconds: Double?,
    /** Every voice the Way carries, not the ones this walk heard. */
    val voicesAlongTheWay: Int,
    /** Every reply in the Way's `replies.json`, this walk's or not (pilgrim-ios #99). */
    val repliesMade: Int,
)

/** The section's text and the summary map's ghost line, built together off Main. */
@Immutable
data class HonorSummaryState(
    val data: HonorSummaryData,
    /** Null when the Way is gone: the summary map draws the walk alone. */
    val ghost: HonorWayLine?,
)

/**
 * Pure assembly for the summary's Honor section (iOS `HonorSummaryModel`,
 * `HonorSummarySection.swift:25-44,98-118@7c200bf`, parity spec G §2–§3).
 * A walk is an honor by its `HONOR_MODE` event alone, and only with the
 * release flag on: with it off an honor walk is a plain walk (AE12).
 */
object HonorSummaryModel {

    fun isHonorWalk(events: List<WalkEventType>, honorEnabled: Boolean): Boolean =
        honorEnabled && WalkEventType.HONOR_MODE in events

    fun summaryState(
        events: List<WalkEventType>,
        honorEnabled: Boolean,
        record: HonorWalkRecord,
    ): HonorSummaryState? = if (isHonorWalk(events, honorEnabled)) summaryState(record) else null

    /**
     * The section shows whether or not the Way still exists. The delta is
     * the engine's own numbers from the link, never a recomputation.
     */
    fun summaryState(record: HonorWalkRecord): HonorSummaryState {
        val way = record.way
        return HonorSummaryState(
            data = HonorSummaryData(
                wayTitle = way?.title,
                arrivedBeforeTheirsSeconds = record.arrival?.let { it.theirSeconds - it.yourSeconds },
                voicesAlongTheWay = way?.voiceCount ?: 0,
                repliesMade = record.replies.size,
            ),
            ghost = way?.let(HonorWayLine::of),
        )
    }

    /** "in their steps", even when the Way is the walker's own earlier walk (pilgrim-ios #109). */
    fun kicker(resources: Resources): String = resources.getString(R.string.honor_summary_kicker)

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
