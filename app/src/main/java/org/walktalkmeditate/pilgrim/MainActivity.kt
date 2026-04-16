// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dagger.hilt.android.AndroidEntryPoint
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PilgrimTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { inner ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(inner),
                        contentAlignment = Alignment.Center,
                    ) {
                        Welcome()
                    }
                }
            }
        }
    }
}

@Composable
private fun Welcome() {
    Text(text = stringResource(R.string.welcome_epigraph))
}
