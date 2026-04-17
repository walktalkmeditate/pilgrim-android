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
                // Edge-to-edge: applying Scaffold's innerPadding to the
                // NavHost keeps content out from under system bars. Each
                // destination adds its own visual padding on top of this.
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    PilgrimNavHost(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}
