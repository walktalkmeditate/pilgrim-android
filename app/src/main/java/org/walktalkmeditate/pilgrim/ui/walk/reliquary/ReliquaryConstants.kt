// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.reliquary

/**
 * Hard cap on pinned photos per walk. Sized to keep the non-lazy grid
 * on Walk Summary cheap (7 rows max at 3 columns) and to prevent
 * accidental MediaStore spam. Raising this would require auditing the
 * non-lazy grid's cost and persistable-URI grant budget (Android caps
 * app-wide persistable grants at 5000).
 */
const val MAX_PINS_PER_WALK: Int = 20
