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
import org.connectbot.agent.AgentMessageKind
import org.connectbot.agent.agentAttentionSignals
import org.connectbot.agent.projectAgentAttentionInbox
import org.connectbot.agent.searchAgentSessions
import org.connectbot.data.entity.Host
import org.connectbot.ui.screens.agents.AgentHostState
import org.connectbot.ui.screens.agents.agentHostChoices
import org.connectbot.ui.screens.agents.filterAgentSessions
import org.connectbot.ui.screens.agents.projectQuestion
import org.connectbot.ui.screens.agents.validAgentCwd
import org.connectbot.ui.screens.agents.validQuestionAnswer
import org.json.JSONArray
import org.json.JSONObject
import org.rmagentma.core.AGENT_TARGET_NATIVE
import org.rmagentma.core.AGENT_TARGET_WSL_PREFIX
import org.rmagentma.core.AgentKind
import org.rmagentma.core.AgentSession
import org.rmagentma.core.HOST_OS_LINUX
import org.rmagentma.core.HOST_OS_WINDOWS
import org.rmagentma.core.ShellCommands
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
        "conversations.create" -> createConversation(params, body)
        "conversation.read" -> conversation(params)
        "messages.list" -> messages(query, params.optString("conversationId"))
        "messages.before" -> messagesBefore(query, params.optString("conversationId"))
        "messages.latest" -> latest(query, params.optString("conversationId"))
        "messages.send" -> send(body, params.optString("conversationId"))
        "turn.cancel" -> cancel(body, params.optString("conversationId"))
        "approvals.list" -> approvals(params.optString("conversationId"))
        "approvals.review" -> review(params, body)
        "interactions.review" -> reviewQuestion(params, body)
        "challenges.read" -> readChallenge()
        "challenges.answer" -> answerChallenge(params, body)
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
        "hosts.refresh" -> refreshHost(params)
        "detection.refresh" -> refreshDetection(params)
        "search.sessions" -> searchSessions(query)
        "inbox.list" -> inbox()
        "queue.list" -> queue()
        "queue.send" -> sendToQueue(body)
        "queue.cancel" -> cancelQueued(params)
        "reference.pack" -> reference(params)
        "index.clear" -> clearIndex()
        "terminal.open" -> terminal(params, body)
        "terminal.command.start" -> terminal(params, body)
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

    /**
     * Creates one conversation on a saved host and answers the row the product now holds, together with the native
     * id the Agent assigned. The Agent and the absolute working directory are checked before anything runs, and the
     * directory itself is verified read-only on the host by the repository, so a directory the host cannot use
     * fails as its own reason. A conversation that opened without a native id is reported as unknown instead of
     * being dressed up with an id this host invented, because that id is what a later request has to name it by.
     */
    private suspend fun createConversation(params: JSONObject, body: JSONObject): ProbeResult {
        val host = requestedHost(params) ?: return hostNotFound()
        val requestedAgent = (body.opt("agent") as? String).orEmpty().ifEmpty { params.optString("agent") }
        val agent = AgentKind.fromWire(requestedAgent) ?: return fail(422, "AGENT_UNKNOWN", "未知的 Agent：$requestedAgent。")
        val cwd = (body.opt("cwd") as? String).orEmpty().ifEmpty { params.optString("cwd") }
        if (!validAgentCwd(cwd)) return fail(422, "INVALID_CWD", "工作目录必须是绝对路径，且不能包含控制字符。")
        val failure = try {
            source.createSession(host.id, agent, cwd)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return fail(500, "SESSION_CREATE_FAILED", "会话未能创建：${reason(error)}")
        }
        if (failure != null) return fail(409, "SESSION_CREATE_FAILED", "会话未能打开：$failure")
        val snapshot = source.snapshot()
        val created = snapshot.session
        if (created == null || created.hostId != host.id || created.agent != agent || !snapshot.active) {
            return fail(409, "SESSION_CREATE_FAILED", "会话未能保持打开，已被其它会话切换替代。")
        }
        val sessionId = created.sessionId.takeIf { it.isNotBlank() }
            ?: return fail(409, "SESSION_ID_UNKNOWN", "会话已打开，但 Agent 未返回原生会话 id，无法在索引中定位它。")
        val pending = snapshot.pending()
        return success(
            RendererProjection.conversation(snapshot, RendererConversationId.of(created), pending, RendererProjection.runtime(snapshot, pending))
                .put("session_id", sessionId)
                .put("agent", agent.wireName)
                .put("cwd", created.cwd)
                .put("created", true),
        )
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

    /**
     * One page of the history that lies before the loaded window. The page is really read from the history store
     * through the repository's own paging entry, and the answer is the page the product now holds: the renderer
     * prepends it above what it already drew. A conversation whose earlier history the product no longer has, or
     * whose page cannot be read, fails as exactly that instead of answering an empty page that reads like the end.
     */
    private suspend fun messagesBefore(query: JSONObject, conversationId: String): ProbeResult {
        val snapshot = openFor(conversationId) ?: return notFound()
        if (!snapshot.active) return fail(409, "CONVERSATION_NOT_OPEN", "该会话尚未就绪；稍后再读取更早的历史。")
        val limit = if (query.has("limit")) query.optString("limit").toIntOrNull() ?: -1 else DEFAULT_PAGE
        if (limit !in 1..MAX_PAGE) return fail(422, "INVALID_CURSOR", "limit 必须是 1..$MAX_PAGE 的整数。")
        val before = query.optString("before").ifEmpty { query.optString("anchor_message_id") }.ifEmpty { null }
        val loaded = RendererProjection.messages(snapshot.events, conversationId, snapshot.epoch, snapshot.turnId)
        // The cursor has to belong to the window the caller is reading, or the page below it would be a guess.
        if (before != null && loaded.none { it.optString("id") == before }) return fail(422, "INVALID_CURSOR", "游标不在当前已加载的历史中。")
        if (!snapshot.hasEarlier) return fail(409, "HISTORY_EXHAUSTED", "该会话没有更早的一页历史。")
        try {
            source.loadEarlier()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return fail(500, "HISTORY_LOAD_FAILED", "更早的历史未能读取：${reason(error)}")
        }
        val earlier = source.snapshot()
        if (!namesOpen(earlier, conversationId)) return fail(409, "CONVERSATION_SWITCHED", "会话已切换，更早的历史不再属于当前会话。")
        val items = RendererProjection.messages(earlier.events, conversationId, earlier.epoch, earlier.turnId)
        // The window now holds the earlier page alone, so it is answered whole, and the newer part the caller
        // still has is what `has_more_after` names.
        return success(RendererProjection.page(items, limit, before = null, after = null, hasEarlier = earlier.hasEarlier, hasLater = true))
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
     * Answers the live question of the open conversation. The reference must be the question card this host
     * published, and the answer has to satisfy the schema the Agent stated, checked with the same projection the
     * Compose conversation form uses. Whether the event is still the pending instance is decided by the repository
     * against the selected session and the exact event, so a question from history can never become answerable
     * here, and a question whose form this host cannot read is refused as unsupported rather than answered blind.
     */
    private suspend fun reviewQuestion(params: JSONObject, body: JSONObject): ProbeResult {
        val conversationId = params.optString("conversationId")
        val snapshot = openFor(conversationId) ?: return notFound()
        val event = snapshot.pendingQuestion()
        val reference = params.optString("requestRef")
        if (reference.isEmpty() || event == null || event.id != reference || !snapshot.active) {
            return fail(409, "NOT_PENDING", "该提问不再是当前挂起项。")
        }
        val epoch = body.optString("epoch")
        if (epoch.isNotEmpty() && epoch != snapshot.epoch) return fail(409, "STALE_APPROVAL", "运行代次已变化，请重新读取。")
        val answer = (body.opt("answer") as? String) ?: (body.opt("data") as? String)
            ?: return fail(422, "INVALID_ANSWER", "回答必须是非空字符串。")
        val form = projectQuestion(event.raw)
        if (form.schema == null) return fail(501, "QUESTION_UNSUPPORTED", "该提问没有本机可读的作答结构。")
        if (!validQuestionAnswer(form, answer)) return fail(422, "INVALID_ANSWER", "该回答不符合提问要求。")
        if (!source.respondQuestion(event, answer)) return fail(409, "NOT_PENDING", "该提问已失效。")
        // The answer is the user's own content and is not echoed back into a payload.
        return success(json("state" to "answered", "request_id" to event.id, "question_id" to event.id))
    }

    /**
     * The challenge the product is waiting on right now, if any: a host key confirmation, a login password or the
     * passphrase of a private key. The answer is what this host must not hand out, so the payload carries the
     * identity, the host it belongs to, the kind and the prompt text alone; a `null` challenge means no connection
     * is asking for input, which is the truth and not a failure.
     */
    private suspend fun readChallenge(): ProbeResult {
        val challenge = source.challenge() ?: return success(json("challenge" to null))
        val hostId = challenge.hostId.takeIf { it > 0 && source.snapshot().hosts.any { host -> host.id == it } }
        return success(
            json(
                "challenge" to json(
                    "id" to challenge.id,
                    "host_id" to hostId?.let(RendererProjection::projectId),
                    "host_name" to challenge.hostName,
                    "kind" to challenge.kind,
                    "message" to challenge.message,
                    // True when the user's input is a secret and must not be shown, stored or logged by the surface.
                    "secret" to challenge.secret,
                ),
            ),
        )
    }

    /**
     * Hands the user's answer to the challenge this host is still waiting on. Only the pending id is answered, an
     * absent answer cancels the request as the Compose dialog's own cancel does, and the value is passed through
     * alone: it is never stored, logged or echoed back.
     */
    private suspend fun answerChallenge(params: JSONObject, body: JSONObject): ProbeResult {
        val id = params.optString("challengeId")
        val current = source.challenge()
        if (id.isEmpty() || current == null || current.id != id) return fail(409, "CHALLENGE_NOT_PENDING", "该凭据请求已不再挂起。")
        val answer = body.opt("answer")
        if (answer != null && answer !== JSONObject.NULL && answer !is String) {
            return fail(422, "INVALID_ANSWER", "答案必须是字符串；取消请省略 answer 或传 null。")
        }
        val text = answer as? String
        if (text != null && text.length > MAX_SECRET_CHARS) return fail(422, "INVALID_ANSWER", "答案超出长度限制。")
        // An absent answer, like an empty one, cancels the request, exactly as the Compose dialog's cancel does.
        if (!source.respondChallenge(id, text?.takeIf { it.isNotEmpty() })) return fail(409, "CHALLENGE_NOT_PENDING", "该凭据请求已不再挂起。")
        return success(json("answered" to true, "challenge_id" to id))
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
        val items = RendererProjection.hostRows(snapshot.hosts, snapshot.availability, sessionCounts(snapshot))
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
        return success(savedHostRow(saved))
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
        return success(savedHostRow(saved))
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

    /**
     * One probe of one saved host through the repository's own refresh entry, which also rescans that host's
     * session index. The answer names what the probe verified and how many conversations the product now holds for
     * this host, so a caller can tell a refreshed index from one that was never read.
     */
    private suspend fun refreshHost(params: JSONObject): ProbeResult {
        val host = requestedHost(params) ?: return hostNotFound()
        val rows = try {
            source.refreshDetection(host.id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return fail(500, "HOST_REFRESH_FAILED", "刷新未能完成：${reason(error)}")
        }
        val sessions = source.snapshot().indexedSessions.count { it.hostId == host.id }
        return success(
            json(
                "host_id" to RendererProjection.projectId(host.id),
                "refreshed" to true,
                "sessions" to sessions,
                "detection" to RendererProjection.detection(rows),
            ),
        )
    }

    /** The saved host an id names; the id is the same `host-<id>` a project row carries. */
    private suspend fun requestedHost(params: JSONObject): Host? {
        val hostId = RendererProjection.hostId(params.optString("hostId")) ?: return null
        return source.snapshot().hosts.firstOrNull { it.id == hostId }
    }

    /**
     * One search across conversations, in the two ways this product can honestly search: the index's own title,
     * preview and directory fields, and the *content* of the selected transcripts read remotely once per host,
     * bounded and read-only. Partial results stay usable, and the coverage of the pass travels with them — a source
     * that failed, a scan the remote clock cut short, a history that is not a file this build can name and a session
     * whose directory was not found are each reported as themselves, so no caller reads this as a history without
     * that text. Nothing is opened, sent or written by a search.
     */
    private suspend fun searchSessions(query: JSONObject): ProbeResult {
        val text = query.optString("q").ifEmpty { query.optString("query") }.trim()
        if (text.isEmpty() || text.length > MAX_QUERY || text.any { it.isISOControl() }) {
            return fail(422, "INVALID_QUERY", "搜索词不能为空、不能包含控制字符，且不得超过 $MAX_QUERY 个字符。")
        }
        val limit = if (query.has("limit")) query.optString("limit").toIntOrNull() ?: -1 else DEFAULT_PAGE
        if (limit !in 1..MAX_PAGE) return fail(422, "INVALID_QUERY", "limit 必须是 1..$MAX_PAGE 的整数。")
        val snapshot = source.snapshot()
        val hostFilter = query.optString("host_id").ifEmpty { query.optString("hostId") }
        val hostId = if (hostFilter.isEmpty()) {
            null
        } else {
            RendererProjection.hostId(hostFilter)?.takeIf { id -> snapshot.hosts.any { it.id == id } }
                ?: return hostNotFound()
        }
        val requestedAgent = query.optString("agent")
        val agent = if (requestedAgent.isEmpty()) null else AgentKind.fromWire(requestedAgent) ?: return fail(422, "AGENT_UNKNOWN", "未知的 Agent：$requestedAgent。")
        val selected = filterAgentSessions(heldSessions(snapshot), hostId, agent, "")
        val index = searchAgentSessions(selected, text, snapshot.scannedSources)
        val outcome = try {
            source.searchTranscripts(text, selected)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return fail(500, "SEARCH_FAILED", "跨会话搜索未能完成：${reason(error)}")
        }
        val held = heldSessions(snapshot).map { RendererConversationId.of(it) }.toSet()
        val items = JSONArray()
        // The index rows are the cheap half and name where the conversation lives; the content hits name the file
        // and line inside it, so the two kinds are answered together under one bound.
        index.hits.take(limit).forEach { items.put(RendererProjection.indexHit(it, held)) }
        val content = outcome.hits.take(limit)
        content.forEach { items.put(RendererProjection.contentHit(it, held)) }
        val hasMore = index.hits.size > limit || outcome.hits.size > limit || outcome.truncated
        return success(
            json(
                "query" to text,
                "items" to items,
                "total" to items.length(),
                "has_more" to hasMore,
                "coverage" to RendererProjection.searchCoverage(outcome, index.scope),
            ),
        )
    }

    /**
     * The attention inbox: a read-only projection over the one conversation this host can honestly report on,
     * because it is the only one whose approvals, questions, failure and running turn are loaded here. Another
     * conversation's approvals are unknown from here — only opening it could show them — so no row is invented for
     * it, and nothing in this route answers, approves or changes any request.
     */
    private suspend fun inbox(): ProbeResult {
        val snapshot = source.snapshot()
        val items = JSONArray()
        projectAgentAttentionInbox(
            agentAttentionSignals(
                session = snapshot.session,
                events = snapshot.events,
                busy = snapshot.busy,
                conversationActive = snapshot.active,
                hasEarlier = snapshot.hasEarlier,
            ),
        ).forEach { items.put(RendererProjection.inboxItem(it)) }
        return success(json("items" to items, "total" to items.length(), "read_only" to true, "scope" to "open_conversation"))
    }

    /**
     * The cross-conversation queue as the product holds it. Reading it neither sends nor delivers anything: a
     * request is handed to its conversation only when that conversation is next opened, so this route can never be
     * a second delivery path.
     */
    private suspend fun queue(): ProbeResult {
        val rows = try {
            source.queued()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return fail(500, "QUEUE_UNAVAILABLE", "消息队列不可读：${reason(error)}")
        }
        val stats = try {
            source.queueStats()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return fail(500, "QUEUE_UNAVAILABLE", "消息队列不可读：${reason(error)}")
        }
        val items = JSONArray()
        rows.forEach { items.put(RendererProjection.queued(it)) }
        return success(json("items" to items, "total" to items.length(), "stats" to RendererProjection.queueStats(stats)))
    }

    /**
     * Queues one message for another conversation. The target has to be a conversation the product already holds —
     * a message for a session nobody knows could never be delivered — the kind is one of the queue's own two, and
     * the text is non-empty and bounded like every other user input. Nothing is sent to any model here: the queue
     * delivers a request when its conversation is next opened and idle, and this answer only reports what is queued.
     */
    private suspend fun sendToQueue(body: JSONObject): ProbeResult {
        val snapshot = source.snapshot()
        val target = queuedTarget(snapshot, body) ?: return fail(404, "CONVERSATION_NOT_FOUND", "队列的目标会话不在产品已持有的会话中。")
        val kind = when (body.optString("kind").lowercase()) {
            "notify" -> AgentMessageKind.NOTIFY
            "request" -> AgentMessageKind.REQUEST
            else -> return fail(422, "INVALID_KIND", "kind 只能是 notify 或 request。")
        }
        val text = body.opt("text")
        if (text !is String || text.isBlank() || text.length > ProbeLimits.CONTENT_UNITS || text.toByteArray(Charsets.UTF_8).size > ProbeLimits.CONTENT_BYTES) {
            return fail(422, "INVALID_CONTENT", "消息内容为空或超出长度限制。")
        }
        val id = try {
            source.queue(target, kind, text)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return fail(500, "QUEUE_UNAVAILABLE", "消息未能入队：${reason(error)}")
        }
        if (id == null) return fail(422, "QUEUE_REFUSED", "队列拒绝了这条消息（目标或内容不符合它的要求）。")
        return success(
            json(
                "queue_id" to id,
                "conversation_id" to RendererConversationId.of(target),
                "host_id" to RendererProjection.projectId(target.hostId),
                "agent" to target.agent.wireName,
                "session_id" to target.sessionId,
                "kind" to kind.name.lowercase(),
                "state" to "pending",
                // Queued is all this host can honestly report: nothing has been delivered yet.
                "queued" to true,
                "delivered" to false,
            ),
        )
    }

    /**
     * Withdraws one queued message the user no longer wants delivered. Only a message the queue still holds as
     * pending is withdrawn: one its conversation already took is refused as itself, because a delivered message is
     * the product's own conversation content and not this queue's to undo.
     */
    private suspend fun cancelQueued(params: JSONObject): ProbeResult {
        val id = params.optString("queueId")
        if (id.isEmpty() || id.length > MAX_QUEUE_ID || id.any { it.isISOControl() }) {
            return fail(422, "INVALID_QUEUE_ID", "队列条目 id 无效。")
        }
        val rows = try {
            source.queued()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return fail(500, "QUEUE_UNAVAILABLE", "消息队列不可读：${reason(error)}")
        }
        if (rows.none { it.id == id }) return fail(404, "QUEUE_NOT_FOUND", "队列中没有这条待投消息。")
        val withdrawn = try {
            source.withdrawQueued(id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return fail(500, "QUEUE_UNAVAILABLE", "消息未能撤回：${reason(error)}")
        }
        if (!withdrawn) return fail(409, "QUEUE_NOT_PENDING", "该消息已被它的会话取走，无法撤回。")
        return success(json("cancelled" to true, "queue_id" to id, "delivered" to false))
    }

    /**
     * One bounded context pack of the open conversation, built by the same `AgentSessionReference` projection the
     * Compose quote uses, so the two surfaces quote a window the same way. Only the open conversation can be quoted:
     * its loaded window is what the pack is made of, and a different row has no window here to quote. The pack is
     * returned as text and nothing else happens — no model call is started.
     */
    private suspend fun reference(params: JSONObject): ProbeResult {
        val conversationId = params.optString("conversationId")
        val snapshot = source.snapshot()
        val session = snapshot.session ?: return notFound()
        if (!namesOpen(snapshot, conversationId)) {
            return if (target(snapshot, conversationId) != null) {
                fail(409, "CONVERSATION_NOT_OPEN", "只能引用当前打开的会话；请先打开它。")
            } else {
                notFound()
            }
        }
        if (!snapshot.active) return fail(409, "CONVERSATION_NOT_OPEN", "该会话尚未就绪；稍后再生成引用包。")
        val host = snapshot.hosts.firstOrNull { it.id == session.hostId }
        return success(RendererProjection.reference(session, host?.nickname.orEmpty(), snapshot.events, conversationId))
    }

    /**
     * Drops the session index cache through the product's own clear entry and answers what the index holds
     * afterwards. The confirmation a user must give is the front end's own step; this host really clears the cache
     * and reports the count the product now holds, and a clear that failed is answered as a failure rather than as
     * a cleanup that happened. Nothing else is touched: hosts, detections, drafts and the queue are their own
     * stores.
     */
    private suspend fun clearIndex(): ProbeResult {
        val before = source.snapshot().indexedSessions.size
        val remaining = try {
            source.clearIndex()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return fail(500, "INDEX_CLEAR_FAILED", "索引缓存未能清理：${reason(error)}")
        }
        return success(json("cleared" to true, "sessions_before" to before, "sessions" to remaining))
    }

    /**
     * The row one host write answers with: the stored host and the number of conversations the product holds for it
     * now, read back from the same snapshot the hosts page reads.
     */
    private suspend fun savedHostRow(host: Host): JSONObject {
        val snapshot = source.snapshot()
        return RendererProjection.hostRow(host, snapshot.availability, sessionCounts(snapshot)[host.id] ?: 0)
    }

    /** Every conversation the product holds: the open one plus every indexed row, each counted once. */
    private fun heldSessions(snapshot: RendererSnapshot): List<AgentSession> =
        (snapshot.indexedSessions + listOfNotNull(snapshot.session)).distinctBy { RendererConversationId.of(it) }

    /** One body field as text; an absent field and an explicit null both carry nothing rather than the word "null". */
    private fun bodyText(body: JSONObject, key: String): String = body.opt(key) as? String ?: ""

    /** How many conversations the index holds per host, so a host row's count is the product's own number. */
    private fun sessionCounts(snapshot: RendererSnapshot): Map<Long, Int> = heldSessions(snapshot).groupingBy { it.hostId }.eachCount()

    /**
     * The conversation one queue request addresses, or null when the product holds no such conversation. The target
     * travels in the body because the bridge carries only string parameters, and it is looked up in the product's
     * own rows, so a message can never be queued for a session nobody could deliver to.
     */
    private fun queuedTarget(snapshot: RendererSnapshot, body: JSONObject): AgentSession? {
        val rows = heldSessions(snapshot)
        val conversationId = bodyText(body, "conversation_id").ifEmpty { bodyText(body, "conversationId") }
        if (conversationId.isNotEmpty()) {
            val parsed = RendererConversationId.parse(conversationId) ?: return null
            return rows.firstOrNull { it.sameConversation(parsed) }
        }
        val hostId = RendererProjection.hostId(bodyText(body, "host_id").ifEmpty { bodyText(body, "hostId") }) ?: return null
        val agent = AgentKind.fromWire(bodyText(body, "agent")) ?: return null
        val sessionId = bodyText(body, "session_id").ifEmpty { bodyText(body, "sessionId") }
        if (sessionId.isBlank()) return null
        return rows.firstOrNull { it.hostId == hostId && it.agent == agent && it.sessionId == sessionId }
    }

    /**
     * The terminal launch this host can hand over for one target — either one conversation, or one saved host. The
     * answer is the command the product's own terminal route would run, built by the same `ShellCommands.start` and
     * wrapped once for the host's Agent target, together with the facts that name it. A conversation does not have
     * to be open, because the TUI reads its session id from the index; a saved host has no session of its own, so
     * its Agent and its directory have to come from somewhere else: the Agent is the one this product already uses
     * on that host, otherwise the first its probe found installed, and the directory is the one the request names.
     * Nothing is launched here — no intent, no activity, no command is run — so the caller decides how to show or
     * run the text. A directory nobody named, a host holding no Agent, an unknown target and a target this app has
     * no shell entry for each fail as themselves instead of producing a command that cannot run, and no credential
     * is ever part of the answer.
     */
    private suspend fun terminal(params: JSONObject, body: JSONObject): ProbeResult {
        val snapshot = source.snapshot()
        val requested = body.opt("cwd")
        if (requested != null && requested !== JSONObject.NULL && requested !is String) {
            return fail(422, "INVALID_CWD", "工作目录必须是字符串形式的绝对路径。")
        }
        val directory = (requested as? String)?.takeIf { it.isNotEmpty() }
        if (directory != null && !validAgentCwd(directory)) return fail(422, "INVALID_CWD", "工作目录必须是绝对路径，且不能包含控制字符。")
        val conversationId = params.optString("conversationId")
        if (conversationId.isNotEmpty()) {
            val session = target(snapshot, conversationId) ?: return notFound()
            val host = snapshot.hosts.firstOrNull { it.id == session.hostId } ?: return hostNotFound()
            val cwd = directory ?: session.cwd.takeIf(::validAgentCwd)
                ?: return fail(409, "WORKSPACE_UNKNOWN", "该会话没有可用的工作目录，无法生成终端命令。")
            return terminalCommand(session.agent, host, cwd, session.sessionId, RendererConversationId.of(session))
        }
        val hostId = params.optString("hostId")
        if (hostId.isEmpty()) return fail(422, "TERMINAL_TARGET_UNKNOWN", "终端命令必须指定一个会话或一台主机。")
        val host = requestedHost(params) ?: return hostNotFound()
        val cwd = directory ?: return fail(422, "INVALID_CWD", "主机范围的终端命令必须给出一个工作目录。")
        val agent = terminalAgent(snapshot, host.id)
            ?: return fail(409, "AGENT_UNAVAILABLE", "该主机上没有探测到可用的 Agent，无法生成终端命令。")
        return terminalCommand(agent, host, cwd, sessionId = null, conversationId = null)
    }

    /**
     * The Agent a host-scoped terminal command starts: the Agent of the conversation this product holds for that
     * host while there is one — the choice this host already made there — otherwise the first Agent that host's
     * probe found installed, in the same order the product offers them. A host holding neither has no Agent whose
     * command could be handed over, which is refused instead of starting one nobody verified.
     */
    private fun terminalAgent(snapshot: RendererSnapshot, hostId: Long): AgentKind? {
        val open = snapshot.session?.takeIf { it.hostId == hostId }?.agent
        return open ?: agentHostChoices(snapshot.availability, hostId).firstOrNull { it.state == AgentHostState.INSTALLED }?.kind
    }

    /**
     * One terminal command, built the way the product's own terminal route builds it: the same `ShellCommands.start`
     * wrapped once for the host's Agent target. A conversation names its own session id, so the command resumes it;
     * a host-scoped command has no session to resume and therefore carries neither a conversation nor a session id
     * instead of an invented one.
     */
    private fun terminalCommand(agent: AgentKind, host: Host, cwd: String, sessionId: String?, conversationId: String?): ProbeResult {
        val remoteTarget = remoteTargetOf(host.osType, host.agentTarget)
        val command = remoteTarget.shellCommand(ShellCommands.start(agent, cwd, sessionId?.takeIf { it.isNotBlank() }))
            ?: return fail(501, "TERMINAL_UNSUPPORTED", "该主机的 Agent 目标（${remoteTarget.id}）没有本机可用的终端入口。")
        val data = json(
            "host_id" to RendererProjection.projectId(host.id),
            "host_name" to host.nickname.ifBlank { host.hostname },
            "agent" to agent.wireName,
            "cwd" to cwd,
            "target" to remoteTarget.id,
            "command" to command,
        )
        if (conversationId != null) {
            data.put("conversation_id", conversationId)
            data.put("session_id", sessionId.orEmpty())
        }
        return success(data)
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

        /** A credential answer is bounded like every other user input; a passphrase or password stays far shorter. */
        const val MAX_SECRET_CHARS = 4096

        /** One search query is bounded because every transcript line a host holds is compared against it. */
        const val MAX_QUERY = 256

        /** A queue id is the uuid this host wrote when it queued the message; anything longer names no row. */
        const val MAX_QUEUE_ID = 64
    }
}
