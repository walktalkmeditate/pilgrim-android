// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.flags

import javax.inject.Inject
import org.walktalkmeditate.pilgrim.BuildConfig

/**
 * The 2.0.0 release flag. Honor, and every change to shipped behavior that
 * ships dark with it (Seek in `:tracker`, the audio gates), reads [honor]
 * through this one injected accessor, so tests can drive either side. It is
 * on in debug builds and off in release until the 2.0.0 flip.
 */
interface ReleaseFlags {
    val honor: Boolean
}

class BuildConfigReleaseFlags @Inject constructor() : ReleaseFlags {
    override val honor: Boolean = BuildConfig.HONOR
}
