/*
 * rmAgentMa
 * Copyright 2026 rmAgentMa contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.connectbot.renderer

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.runBlocking
import org.connectbot.R
import org.json.JSONObject
import org.rmagentma.rendererhost.ProbeAssets
import org.rmagentma.rendererhost.ProbeBridge
import org.rmagentma.rendererhost.ProbeResult
import org.rmagentma.rendererhost.json
import java.util.concurrent.Executors
import javax.inject.Inject

/**
 * Debug-only host for the packaged renderer. It reuses the probe's bridge, limits and asset loader, but answers
 * every action from the product's own repositories instead of synthetic data: the conversation it serves is the
 * session the Compose chat already has open, and actions the product cannot serve fail with a stable code.
 */
@AndroidEntryPoint
class RendererHostActivity : ComponentActivity() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var container: LinearLayout

    @Inject
    lateinit var source: AgentRendererSource

    private val actions by lazy { RendererActions(source) }

    @Volatile
    private var bridge = ProbeBridge()

    @Volatile
    private var subscribed = false
    private var web: WebView? = null
    private var reply: JavaScriptReplyProxy? = null

    @Volatile
    private var closed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.fitsSystemWindows = true
        setContentView(container)
        source.open(::publish)
        worker.execute {
            // The manifest read is disk I/O, so it stays off the main thread exactly as the probe does it.
            val assets = try {
                ProbeAssets(this.assets)
            } catch (_: Exception) {
                null
            }
            main.post {
                when {
                    closed -> Unit
                    assets == null -> status(R.string.renderer_assets_missing)
                    !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) -> status(R.string.renderer_bridge_unsupported)
                    else -> openRenderer(assets)
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun openRenderer(rendererAssets: ProbeAssets) {
        val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/renderer/", rendererAssets).build()
        val view = WebView(this)
        web = view
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            blockNetworkLoads = true
            mediaPlaybackRequiresUserGesture = true
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                if (request.method != "GET" || !ProbeBridge.isOrigin("${request.url.scheme}://${request.url.encodedAuthority}")) return ProbeAssets.failure()
                return loader.shouldInterceptRequest(request.url) ?: ProbeAssets.failure()
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = !request.isForMainFrame || !ProbeBridge.isEntry(request.url.toString())

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (view !== web || closed) return
                // A fresh bridge per document, as the probe does: in-flight work for the previous document can then
                // never answer the new one, and the new page always gets a clean handshake.
                bridge = ProbeBridge()
                reply = null
                subscribed = false
                if (!ProbeBridge.isEntry(url)) {
                    view.stopLoading()
                    status(R.string.renderer_navigation_blocked)
                } else {
                    bridge.commit(url)
                }
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                dispose(view)
                status(R.string.renderer_gone)
                return true
            }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(view, "RmAgentHost", setOf(ProbeBridge.ORIGIN)) { sender, message, origin, mainFrame, proxy ->
                if (sender !== web || message.type != WebMessageCompat.TYPE_STRING) return@addWebMessageListener
                val raw = message.data ?: return@addWebMessageListener
                if (!ProbeBridge.isOrigin(origin.toString()) || !mainFrame) return@addWebMessageListener
                val current = bridge
                val expected = current.documentId
                worker.execute {
                    if (current !== bridge || closed) return@execute
                    // One worker thread per document serializes every action, so a send, a cancel and an approval
                    // can never race each other or the Compose path into the same ACP session.
                    val response = current.receive(origin.toString(), mainFrame, raw) { action, payload -> handle(action, payload) }
                    val document = response?.optString("documentId")
                    main.post {
                        if (closed || current !== bridge || sender !== web || response == null || document != bridge.documentId || (expected.isNotEmpty() && expected != bridge.documentId)) return@post
                        reply = proxy
                        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) proxy.postMessage(response.toString())
                    }
                }
            }
        }
        container.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        view.loadUrl(ProbeBridge.ENTRY)
    }

    private fun handle(action: String, payload: JSONObject): ProbeResult = runBlocking {
        when (action) {
            "events.subscribe" -> {
                subscribed = true
                source.announce()
                ProbeResult(body = json("subscribed" to true, "conversation_id" to RENDERER_CONVERSATION))
            }

            "events.unsubscribe" -> {
                subscribed = false
                ProbeResult(body = json("subscribed" to false))
            }

            else -> actions.handle(action, payload)
        }
    }

    /** Forwards one conversation event to the live document; a stale or closed document never sees it. */
    private fun publish(name: String, data: JSONObject, epoch: String, seq: Long) {
        val current = bridge
        val document = current.documentId
        if (document.isEmpty()) return
        val event = current.event(name, data, epoch, seq).toString()
        main.post {
            if (closed || !subscribed || current !== bridge || bridge.documentId != document) return@post
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) reply?.postMessage(event)
        }
    }

    private fun dispose(view: WebView) {
        if (web === view) web = null
        container.removeView(view)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) WebViewCompat.removeWebMessageListener(view, "RmAgentHost")
        view.stopLoading()
        view.destroy()
    }

    private fun status(resource: Int) {
        container.removeAllViews()
        container.addView(
            TextView(this).apply {
                setText(resource)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            },
        )
    }

    override fun onDestroy() {
        closed = true
        subscribed = false
        reply = null
        web?.let(::dispose)
        source.close()
        worker.shutdown()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
