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

package org.rmagentma.hostprobe

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.View
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import org.rmagentma.rendererhost.ProbeAssets
import org.rmagentma.rendererhost.ProbeAttachments
import org.rmagentma.rendererhost.ProbeBridge
import org.rmagentma.rendererhost.ProbeFailure
import org.rmagentma.rendererhost.requireProbe
class ProbeActivity : Activity() {
    private val owner get() = application as ProbeApplication
    private val worker get() = owner.worker
    private val main = Handler(Looper.getMainLooper())
    private lateinit var store: ProbeStore

    @Volatile
    private lateinit var bridge: ProbeBridge
    private var eventListener: ((String, Any?, String, Long) -> Unit)? = null
    private lateinit var attachments: ProbeAttachments
    private lateinit var container: LinearLayout
    private var web: WebView? = null
    private var reply: JavaScriptReplyProxy? = null

    @Volatile
    private var subscribed = false
    private var chooser: ValueCallback<Array<Uri>>? = null
    private var chooserDocument = ""
    private var review: AlertDialog? = null

    @Volatile
    private var closed = false
    private var pickerCode = -1

    @Volatile
    private var foreground = false

    @Volatile
    private var reviewGeneration = 0L
    private data class ReviewBinding(val document: String, val reference: String, val epoch: String, val details: String, val deadline: Long, val generation: Long)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.fitsSystemWindows = true
        setContentView(container)
        status(R.string.loading)
        worker.execute {
            if (closed) return@execute
            if (owner.stateFailure) {
                main.post { if (!closed) status(R.string.state_error) }
                return@execute
            }
            store = owner.store
            attachments = owner.attachments
            bridge = ProbeBridge()
            eventListener = { name, data, epoch, seq ->
                val document = bridge.documentId
                val event = bridge.event(name, data, epoch, seq).toString()
                main.post {
                    if (!closed && document == bridge.documentId && name == "confirmation.remove") {
                        reviewGeneration++
                        review?.dismiss()
                        review = null
                    }
                    if (!closed && subscribed && document == bridge.documentId && document.isNotEmpty() && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) reply?.postMessage(event)
                }
            }
            store.listener = eventListener
            main.post { if (!closed) openRenderer() }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun openRenderer() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            status(R.string.bridge_unsupported)
            return
        }
        if (owner.assetsFailure) {
            status(R.string.assets_missing)
            return
        }
        val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/renderer/", owner.rendererAssets).build()
        container.removeAllViews()
        container.addView(
            Button(this).apply {
                setText(R.string.reload)
                setOnClickListener {
                    disposeWeb()
                    openRenderer()
                }
            },
        )
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            container.addView(
                Button(this).apply {
                    setText(R.string.crash_probe)
                    setOnClickListener {
                        AlertDialog.Builder(this@ProbeActivity).setMessage(R.string.crash_confirm)
                            .setPositiveButton(android.R.string.ok) { _, _ -> web?.loadUrl("chrome://crash") }
                            .setNegativeButton(android.R.string.cancel, null).show()
                    }
                },
            )
        }
        container.addView(TextView(this).apply { setText(R.string.background_unverified) })
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

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                if (view !== web || closed) return
                revoke()
                if (!ProbeBridge.isEntry(url)) {
                    view.stopLoading()
                    status(R.string.navigation_blocked)
                } else {
                    bridge.commit(url)
                }
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                disposeWeb()
                status(R.string.renderer_gone)
                container.addView(
                    Button(this@ProbeActivity).apply {
                        setText(R.string.reload)
                        setOnClickListener { openRenderer() }
                    },
                )
                return true
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                cancelChooser()
                if (bridge.documentId.isEmpty() || view !== web || params.isCaptureEnabled) {
                    callback.onReceiveValue(null)
                    return true
                }
                chooser = callback
                chooserDocument = bridge.documentId
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, ProbeAttachments.MIME_TYPES.toTypedArray())
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try {
                    if (owner.nextPickerCode >= 65000) {
                        cancelChooser()
                    } else {
                        pickerCode = owner.nextPickerCode++
                        startActivityForResult(intent, pickerCode)
                    }
                } catch (_: Exception) {
                    cancelChooser()
                }
                return true
            }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(view, "RmAgentHost", setOf(ProbeBridge.ORIGIN)) { sender, message, origin, mainFrame, proxy ->
                if (sender !== web || message.type != androidx.webkit.WebMessageCompat.TYPE_STRING) return@addWebMessageListener
                val raw = message.data ?: return@addWebMessageListener
                if (!ProbeBridge.isOrigin(origin.toString()) || !mainFrame) return@addWebMessageListener
                val currentBridge = bridge
                val expected = currentBridge.documentId
                worker.execute {
                    if (currentBridge !== bridge || closed) return@execute
                    var reviewRequest: JSONObject? = null
                    val response = currentBridge.receive(origin.toString(), mainFrame, raw) { action, payload ->
                        if (action == "events.subscribe") subscribed = true
                        if (action == "events.unsubscribe") subscribed = false
                        ProbeActions(store, attachments).handle(action, payload, currentBridge.documentId).also {
                            if (action == "approvals.review" && it.status == 202) reviewRequest = payload
                        }
                    }
                    val responseDocument = response?.optString("documentId")
                    main.post {
                        if (closed || currentBridge !== bridge || sender !== web || response == null || responseDocument != bridge.documentId || (expected.isNotEmpty() && expected != bridge.documentId)) return@post
                        reply = proxy
                        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) proxy.postMessage(response.toString())
                        reviewRequest?.let { showApproval(it, bridge.documentId) }
                    }
                }
            }
        }
        container.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        view.loadUrl(ProbeBridge.ENTRY)
    }

    private fun showApproval(payload: JSONObject, document: String) {
        if (!foreground || closed || document != bridge.documentId) return
        review?.dismiss()
        val reference = payload.getJSONObject("params").getString("requestRef")
        val epoch = payload.getJSONObject("body").getString("epoch")
        val visibleGeneration = reviewGeneration
        worker.execute {
            val current = try {
                store.approval(reference, epoch)
            } catch (_: ProbeFailure) {
                return@execute
            }
            main.post {
                if (!foreground || closed || document != bridge.documentId || visibleGeneration != reviewGeneration) return@post
                review?.dismiss()
                val reviewed = ReviewBinding(document, reference, epoch, current.toString(), android.os.SystemClock.elapsedRealtime() + 60000, visibleGeneration)
                review = AlertDialog.Builder(this)
                    .setTitle(R.string.approval_title)
                    .setMessage(getString(R.string.approval_detail) + "\n\n" + getString(R.string.approval_reference, reference))
                    .setPositiveButton(R.string.allow_once) { _, _ -> decide(reviewed, "allow-once") }
                    .setNegativeButton(R.string.reject_once) { _, _ -> decide(reviewed, "reject-once") }
                    .setNeutralButton(R.string.later, null)
                    .show()
            }
        }
    }

    private fun decide(binding: ReviewBinding, option: String) {
        worker.execute {
            if (binding.document != bridge.documentId || closed || !foreground || binding.generation != reviewGeneration || binding.deadline < android.os.SystemClock.elapsedRealtime()) return@execute
            try {
                requireProbe(store.approval(binding.reference, binding.epoch).toString() == binding.details, 409, "STALE_APPROVAL")
                store.decide(binding.reference, binding.epoch, option)
            } catch (_: ProbeFailure) {
                main.post { if (!closed) AlertDialog.Builder(this).setMessage(R.string.approval_expired).setPositiveButton(android.R.string.ok, null).show() }
            }
        }
    }

    @Deprecated("Activity result compatibility for API 24")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != pickerCode) return
        pickerCode = -1
        val callback = chooser ?: return
        val document = chooserDocument
        chooser = null
        if (resultCode != RESULT_OK || data == null || document != bridge.documentId) {
            callback.onReceiveValue(null)
            return
        }
        val uris = if (data.clipData != null) (0 until data.clipData!!.itemCount).map { data.clipData!!.getItemAt(it).uri } else listOfNotNull(data.data)
        worker.execute {
            val valid = uris.isNotEmpty() && uris.size <= 4 && uris.all { uri ->
                try {
                    require(uri.scheme == "content" && uri.authority != packageName && uri.authority?.startsWith("$packageName.") != true)
                    require(contentResolver.getType(uri) in ProbeAttachments.MIME_TYPES)
                    val size = contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1
                    } ?: -1
                    require(size in 0..ProbeAttachments.MAX_SIZE)
                    contentResolver.openInputStream(uri)?.use { it.read() } ?: error("Unreadable URI")
                    true
                } catch (_: Exception) {
                    false
                }
            }
            main.post { callback.onReceiveValue(if (valid && !closed && document == bridge.documentId) uris.toTypedArray() else null) }
        }
    }

    private fun cancelChooser() {
        chooser?.onReceiveValue(null)
        chooser = null
        chooserDocument = ""
        pickerCode = -1
    }

    private fun revoke() {
        reviewGeneration++
        if (::bridge.isInitialized) {
            val old = bridge.documentId
            bridge.invalidate()
            bridge = ProbeBridge()
            if (::attachments.isInitialized) worker.execute { attachments.invalidate(old) }
        }
        subscribed = false
        reply = null
        cancelChooser()
        review?.dismiss()
        review = null
    }

    private fun disposeWeb() {
        revoke()
        web?.let { view ->
            container.removeView(view)
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) WebViewCompat.removeWebMessageListener(view, "RmAgentHost")
            view.stopLoading()
            view.destroy()
        }
        web = null
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

    override fun onStart() {
        super.onStart()
        foreground = true
    }

    override fun onStop() {
        foreground = false
        reviewGeneration++
        review?.dismiss()
        review = null
        super.onStop()
    }

    override fun onDestroy() {
        closed = true
        disposeWeb()
        main.removeCallbacksAndMessages(null)
        val previousListener = eventListener
        if (::store.isInitialized) worker.execute { if (store.listener === previousListener) store.listener = null }
        super.onDestroy()
    }
}
