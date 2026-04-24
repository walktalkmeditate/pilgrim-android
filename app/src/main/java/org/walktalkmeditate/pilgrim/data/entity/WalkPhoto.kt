// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.entity

import androidx.compose.runtime.Immutable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * A photo the user pinned to a walk. Stores only the MediaStore
 * `content://` URI (as a String) — never the photo bytes. We hold a
 * persistable read grant via `ContentResolver.takePersistableUriPermission`
 * so the URI survives process death.
 *
 * `@Immutable` because this class holds only primitives + `String?` —
 * matches the Compose-stability pattern used by `WalkSummary` so the
 * reliquary grid doesn't force wholesale recomposition of sibling
 * Walk Summary sections on every observed emission.
 */
@Entity(
    tableName = "walk_photos",
    foreignKeys = [
        ForeignKey(
            entity = Walk::class,
            parentColumns = ["id"],
            childColumns = ["walk_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("walk_id"),
        Index("uuid", unique = true),
    ],
)
@Immutable
data class WalkPhoto(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val uuid: String = UUID.randomUUID().toString(),
    @ColumnInfo(name = "walk_id")
    val walkId: Long,
    @ColumnInfo(name = "photo_uri")
    val photoUri: String,
    @ColumnInfo(name = "pinned_at")
    val pinnedAt: Long,
    @ColumnInfo(name = "taken_at")
    val takenAt: Long? = null,
) {
    init {
        // walkId must be a real FK target. Room's autoGenerate starts at 1
        // so 0 or negative values indicate a construction bug (unfinished
        // walk, unset savedStateHandle, test stub). Reject at write time.
        require(walkId > 0) {
            "walkId must be positive (got $walkId) for photo $uuid"
        }
        require(photoUri.isNotBlank()) {
            "photoUri must not be blank for walk $walkId, photo $uuid"
        }
        require(pinnedAt > 0) {
            "pinnedAt must be positive epoch ms (got $pinnedAt) for walk $walkId, photo $uuid"
        }
    }
}
