// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import dagger.hilt.android.AndroidEntryPoint
import org.walktalkmeditate.pilgrim.ui.navigation.PilgrimNavHost
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PilgrimTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { inner ->
                    PilgrimNavHost(
                        // Apply the inner padding through the NavHost so
                        // each destination decides how to consume it.
                        // Placeholder: each screen does its own padding
                        // for now; we'll revisit with per-route scaffolds.
                    )
                    // Unused padding parameter intentionally ignored —
                    // edge-to-edge destinations handle insets themselves.
                    inner.calculateTopPadding()
                }
            }
        }
    }
}
