// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dagger.hilt.android.AndroidEntryPoint
import org.walktalkmeditate.pilgrim.ui.navigation.PilgrimNavHost
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme
import org.walktalkmeditate.pilgrim.widget.DeepLinkTarget

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /**
     * Pending widget deep-link target. Read from [Intent] in onCreate +
     * onNewIntent, consumed by [PilgrimNavHost] once permissions are
     * cleared. Hoisted out of `setContent` so onNewIntent can update it
     * without triggering a full recomposition rebuild.
     */
    private val pendingDeepLink = mutableStateOf<DeepLinkTarget?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Stage 9-A: parse widget intent extras at first launch.
        pendingDeepLink.value = DeepLinkTarget.parse(intent)
        setContent {
            PilgrimTheme {
                // Stage 9.5-A: PilgrimNavHost owns the only Scaffold in
                // the chain. MainActivity's previous Scaffold was
                // double-counting insets (parent + child both consuming
                // status/nav-bar padding) and would have produced bottom-
                // bar gaps above the gesture inset.
                val deepLink by pendingDeepLink
                PilgrimNavHost(
                    pendingDeepLink = deepLink,
                    onDeepLinkConsumed = {
                        pendingDeepLink.value = null
                        // Strip the deep-link extras from the attached
                        // intent so a config change (rotation, locale)
                        // doesn't re-parse + re-navigate them.
                        // setIntent persists the mutation across
                        // activity recreation.
                        val cleared = intent.apply {
                            removeExtra(DeepLinkTarget.EXTRA_DEEP_LINK)
                            removeExtra(DeepLinkTarget.EXTRA_WALK_ID)
                        }
                        setIntent(cleared)
                    },
                )
            }
        }
    }

    /**
     * Stage 9-A: singleTop launch mode means widget taps land here for
     * a re-running activity. setIntent() first so subsequent
     * getIntent() reads the fresh intent (Android docs requirement),
     * then re-parse for the deep-link target.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingDeepLink.value = DeepLinkTarget.parse(intent)
    }
}
