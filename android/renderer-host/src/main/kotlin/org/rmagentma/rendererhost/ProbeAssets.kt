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

package org.rmagentma.rendererhost

import android.content.res.AssetManager
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.URLConnection

class ProbeAssets(private val assets: AssetManager) : WebViewAssetLoader.PathHandler {
    private val files: Set<String> = assets.open("renderer/asset-manifest.json").use { stream ->
        val records = JSONObject(stream.readBytes().toString(Charsets.UTF_8)).getJSONArray("files")
        (0 until records.length()).map { records.getJSONObject(it).getString("path") }.toSet()
    }

    override fun handle(path: String): WebResourceResponse {
        if (path !in files || path.split('/').contains("..")) return failure()
        return try {
            val mime = when (path.substringAfterLast('.')) {
                "js", "mjs" -> "application/javascript"
                "css" -> "text/css"
                "html" -> "text/html"
                "json" -> "application/json"
                "woff2" -> "font/woff2"
                "woff" -> "font/woff"
                "wasm" -> "application/wasm"
                else -> URLConnection.guessContentTypeFromName(path) ?: "application/octet-stream"
            }
            WebResourceResponse(
                mime,
                "UTF-8",
                200,
                "OK",
                mapOf(
                    "Content-Security-Policy" to "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; font-src 'self'; connect-src 'none'; worker-src 'self' blob:; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'",
                    "X-Content-Type-Options" to "nosniff",
                    "Cache-Control" to "no-store",
                ),
                assets.open("renderer/$path"),
            )
        } catch (_: Exception) {
            failure()
        }
    }

    companion object {
        fun failure(): WebResourceResponse = WebResourceResponse(
            "text/plain",
            "UTF-8",
            404,
            "Not Found",
            mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff"),
            ByteArrayInputStream("未找到已打包的 renderer 资源".toByteArray()),
        )
    }
}
