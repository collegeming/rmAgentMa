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
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.runBlocking
import org.connectbot.R
import org.connectbot.ui.MainActivity
import org.json.JSONObject
import org.rmagentma.rendererhost.ProbeAssets
import org.rmagentma.rendererhost.ProbeBridge
import org.rmagentma.rendererhost.ProbeResult
import org.rmagentma.rendererhost.json
import timber.log.Timber
import java.util.concurrent.Executors
import javax.inject.Inject

/**
 * Debug-only host for the packaged renderer. It reuses the probe's bridge, limits and asset loader, but answers
 * every action from the product's own repositories instead of synthetic data: the conversation list is the
 * product's session index, selecting a row opens it through the same repository switch the Compose chat uses,
 * and actions the product cannot serve fail with a stable code.
 *
 * A renderer that cannot serve its surface never exits silently: it restarts [MainActivity] with one of the
 * stable failure codes below, and that activity reopens the Compose fallback with the code and its retry entry.
 */
@AndroidEntryPoint
class RendererHostActivity : ComponentActivity() {
    companion object {
        /** Packaged renderer assets are absent or their manifest cannot be read. */
        const val FAILURE_ASSETS_MISSING = "renderer_assets_missing"

        /** This system WebView lacks the web message bridge the renderer needs. */
        const val FAILURE_BRIDGE_UNSUPPORTED = "renderer_bridge_unsupported"

        /** The renderer's entry document failed to load. */
        const val FAILURE_LOAD_FAILED = "renderer_load_failed"

        /** The WebView render process holding the renderer exited. */
        const val FAILURE_RENDER_PROCESS_GONE = "renderer_gone"
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var container: LinearLayout

    /** The local-network grants this system may require; the shared rule keeps them identical to what tests check. */
    private val networkGrants: List<String> = networkPermissions(Build.VERSION.SDK_INT)

    private var permissionRequested = false
    private var notice: TextView? = null

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.any { !it.value }) showNetworkNotice()
    }

    @Inject
    lateinit var source: AgentRendererSource

    private val actions by lazy { RendererActions(source) }

    // Product projects are saved hosts (`host-<id>`) and conversations are the product's derived session ids,
    // so the bridge must not pin either of the probe's synthetic ids.
    @Volatile
    private var bridge = productBridge()

    @Volatile
    private var subscribed = false
    private var web: WebView? = null
    private var reply: JavaScriptReplyProxy? = null

    @Volatile
    private var closed = false

    // One failure decides the exit: a second report from the dying WebView must not restart the shell twice.
    @Volatile
    private var leaving = false

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
                    assets == null -> fail(FAILURE_ASSETS_MISSING)
                    !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) -> fail(FAILURE_BRIDGE_UNSUPPORTED)
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
                val response = loader.shouldInterceptRequest(request.url)
                // The document itself must come out of the packaged manifest: a path the manifest does not list
                // is a load this host cannot serve, and the shell states that instead of showing a 404 body.
                if (request.isForMainFrame && (response == null || response.statusCode >= 400)) main.post { fail(FAILURE_LOAD_FAILED) }
                return response ?: ProbeAssets.failure()
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = !request.isForMainFrame || !ProbeBridge.isEntry(request.url.toString())

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (view !== web || closed) return
                // A fresh bridge per document, as the probe does: in-flight work for the previous document can then
                // never answer the new one, and the new page always gets a clean handshake.
                bridge = productBridge()
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
                fail(FAILURE_RENDER_PROCESS_GONE)
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
        view.loadUrl(entryUrl())
    }

    private fun productBridge(): ProbeBridge = ProbeBridge(
        projectId = null,
        acceptsConversation = { RendererConversationId.parse(it) != null },
    )

    /**
     * The renderer's own bootstrap opens `/conversation/conv-probe-1` when the document has no route, so the
     * entry carries the conversation the product really has open; with none, it falls back to that legacy path.
     */
    private fun entryUrl(): String {
        val id = source.conversationId() ?: return ProbeBridge.ENTRY
        return "${ProbeBridge.ENTRY}#/conversation/$id"
    }

    private fun handle(action: String, payload: JSONObject): ProbeResult = runBlocking {
        localNetworkGate(action)?.let { return@runBlocking it }
        when (action) {
            "events.subscribe" -> {
                subscribed = true
                source.announce()
                ProbeResult(body = json("subscribed" to true, "conversation_id" to (source.conversationId() ?: RENDERER_CONVERSATION)))
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

    /**
     * The nearby/local-network grants a host behind the LAN needs, asked for once, the first time an action has to
     * reach one. A refusal is shown in the interface and in the answer of that route — never as a silent failure —
     * so a user can grant the permission and retry the action that needed it.
     */
    private fun localNetworkGate(action: String): ProbeResult? {
        if (action !in NETWORK_ACTIONS) return null
        if (!localNetworkRefused(action, networkGrants, ::granted)) {
            main.post { clearNetworkNotice() }
            return null
        }
        main.post {
            if (closed) return@post
            if (!permissionRequested) {
                permissionRequested = true
                permissionLauncher.launch(networkGrants.toTypedArray())
            }
            showNetworkNotice()
        }
        return ProbeResult(
            403,
            json(
                "success" to false,
                "code" to LOCAL_NETWORK_PERMISSION_REQUIRED,
                "error" to getString(R.string.renderer_local_network_denied),
            ),
        )
    }

    private fun granted(permission: String): Boolean = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /** Makes the degraded local-network access visible on the surface instead of leaving the routes to fail alone. */
    private fun showNetworkNotice() {
        if (closed) return
        notice?.let { return }
        val bar = TextView(this).apply {
            setText(R.string.renderer_local_network_denied)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        notice = bar
        container.addView(bar, 0)
    }

    private fun clearNetworkNotice() {
        val bar = notice ?: return
        notice = null
        container.removeView(bar)
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

    /**
     * Leaves the renderer with one stable code instead of a dead surface: the restarted activity shows that
     * code on the Compose fallback together with its retry entry.
     */
    private fun fail(code: String) {
        if (leaving || closed) return
        leaving = true
        Timber.w("Renderer host failed: %s", code)
        startActivity(
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_RENDERER_FAILURE, code),
        )
        finish()
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
