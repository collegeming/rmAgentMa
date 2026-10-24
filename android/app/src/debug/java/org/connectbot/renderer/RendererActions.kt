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

import org.connectbot.agent.AgentConfigAccess
import org.connectbot.agent.AgentConfigBusyException
import org.json.JSONArray
import org.json.JSONObject
import org.rmagentma.rendererhost.ProbeFailure
import org.rmagentma.rendererhost.ProbeLimits
import org.rmagentma.rendererhost.ProbeResult
import org.rmagentma.rendererhost.json

/**
 * The renderer's action layer over real product state. Every route either answers from a repository that
 * already holds the fact, or fails with a stable code: a route this build cannot serve reports
 * `501 <NAME>_UNSUPPORTED` with the reason in `error`, while a route it serves reports the real conflict
 * (not ready, not pending, stale turn). Nothing here fabricates chat history, approvals, drafts, usage or
 * settings.
 */
internal class RendererActions(private val source: RendererSource) {
    suspend fun handle(action: String, payload: JSONObject): ProbeResult {
        val params = payload.optJSONObject("params") ?: JSONObject()
        val query = payload.optJSONObject("query") ?: JSONObject()
        val body = payload.optJSONObject("body") ?: JSONObject()
        return try {
            dispatch(action, params, query, body)
        } catch (failure: ProbeFailure) {
            fail(failure.status, failure.code, failure.code)
        } catch (error: Exception) {
            fail(500, "HANDLE_FAILED", "无法处理该请求：${error.message ?: error.javaClass.simpleName}")
        }
    }

    private suspend fun dispatch(action: String, params: JSONObject, query: JSONObject, body: JSONObject): ProbeResult = when (action) {
        "auth.read" -> auth()
        "settings.client.read" -> success(json(THEME_KEY to source.snapshot().theme))
        "settings.client.write" -> writeSettings(body)
        "conversations.list" -> conversations()
        "conversation.read" -> conversation(params)
        "messages.list" -> messages(query)
        "messages.latest" -> latest(query)
        "messages.send" -> send(body)
        "turn.cancel" -> cancel(body)
        "approvals.list" -> approvals()
        "approvals.review" -> review(params, body)
        "runtime.ensure" -> ensure()
        "runtime.snapshot" -> snapshot()
        "config.read" -> readConfig(params)
        "config.set" -> setConfig(params, body)
        "draft.load" -> loadDraft(params)
        "draft.save" -> saveDraft(params, body)
        "usage.read" -> usage()
        "projects.list" -> success(RendererProjection.projects(source.snapshot().hosts))
        "project.read" -> readProject(params)
        "assistants.list" -> success(RendererProjection.assistants(source.snapshot().availability))
        "agents.list" -> success(RendererProjection.agents(source.snapshot().availability))
        "adapters.list" -> success(RendererProjection.adapters(source.snapshot().availability))
        else -> unsupported(action)
    }

    /** Identity of the host behind the current conversation: nickname and user name only. */
    private suspend fun auth(): ProbeResult {
        val snapshot = source.snapshot()
        if (snapshot.session == null) return fail(409, "HOST_NOT_CONNECTED", "尚未打开任何主机会话，无法确定身份。")
        val host = snapshot.host ?: return fail(409, "HOST_NOT_CONNECTED", "该会话的主机已被删除。")
        if (host.username.isBlank()) return fail(409, "HOST_NOT_CONNECTED", "该主机未记录登录用户名。")
        return ProbeResult(
            body = json(
                "success" to true,
                // Only the saved login name leaves the host; no key, password or agent credential is read here.
                "user" to json("id" to "host-${host.id}", "username" to host.username),
            ),
        )
    }

    private suspend fun writeSettings(body: JSONObject): ProbeResult {
        val updates = body.optJSONObject("settings") ?: body
        val keys = updates.keys().asSequence().toList()
        if (keys.isEmpty() || keys.any { it != THEME_KEY }) {
            return fail(422, "SETTING_DENIED", "只有产品主题（$THEME_KEY）可以写入。")
        }
        val theme = updates.optString(THEME_KEY)
        if (theme !in THEME_VALUES) return fail(422, "SETTING_DENIED", "主题只能是 light、dark 或 system。")
        source.setTheme(theme)
        return success(json(THEME_KEY to theme))
    }

