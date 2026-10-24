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

import org.json.JSONObject
import java.net.URI
import java.util.UUID

/**
 * [projectId] pins the single project a synthetic host serves; the product host passes null because its
 * projects are saved hosts and its action layer resolves or refuses each id. [acceptsConversation] lets the
 * product host serve its own derived conversation ids while the probe keeps answering only its fixed one.
 */
class ProbeBridge(
    private val projectId: String? = PROJECT,
    private val acceptsConversation: (String) -> Boolean = { false },
) {
    @Volatile
    var documentId: String = ""
        private set

    @Volatile
    private var committed = false
    private data class Receipt(val fingerprint: String, val response: String, val bytes: Int)
    private val receipts = linkedMapOf<Long, Receipt>()
    private var receiptBytes = 0
    private var highWater = 0L
    private var handshakeRequest = ""

    fun invalidate() {
        documentId = ""
        committed = false
    }

    fun commit(url: String) {
        receipts.clear()
        receiptBytes = 0
        highWater = 0
        handshakeRequest = ""
        committed = isEntry(url)
    }

    fun receive(origin: String, mainFrame: Boolean, raw: String, execute: (String, JSONObject) -> ProbeResult): JSONObject? {
        if (!committed || !mainFrame || !isOrigin(origin)) return null
        var requestId = ""
        try {
            requireProbe(raw.length <= ProbeLimits.MESSAGE_BYTES && raw.toByteArray(Charsets.UTF_8).size <= ProbeLimits.MESSAGE_BYTES, 413, "MESSAGE_TOO_LARGE")
            var depth = 0
            var quoted = false
            var escaped = false
            raw.forEach { char ->
                if (quoted) {
                    if (escaped) {
                        escaped = false
                    } else if (char == '\\') {
                        escaped = true
                    } else if (char == '"') {
                        quoted = false
                    }
                } else {
                    when (char) {
                        '"' -> quoted = true

                        '{', '[' -> {
                            depth++
                            requireProbe(depth <= 12, 400, "JSON_TOO_DEEP")
                        }

                        '}', ']' -> depth--
                    }
                }
            }
            val request = JSONObject(raw)
            requireProbe(request.keys().asSequence().toSet() == setOf("requestId", "documentId", "action", "payload"))
            requestId = request.string("requestId", 128)
            val action = request.string("action", 80)
            val payload = request.optJSONObject("payload") ?: throw ProbeFailure(400, "INVALID_PAYLOAD")
            val suppliedDocument = request.opt("documentId")
            requireProbe(suppliedDocument is String)
            if (action == "host.handshake") {
                requireProbe(payload.keys().asSequence().toSet() == setOf("params", "query", "body"))
                requireProbe(payload.optJSONObject("params")?.length() == 0 && payload.optJSONObject("query")?.length() == 0 && payload.isNull("body"))
                requireProbe(suppliedDocument == "", 409, "STALE_DOCUMENT")
                requireProbe(documentId.isEmpty() || handshakeRequest == requestId, 409, "ALREADY_HANDSHAKEN")
                if (documentId.isEmpty()) {
                    highWater = requestSequence(requestId)
                    documentId = UUID.randomUUID().toString()
                }
                handshakeRequest = requestId
                return response(requestId, ProbeResult(body = json("documentId" to documentId, "protocolVersion" to 1)))
            }
            requireProbe(documentId.isNotEmpty() && suppliedDocument == documentId, 409, "STALE_DOCUMENT")
            requireProbe(action in ACTIONS, 404, "UNKNOWN_ACTION")
            requireProbe(payload.keys().asSequence().toSet() == setOf("params", "query", "body"))
            val params = payload.optJSONObject("params") ?: throw ProbeFailure(400, "INVALID_PARAMS")
            val query = payload.optJSONObject("query") ?: throw ProbeFailure(400, "INVALID_QUERY")
            requireProbe(query.keys().asSequence().all { query.opt(it) is String })
            requireProbe(params.keys().asSequence().all { params.opt(it) is String })
            requireProbe(payload.isNull("body") || payload.optJSONObject("body") != null)
            val allowedParams = when (action) {
                "config.set" -> setOf("conversationId", "optionId")
                "approvals.review" -> setOf("conversationId", "requestRef")
                "project.read" -> setOf("projectId")
                in CONVERSATION_ACTIONS -> setOf("conversationId")
                else -> emptySet()
            }
            requireProbe(params.keys().asSequence().all { it in allowedParams })
            if (action in CONVERSATION_ACTIONS) {
                val conversation = params.opt("conversationId")
                requireProbe(
                    conversation == CONVERSATION ||
                        (conversation is String && acceptsConversation(conversation)) ||
                        (action in DEFAULT_CONVERSATION_ACTIONS && conversation == null),
                    404,
                    "CONVERSATION_NOT_FOUND",
                )
            }
            if (action == "project.read" && projectId != null) requireProbe(params.opt("projectId") == projectId, 404, "PROJECT_NOT_FOUND")
            val sequence = requestSequence(requestId)
            val fingerprint = java.security.MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            val previous = receipts[sequence]
            if (previous != null) {
                requireProbe(previous.fingerprint == fingerprint, 409, "REQUEST_ID_CONFLICT")
                return JSONObject(previous.response)
            }
            requireProbe(sequence > highWater, 409, "REQUEST_REPLAY_EXPIRED")
            highWater = sequence
            val result = try {
                execute(action, payload)
            } catch (failure: ProbeFailure) {
                ProbeResult(failure.status, json("success" to false, "code" to failure.code, "error" to failure.code))
            } catch (_: Exception) {
                ProbeResult(500, json("success" to false, "code" to "RESULT_UNKNOWN", "error" to "RESULT_UNKNOWN"))
            }
            val response = response(requestId, result)
            val serialized = response.toString()
            val bytes = serialized.toByteArray(Charsets.UTF_8).size
            if (bytes <= REPLAY_BYTES) {
                while (receipts.size >= REPLAY_COUNT || receiptBytes + bytes > REPLAY_BYTES) {
                    val oldest = receipts.keys.first()
                    receiptBytes -= receipts.remove(oldest)!!.bytes
                }
                receipts[sequence] = Receipt(fingerprint, serialized, bytes)
                receiptBytes += bytes
            }
            return response
        } catch (failure: ProbeFailure) {
            return response(requestId, ProbeResult(failure.status, json("success" to false, "code" to failure.code, "error" to failure.code)))
        } catch (_: Exception) {
            return response(requestId, ProbeResult(400, json("success" to false, "code" to "INVALID_REQUEST", "error" to "INVALID_REQUEST")))
        }
    }

    fun response(requestId: String, result: ProbeResult): JSONObject = json(
        "requestId" to requestId,
        "documentId" to documentId,
        "ok" to (result.status in 200..299),
        "status" to result.status,
        "body" to result.body,
    )

    fun event(name: String, data: Any?, epoch: String, seq: Long): JSONObject = json(
        "type" to "event",
        "documentId" to documentId,
        "name" to name,
        "data" to data,
        "epoch" to epoch,
        "seq" to seq,
    )

    companion object {
        const val REPLAY_COUNT = 256
        const val REPLAY_BYTES = 2 * 1024 * 1024
        private fun requestSequence(id: String): Long {
            requireProbe(id.matches(Regex("renderer-[1-9][0-9]{0,15}")), 400, "INVALID_REQUEST_ID")
            val sequence = id.removePrefix("renderer-").toLongOrNull()
            requireProbe(sequence != null && sequence <= 9007199254740991L, 400, "INVALID_REQUEST_ID")
            return sequence!!
        }
        const val ORIGIN = "https://appassets.androidplatform.net"
        const val ENTRY = "$ORIGIN/assets/renderer/index.html"
        const val CONVERSATION = "conv-probe-1"
        const val PROJECT = "project-probe-1"
        val ACTIONS = setOf(
            "auth.read", "settings.client.read", "settings.client.write", "settings.read",
            "conversations.list", "conversation.read", "messages.list", "messages.latest",
            "runtime.ensure", "config.read", "config.set", "messages.send", "turn.cancel",
            "approvals.list", "approvals.review", "runtime.visible", "commands.list", "usage.read",
            "artifacts.list", "projects.list", "project.read", "assistants.list", "agents.list",
            "providers.list", "mcp.list", "skills.list", "cron.list", "teams.list", "adapters.list",
            "events.subscribe", "events.unsubscribe", "runtime.snapshot", "draft.load", "draft.save",
            "attachment.begin", "attachment.chunk", "attachment.finish", "attachment.abort",
        )
        private val DEFAULT_CONVERSATION_ACTIONS = setOf("events.subscribe", "events.unsubscribe", "runtime.snapshot", "draft.load", "draft.save", "attachment.begin", "attachment.chunk", "attachment.finish", "attachment.abort")
        private val CONVERSATION_ACTIONS = ACTIONS.filter { action ->
            action.substringBefore('.') in setOf("conversation", "messages", "runtime", "config", "turn", "approvals", "commands", "usage", "artifacts", "draft", "attachment", "events")
        }.toSet()

        fun isOrigin(value: String): Boolean = try {
            val uri = URI(value)
            uri.scheme == "https" && uri.host == "appassets.androidplatform.net" &&
                uri.port in setOf(-1, 443) && uri.rawUserInfo == null &&
                uri.rawPath.isNullOrEmpty() && uri.rawQuery == null && uri.rawFragment == null
        } catch (_: Exception) {
            false
        }

        fun isEntry(value: String): Boolean = try {
            val uri = URI(value)
            isOrigin("${uri.scheme}://${uri.rawAuthority}") && uri.rawPath == "/assets/renderer/index.html" && uri.rawQuery == null
        } catch (_: Exception) {
            false
        }
    }
}
