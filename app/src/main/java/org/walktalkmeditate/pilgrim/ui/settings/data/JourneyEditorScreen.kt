// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import java.time.Instant
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.ui.design.PilgrimDetailScaffold
import org.walktalkmeditate.pilgrim.ui.theme.LocalPilgrimDarkTheme
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * iOS parity v1.6.0 `JourneyEditorView`. Loads
 * `https://edit.pilgrimapp.org`, ships the user's `.pilgrim` ZIP into
 * the web editor via `window.pilgrimViewer.loadFile(...)`, then
 * intercepts the editor's save flow via a JS shim that posts
 * base64-encoded bytes back to the host. On Android the host receives
 * via `@JavascriptInterface`, writes to a FileProvider-shared temp
 * file, and surfaces the system share sheet.
 *
 * Non-persistent storage policy: cookies + cache are wiped on every
 * entry so a previously-cached editor bundle doesn't shadow the host
 * detection path. Same trade-off as iOS — the editor bundle re-fetches
 * each visit (~600 KB gzipped) in exchange for guaranteed-fresh JS.
 */
@Composable
fun JourneyEditorScreen(
    onBack: () -> Unit,
    viewModel: JourneyEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
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
        title = stringResource(R.string.journey_editor_title),
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
                JourneyEditorState.Loading -> LoadingPlaceholder()
                JourneyEditorState.NoWalks -> Text(
                    text = stringResource(R.string.journey_viewer_no_walks),
                    style = pilgrimType.body,
                    color = pilgrimColors.fog,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(24.dp),
                )
                is JourneyEditorState.Error -> Text(
                    text = current.message,
                    style = pilgrimType.body,
                    color = pilgrimColors.rust,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(24.dp),
                )
                is JourneyEditorState.Ready -> EditorWebView(
                    filename = current.filename,
                    base64Payload = current.base64Payload,
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
            text = stringResource(R.string.journey_editor_loading),
            style = pilgrimType.caption,
            color = pilgrimColors.fog,
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun EditorWebView(filename: String, base64Payload: String, isDark: Boolean) {
    val context = LocalContext.current
    var injected by remember { mutableStateOf(false) }
    val theme = if (isDark) "dark" else "light"

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            // Wipe cookies + cache so the previously-cached editor
            // bundle doesn't shadow the host detection. Best-effort —
            // CookieManager removeAllCookies is async but idempotent
            // and we don't gate on completion.
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebView(ctx).apply {
                clearCache(true)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                setBackgroundColor(0)
                addJavascriptInterface(
                    SaveBridge(ctx, filename),
                    "PilgrimSaveBridge",
                )
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
                            MapboxRefererInterceptor.maybeIntercept(request, "$EDITOR_URL/")
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
                        // Bridge-readiness poll (matches iOS v1.6.0 fix
                        // for the race where window.pilgrimViewer.loadFile
                        // wasn't defined yet at the fixed-1s delay point).
                        // Polls every 100ms for up to 5s for a defined
                        // loadFile fn, then injects. Save-flow shim is
                        // also injected — overrides anchor[download].click
                        // and forwards the blob to PilgrimSaveBridge.
                        val payload = "${'"'}$filename${'"'}, ${'"'}${escapeJsString(base64Payload)}${'"'}"
                        val script = """
                            (function() {
                                // Drive theme from the app's resolved appearance (before any
                                // map/UI renders) — see JourneyViewerScreen for rationale.
                                try {
                                    document.documentElement.setAttribute('data-theme', '$theme');
                                    localStorage.setItem('pilgrim-viewer-theme', '$theme');
                                } catch (e) {}

                                // Android WebView reports a 0-height layout viewport, so the
                                // web editor's height:100%/vh chain collapses the flex layout
                                // (incl. the Mapbox map) to 0px. % / vh can't fix it; stamp a
                                // concrete pixel height from innerHeight, then resize.
                                function fixHeights() {
                                    var px = window.innerHeight + 'px';
                                    document.documentElement.style.height = px;
                                    document.body.style.height = px;
                                    var root = document.body.firstElementChild;
                                    if (root) root.style.height = px;
                                    window.dispatchEvent(new Event('resize'));
                                }
                                fixHeights();
                                var fn = 0;
                                var fiv = setInterval(function() { fixHeights(); if (++fn > 8) clearInterval(fiv); }, 250);
                                window.addEventListener('orientationchange', fixHeights);
                                var attempts = 0;
                                function tryLoad() {
                                    if (window.pilgrimViewer && typeof window.pilgrimViewer.loadFile === 'function') {
                                        try {
                                            window.pilgrimViewer.loadFile($payload);
                                            setTimeout(function() { window.dispatchEvent(new Event('resize')); }, 300);
                                        } catch (e) { console.error('loadFile error', e); }
                                    } else if (attempts++ < 50) {
                                        setTimeout(tryLoad, 100);
                                    } else {
                                        console.warn('pilgrimViewer.loadFile never resolved');
                                    }
                                }
                                tryLoad();
                                // Capture-phase click intercept for the editor's
                                // anchor[download] save trigger — converts the
                                // blob URL to base64 + posts to PilgrimSaveBridge.
                                document.addEventListener('click', function(ev) {
                                    var a = ev.target.closest && ev.target.closest('a[download]');
                                    if (!a) return;
                                    var href = a.getAttribute('href');
                                    if (!href || href.indexOf('blob:') !== 0) return;
                                    ev.preventDefault();
                                    ev.stopPropagation();
                                    if (ev.stopImmediatePropagation) ev.stopImmediatePropagation();
                                    fetch(href).then(function(r) { return r.arrayBuffer(); })
                                        .then(function(buf) {
                                            var bytes = new Uint8Array(buf);
                                            var bin = '';
                                            for (var i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
                                            var b64 = btoa(bin);
                                            var name = a.getAttribute('download') || 'walks.pilgrim';
                                            PilgrimSaveBridge.save(name, b64);
                                        })
                                        .catch(function(err) { console.error('blob fetch failed', err); });
                                }, true);
                            })();
                        """.trimIndent()
                        view.postDelayed({
                            if (view.parent == null) return@postDelayed
                            view.evaluateJavascript(script, null)
                        }, 50L)
                    }
                }
                loadUrl(EDITOR_URL)
            }
        },
        update = { },
        onRelease = { webView ->
            webView.stopLoading()
            webView.webViewClient = WebViewClient()
            webView.removeJavascriptInterface("PilgrimSaveBridge")
            webView.removeAllViews()
            webView.destroy()
        },
    )
}

