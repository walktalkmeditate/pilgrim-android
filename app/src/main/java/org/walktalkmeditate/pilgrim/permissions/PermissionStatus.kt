// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.permissions

/**
 * iOS-faithful 4-state permission summary used by the Settings
 * Permissions card. Granted / NotDetermined / Denied / Restricted
 * mirrors `PermissionStatusViewModel.PermissionState` on iOS.
 *
 * Android has no first-class `notDetermined` signal — we approximate
 * via [PermissionAskedStore]: ungranted + never-asked → NotDetermined,
 * ungranted + asked → Denied. `Restricted` has no Android equivalent
 * for our three permissions; the branch is included for symmetry but
 * is unreachable in production.
 */
enum class PermissionStatus { Granted, NotDetermined, Denied, Restricted }