    private suspend fun conversations(): ProbeResult {
        val snapshot = source.snapshot()
        val items = JSONArray()
        val session = snapshot.session
        if (session != null) {
            val pending = snapshot.pending()
            items.put(RendererProjection.conversation(snapshot, pending, RendererProjection.runtime(snapshot, pending)))
        }
        return success(json("items" to items, "total" to items.length(), "has_more" to false))
    }

    private suspend fun conversation(params: JSONObject): ProbeResult {
        if (params.optString("conversationId") != RENDERER_CONVERSATION) return notFound()
        val snapshot = source.snapshot()
        if (snapshot.session == null) return notFound()
        val pending = snapshot.pending()
        return success(RendererProjection.conversation(snapshot, pending, RendererProjection.runtime(snapshot, pending)))
    }

    private suspend fun messages(query: JSONObject): ProbeResult {
        val snapshot = source.snapshot()
        if (snapshot.session == null) return notFound()
        val limit = if (query.has("limit")) query.optString("limit").toIntOrNull() ?: -1 else DEFAULT_PAGE
        if (limit !in 1..MAX_PAGE) return fail(422, "INVALID_CURSOR", "limit 必须是 1..$MAX_PAGE 的整数。")
        val items = RendererProjection.messages(snapshot.events, snapshot.epoch, snapshot.turnId)
        val before = query.optString("before").ifEmpty { query.optString("anchor_message_id") }.ifEmpty { null }
        val after = query.optString("after").ifEmpty { null }
        // A cursor this window does not hold means the page cannot be served honestly from here.
        if (before != null && items.none { it.optString("id") == before }) return fail(422, "INVALID_CURSOR", "游标不在当前已加载的历史中。")
        if (after != null && items.none { it.optString("id") == after }) return fail(422, "INVALID_CURSOR", "游标不在当前已加载的历史中。")
        return success(RendererProjection.page(items, limit, before, after, snapshot.hasEarlier))
    }

    private suspend fun latest(query: JSONObject): ProbeResult {
        val snapshot = source.snapshot()
        if (snapshot.session == null) return notFound()
        val items = RendererProjection.messages(snapshot.events, snapshot.epoch, snapshot.turnId)
        return success(RendererProjection.latest(items, query.optString("type").ifEmpty { null }))
    }

    private suspend fun send(body: JSONObject): ProbeResult {
        val snapshot = source.snapshot()
        if (snapshot.session == null) return notFound()
        if (!snapshot.active) return fail(409, "CONVERSATION_NOT_READY", "会话尚未打开或不处于可发送状态。")
        if (snapshot.turnId != null) return fail(409, "TURN_BUSY", "上一个回合尚未结束。")
        if (snapshot.pending() != null) return fail(409, "APPROVAL_PENDING", "有审批请求待回答，暂不能发送。")
        if ((body.optJSONArray("files")?.length() ?: 0) > 0) return fail(422, "ATTACHMENT_UNSUPPORTED", "此版本只发送文本。")
        if ((body.optJSONArray("sessions")?.length() ?: 0) > 0) return fail(422, "SESSION_REFERENCES_UNSUPPORTED", "此版本不支持引用其它会话。")
        if ((body.optJSONArray("inject_skills")?.length() ?: 0) > 0) return fail(422, "SKILLS_UNSUPPORTED", "此版本不支持注入技能。")
        val content = body.opt("content")
        if (content !is String || content.isBlank() || content.length > ProbeLimits.CONTENT_UNITS || content.toByteArray(Charsets.UTF_8).size > ProbeLimits.CONTENT_BYTES) {
            return fail(422, "INVALID_CONTENT", "消息内容为空或超出长度限制。")
        }
        val messageId = source.send(content) ?: return fail(409, "TURN_BUSY", "会话正在处理其它操作，请稍后重试。")
        val accepted = source.snapshot()
        return ProbeResult(
            202,
            json(
                "success" to true,
                "data" to json(
                    "msg_id" to messageId,
                    "turn_id" to accepted.turnId,
                    "runtime" to RendererProjection.runtime(accepted, accepted.pending()),
                ),
            ),
        )
    }

