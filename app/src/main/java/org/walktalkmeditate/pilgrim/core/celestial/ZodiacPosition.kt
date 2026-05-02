// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.celestial

import androidx.compose.runtime.Immutable

/** Position within a single zodiac sign — degree is `[0.0, 30.0)`. */
@Immutable
data class ZodiacPosition(val sign: ZodiacSign, val degree: Double)
