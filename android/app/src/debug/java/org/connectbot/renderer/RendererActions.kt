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

import kotlinx.coroutines.CancellationException
import org.connectbot.agent.AgentConfigAccess
import org.connectbot.agent.AgentConfigBusyException
import org.connectbot.data.entity.Host
import org.json.JSONArray
import org.json.JSONObject
import org.rmagentma.core.AGENT_TARGET_NATIVE
import org.rmagentma.core.AGENT_TARGET_WSL_PREFIX
import org.rmagentma.core.AgentSession
import org.rmagentma.core.HOST_OS_LINUX
import org.rmagentma.core.HOST_OS_WINDOWS
import org.rmagentma.core.remoteTargetOf
import org.rmagentma.core.validWslDistribution
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
        } catch (invalid: InvalidHost) {
            fail(422, "HOST_INVALID", invalid.reason)
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
        "messages.list" -> messages(query, params.optString("conversationId"))
        "messages.latest" -> latest(query, params.optString("conversationId"))
        "messages.send" -> send(body, params.optString("conversationId"))
        "turn.cancel" -> cancel(body, params.optString("conversationId"))
        "approvals.list" -> approvals(params.optString("conversationId"))
        "approvals.review" -> review(params, body)
        "runtime.ensure" -> ensure(params)
        "runtime.snapshot" -> snapshot()
        "config.read" -> readConfig(params)
        "config.set" -> setConfig(params, body)
        "draft.load" -> loadDraft(params)
        "draft.save" -> saveDraft(params, body)
        "usage.read" -> usage(params.optString("conversationId"))
        "projects.list" -> success(RendererProjection.projects(source.snapshot().hosts))
        "project.read" -> readProject(params)
        "hosts.list" -> listHosts()
        "hosts.create" -> createHost(body)
        "hosts.update" -> updateHost(params, body)
        "hosts.delete" -> deleteHost(params)
        "detection.refresh" -> refreshDetection(params)
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

    /**
     * The product's own index as the renderer's conversation list: one row per (host, agent, session), each with
     * the stable derived id a later `conversation.read` / `runtime.ensure` selects it by. The open conversation is
     * included even before the index has refreshed it, and a row whose host was deleted is not listed.
     */
    private suspend fun conversations(): ProbeResult {
        val snapshot = source.snapshot()
        val open = snapshot.session
        val hostIds = snapshot.hosts.map { it.id }.toSet()
        val rows = (snapshot.indexedSessions + listOfNotNull(open))
            // A row whose host was deleted is not offered; the open conversation stays listed even if the host
            // read failed, because opening it already proved that its host exists.
            .filter { it.sessionId.isNotBlank() && (it.hostId in hostIds || open?.sameConversation(it) == true) }
            .distinctBy { RendererConversationId.of(it) }
            .sortedWith(compareByDescending<AgentSession> { it.updatedAt }.thenBy { RendererConversationId.of(it) })
        val items = JSONArray()
        rows.forEach { row ->
            val view = view(snapshot, row)
            val pending = view.pending()
            items.put(RendererProjection.conversation(view, RendererConversationId.of(row), pending, RendererProjection.runtime(view, pending)))
        }
        return success(json("items" to items, "total" to items.length(), "has_more" to false))
    }

    /**
     * One conversation. Asking by a derived id is the renderer's selection step, so a conversation that is not
     * open yet is opened through the repository's exclusive switch before its row is answered.
     */
    private suspend fun conversation(params: JSONObject): ProbeResult {
        val requested = params.optString("conversationId")
        return when (val selection = select(requested, reopen = false)) {
            is Selection.Refused -> selection.result

            is Selection.Open -> {
                val pending = selection.snapshot.pending()
                success(RendererProjection.conversation(selection.snapshot, requested, pending, RendererProjection.runtime(selection.snapshot, pending)))
            }
        }
    }

    private suspend fun messages(query: JSONObject, conversationId: String): ProbeResult {
        val snapshot = openFor(conversationId) ?: return notFound()
        val limit = if (query.has("limit")) query.optString("limit").toIntOrNull() ?: -1 else DEFAULT_PAGE
        if (limit !in 1..MAX_PAGE) return fail(422, "INVALID_CURSOR", "limit 必须是 1..$MAX_PAGE 的整数。")
        val items = RendererProjection.messages(snapshot.events, conversationId, snapshot.epoch, snapshot.turnId)
        val before = query.optString("before").ifEmpty { query.optString("anchor_message_id") }.ifEmpty { null }
        val after = query.optString("after").ifEmpty { null }
        // A cursor this window does not hold means the page cannot be served honestly from here.
        if (before != null && items.none { it.optString("id") == before }) return fail(422, "INVALID_CURSOR", "游标不在当前已加载的历史中。")
        if (after != null && items.none { it.optString("id") == after }) return fail(422, "INVALID_CURSOR", "游标不在当前已加载的历史中。")
        return success(RendererProjection.page(items, limit, before, after, snapshot.hasEarlier))
    }

    private suspend fun latest(query: JSONObject, conversationId: String): ProbeResult {
        val snapshot = openFor(conversationId) ?: return notFound()
        val items = RendererProjection.messages(snapshot.events, conversationId, snapshot.epoch, snapshot.turnId)
        return success(RendererProjection.latest(items, query.optString("type").ifEmpty { null }))
    }

    private suspend fun send(body: JSONObject, conversationId: String): ProbeResult {
        val snapshot = openFor(conversationId) ?: return notFound()
        if (!snapshot.active || snapshot.session == null) return fail(409, "CONVERSATION_NOT_READY", "会话尚未打开或不处于可发送状态。")
        if (snapshot.turnId != null) return fail(409, "TURN_BUSY", "上一个回合尚未结束。")
        if (snapshot.pending() != null) return fail(409, "APPROVAL_PENDING", "有审批请求待回答，暂不能发送。")
        if ((body.optJSONArray("files")?.length() ?: 0) > 0) return fail(422, "ATTACHMENT_UNSUPPORTED", "此版本只发送文本。")
        if ((body.optJSONArray("sessions")?.length() ?: 0) > 0) return fail(422, "SESSION_REFERENCES_UNSUPPORTED", "此版本不支持引用其它会话。")
        if ((body.optJSONArray("inject_skills")?.length() ?: 0) > 0) return fail(422, "SKILLS_UNSUPPORTED", "此版本不支持注入技能。")
        val content = body.opt("content")
        if (content !is String || content.isBlank() || content.length > ProbeLimits.CONTENT_UNITS || content.toByteArray(Charsets.UTF_8).size > ProbeLimits.CONTENT_BYTES) {
            return fail(422, "INVALID_CONTENT", "消息内容为空或超出长度限制。")
        }
        val prepared = snapshot.session
        val messageId = source.send(content, prepared) ?: return fail(409, "TURN_BUSY", "会话正在处理其它操作，请稍后重试。")
        val accepted = source.snapshot()
        // The message was accepted for the conversation it was written for; if that conversation was replaced
        // while it was in flight, no runtime of the replacement may be reported as its result.
        if (!namesOpen(accepted, conversationId)) return fail(409, "CONVERSATION_SWITCHED", "会话已切换，该消息的结果不再属于当前会话。")
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

    private suspend fun cancel(body: JSONObject, conversationId: String): ProbeResult {
        val snapshot = openFor(conversationId) ?: return notFound()
        if (!snapshot.active || snapshot.session == null) return fail(409, "CONVERSATION_NOT_READY", "会话尚未打开。")
        val turn = snapshot.turnId ?: return fail(409, "TURN_NOT_ACTIVE", "当前没有正在进行的回合。")
        val requested = body.optString("turn_id")
        if (requested.isNotEmpty() && requested != turn) return fail(409, "STALE_TURN", "该回合已不是当前回合。")
        source.cancel(snapshot.session)
        val cancelled = source.snapshot()
        if (!namesOpen(cancelled, conversationId)) return fail(409, "CONVERSATION_SWITCHED", "会话已切换，取消结果不再属于当前会话。")
        return success(json("runtime" to RendererProjection.runtime(cancelled, cancelled.pending()), "turn_id" to turn))
    }

    private suspend fun approvals(conversationId: String): ProbeResult {
        val snapshot = openFor(conversationId) ?: return notFound()
        val pending = JSONArray()
        snapshot.pending()?.let { pending.put(RendererProjection.permission(it, conversationId, snapshot.epoch, snapshot.turnId)) }
        return success(pending)
    }

    /**
     * Answers the live approval. The repository itself re-checks that this event is still the pending instance of
     * the selected session, so a historical or already-answered card can never become actionable here.
     */
    private suspend fun review(params: JSONObject, body: JSONObject): ProbeResult {
        val conversationId = params.optString("conversationId")
        val snapshot = openFor(conversationId) ?: return notFound()
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
     * The renderer's `runtime.ensure`: it makes the requested conversation the open one through the product's
     * single exclusive switch entry, then reports that conversation's own runtime. A conversation that stays
     * closed or failed is answered with the real reason instead of an idle-looking success.
     */
    private suspend fun ensure(params: JSONObject): ProbeResult {
        val requested = params.optString("conversationId")
        return when (val selection = select(requested, reopen = true)) {
            is Selection.Refused -> selection.result

            is Selection.Open -> success(
                json(
                    "recovered" to false,
                    "config_options" to catalogue(selection.snapshot),
                    "runtime" to RendererProjection.runtime(selection.snapshot, selection.snapshot.pending()),
                ),
            )
        }
    }

    private suspend fun snapshot(): ProbeResult {
        val snapshot = source.snapshot()
        val session = snapshot.session ?: return notFound()
        val conversationId = RendererConversationId.of(session)
        val pending = JSONArray()
        snapshot.pending()?.let { pending.put(RendererProjection.permission(it, conversationId, snapshot.epoch, snapshot.turnId)) }
        return ProbeResult(
            body = json(
                "epoch" to snapshot.epoch,
                "seq" to snapshot.seq,
                "conversation_id" to conversationId,
                "runtime" to RendererProjection.runtime(snapshot, snapshot.pending()),
                "messages" to JSONArray(RendererProjection.messages(snapshot.events, conversationId, snapshot.epoch, snapshot.turnId)),
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
        val conversationId = params.optString("conversationId")
        val snapshot = openFor(conversationId) ?: return notFound()
        if (!snapshot.active) return fail(409, "CONVERSATION_NOT_OPEN", "该会话尚未就绪；请重试。")
        return when (val access = source.config()) {
            is AgentConfigAccess.Available -> {
                if (!namesOpen(source.snapshot(), conversationId)) {
                    return fail(409, "CONVERSATION_SWITCHED", "会话已切换，配置不再属于当前会话。")
                }
                success(json("config_options" to RendererProjection.configOptions(access.config)))
            }

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
        val conversationId = params.optString("conversationId")
        val optionId = params.optString("optionId")
        val value = body.opt("value")
        if (value !is String || value.isEmpty()) return fail(422, "INVALID_CONFIG", "配置值必须是非空字符串。")
        val snapshot = openFor(conversationId) ?: return notFound()
        if (!snapshot.active || snapshot.session == null) return fail(409, "CONVERSATION_NOT_READY", "会话尚未打开，暂时不能切换配置。")
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
            source.setConfigOption(optionId, value, snapshot.session)
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
    private suspend fun usage(conversationId: String): ProbeResult {
        val snapshot = openFor(conversationId) ?: return notFound()
        val reported = snapshot.events.lastOrNull { it.type == "usage" }
        return success(RendererProjection.usage(reported))
    }

    /**
     * Reads back the draft the user left in the renderer's send box. Only the open conversation answers here: an
     * empty draft for a conversation this host has not opened would wipe the text the user really typed.
     */
    private suspend fun loadDraft(params: JSONObject): ProbeResult {
        val conversationId = params.optString("conversationId")
        val snapshot = openFor(conversationId) ?: return notFound()
        val session = snapshot.session ?: return notFound()
        if (!snapshot.active) return fail(409, "CONVERSATION_NOT_OPEN", "该会话尚未打开；请重试。")
        return success(RendererProjection.draft(source.draft(RendererProjection.draftKey(session)) ?: "", conversationId))
    }

    /**
     * Stores the send box's text for the open conversation. Text over the draft limit is refused rather than cut,
     * and an attachment reference is refused too: this host issues no upload reference, so one could only name a
     * file it never accepted.
     */
    private suspend fun saveDraft(params: JSONObject, body: JSONObject): ProbeResult {
        val conversationId = params.optString("conversationId")
        val snapshot = openFor(conversationId) ?: return notFound()
        val session = snapshot.session ?: return notFound()
        if (!snapshot.active) return fail(409, "CONVERSATION_NOT_OPEN", "该会话尚未打开；请重试。")
        if ((body.optJSONArray("files")?.length() ?: 0) > 0) return fail(422, "ATTACHMENT_UNSUPPORTED", "此版本只保存文本草稿。")
        val text = body.opt("text")
        if (text !is String) return fail(422, "DRAFT_INVALID", "草稿内容必须是字符串。")
        if (text.length > ProbeLimits.DRAFT_UNITS || text.toByteArray(Charsets.UTF_8).size > ProbeLimits.DRAFT_BYTES) {
            return fail(413, "DRAFT_TOO_LARGE", "草稿超出长度限制，请缩短后再保存。")
        }
        source.saveDraft(RendererProjection.draftKey(session), text)
        return success(RendererProjection.draft(text, conversationId).put("saved", true))
    }

    /**
     * One saved host as the renderer's project, with the conversation's own working directory as its workspace
     * root. The directory is really listed once, bounded and read-only, so the root reports the state the host
     * observed: a directory that is gone, unreadable or behind an unreachable host is flagged as itself instead of
     * being answered as empty. A host whose workspace this product never learned fails explicitly, because an
     * invented or empty directory would be read as "the folder is empty".
     */
    private suspend fun readProject(params: JSONObject): ProbeResult {
        val id = params.optString("projectId")
        val snapshot = source.snapshot()
        val host = snapshot.hosts.firstOrNull { RendererProjection.projectId(it.id) == id }
            ?: return fail(404, "PROJECT_NOT_FOUND", "没有这个项目。")
        val directory = workspaceDirectory(snapshot, host.id)
            ?: return fail(409, "WORKSPACE_UNKNOWN", "该主机还没有会话记录它的工作目录，无法读取文件区。")
        // A host that cannot be reached keeps its transport error to itself here: the root still belongs to the
        // project, so it is reported as the directory nobody could read rather than as a failed project.
        val listing = try {
            source.listWorkspace(host.id, directory)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        return success(RendererProjection.project(host, RendererProjection.workspaceRoot(directory, listing)))
    }

    /**
     * The working directory one project is presented with: the open conversation's own when it belongs to that
     * host, otherwise the newest indexed session of that host — both are directories the product already holds,
     * never a path guessed for the host.
     */
    private fun workspaceDirectory(snapshot: RendererSnapshot, hostId: Long): String? {
        val open = snapshot.session?.takeIf { it.hostId == hostId }?.cwd?.takeIf { it.startsWith('/') }
        val indexed = snapshot.indexedSessions
            .filter { it.hostId == hostId && it.cwd.startsWith('/') }
            .maxByOrNull { it.updatedAt }
            ?.cwd
        return open ?: indexed
    }

    /**
     * The product's own saved hosts, each with whatever probe verdict it already holds. These are the SSH hosts
     * an Agent can run on — the same rows the renderer already draws as projects — so a host of another protocol
     * is not listed here, and a row nobody probed says so instead of looking installed.
     */
    private suspend fun listHosts(): ProbeResult {
        val snapshot = source.snapshot()
        val items = RendererProjection.hostRows(snapshot.hosts, snapshot.availability)
        return success(json("items" to items, "total" to items.length(), "has_more" to false))
    }

    /**
     * Creates one saved host from the fields of the request body. Structured fields travel in the body because the
     * bridge carries only string parameters; nickname and address are required, everything else takes the value a
     * new SSH host is created with. The nickname column is unique across every host, so a taken name is refused
     * before anything is written.
     */
    private suspend fun createHost(body: JSONObject): ProbeResult {
        val fields = hostFields(body)
        val nickname = fields.nickname?.takeIf { it.isNotBlank() } ?: invalid("昵称不能为空。")
        val protocol = fields.protocol ?: SSH_PROTOCOL
        if (protocol != SSH_PROTOCOL) invalid("只能新建 SSH 主机：Agent 连接需要 SSH。")
        val hostname = fields.hostname?.takeIf { it.isNotBlank() } ?: invalid("地址不能为空。")
        val target = hostTarget(fields.osType ?: HOST_OS_LINUX, fields.agentTarget ?: AGENT_TARGET_NATIVE)
        if (source.hostByNickname(nickname) != null) return fail(409, "HOST_NICKNAME_TAKEN", "已有同名主机：$nickname。")
        val created = Host.createSshHost(nickname, hostname, fields.port ?: DEFAULT_SSH_PORT, fields.username.orEmpty())
            .copy(osType = target.osType, agentTarget = target.agentTarget)
        val saved = try {
            source.saveHost(created)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return fail(500, "HOST_SAVE_FAILED", "主机未能保存：${reason(error)}")
        }
        return success(RendererProjection.hostRow(saved, source.snapshot().availability))
    }

    /**
     * Updates one saved host in place. A field the body omits keeps what the row holds, and the row is written back
     * as itself with only those fields replaced, so a changed address rewrites that address alone: the id, the
     * saved key association, the profile, the jump host and the known hosts of this host stay its own.
     */
    private suspend fun updateHost(params: JSONObject, body: JSONObject): ProbeResult {
        val host = requestedHost(params) ?: return hostNotFound()
        val fields = hostFields(body)
        val nickname = (fields.nickname ?: host.nickname).takeIf { it.isNotBlank() } ?: invalid("昵称不能为空。")
        if (fields.protocol != null && fields.protocol != SSH_PROTOCOL) invalid("SSH 主机不能改成 ${fields.protocol}：Agent 连接需要 SSH。")
        val hostname = (fields.hostname ?: host.hostname).takeIf { it.isNotBlank() } ?: invalid("地址不能为空。")
        val target = hostTarget(fields.osType ?: host.osType, fields.agentTarget ?: host.agentTarget)
        if (nickname != host.nickname && source.hostByNickname(nickname) != null) {
            return fail(409, "HOST_NICKNAME_TAKEN", "已有同名主机：$nickname。")
        }
        val updated = host.copy(
            nickname = nickname,
            hostname = hostname,
            port = fields.port ?: host.port,
            username = fields.username ?: host.username,
            osType = target.osType,
            agentTarget = target.agentTarget,
        )
        val saved = try {
            source.saveHost(updated)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return fail(500, "HOST_SAVE_FAILED", "主机未能保存：${reason(error)}")
        }
        return success(RendererProjection.hostRow(saved, source.snapshot().availability))
    }

    /**
     * Deletes one saved host. The confirmation a user must give is the front end's own step: this host performs the
     * real deletion and nothing else, and the product's own repository readers drop what that host held — its saved
     * credential, its indexed sessions and its detections.
     */
    private suspend fun deleteHost(params: JSONObject): ProbeResult {
        val host = requestedHost(params) ?: return hostNotFound()
        try {
            source.deleteHost(host)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return fail(500, "HOST_DELETE_FAILED", "主机未能删除：${reason(error)}")
        }
        return success(json("deleted" to true, "host_id" to RendererProjection.projectId(host.id)))
    }

    /**
     * One probe of one saved host's Agent target, through the repository entry that also writes every executable,
     * version and capability it verified into `AgentDetectionStore`. Each Agent answers with its own state and what
     * the probe found. A Windows host whose Agent would run on Windows itself has no entry in this app, so its
     * Agents answer `target_unsupported` rather than looking installed, and a probe that never reached the host
     * keeps the path it detected before instead of claiming a new one.
     */
    private suspend fun refreshDetection(params: JSONObject): ProbeResult {
        val host = requestedHost(params) ?: return hostNotFound()
        val rows = try {
            source.refreshDetection(host.id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return fail(500, "DETECTION_FAILED", "探测未能完成：${reason(error)}")
        }
        val target = remoteTargetOf(host.osType, host.agentTarget)
        return success(
            json(
                "host_id" to RendererProjection.projectId(host.id),
                "target" to target.id,
                "target_supported" to target.supported,
                "detection" to RendererProjection.detection(rows),
            ),
        )
    }

    /** The saved host an id names; the id is the same `host-<id>` a project row carries. */
    private suspend fun requestedHost(params: JSONObject): Host? {
        val hostId = RendererProjection.hostId(params.optString("hostId")) ?: return null
        return source.snapshot().hosts.firstOrNull { it.id == hostId }
    }

    private fun hostNotFound(): ProbeResult = fail(404, "HOST_NOT_FOUND", "没有这台主机。")

    /** One create or update body; a field the body does not carry stays null so an update can send only changes. */
    private data class HostFields(
        val nickname: String?,
        val hostname: String?,
        val port: Int?,
        val username: String?,
        val protocol: String?,
        val osType: String?,
        val agentTarget: String?,
    )

    private fun hostFields(body: JSONObject): HostFields = HostFields(
        nickname = hostText(body, "nickname", "昵称", MAX_NICKNAME),
        hostname = hostText(body, "hostname", "地址", MAX_HOSTNAME),
        port = hostPort(body),
        username = hostText(body, "username", "用户名", MAX_USERNAME),
        protocol = hostText(body, "protocol", "协议", MAX_PROTOCOL),
        osType = hostText(body, "os_type", "操作系统", MAX_OS_TYPE),
        agentTarget = hostText(body, "agent_target", "Agent 目标", MAX_AGENT_TARGET),
    )

    /** A field that is absent or explicitly null carries nothing; any other shape has to be a usable string. */
    private fun hostText(body: JSONObject, key: String, label: String, max: Int): String? {
        if (body.isNull(key)) return null
        val text = body.opt(key) as? String ?: invalid("$label 无效。")
        if (text.length > max) invalid("$label 无效。")
        if (text.any { it.isISOControl() }) invalid("$label 不能包含控制字符。")
        return text
    }

    /** A port arrives as a number or as a numeric string; anything that is not a whole port number is refused. */
    private fun hostPort(body: JSONObject): Int? {
        if (body.isNull("port")) return null
        val value = body.opt("port")
        val parsed = when (value) {
            is Number -> value.toDouble().takeIf { it % 1.0 == 0.0 }?.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }
        return parsed?.takeIf { it in 1..MAX_PORT } ?: invalid("端口必须是 1..$MAX_PORT 的整数。")
    }

    /** The OS and Agent target a saved host may carry, both checked against what this app can address. */
    private data class HostTarget(val osType: String, val agentTarget: String)

    /**
     * A Windows host may hold the Agent in one WSL distribution it names, or on Windows itself — a target this app
     * has no entry for, which every probe reports as unsupported. A Linux host runs its Agent in its own shell, so
     * that is the only target stored for it, and no other value is stored as a target nothing could reach.
     */
    private fun hostTarget(osType: String, agentTarget: String): HostTarget = when (osType) {
        HOST_OS_LINUX -> HostTarget(HOST_OS_LINUX, AGENT_TARGET_NATIVE)

        HOST_OS_WINDOWS -> when {
            agentTarget == AGENT_TARGET_NATIVE -> HostTarget(HOST_OS_WINDOWS, AGENT_TARGET_NATIVE)
            agentTarget.startsWith(AGENT_TARGET_WSL_PREFIX) &&
                validWslDistribution(agentTarget.removePrefix(AGENT_TARGET_WSL_PREFIX)) -> HostTarget(HOST_OS_WINDOWS, agentTarget)

            else -> invalid("Windows 主机的 Agent 目标必须是 native 或 wsl:<发行版>。")
        }

        else -> invalid("操作系统只能是 linux 或 windows。")
    }

    /** A create or update field this host cannot store; [handle] answers it as `422 HOST_INVALID`. */
    private class InvalidHost(val reason: String) : RuntimeException(reason)

    private fun invalid(reason: String): Nothing = throw InvalidHost(reason)

    /** The repository failure behind a stable code, kept readable for whoever has to fix it. */
    private fun reason(error: Exception): String = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName

    /** The snapshot of the conversation [conversationId] names, or null when a different one is open. */
    private suspend fun openFor(conversationId: String): RendererSnapshot? = source.snapshot().takeIf { namesOpen(it, conversationId) }

    /**
     * True when [conversationId] still names the open conversation. The bridge's legacy id answers for whatever
     * conversation is open, which keeps the frozen renderer bootstrap working.
     */
    private fun namesOpen(snapshot: RendererSnapshot, conversationId: String): Boolean {
        val open = snapshot.session ?: return false
        return conversationId == RENDERER_CONVERSATION || conversationId == RendererConversationId.of(open)
    }

    /** The indexed session an id names; an id outside the index never selects anything. */
    private fun target(snapshot: RendererSnapshot, conversationId: String): AgentSession? {
        if (conversationId == RENDERER_CONVERSATION) return snapshot.session
        val parsed = RendererConversationId.parse(conversationId) ?: return null
        return (snapshot.indexedSessions + listOfNotNull(snapshot.session)).firstOrNull { it.sameConversation(parsed) }
    }

    /**
     * Makes the requested conversation the open one through [RendererSource.open], which is the product's
     * repository switch. The answer is only an open conversation when that exact conversation is the ready one
     * afterwards; a failed open reports the repository's own reason, and a newer switch that won the race is a
     * refusal rather than a success for the replaced conversation.
     */
    private suspend fun select(conversationId: String, reopen: Boolean): Selection {
        var snapshot = source.snapshot()
        val target = target(snapshot, conversationId)
            ?: return Selection.Refused(fail(404, "CONVERSATION_NOT_FOUND", "索引中没有这个会话。"))
        val current = snapshot.session
        if (current != null && current.sameConversation(target)) {
            if (snapshot.active || snapshot.loading) return Selection.Open(snapshot, current)
            if (!reopen) return Selection.Refused(fail(409, "CONVERSATION_NOT_OPEN", "该会话尚未打开，请重试。"))
        }
        val failure = source.open(target)
        snapshot = source.snapshot()
        val opened = snapshot.session
        if (failure != null || opened == null || !opened.sameConversation(target) || !snapshot.active) {
            return Selection.Refused(fail(409, "CONVERSATION_OPEN_FAILED", failure ?: "该会话未能保持打开，已被其它会话切换替代。"))
        }
        return Selection.Open(snapshot, opened)
    }

    /** The open snapshot for one row; every other row gets its row facts but none of the open conversation's state. */
    private fun view(snapshot: RendererSnapshot, row: AgentSession): RendererSnapshot {
        val current = snapshot.session
        val same = current != null && current.sameConversation(row)
        if (same && snapshot.active) return snapshot
        return snapshot.copy(
            session = row,
            indexed = row,
            events = emptyList(),
            active = false,
            loading = same && snapshot.loading,
            turnId = null,
            cancelling = false,
            hasEarlier = false,
        )
    }

    private sealed interface Selection {
        data class Open(val snapshot: RendererSnapshot, val session: AgentSession) : Selection
        data class Refused(val result: ProbeResult) : Selection
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

        /** The protocol a host the renderer creates or keeps has to carry: an Agent is reached over SSH. */
        const val SSH_PROTOCOL = "ssh"
        const val DEFAULT_SSH_PORT = 22
        const val MAX_PORT = 65535
        const val MAX_NICKNAME = 100
        const val MAX_HOSTNAME = 255
        const val MAX_USERNAME = 128
        const val MAX_PROTOCOL = 16
        const val MAX_OS_TYPE = 16
        const val MAX_AGENT_TARGET = 128
    }
}