    private suspend fun cancel(body: JSONObject): ProbeResult {
        val snapshot = source.snapshot()
        if (snapshot.session == null) return notFound()
        if (!snapshot.active) return fail(409, "CONVERSATION_NOT_READY", "会话尚未打开。")
        val turn = snapshot.turnId ?: return fail(409, "TURN_NOT_ACTIVE", "当前没有正在进行的回合。")
        val requested = body.optString("turn_id")
        if (requested.isNotEmpty() && requested != turn) return fail(409, "STALE_TURN", "该回合已不是当前回合。")
        source.cancel()
        val cancelled = source.snapshot()
        return success(json("runtime" to RendererProjection.runtime(cancelled, cancelled.pending()), "turn_id" to turn))
    }

    private suspend fun approvals(): ProbeResult {
        val snapshot = source.snapshot()
        if (snapshot.session == null) return notFound()
        val pending = JSONArray()
        snapshot.pending()?.let { pending.put(RendererProjection.permission(it, snapshot.epoch, snapshot.turnId)) }
        return success(pending)
    }

    /**
     * Answers the live approval. The repository itself re-checks that this event is still the pending instance of
     * the selected session, so a historical or already-answered card can never become actionable here.
     */
    private suspend fun review(params: JSONObject, body: JSONObject): ProbeResult {
        val snapshot = source.snapshot()
        if (snapshot.session == null) return notFound()
        val event = snapshot.pending()
        val reference = params.optString("requestRef")
        if (reference.isEmpty() || event == null || event.id != reference || !snapshot.active) {
            return fail(409, "NOT_PENDING", "该审批请求不再是当前挂起项。")
        }
        val epoch = body.optString("epoch")
        if (epoch.isNotEmpty() && epoch != snapshot.epoch) return fail(409, "STALE_APPROVAL", "运行代次已变化，请重新读取。")
        val option = body.optString("data").ifEmpty { body.optString("option_id") }
        if (option.isEmpty() || event.options.none { it.id == option }) {
            return fail(422, "OPTION_NOT_ALLOWED", "该选项不属于此审批请求。")
        }
        if (!source.respondPermission(event, option)) return fail(409, "NOT_PENDING", "该审批请求已失效。")
        return success(json("state" to "answered", "request_id" to event.id, "option_id" to option))
    }

    /**
     * The product opens a conversation, never the renderer: this answers only for the session that is already
     * open, so no second writer is ever attached to the same ACP session.
     */
    private suspend fun ensure(): ProbeResult {
        val snapshot = source.snapshot()
        if (snapshot.session == null) return notFound()
        if (!snapshot.active && !snapshot.loading) {
            return fail(409, "CONVERSATION_NOT_OPEN", "该会话未打开；请在产品对话页打开后重试。")
        }
        return success(
            json(
                "recovered" to false,
                "config_options" to catalogue(snapshot),
                "runtime" to RendererProjection.runtime(snapshot, snapshot.pending()),
            ),
        )
    }

    private suspend fun snapshot(): ProbeResult {
        val snapshot = source.snapshot()
        if (snapshot.session == null) return notFound()
        val pending = JSONArray()
        snapshot.pending()?.let { pending.put(RendererProjection.permission(it, snapshot.epoch, snapshot.turnId)) }
        return ProbeResult(
            body = json(
                "epoch" to snapshot.epoch,
                "seq" to snapshot.seq,
                "conversation_id" to RENDERER_CONVERSATION,
                "runtime" to RendererProjection.runtime(snapshot, snapshot.pending()),
                "messages" to JSONArray(RendererProjection.messages(snapshot.events, snapshot.epoch, snapshot.turnId)),
                "pending" to pending,
                "config_options" to catalogue(snapshot),
                // Background execution is not implemented on this host and must not look verified.
                "backgroundVerified" to false,
            ),
        )
    }

    /**
     * The Agent's own configuration catalogue for the open conversation. A conversation that is not open yet has
     * none to publish, so this reports an empty list instead of the choices of whichever session came before it.
     */
    private suspend fun catalogue(snapshot: RendererSnapshot): JSONArray {
        if (!snapshot.active) return JSONArray()
        val access = source.config()
        return if (access is AgentConfigAccess.Available) RendererProjection.configOptions(access.config) else JSONArray()
    }

