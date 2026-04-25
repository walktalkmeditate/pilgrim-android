// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * AppWidget receiver bound to [PilgrimWidget]. Intentionally NOT
 * `@AndroidEntryPoint` — the receiver injects nothing, and the widget
 * composable resolves its dependencies via [WidgetEntryPoint] at
 * compose time.
 */
class PilgrimWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PilgrimWidget()
}
