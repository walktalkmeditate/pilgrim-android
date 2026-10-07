// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.honor.pilgrimage

import android.content.res.Resources
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.max
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageCopy
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageTilesManager.Phase
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageTilesManager.Status
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.StylePackRequest
import org.walktalkmeditate.pilgrim.domain.honor.digits
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimCornerRadius
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimOpacity
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/** iOS `PilgrimageMapsRowModel` (`PilgrimageMapsRow.swift:3-24@7c200bf`, spec D C4 §1.3): the row's words. */
object PilgrimageMapsRowModel {

    /**
     * Decimal megabytes, a tie rounded away from zero, never under 1, as
     * plain ASCII digits: 400 KB reads "1 MB", 2.5 MB "3 MB", a gigabyte
     * "1000 MB" (C1 §10). `Math.round` takes a tie up, which is Swift's
     * `rounded()` for a byte count; a negative one meets the floor.
     */
    fun megabytes(resources: Resources, bytes: Long): String =
        resources.getString(R.string.pilgrimage_maps_megabytes, max(1L, Math.round(bytes / BYTES_PER_MEGABYTE)).toString())

    /** A partial save counts its stages; nothing saved shows the estimate, and so would `Saved`, which the saved face takes first. */
    fun label(resources: Resources, status: Status, estimateBytes: Long): String = when (status) {
        is Status.Partial -> resources.getString(R.string.pilgrimage_maps_save_partial, digits(status.saved), digits(status.of))
        Status.None, is Status.Saved -> resources.getString(R.string.pilgrimage_maps_save_estimate, megabytes(resources, estimateBytes))
    }

    /** The store's own bytes, so no tilde. */
    fun savedLine(resources: Resources, bytes: Long): String =
        resources.getString(R.string.pilgrimage_maps_saved, megabytes(resources, bytes))

    /** The saved face's TalkBack label, iOS's `accessibilityLabel`, promising a save that adds nothing to a current route (D7, matched). */
    fun savedA11y(resources: Resources, bytes: Long): String =
        resources.getString(R.string.pilgrimage_maps_saved_a11y, megabytes(resources, bytes))

    /** [done] and [total] both count the style packs ahead of the stages; the walker counts stages, so both sides drop them. */
    fun savingLine(resources: Resources, done: Int, total: Int): String {
        val packs = StylePackRequest.entries.size
        return resources.getString(R.string.pilgrimage_maps_saving, digits(max(done - packs, 0)), digits(max(total - packs, 0)))
    }

    private const val BYTES_PER_MEGABYTE = 1_000_000.0
}

/**
 * iOS `PilgrimageMapsRow` (`PilgrimageMapsRow.swift:26-100@7c200bf`, spec D
 * C4 §1.3): under the route page's download button, the phase picks the
 * face first and the status second. Saving is its line and "cancel"; idle
 * or failed is the saved button with its check, or the full-width "Save
 * maps for the way"; a failure adds its line in rust under that face, the
 * saved one included. The estimate and the status arrive computed, so
 * drawing the row reads no store.
 *
 * With [enabled] false (the package downloading, or the page holding
 * itself) every control is disabled and every colour kept, as iOS's
 * explicit colours leave its disabled row looking as it was.
 */
@Composable
fun PilgrimageMapsRow(
    estimateBytes: Long,
    status: Status,
    phase: Phase,
    enabled: Boolean,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.xs)) {
        when (phase) {
            is Phase.Saving -> SavingFace(phase, enabled, onCancel)
            Phase.Idle, is Phase.Failed -> {
                if (status is Status.Saved) SavedFace(status.bytes, enabled, onSave) else SaveButton(status, estimateBytes, enabled, onSave)
                if (phase is Phase.Failed) {
                    Text(text = stringResource(PilgrimageCopy.line(phase.error)), style = pilgrimType.caption, color = pilgrimColors.rust)
                }
            }
        }
    }
}

@Composable
private fun SavingFace(saving: Phase.Saving, enabled: Boolean, onCancel: () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = PilgrimageMapsRowModel.savingLine(LocalResources.current, saving.done, saving.total),
            style = pilgrimType.caption,
            color = pilgrimColors.fog,
        )
        TextButton(onClick = onCancel, enabled = enabled, contentPadding = PaddingValues(0.dp)) {
            Text(text = stringResource(R.string.pilgrimage_maps_cancel), style = pilgrimType.caption, color = pilgrimColors.stone)
        }
    }
}

/** One button TalkBack reads by its label alone, the check unspoken, as iOS's `accessibilityLabel` replaces its children. Not full width. */
@Composable
private fun SavedFace(bytes: Long, enabled: Boolean, onSave: () -> Unit) {
    val resources = LocalResources.current
    val label = PilgrimageMapsRowModel.savedA11y(resources, bytes)
    Row(
        modifier = Modifier
            .clickable(enabled = enabled, role = Role.Button, onClick = onSave)
            .semantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = InstallBadge.ON_YOUR_PHONE.glyph,
            contentDescription = null,
            tint = pilgrimColors.moss,
            modifier = Modifier.size(BADGE_SIZE),
        )
        Text(
            text = PilgrimageMapsRowModel.savedLine(resources, bytes),
            style = pilgrimType.caption,
            color = pilgrimColors.fog,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

@Composable
private fun SaveButton(status: Status, estimateBytes: Long, enabled: Boolean, onSave: () -> Unit) {
    val plate = pilgrimColors.stone.copy(alpha = PilgrimOpacity.LIGHT)
    Button(
        onClick = onSave,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(PilgrimCornerRadius.normal),
        contentPadding = PaddingValues(vertical = 12.dp),
        elevation = null,
        colors = ButtonDefaults.buttonColors(
            containerColor = plate,
            contentColor = pilgrimColors.stone,
            disabledContainerColor = plate,
            disabledContentColor = pilgrimColors.stone,
        ),
    ) {
        Text(
            text = PilgrimageMapsRowModel.label(LocalResources.current, status, estimateBytes),
            style = pilgrimType.button,
            color = pilgrimColors.stone,
        )
    }
}
