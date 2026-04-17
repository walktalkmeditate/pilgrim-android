// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim

import android.app.Application
import com.mapbox.common.MapboxOptions
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class PilgrimApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Mapbox reads the public access token (pk.xxx) here — the token
        // value is injected from local.properties at build time via
        // BuildConfig.MAPBOX_ACCESS_TOKEN. Empty token is accepted but
        // map tiles will fail to load; the placeholder map card handles
        // that visually until a valid token is configured.
        MapboxOptions.accessToken = BuildConfig.MAPBOX_ACCESS_TOKEN
    }
}
