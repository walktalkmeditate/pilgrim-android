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
 * `content://` URI (as a String) — never the photo bytes. The VM
 * holds a persistable read grant via
 * `ContentResolver.takePersistableUriPermission` so Photo Picker URIs
 * typically survive process death. Some OEM pickers and SAF-fallback
 * URIs on API 28-29 reject the grant call — verify during device QA.
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
    /**
     * Stage 7-B: top-confidence ML Kit image label (e.g. "Plant",
     * "Building"), or null when the labeler returned no result above
     * the confidence threshold, or the photo couldn't be read. Null
     * before the analysis worker has run for this row.
     */
    @ColumnInfo(name = "top_label")
    val topLabel: String? = null,
    /**
     * Stage 7-B: confidence for [topLabel] in [0.0, 1.0]. Null when
     * [topLabel] is null.
     */
    @ColumnInfo(name = "top_label_confidence")
    val topLabelConfidence: Double? = null,
    /**
     * Stage 7-B: epoch ms when the analysis worker committed for this
     * row. Null means analysis is pending. Set to the attempt time
     * even on labeler failure / unreadable URI so the worker doesn't
     * retry the same broken photo forever — the UI tombstone takes
     * over when [topLabel] is null post-analysis.
     */
    @ColumnInfo(name = "analyzed_at")
    val analyzedAt: Long? = null,
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
        // Stage 7-B: topLabel and topLabelConfidence are populated
        // together by the analysis runner (`top?.text` + `top?.confidence`
        // from the same LabeledResult). Reject half-populated pairs so
        // a future regression writes through updatePhotoAnalysis fails
        // loudly instead of corrupting the tombstone-vs-labeled
        // distinction downstream.
        require((topLabel == null) == (topLabelConfidence == null)) {
            "topLabel and topLabelConfidence must both be null or both non-null " +
                "(got label=$topLabel, confidence=$topLabelConfidence) for photo $uuid"
        }
        require(topLabelConfidence == null || topLabelConfidence in 0.0..1.0) {
            "topLabelConfidence must be null or within [0.0, 1.0] " +
                "(got $topLabelConfidence) for photo $uuid"
        }
        // A zero analyzedAt would read as "pending" in
        // getPendingAnalysisForWalk's `IS NULL` check only for null —
        // but a `0` epoch-ms is a construction bug (clock not set).
        // Reject it so the worker's retry logic isn't confused.
        require(analyzedAt == null || analyzedAt > 0) {
            "analyzedAt must be null or positive epoch ms " +
                "(got $analyzedAt) for photo $uuid"
        }
    }
}