/**
 * Receives the base64-encoded `.pilgrim` ZIP from the editor's save
 * flow (intercepted in JS). Writes to a FileProvider-shared cache
 * file under `journey-editor-saves/` and posts an ACTION_SEND chooser
 * so the user can route to Files, Drive, AirDrop equivalents, etc.
 */
private class SaveBridge(
    private val context: Context,
    private val suggestedFilename: String,
) {
    @JavascriptInterface
    fun save(filename: String, base64: String) {
        try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            val finalName = filename.ifBlank { suggestedFilename }
            val savesDir = File(context.cacheDir, "journey-editor-saves").apply { mkdirs() }
            // Stamp filename with epoch so concurrent saves don't clash.
            val outFile = File(savesDir, "${Instant.now().epochSecond}-$finalName")
            outFile.writeBytes(bytes)
            val authority = "${context.packageName}.fileprovider"
            val uri: Uri = FileProvider.getUriForFile(context, authority, outFile)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(
                Intent.createChooser(intent, "Save edited journey").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        } catch (t: Throwable) {
            Log.w(TAG, "save bridge failed", t)
        }
    }

    private companion object {
        const val TAG = "JourneyEditorBridge"
    }
}

private fun escapeJsString(s: String): String =
    s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

private const val EDITOR_URL = "https://edit.pilgrimapp.org"

private const val TAG = "JourneyEditor"
