// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.honor

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import org.walktalkmeditate.pilgrim.data.entity.Walk

/**
 * What a notice spoke of. [WATER] names a `WayMark`; iOS PR #91, if it
 * folds in, adds a temple stamp naming a `WayMoment`. [UNKNOWN] is what a
 * later build's kind reads as here, and every reader ignores it.
 */
enum class HonorNoticeKind { WATER, UNKNOWN }

/**
 * One notice a stage walk spoke, written by `:tracker` in the step that
 * fired it, before its haptic (pilgrimage-stage spec P3 §6.2, Annex A.5.2).
 * The water marks spoken are this walk's [HonorNoticeKind.WATER] rows, and
 * the caption is the latest row, shown for what is left of its 20 s from
 * [firedAt]. iOS keeps both in memory and never resumes a walk; a revived
 * `:tracker` rebuilds the fired set from these rows and never speaks a
 * notice twice. Deleted by the finalize step.
 */
@Entity(
    tableName = "honor_notices",
    primaryKeys = ["walk_id", "kind", "ref_id"],
    foreignKeys = [
        ForeignKey(
            entity = Walk::class,
            parentColumns = ["id"],
            childColumns = ["walk_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class HonorNoticeEntity(
    @ColumnInfo(name = "walk_id")
    val walkId: Long,
    val kind: HonorNoticeKind,
    /** The `WayMark.id` of a water notice. */
    @ColumnInfo(name = "ref_id")
    val refId: String,
    /** How far ahead along the line, unrounded, as the engine measured it. */
    val meters: Double,
    /** Epoch milliseconds on the wall clock, when the notice fired. */
    @ColumnInfo(name = "fired_at")
    val firedAt: Long,
)
