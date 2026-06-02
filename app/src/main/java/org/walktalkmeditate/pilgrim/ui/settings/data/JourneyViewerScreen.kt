// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.data

import android.annotation.SuppressLint
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
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
import org.walktalkmeditate.pilgrim.ui.design.PilgrimDetailScaffold
import org.walktalkmeditate.pilgrim.ui.theme.LocalPilgrimDarkTheme
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

@Composable
fun JourneyViewerScreen(
    onBack: () -> Unit,
    viewModel: JourneyViewerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // iOS parity `JourneyViewerView.swift:.task { await prepareData() }`
    // — re-load on every view appearance so a walk added in another tab
    // while this route is in the back-stack appears on return.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    PilgrimDetailScaffold(
        title = stringResource(R.string.journey_viewer_title),
        onBack = onBack,
        backContentDescription = stringResource(R.string.journey_viewer_back),
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
                    isDark = LocalPilgrimDarkTheme.current,
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
private fun JourneyWebView(walksJson: String, manifestJson: String, isDark: Boolean) {
    var injected by remember { mutableStateOf(false) }
    val theme = if (isDark) "dark" else "light"
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                setBackgroundColor(0)
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                        Log.d(TAG, "[console] ${message.message()} @${message.sourceId()}:${message.lineNumber()}")
                        return true
                    }
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): WebResourceResponse? {
                        if (request != null) {
                            MapboxRefererInterceptor.maybeIntercept(request, "$VIEWER_URL/")
                                ?.let { return it }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?,
                    ) {
                        Log.w(TAG, "load error ${error?.errorCode} ${error?.description} url=${request?.url}")
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (injected || view == null) return
                        injected = true
                        // iOS v1.6.0 fix (JourneyViewerView.waitForBridgeReady):
                        // the viewer's JS attaches `window.pilgrimViewer`
                        // asynchronously after DOM load. The old fixed 1s
                        // delay was a guess that left the page BLANK when
                        // the bundle took longer (the bug this fixes). Mirror
                        // the editor's self-polling shim: retry every 100ms
                        // up to ~5s for the bridge, then call loadData.
                        val safeWalks = escapeJsBoundary(walksJson)
                        val safeManifest = escapeJsBoundary(manifestJson)
                        val payload = """{"walks":$safeWalks,"manifest":$safeManifest}"""
                        val script = """
                            (function() {
                                // Drive the viewer's theme from the app's resolved appearance
                                // (LocalPilgrimDarkTheme) rather than the WebView's
                                // prefers-color-scheme (which Android doesn't propagate from
                                // the in-app appearance toggle) or stale localStorage. Set
                                // BEFORE loadData so the Mapbox style (renderer reads
                                // data-theme) and the UI chrome both pick it up.
                                try {
                                    document.documentElement.setAttribute('data-theme', '$theme');
                                    localStorage.setItem('pilgrim-viewer-theme', '$theme');
                                } catch (e) {}

                                // Android System WebView (unlike iOS WKWebView) reports a
                                // 0-height LAYOUT viewport, so the web app's height:100%/vh
                                // chain collapses <body> and the Mapbox map container to 0px
                                // even though the VISUAL viewport (window.innerHeight) is
                                // correct. % and vh can't fix it — they resolve against the
                                // broken layout viewport. Stamp a concrete pixel height from
                                // innerHeight onto the html->body->root chain, then fire resize
                                // so Mapbox GL re-measures.
                                function fixHeights() {
                                    var px = window.innerHeight + 'px';
                                    document.documentElement.style.height = px;
                                    document.body.style.height = px;
                                    var root = document.body.firstElementChild;
                                    if (root) root.style.height = px;
                                    window.dispatchEvent(new Event('resize'));
                                }
                                fixHeights();
                                // Re-stamp a few times to catch the SPA mounting its root after
                                // our first pass, and on real viewport changes.
                                var n = 0;
                                var iv = setInterval(function() { fixHeights(); if (++n > 8) clearInterval(iv); }, 250);
                                window.addEventListener('orientationchange', fixHeights);

                                var data = $payload;
                                var attempts = 0;
                                function tryLoad() {
                                    if (window.pilgrimViewer && typeof window.pilgrimViewer.loadData === 'function') {
                                        try {
                                            window.pilgrimViewer.loadData(data);
                                            fixHeights();
                                        } catch (e) { console.error('loadData error', e); }
                                    } else if (attempts++ < 50) {
                                        setTimeout(tryLoad, 100);
                                    } else {
                                        console.warn('pilgrimViewer.loadData never resolved');
                                    }
                                }
                                tryLoad();
                            })();
                        """.trimIndent()
                        // Defensive: the WebView may have been destroyed
                        // before this runs. `view.parent` is the cheapest
                        // still-attached signal.
                        if (view.parent == null) return
                        view.evaluateJavascript(script, null)
                    }
                }
                loadUrl(VIEWER_URL)
            }
        },
        update = { },
        onRelease = { webView ->
            // Stop in-flight loads, drop callback handlers, then destroy
            // the renderer process. Without this, AndroidView leaks the
            // WebView (Chromium renderer + JS engine + DOM storage)
            // every time the user enters/exits Journey Viewer.
            webView.stopLoading()
            webView.webViewClient = WebViewClient()
            webView.removeAllViews()
            webView.destroy()
        },
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

private const val TAG = "JourneyViewer"