    /**
     * Reads the open conversation's configuration. The catalogue is whatever the Agent reported, including option
     * types this host cannot draw, and it is only served while that same conversation is open.
     */
    private suspend fun readConfig(params: JSONObject): ProbeResult {
        if (params.optString("conversationId") != RENDERER_CONVERSATION) return notFound()
        val snapshot = source.snapshot()
        if (snapshot.session == null) return notFound()
        if (!snapshot.active) return fail(409, "CONVERSATION_NOT_OPEN", "该会话尚未就绪；请在产品对话页打开后重试。")
        return when (val access = source.config()) {
            is AgentConfigAccess.Available -> success(json("config_options" to RendererProjection.configOptions(access.config)))
            AgentConfigAccess.Unsupported -> fail(501, "CONFIG_UNSUPPORTED", "该会话的 Agent 没有可读的模型或配置选项。")
            else -> fail(409, "CONVERSATION_NOT_OPEN", "该会话在读取期间已关闭。")
        }
    }

    /**
     * Sets one option of the catalogue this host published, and answers with the Agent's own confirmation. A value
     * the Agent never advertised is refused before anything is sent, and a change the Agent took without reporting
     * a snapshot is answered as `command_ack` rather than as applied.
     */
    private suspend fun setConfig(params: JSONObject, body: JSONObject): ProbeResult {
        if (params.optString("conversationId") != RENDERER_CONVERSATION) return notFound()
        val optionId = params.optString("optionId")
        val value = body.opt("value")
        if (value !is String || value.isEmpty()) return fail(422, "INVALID_CONFIG", "配置值必须是非空字符串。")
        val snapshot = source.snapshot()
        if (snapshot.session == null) return notFound()
        if (!snapshot.active) return fail(409, "CONVERSATION_NOT_READY", "会话尚未打开，暂时不能切换配置。")
        val published = when (val access = source.config()) {
            is AgentConfigAccess.Available -> RendererProjection.configOptions(access.config)
            AgentConfigAccess.Unsupported -> return fail(501, "CONFIG_UNSUPPORTED", "该会话的 Agent 没有可切换的模型或配置选项。")
            else -> return fail(409, "CONVERSATION_NOT_READY", "该会话在写入期间已关闭。")
        }
        val option = option(published, optionId) ?: return fail(404, "OPTION_NOT_FOUND", "该会话没有这个配置选项。")
        val choices = option.optJSONArray("options") ?: JSONArray()
        if (choices.length() > 0 && (0 until choices.length()).none { choices.getJSONObject(it).optString("value") == value }) {
            return fail(422, "INVALID_CONFIG", "该配置选项不接受这个值。")
        }
        val result = try {
            source.setConfigOption(optionId, value)
        } catch (busy: AgentConfigBusyException) {
            // A busy Agent refused the write; retrying is the only honest answer, not a generic failure.
            return fail(409, "CONFIG_BUSY", "Agent 正在处理其它更新，配置尚未生效，请稍后重试。")
        }
        return when (result) {
            is AgentConfigAccess.Available -> {
                val updated = RendererProjection.configOptions(result.config)
                success(json("confirmation" to confirmation(updated, optionId, value), "config_options" to updated))
            }

            // The agent answered the write, so it was delivered; without a snapshot no value can be claimed.
            is AgentConfigAccess.Unconfirmed -> success(
                json("confirmation" to "command_ack", "config_options" to result.config?.let(RendererProjection::configOptions)),
            )

            AgentConfigAccess.Unsupported -> fail(501, "CONFIG_UNSUPPORTED", "该会话的 Agent 没有可切换的模型或配置选项。")

            else -> fail(409, "CONVERSATION_NOT_READY", "该会话已关闭或已切换，配置未在此会话生效。")
        }
    }

    /** `observed` only when the Agent's own new catalogue reports the requested value in use. */
    private fun confirmation(catalog: JSONArray, optionId: String, value: String): String {
        val current = option(catalog, optionId)?.opt("current_value") as? String
        return if (current == value) "observed" else "pending_next_turn"
    }

    private fun option(catalog: JSONArray, optionId: String): JSONObject? = (0 until catalog.length()).map { catalog.getJSONObject(it) }.firstOrNull { it.optString("id") == optionId }

    /**
     * The context usage the Agent itself reported in the loaded window, and nothing else. `data` stays null until
     * an agent sends a usage report, because a token count invented on this host would be a lie about the session.
     */
    private suspend fun usage(): ProbeResult {
        val snapshot = source.snapshot()
        if (snapshot.session == null) return notFound()
        val reported = snapshot.events.lastOrNull { it.type == "usage" }
        return success(RendererProjection.usage(reported))
    }

