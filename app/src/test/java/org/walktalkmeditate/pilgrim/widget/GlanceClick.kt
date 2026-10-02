// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.widget

import android.content.Intent
import android.net.Uri

/**
 * What Glance 1.1.1 does to a widget's start-activity intent before it
 * wraps it in the click's PendingIntent: an intent with no data gets a
 * unique `glance-action:CALLBACK?appWidgetId=…&viewId=…&viewSize=…&extraData=`
 * id (`ApplyActionKt.getPendingIntentForAction`, `ActionTrampolineKt.createUniqueUri`),
 * and no action. Glance's builder takes its internal translation context,
 * so the shape is rebuilt here.
 */
internal fun Intent.asGlanceClick(appWidgetId: Int = 7, viewId: Int = 3): Intent = apply {
    if (data != null) return@apply
    data = Uri.Builder()
        .scheme("glance-action")
        .path("CALLBACK")
        .appendQueryParameter("appWidgetId", appWidgetId.toString())
        .appendQueryParameter("viewId", viewId.toString())
        .appendQueryParameter("viewSize", "110.0.dp x 110.0.dp")
        .appendQueryParameter("extraData", "")
        .build()
}
