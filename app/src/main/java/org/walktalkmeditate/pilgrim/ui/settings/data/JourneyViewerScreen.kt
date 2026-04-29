// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.data

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JourneyViewerScreen(
    onBack: () -> Unit,
    viewModel: JourneyViewerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.journey_viewer_title),
                        style = pilgrimType.heading,
                        color = pilgrimColors.ink,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.journey_viewer_back),
                            tint = pilgrimColors.ink,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = pilgrimColors.parchment,
                ),
            )
        },
        containerColor = pilgrimColors.parchment,
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            when (val current = state) {
                JourneyState.Loading -> LoadingPlaceholder()
                JourneyState.NoWalks -> NoWalksPlaceholder()
                is JourneyState.Error -> ErrorPlaceholder(message = current.message)
                is JourneyState.Ready -> JourneyWebView(
                    walksJson = current.walksJson,
                    manifestJson = current.manifestJson,
                )
            }
        }
    }
}

@Composable
private fun LoadingPlaceholder() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CircularProgressIndicator(color = pilgrimColors.stone, modifier = Modifier.size(48.dp))
        Text(
            text = stringResource(R.string.journey_viewer_loading),
            style = pilgrimType.caption,
            color = pilgrimColors.fog,
        )
    }
}

@Composable
private fun NoWalksPlaceholder() {
    Text(
        text = stringResource(R.string.journey_viewer_no_walks),
        style = pilgrimType.body,
        color = pilgrimColors.fog,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(24.dp),
    )
}

@Composable
private fun ErrorPlaceholder(message: String) {
    Text(
        text = message,
        style = pilgrimType.body,
        color = pilgrimColors.rust,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(24.dp),
    )
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun JourneyWebView(walksJson: String, manifestJson: String) {
    var injected by remember { mutableStateOf(false) }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                setBackgroundColor(0)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (injected || view == null) return
                        injected = true
                        // iOS uses a 1-second delay after onPageFinished
                        // before injecting because the viewer's JS may
                        // attach `window.pilgrimViewer` asynchronously
                        // after DOM load. Mirror that here.
                        // JourneyViewerView.swift:201.
                        view.postDelayed({
                            val safeWalks = escapeJsBoundary(walksJson)
                            val safeManifest = escapeJsBoundary(manifestJson)
                            val payload = """{"walks":$safeWalks,"manifest":$safeManifest}"""
                            view.evaluateJavascript("window.pilgrimViewer.loadData($payload);", null)
                        }, INJECTION_DELAY_MS)
                    }
                }
                loadUrl(VIEWER_URL)
            }
        },
        update = { },
    )
}

/**
 * Defense in depth: U+2028 (line separator) and U+2029 (paragraph
 * separator) are valid in JSON but illegal in pre-ES2019 JS string
 * literals. Modern WebView (Chromium ≥ M58) handles them, but a
 * downlevel runtime would throw on any user-supplied transcription
 * or intention containing those code points.
 */
private fun escapeJsBoundary(json: String): String =
    json.replace(" ", "\\u2028").replace(" ", "\\u2029")

private const val VIEWER_URL = "https://view.pilgrimapp.org"

private const val INJECTION_DELAY_MS = 1_000L
