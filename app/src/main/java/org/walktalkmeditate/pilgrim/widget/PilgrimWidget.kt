// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.provideContent
import androidx.glance.text.Text

/**
 * Stage 9-A Task 7 stub — full Body composable lands in Task 9. The
 * worker references this class to call `updateAll(context)` so we
 * need a minimal compile-time placeholder.
 */
class PilgrimWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            Text("Pilgrim")
        }
    }
}