    /**
     * Reads back the draft the user left in the renderer's send box. Only the open conversation answers here: an
     * empty draft for a conversation this host has not opened would wipe the text the user really typed.
     */
    private suspend fun loadDraft(params: JSONObject): ProbeResult {
        if (params.optString("conversationId") != RENDERER_CONVERSATION) return notFound()
        val snapshot = source.snapshot()
        val session = snapshot.session ?: return notFound()
        if (!snapshot.active) return fail(409, "CONVERSATION_NOT_OPEN", "该会话尚未打开；请在产品对话页打开后重试。")
        return success(RendererProjection.draft(source.draft(RendererProjection.draftKey(session)) ?: ""))
    }

    /**
     * Stores the send box's text for the open conversation. Text over the draft limit is refused rather than cut,
     * and an attachment reference is refused too: this host issues no upload reference, so one could only name a
     * file it never accepted.
     */
    private suspend fun saveDraft(params: JSONObject, body: JSONObject): ProbeResult {
        if (params.optString("conversationId") != RENDERER_CONVERSATION) return notFound()
        val snapshot = source.snapshot()
        val session = snapshot.session ?: return notFound()
        if (!snapshot.active) return fail(409, "CONVERSATION_NOT_OPEN", "该会话尚未打开；请在产品对话页打开后重试。")
        if ((body.optJSONArray("files")?.length() ?: 0) > 0) return fail(422, "ATTACHMENT_UNSUPPORTED", "此版本只保存文本草稿。")
        val text = body.opt("text")
        if (text !is String) return fail(422, "DRAFT_INVALID", "草稿内容必须是字符串。")
        if (text.length > ProbeLimits.DRAFT_UNITS || text.toByteArray(Charsets.UTF_8).size > ProbeLimits.DRAFT_BYTES) {
            return fail(413, "DRAFT_TOO_LARGE", "草稿超出长度限制，请缩短后再保存。")
        }
        source.saveDraft(RendererProjection.draftKey(session), text)
        return success(RendererProjection.draft(text).put("saved", true))
    }

    private suspend fun readProject(params: JSONObject): ProbeResult {
        // The renderer requests the conversation's `project_id`, which this host maps to the saved host behind it.
        val id = params.optString("projectId")
        val host = source.snapshot().hosts.firstOrNull { RendererProjection.projectId(it.id) == id }
        return if (host == null) fail(404, "PROJECT_NOT_FOUND", "没有这个项目。") else success(RendererProjection.project(host))
    }

    private fun unsupported(action: String): ProbeResult = when (action) {
        "runtime.visible" -> fail(501, "LEASE_UNSUPPORTED", "本机不支持会话可见租约。")
        "attachment.begin", "attachment.chunk", "attachment.finish", "attachment.abort" -> fail(501, "ATTACHMENT_UNSUPPORTED", "本机未开放附件上传。")
        "commands.list" -> fail(501, "COMMANDS_UNSUPPORTED", "本机未向渲染器提供斜杠命令目录。")
        "artifacts.list" -> fail(501, "ARTIFACTS_UNSUPPORTED", "本机没有会话产物列表。")
        "providers.list" -> fail(501, "PROVIDERS_UNSUPPORTED", "本机不向渲染器提供模型供应商目录。")
        "mcp.list" -> fail(501, "MCP_UNSUPPORTED", "本机不支持 MCP 服务器管理。")
        "skills.list" -> fail(501, "SKILLS_UNSUPPORTED", "本机不支持技能管理。")
        "cron.list" -> fail(501, "CRON_UNSUPPORTED", "本机不支持定时任务。")
        "teams.list" -> fail(501, "TEAMS_UNSUPPORTED", "本机不支持团队模式。")
        "settings.read" -> fail(501, "SETTINGS_UNSUPPORTED", "本机只有客户端设置（主题），没有服务端设置。")
        else -> fail(404, "UNKNOWN_ACTION", "未知动作：$action")
    }

    private fun notFound(): ProbeResult = fail(404, "CONVERSATION_NOT_FOUND", "当前没有可用的会话。")

    private fun success(data: Any?): ProbeResult = ProbeResult(body = json("success" to true, "data" to data))

    private fun fail(status: Int, code: String, reason: String): ProbeResult = ProbeResult(status, json("success" to false, "code" to code, "error" to reason))

    private companion object {
        const val THEME_KEY = "theme.activeId"
        val THEME_VALUES = setOf("light", "dark", "system")
        const val DEFAULT_PAGE = 50
        const val MAX_PAGE = 200
    }
}
