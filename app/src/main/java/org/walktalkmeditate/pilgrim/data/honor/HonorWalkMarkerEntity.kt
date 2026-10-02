// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.honor

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * What outlives a walk's Honor session: that it finished, when, and how.
 * Written after the walk's link file, so a summary that sees the marker
 * can trust the file (files raise no Room invalidation). Keyed by the
 * walk's uuid like the link file, with no foreign key: a web-editor
 * re-import deletes the walk row and inserts it under a new Room id, and
 * the marker must survive that, as it survives every walk delete. It
 * carries no share id and no numbers.
 */
@Entity(tableName = "honor_walk_markers")
data class HonorWalkMarkerEntity(
    @PrimaryKey
    @ColumnInfo(name = "walk_uuid")
    val walkUuid: String,
    @ColumnInfo(name = "finished_at")
    val finishedAt: Long,
    @ColumnInfo(name = "finish_kind")
    val finishKind: HonorFinishKind,
)
