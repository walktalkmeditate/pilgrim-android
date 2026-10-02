// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.honor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Signpost
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.core.flags.ReleaseFlags
import org.walktalkmeditate.pilgrim.honor.OwnWalkWays
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * The summary's door shows when the release flag is on, the host can open
 * the overview (the post-walk, journal, Goshuin, and widget summaries, not
 * the Recordings list: iOS's `onWalkAgain` rule and owner decision 5), and
 * the walk's route has at least two points (`WalkSummaryView.swift:676-691@7c200bf`).
 */
fun walkAgainDoorShows(honorEnabled: Boolean, hostOffersDoor: Boolean, routePointCount: Int): Boolean =
    honorEnabled && hostOffersDoor && routePointCount >= 2

/** iOS `Label("walk this again", systemImage: "signpost.right")` in stone, no card or frame. */
@Composable
fun WalkAgainDoor(onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onClick, modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
        ) {
            Icon(
                imageVector = Icons.Outlined.Signpost,
                contentDescription = null,
                tint = pilgrimColors.stone,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = stringResource(R.string.honor_walk_this_again),
                style = pilgrimType.button,
                color = pilgrimColors.stone,
            )
        }
    }
}

/** The answer to one "walk this again" tap: whether the walk built a Way. */
data class WalkAgainResult(val sourceWalkId: Long, val built: Boolean)

/**
 * Builds the Way for "walk this again" before the summary closes: a Way
 * opens the overview; no Way (a route under 20 m, say) closes the summary
 * and opens nothing, with no alert, as iOS ships it (pilgrim-ios #110,
 * matched).
 */
@HiltViewModel
class WalkAgainViewModel @Inject constructor(
    private val releaseFlags: ReleaseFlags,
    private val ownWalkWays: OwnWalkWays,
) : ViewModel() {

    val honorEnabled: Boolean get() = releaseFlags.honor

    private val _results = MutableSharedFlow<WalkAgainResult>(extraBufferCapacity = 1)
    val results: SharedFlow<WalkAgainResult> = _results.asSharedFlow()

    private val building = AtomicBoolean(false)

    fun walkAgain(sourceWalkId: Long) {
        if (!honorEnabled || !building.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                val built = ownWalkWays.build(sourceWalkId) is OwnWalkWays.Built.Ready
                _results.emit(WalkAgainResult(sourceWalkId, built))
            } finally {
                building.set(false)
            }
        }
    }
}
