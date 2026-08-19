// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iOS parity `PilgrimMapView.swift:210-221@2ee1185` — the live map
 * enters the follow-puck viewport ONCE, and re-enters only when the
 * bottom inset moves by more than half a point:
 *
 * ```swift
 * if followsUserLocation {
 *     let padding = UIEdgeInsets(top: 0, left: 0, bottom: bottomInset, right: 0)
 *     let insetChanged = abs(context.coordinator.lastBottomInset - bottomInset) > 0.5
 *     if !context.coordinator.isFollowing || insetChanged {
 *         context.coordinator.isFollowing = true
 *         context.coordinator.lastBottomInset = bottomInset
 *         mapView.viewport.transition(...)
 *     }
 * }
 * ```
 *
 * There is deliberately NO per-GPS-sample recentering: the viewport
 * state tracks the puck itself, and a user pan idles it until the next
 * inset change.
 */
class FollowViewportDecisionTest {

    @Test
    fun `first entry always transitions`() {
        assertTrue(
            shouldEnterFollowViewport(
                isFollowing = false,
                lastBottomInsetDp = 0f,
                bottomInsetDp = 0f,
            ),
        )
    }

    @Test
    fun `already following with an unchanged inset does not re-transition`() {
        assertFalse(
            shouldEnterFollowViewport(
                isFollowing = true,
                lastBottomInsetDp = 180f,
                bottomInsetDp = 180f,
            ),
        )
    }

    @Test
    fun `inset delta at the threshold does not re-transition`() {
        // iOS uses a strict `> 0.5`, so exactly 0.5 is a no-op.
        assertFalse(
            shouldEnterFollowViewport(
                isFollowing = true,
                lastBottomInsetDp = 180f,
                bottomInsetDp = 180.5f,
            ),
        )
    }

    @Test
    fun `inset delta past the threshold re-transitions`() {
        assertTrue(
            shouldEnterFollowViewport(
                isFollowing = true,
                lastBottomInsetDp = 180f,
                bottomInsetDp = 180.6f,
            ),
        )
    }

    @Test
    fun `a shrinking inset re-transitions too`() {
        // abs() — the sheet collapsing must re-pad exactly like it
        // expanding does.
        assertTrue(
            shouldEnterFollowViewport(
                isFollowing = true,
                lastBottomInsetDp = 320f,
                bottomInsetDp = 180f,
            ),
        )
    }

    @Test
    fun `sub-threshold float noise never re-transitions`() {
        assertFalse(
            shouldEnterFollowViewport(
                isFollowing = true,
                lastBottomInsetDp = 179.9999f,
                bottomInsetDp = 180f,
            ),
        )
    }

    @Test
    fun `threshold matches the iOS half-point guard`() {
        assertTrue(FOLLOW_INSET_EPSILON_DP == 0.5f)
    }
}
