// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.honor

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import org.walktalkmeditate.pilgrim.data.entity.Walk

/**
 * What the walker did to a place card: the one Honor table the UI
 * writes. iOS keeps both in memory for the walk (`touchedCardIDs`, the
 * card queue); a row survives a UI restart so a dismissed card stays
 * dismissed. Deleted by the finalize step, the one named exception to
 * one writer per table.
 */
@Entity(
    tableName = "honor_card_states",
    primaryKeys = ["walk_id", "moment_id"],
    foreignKeys = [
        ForeignKey(
            entity = Walk::class,
            parentColumns = ["id"],
            childColumns = ["walk_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class HonorCardStateEntity(
    @ColumnInfo(name = "walk_id")
    val walkId: Long,
    @ColumnInfo(name = "moment_id")
    val momentId: String,
    val dismissed: Boolean = false,
    /** Played again, replied to, or scrubbed: a touched voice card never retires on its own. */
    val touched: Boolean = false,
)
