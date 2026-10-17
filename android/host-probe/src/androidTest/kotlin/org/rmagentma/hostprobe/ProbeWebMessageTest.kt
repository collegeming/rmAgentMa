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

import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ProbeWebMessageTest {
    @Test
    fun realWebViewHandshakeReplyProxyAndIframeRejection() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val latch = CountDownLatch(1)
        val iframe = CountDownLatch(1)
        var view: WebView? = null
        var document = ""
        instrumentation.runOnMainSync {
            assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
            val bridge = ProbeBridge()
            bridge.commit(ProbeBridge.ENTRY)
            view = WebView(instrumentation.targetContext).apply {
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.blockNetworkLoads = true
                WebViewCompat.addWebMessageListener(this, "RmAgentHost", setOf(ProbeBridge.ORIGIN)) { _, message, origin, mainFrame, reply ->
                    assertEquals(WebMessageCompat.TYPE_STRING, message.type)
                    if (message.data == "reply-observed") {
                        assertTrue(mainFrame)
                        latch.countDown()
                    } else if (!mainFrame) {
                        assertEquals(null, bridge.receive(origin.toString(), false, message.data!!) { _, _ -> error("Iframe executed") })
                        iframe.countDown()
                    } else {
                        val response = bridge.receive(origin.toString(), true, message.data!!) { _, _ -> error("Handshake executed product action") }!!
                        document = response.getJSONObject("body").getString("documentId")
                        reply.postMessage(response.toString())
                    }
                }
                val request = json("requestId" to "renderer-1", "documentId" to "", "action" to "host.handshake", "payload" to json("params" to JSONObject(), "query" to JSONObject(), "body" to null)).toString()
                val html = """<!doctype html><meta charset="utf-8"><script>RmAgentHost.onmessage=function(e){const r=JSON.parse(e.data);if(r.ok&&r.body.documentId===r.documentId)RmAgentHost.postMessage('reply-observed');};RmAgentHost.postMessage(${JSONObject.quote(request)});</script><iframe srcdoc="&lt;script&gt;RmAgentHost.postMessage('{}')&lt;/script&gt;"></iframe>"""
                loadDataWithBaseURL(ProbeBridge.ENTRY, html, "text/html", "UTF-8", null)
            }
        }
        try {
            assertTrue("replyProxy message did not reach JS onmessage", latch.await(10, TimeUnit.SECONDS))
            assertTrue("same-origin iframe test did not execute", iframe.await(10, TimeUnit.SECONDS))
            assertTrue(document.isNotEmpty())
        } finally {
            instrumentation.runOnMainSync { view?.destroy() }
        }
    }

    @Test
    fun packagedRealRendererHasClosedAssetListAndNoNetworkPermission() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val loader = ProbeAssets(context.assets)
        assertEquals(404, loader.handle("missing.js").statusCode)
        assertEquals(404, loader.handle("../synthetic-state.json").statusCode)
        val index = loader.handle("index.html")
        assertEquals(200, index.statusCode)
        assertTrue(index.responseHeaders.getValue("Content-Security-Policy").contains("connect-src 'none'"))
        val manifest = context.assets.open("renderer/asset-manifest.json").use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
        assertEquals("index.html", manifest.getString("entry"))
        val permissions = context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertFalse(permissions.contains("android.permission.INTERNET"))
    }
}
