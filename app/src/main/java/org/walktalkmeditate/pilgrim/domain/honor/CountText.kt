// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain.honor

import android.content.res.Resources
import androidx.annotation.StringRes
import java.util.Locale

/** A count as iOS interpolates an `Int`: plain ASCII digits, no grouping. */
internal fun digits(count: Int): String = String.format(Locale.US, "%d", count)

/**
 * [one] at exactly 1 and [other] at any other count, as iOS's `count == 1`
 * picks, whatever the phone's plural rules; the count goes in as [digits],
 * after any [leading] arguments.
 */
internal fun Resources.countString(
    count: Int,
    @StringRes one: Int,
    @StringRes other: Int,
    vararg leading: Any,
): String = getString(if (count == 1) one else other, *leading, digits(count))
