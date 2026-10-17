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

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal class ProbeStore(file: File, private val schedule: (Long, () -> Unit) -> Unit) {
    private val persistence = AtomicFile(file)
    val epoch: String = UUID.randomUUID().toString()
    var seq = 0L
        private set
    var listener: ((String, Any?, String, Long) -> Unit)? = null
    private var model = "probe-model-a"
    private var draft = ""
    private var draftFiles = JSONArray()
    private var turn = ""
    private var phase = "idle"
    private var messages = JSONArray()
    private var pending: JSONObject? = null
    private var ledger = JSONObject()
    private val events = ArrayDeque<JSONObject>()
    private var settings = json(
        "language" to "zh-CN",
        "theme" to "light",
        "colorScheme" to "default",
        "notificationEnabled" to false,
        "cronNotificationEnabled" to false,
        "keepAwake" to false,
        "saveUploadToWorkspace" to false,
    )

    init {
        if (file.exists() || File("${file.path}.bak").exists()) {
            val saved = persistence.openRead().use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
            model = saved.getString("model")
            draft = saved.getString("draft")
            draftFiles = saved.optJSONArray("draftFiles") ?: JSONArray()
            messages = saved.getJSONArray("messages")
            ledger = saved.getJSONObject("ledger")
            settings = saved.getJSONObject("settings")
            phase = if (saved.getString("phase") in setOf("running", "awaiting_approval")) "interrupted" else "idle"
            saved.optJSONObject("pending")?.let { old ->
                replaceMessage(message("acp_permission", old.put("live", false).put("readonly", true).put("state", "invalidated"), old.optString("msg_id", "historic-${UUID.randomUUID()}")))
            }
            for (index in 0 until messages.length()) messages.put(index, readonly(messages.getJSONObject(index)))
        } else {
            messages.put(message("text", json("content" to "这是隔离的 Android 宿主探针。全部内容为合成数据，不连接模型或 SSH。"), "welcome"))
            messages.put(
                message(
                    "acp_permission",
                    json(
                        "request_id" to "historical-request",
                        "live" to false,
                        "readonly" to true,
                        "state" to "sent",
                        "session_id" to CONVERSATION,
                        "epoch" to "historical",
                        "tool_call" to json("tool_call_id" to "historic-tool", "title" to "历史审批（只读）"),
                        "options" to JSONArray(),
                    ),
                    "historic-permission",
                ),
            )
        }
    }

    fun handle(action: String, payload: JSONObject): ProbeResult {
        val params = payload.getJSONObject("params")
        val query = payload.getJSONObject("query")
        val body = payload.optJSONObject("body") ?: JSONObject()
        if (action == "auth.read") return ProbeResult(body = json("success" to true, "user" to json("id" to "probe-user-1", "username" to "offline-probe")))
        val result = when (action) {
            "settings.client.read", "settings.read" -> ProbeResult(body = settings)

            "settings.client.write" -> {
                val updates = body.optJSONObject("settings") ?: body
                requireProbe(updates.keys().asSequence().all { settings.has(it) })
                if (updates.has("language")) requireProbe(updates.optString("language") == "zh-CN")
                if (updates.has("theme")) requireProbe(updates.optString("theme") in setOf("light", "dark", "system"))
                requireProbe(
                    updates.keys().asSequence().all { key ->
                        when (key) {
                            "language", "theme" -> updates.opt(key) is String
                            "colorScheme" -> updates.optString(key) == "default"
                            else -> updates.opt(key) is Boolean && updates.opt(key) == false
                        }
                    },
                    422,
                    "SETTING_DENIED",
                )
                updates.keys().forEach { settings.put(it, updates.get(it)) }
                persist()
                ProbeResult(body = settings)
            }

            "conversations.list" -> ProbeResult(body = json("items" to array(conversation()), "total" to 1, "has_more" to false))

            "conversation.read" -> ProbeResult(body = conversation())

            "projects.list" -> ProbeResult(body = array(project()))

            "project.read" -> ProbeResult(body = project())

            "messages.list" -> ProbeResult(body = page(query))

            "messages.latest" -> {
                val latest = (messages.length() - 1 downTo 0).firstOrNull { messages.getJSONObject(it).optString("type") == query.optString("type") }
                ProbeResult(body = latest?.let { readonly(messages.getJSONObject(it)) })
            }

            "runtime.ensure" -> ProbeResult(body = json("recovered" to (phase == "interrupted"), "config_options" to config(), "runtime" to runtime()))

            "config.read" -> ProbeResult(body = json("config_options" to config()))

            "config.set" -> {
                requireProbe(params.optString("optionId") == "model", 404, "OPTION_NOT_FOUND")
                requireProbe(phase !in setOf("running", "awaiting_approval"), 409, "TURN_BUSY")
                val value = body.string("value")
                requireProbe(value in setOf("probe-model-a", "probe-model-b"), 422, "MODEL_NOT_ALLOWED")
                model = value
                persist()
                emit("acp_config_option", json("config_options" to config()))
                ProbeResult(body = json("confirmation" to "observed", "config_options" to config()))
            }

            "messages.send" -> send(body)

            "turn.cancel" -> {
                requireProbe(body.optString("turn_id") == turn && turn.isNotEmpty(), 409, "STALE_TURN")
                requireProbe(phase in setOf("running", "awaiting_approval"), 409, "TURN_NOT_ACTIVE")
                val reference = pending?.optString("request_id")
                pending?.let { old -> replaceMessage(message("acp_permission", old.put("live", false).put("readonly", true).put("state", "invalidated"), old.getString("msg_id"))) }
                pending = null
                phase = "idle"
                persist()
                publish("confirmation.remove", json("conversation_id" to CONVERSATION, "id" to reference, "request_id" to reference, "call_id" to reference, "epoch" to epoch))
                emit("finish", json("stop_reason" to "cancelled"))
                publish("turn.completed", json("conversation_id" to CONVERSATION, "session_id" to CONVERSATION, "turn_id" to turn, "status" to "finished", "outcome" to "cancelled", "state" to "stopped", "runtime" to runtime()))
                ProbeResult(body = json("runtime" to runtime(), "turn_id" to turn))
            }

            "approvals.list" -> ProbeResult(body = pending?.let { array(it) } ?: JSONArray())

            "approvals.review" -> {
                val current = approval(params.string("requestRef"), body.string("epoch"))
                requireProbe(!body.optBoolean("always_allow"), 422, "OPTION_NOT_ALLOWED")
                val candidate = body.optString("data", body.optString("option_id"))
                requireProbe(candidate in setOf("allow-once", "reject-once"), 422, "OPTION_NOT_ALLOWED")
                ProbeResult(202, json("state" to "native_review_required", "request_id" to current.getString("request_id")))
            }

            "runtime.snapshot", "events.subscribe" -> {
                val snapshot = snapshot().put("seq", seq + 1)
                publish("runtime.snapshot", snapshot)
                ProbeResult(body = snapshot)
            }

            "events.unsubscribe", "runtime.visible" -> ProbeResult(body = json("runtime" to runtime(), "expires_at" to System.currentTimeMillis() + 60000))

            "draft.load" -> ProbeResult(body = json("text" to draft, "files" to draftFiles, "conversation_id" to CONVERSATION))

            "draft.save" -> {
                val text = body.opt("text")
                requireProbe(text is String && text.length <= ProbeLimits.DRAFT_UNITS && text.toByteArray(Charsets.UTF_8).size <= ProbeLimits.DRAFT_BYTES, 413, "DRAFT_TOO_LARGE")
                requireProbe(!body.has("files") || body.optJSONArray("files") != null, 422, "INVALID_ATTACHMENT")
                val files = body.optJSONArray("files") ?: draftFiles
                validateFileShape(files)
                draft = text as String
                draftFiles = JSONArray(files.toString())
                persist()
                ProbeResult(body = json("text" to draft, "files" to draftFiles, "saved" to true))
            }

            "usage.read" -> ProbeResult(body = null)

            "commands.list", "artifacts.list", "assistants.list", "agents.list", "providers.list", "mcp.list", "skills.list", "cron.list", "teams.list", "adapters.list" -> ProbeResult(body = JSONArray())

            else -> throw ProbeFailure(404, "UNKNOWN_ACTION")
        }
        return if (action in setOf("events.subscribe", "events.unsubscribe", "runtime.snapshot", "draft.load", "draft.save")) result else result.copy(body = json("success" to true, "data" to result.body))
    }

    private fun validateFileShape(files: JSONArray) {
        requireProbe(files.length() <= 4, 413, "UPLOAD_LIMIT")
        for (index in 0 until files.length()) {
            val reference = files.optJSONObject(index) ?: throw ProbeFailure(422, "INVALID_ATTACHMENT")
            requireProbe(reference.optString("kind") == "upload" && reference.optString("path").matches(Regex("probe-upload:[a-f0-9-]{36}")), 422, "ATTACHMENT_SOURCE_NOT_ALLOWED")
        }
    }

    private fun readonly(source: JSONObject): JSONObject = JSONObject(source.toString()).apply {
        if (optString("type") == "acp_permission") {
            getJSONObject("content").put("readonly", true).put("live", false)
            put("status", "finish")
        }
    }

    private fun page(query: JSONObject): JSONObject {
        val limit = query.optString("limit", "50").toIntOrNull()
        requireProbe(limit != null && limit in 1..200, 422, "INVALID_CURSOR")
        val before = query.optString("before")
        val end = if (before.isEmpty()) messages.length() else (0 until messages.length()).firstOrNull { messages.getJSONObject(it).optString("id") == before } ?: -1
        requireProbe(end >= 0, 422, "INVALID_CURSOR")
        val start = maxOf(0, end - limit!!)
        val items = JSONArray()
        for (index in start until end) items.put(readonly(messages.getJSONObject(index)))
        return json("items" to items, "oldest_cursor" to items.optJSONObject(0)?.optString("id"), "newest_cursor" to items.optJSONObject(items.length() - 1)?.optString("id"), "has_more_before" to (start > 0), "has_more_after" to (end < messages.length()))
    }

    private fun send(body: JSONObject): ProbeResult {
        val content = body.string("content", ProbeLimits.CONTENT_UNITS)
        requireProbe(content.toByteArray(Charsets.UTF_8).size <= ProbeLimits.CONTENT_BYTES, 413, "CONTENT_TOO_LARGE")
        requireProbe(content.isNotBlank(), 422, "INVALID_CONTENT")
        requireProbe(body.optJSONArray("inject_skills").let { it == null || it.length() == 0 }, 422, "SKILLS_UNSUPPORTED")
        val loadingId = body.string("loading_id", 128)
        val fingerprint = json("content" to content, "files" to (body.optJSONArray("files") ?: JSONArray()), "sessions" to (body.optJSONArray("sessions") ?: JSONArray())).toString()
        val old = ledger.optJSONObject(loadingId)
        if (old != null) {
            requireProbe(old.getString("fingerprint") == fingerprint, 409, "SEND_ID_CONFLICT")
            return ProbeResult(body = old.getJSONObject("result"))
        }
        requireProbe(ledger.length() < 512, 429, "PROBE_SEND_LIMIT")
        requireProbe(messages.length() < 2048, 413, "PROBE_HISTORY_LIMIT")
        requireProbe(phase !in setOf("running", "awaiting_approval"), 409, "TURN_BUSY")
        val files = body.optJSONArray("files") ?: JSONArray()
        validateFileShape(files)
        requireProbe((body.optJSONArray("sessions")?.length() ?: 0) == 0, 422, "SESSION_REFERENCES_UNSUPPORTED")
        turn = UUID.randomUUID().toString()
        val currentTurn = turn
        phase = "running"
        val msgId = UUID.randomUUID().toString()
        messages.put(message("text", json("content" to content, "files" to files), msgId, "user"))
        val result = json("msg_id" to msgId, "turn_id" to turn, "runtime" to runtime())
        ledger.put(loadingId, json("fingerprint" to fingerprint, "result" to result))
        persist()
        publish("message.userCreated", json("conversation_id" to CONVERSATION, "msg_id" to msgId, "client_msg_id" to loadingId, "turn_id" to turn, "content" to content, "position" to "right", "status" to "finish", "hidden" to false, "created_at" to System.currentTimeMillis()))
        emit("start", json("turn_id" to turn))
        schedule(150) { if (turn == currentTurn && phase == "running") textChunk("中文分块：", "answer-$currentTurn") }
        schedule(350) {
            if (turn == currentTurn && phase == "running") {
                textChunk("宿主已收到合成任务。", "answer-$currentTurn")
                val tool = json(
                    "tool_call_id" to "tool-$turn",
                    "title" to "合成文件改动",
                    "kind" to "edit",
                    "status" to "pending",
                    "content" to array(json("type" to "diff", "path" to "探针.txt", "old_text" to "旧内容\n", "new_text" to "中文新内容\n")),
                    "raw_input" to json("path" to "探针.txt"),
                    "sessionUpdate" to "tool_call",
                )
                val toolContent = json(
                    "session_id" to CONVERSATION,
                    "update" to JSONObject(tool.toString()).apply {
                        put("rawInput", remove("raw_input"))
                    },
                )
                messages.put(message("acp_tool_call", toolContent, "tool-$turn"))
                emit("acp_tool_call", toolContent, "tool-$turn")
                val requestId = UUID.randomUUID().toString()
                val permissionTool = json(
                    "tool_call_id" to tool.getString("tool_call_id"),
                    "title" to tool.getString("title"),
                    "kind" to tool.getString("kind"),
                    "raw_input" to tool.getJSONObject("raw_input"),
                    "content" to tool.getJSONArray("content"),
                )
                pending = json(
                    "session_id" to CONVERSATION, "request_id" to requestId, "epoch" to epoch,
                    "conversation_id" to CONVERSATION, "tool_call_id" to "tool-$turn", "tool_call" to permissionTool,
                    "id" to requestId, "call_id" to requestId, "title" to tool.getString("title"),
                    "description" to "只更新隔离合成状态，不写用户文件", "turn_id" to turn,
                    "live" to true, "state" to "waiting", "msg_id" to "permission-$turn",
                    "options" to array(
                        json("option_id" to "allow-once", "kind" to "allow_once", "name" to "允许本次"),
                        json("option_id" to "reject-once", "kind" to "reject_once", "name" to "拒绝本次"),
                    ),
                )
                messages.put(message("acp_permission", pending, "permission-$turn"))
                phase = "awaiting_approval"
                persist()
                emit("acp_permission", pending, "permission-$turn")
            }
        }
        return ProbeResult(202, result)
    }

    fun approval(reference: String, expectedEpoch: String): JSONObject {
        val current = pending
        requireProbe(current != null && current.optString("request_id") == reference && expectedEpoch == epoch && phase == "awaiting_approval", 409, "NOT_PENDING")
        return JSONObject(current.toString())
    }

    fun decide(reference: String, expectedEpoch: String, option: String): ProbeResult {
        val current = approval(reference, expectedEpoch)
        requireProbe(option in setOf("allow-once", "reject-once"), 422, "OPTION_NOT_ALLOWED")
        pending = null
        phase = "idle"
        current.put("live", false).put("readonly", true).put("state", "sent").put("selected", option)
        replaceMessage(message("acp_permission", current, "permission-$turn"))
        val tool = JSONObject(current.getJSONObject("tool_call").toString())
        tool.put("rawInput", tool.remove("raw_input"))
        tool.put("status", if (option == "allow-once") "completed" else "failed").put("sessionUpdate", "tool_call_update")
        val toolContent = json("session_id" to CONVERSATION, "update" to tool)
        replaceMessage(message("acp_tool_call", toolContent, "tool-$turn"))
        persist()
        publish("confirmation.remove", json("conversation_id" to CONVERSATION, "id" to reference, "request_id" to reference, "call_id" to reference, "epoch" to epoch))
        emit("acp_tool_call", toolContent, "tool-$turn")
        val answer = if (option == "allow-once") "原生已允许本次合成操作；未写入任何用户文件。" else "原生已拒绝，本次合成操作未执行。"
        textChunk(answer, "answer-$turn")
        emit("finish", json("stop_reason" to "end_turn"))
        publish("turn.completed", json("conversation_id" to CONVERSATION, "session_id" to CONVERSATION, "turn_id" to turn, "status" to "finished", "outcome" to "completed", "state" to "ai_waiting_input", "runtime" to runtime()))
        return ProbeResult(body = json("state" to "sent", "request_id" to reference))
    }

    private fun replaceMessage(message: JSONObject) {
        val index = (0 until messages.length()).firstOrNull { messages.getJSONObject(it).getString("id") == message.getString("id") }
        if (index == null) messages.put(message) else messages.put(index, message)
    }

    private fun textChunk(text: String, id: String) {
        val existing = (0 until messages.length()).firstOrNull { messages.getJSONObject(it).getString("id") == id }
        if (existing == null) {
            messages.put(message("text", json("content" to text), id))
        } else {
            val content = messages.getJSONObject(existing).getJSONObject("content")
            content.put("content", content.getString("content") + text)
        }
        emit("text", json("content" to text), id)
    }

    private fun emit(type: String, data: Any?, id: String = UUID.randomUUID().toString()) {
        publish("message.stream", json("type" to type, "data" to data, "conversation_id" to CONVERSATION, "msg_id" to id, "turn_id" to turn))
        if (type in setOf("start", "finish", "acp_permission", "acp_config_option")) publish("runtime.updated", json("conversation_id" to CONVERSATION, "runtime" to runtime()))
    }

    private fun publish(name: String, data: Any?) {
        seq++
        events.addLast(json("name" to name, "data" to data, "epoch" to epoch, "seq" to seq))
        if (events.size > 256) events.removeFirst()
        persist()
        listener?.invoke(name, data, epoch, seq)
    }

    fun snapshot(): JSONObject = json(
        "epoch" to epoch, "seq" to seq, "conversation_id" to CONVERSATION,
        "runtime" to runtime(), "messages" to JSONArray((0 until messages.length()).map { readonly(messages.getJSONObject(it)) }), "pending" to (pending?.let { array(it) } ?: JSONArray()),
        "draft" to json("text" to draft, "files" to draftFiles), "config_options" to config(),
        "backgroundVerified" to false,
    )

    private fun runtime(): JSONObject = json(
        "state" to (if (phase == "awaiting_approval") "waiting_confirmation" else phase),
        "can_send_message" to (phase == "idle" || phase == "interrupted"),
        "has_task" to (phase in setOf("running", "awaiting_approval")),
        "is_processing" to (phase in setOf("running", "awaiting_approval")),
        "pending_confirmations" to (if (pending == null) 0 else 1),
        "turn_id" to turn, "epoch" to epoch, "seq" to seq, "supports_midturn_delivery" to false,
    )

    private fun config(): JSONArray = array(
        json(
            "id" to "model",
            "name" to "探针模型",
            "category" to "model",
            "type" to "select",
            "current_value" to model,
            "options" to array(json("value" to "probe-model-a", "name" to "本地合成模型甲"), json("value" to "probe-model-b", "name" to "本地合成模型乙")),
        ),
    )

    private fun conversation(): JSONObject = json(
        "id" to CONVERSATION,
        "name" to "中文宿主探针",
        "type" to "acp",
        "project_id" to PROJECT,
        "created_at" to 1,
        "modified_at" to System.currentTimeMillis(),
        "model" to json("provider_id" to "fake", "use_model" to model, "model" to model),
        "status" to (if (phase in setOf("running", "awaiting_approval")) "running" else "finished"),
        "fork_capability" to json("at_turn" to false),
        "prompt_capability" to json("image" to false, "audio" to false),
        "extra" to json("workspace" to "/probe", "backend" to "dsh", "agent_name" to "dsh", "custom_workspace" to true, "cached_config_options" to config()),
        "runtime" to runtime(),
    )

    private fun project(): JSONObject = json("project_id" to PROJECT, "name" to "隔离探针项目", "explorer" to json("workspace_pe_id" to null, "entries" to JSONArray()))

    private fun message(type: String, content: Any?, id: String, position: String = "left"): JSONObject = json(
        "id" to id,
        "msg_id" to id,
        "conversation_id" to CONVERSATION,
        "type" to type,
        "content" to content,
        "position" to (if (position == "user") "right" else position),
        "created_at" to System.currentTimeMillis(),
        "status" to "finish",
        "turn_id" to turn,
    )

    private fun persist() {
        val saved = json("model" to model, "draft" to draft, "draftFiles" to draftFiles, "phase" to phase, "messages" to messages, "pending" to pending, "ledger" to ledger, "settings" to settings)
        val stream = persistence.startWrite()
        try {
            stream.write(saved.toString().toByteArray())
            persistence.finishWrite(stream)
        } catch (failure: Exception) {
            persistence.failWrite(stream)
            throw failure
        }
    }

    companion object {
        const val CONVERSATION = "conv-probe-1"
        const val PROJECT = "project-probe-1"
    }
}
