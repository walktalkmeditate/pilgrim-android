// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain

/**
 * Domain enum for activity-interval classification. Persisted via
 * [org.walktalkmeditate.pilgrim.data.entity.ActivityInterval]. Kept in the
 * domain package so stats logic (pace, effective duration) does not need
 * to import the Room layer.
 */
enum class ActivityType {
    WALKING,
    TALKING,
    MEDITATING,
}
