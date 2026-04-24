// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.ui.etegami.EtegamiBitmapRenderer
import org.walktalkmeditate.pilgrim.ui.etegami.EtegamiSpec
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors

/**
 * Stage 7-C: Walk Summary preview card for the etegami postcard.
 * Renders [EtegamiBitmapRenderer] on first composition via
 * [produceState] (Dispatchers.Default happens inside `render`), then
 * displays the result via [Image] with `ContentScale.Fit` so the
 * 1080×1920 artifact scales to the card's width while preserving
 * aspect.
 *
 * On render failure (OOM, typeface resolution error) the card shows
 * a loading spinner indefinitely — silent fallback matches the
 * iOS-parity spirit of an unobtrusive preview. 7-D can add a "retry"
 * affordance when it wires sharing.
 */
@Composable
fun WalkEtegamiCard(
    spec: EtegamiSpec,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, spec) {
        value = runCatching {
            EtegamiBitmapRenderer.render(spec, context).asImageBitmap()
        }.getOrNull()
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = pilgrimColors.parchmentSecondary),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(
                    EtegamiBitmapRenderer.WIDTH_PX.toFloat() / EtegamiBitmapRenderer.HEIGHT_PX,
                ),
            contentAlignment = Alignment.Center,
        ) {
            val b = bitmap
            if (b != null) {
                Image(
                    bitmap = b,
                    contentDescription = "Etegami postcard for this walk",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = pilgrimColors.stone,
                )
            }
        }
    }
}
