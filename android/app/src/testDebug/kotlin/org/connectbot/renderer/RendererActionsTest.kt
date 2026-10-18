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

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.assertj.core.api.Assertions.assertThat
import org.connectbot.data.entity.Host
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.rmagentma.core.AgentAvailability
import org.rmagentma.core.AgentEvent
import org.rmagentma.core.AgentKind
import org.rmagentma.core.AgentOption
import org.rmagentma.core.AgentSession

@RunWith(AndroidJUnit4::class)
class RendererActionsTest {
    private val session = AgentSession(hostId = 7, agent = AgentKind.KIMI, sessionId = "session-a", cwd = "/workspace/app", updatedAt = 1_000)
    private val host = Host(id = 7, nickname = "工作机", username = "colle", hostname = "example.test")

    @Test
    fun unsupportedRoutesFailWithAStableCodeAndReason() = runTest {
        val actions = RendererActions(FakeSource())
        val expected = mapOf(
            "config.read" to "CONFIG_UNSUPPORTED",
            "config.set" to "CONFIG_UNSUPPORTED",
            "runtime.visible" to "LEASE_UNSUPPORTED",
            "draft.load" to "DRAFT_UNSUPPORTED",
            "draft.save" to "DRAFT_UNSUPPORTED",
            "attachment.begin" to "ATTACHMENT_UNSUPPORTED",
            "commands.list" to "COMMANDS_UNSUPPORTED",
            "artifacts.list" to "ARTIFACTS_UNSUPPORTED",
            "providers.list" to "PROVIDERS_UNSUPPORTED",
            "mcp.list" to "MCP_UNSUPPORTED",
            "skills.list" to "SKILLS_UNSUPPORTED",
            "cron.list" to "CRON_UNSUPPORTED",
            "teams.list" to "TEAMS_UNSUPPORTED",
            "settings.read" to "SETTINGS_UNSUPPORTED",
        )
        for ((action, code) in expected) {
            val result = actions.handle(action, payload(conversation = true))
            assertThat(result.status).describedAs(action).isEqualTo(501)
            val body = result.body as JSONObject
            assertThat(body.getBoolean("success")).isFalse()
            assertThat(body.getString("code")).describedAs(action).isEqualTo(code)
            // The reason travels with the code so a product surface can tell "not supported" from "failed".
            assertThat(body.getString("error")).describedAs(action).isNotBlank()
        }
    }

    @Test
    fun conversationListComesFromTheInjectedIndexRow() = runTest {
        val opened = this@RendererActionsTest.session
        val source = FakeSource().apply {
            session = opened
            host = this@RendererActionsTest.host
            indexed = opened.copy(title = "索引标题", createdAt = 500, updatedAt = 900)
            active = true
        }
        val body = (RendererActions(source).handle("conversations.list", payload()).body as JSONObject)
        assertThat(body.getBoolean("success")).isTrue()
        val conversation = body.getJSONObject("data").getJSONArray("items").getJSONObject(0)
        assertThat(conversation.getString("id")).isEqualTo(RENDERER_CONVERSATION)
        assertThat(conversation.getString("name")).isEqualTo("索引标题")
        assertThat(conversation.getString("project_id")).isEqualTo("host-7")
        assertThat(conversation.getJSONObject("extra").getString("workspace")).isEqualTo("/workspace/app")
        assertThat(conversation.getJSONObject("extra").getString("backend")).isEqualTo("kimi")
        assertThat(conversation.getLong("created_at")).isEqualTo(500)
        assertThat(conversation.getJSONObject("runtime").getString("state")).isEqualTo("idle")
        assertThat(conversation.getJSONObject("runtime").getBoolean("can_send_message")).isTrue()
    }

    @Test
    fun withoutASessionEveryConversationRouteFailsInsteadOfFakingData() = runTest {
        val actions = RendererActions(FakeSource())
        for (action in listOf("conversation.read", "messages.list", "messages.latest", "approvals.list", "runtime.ensure", "usage.read")) {
            val result = actions.handle(action, payload(conversation = true))
            assertThat(result.status).describedAs(action).isEqualTo(404)
            assertThat(((result.body as JSONObject).getString("code"))).isEqualTo("CONVERSATION_NOT_FOUND")
        }
        val list = RendererActions(FakeSource()).handle("conversations.list", payload()).body as JSONObject
        assertThat(list.getJSONObject("data").getJSONArray("items").length()).isZero()
    }

    @Test
    fun messagesUseThePersistedHistoryKeyAndValidateTheCursor() = runTest {
        val source = FakeSource().apply {
            session = this@RendererActionsTest.session
            active = true
            turnId = "turn-1"
            events = listOf(
                AgentEvent(type = "user", text = "你好", id = "local-1", historyKey = 3),
                AgentEvent(type = "content", text = "在", id = "answer", historyKey = 4),
                AgentEvent(type = "complete", status = "end_turn", historyKey = 5),
            )
        }
        val actions = RendererActions(source)
        val page = (actions.handle("messages.list", payload(conversation = true, query = mapOf("limit" to "50"))).body as JSONObject).getJSONObject("data")
        assertThat(page.getJSONArray("items").length()).isEqualTo(2)
        assertThat(page.getJSONArray("items").getJSONObject(0).getString("msg_id")).isEqualTo("m3")
        assertThat(page.getJSONArray("items").getJSONObject(0).getString("position")).isEqualTo("right")
        assertThat(page.getJSONArray("items").getJSONObject(0).getJSONObject("content").getString("content")).isEqualTo("你好")
        assertThat(page.getJSONArray("items").getJSONObject(1).getString("id")).isEqualTo("m4")
        assertThat(page.getJSONArray("items").getJSONObject(1).getJSONObject("content").getBoolean("replace")).isTrue()
        assertThat(page.getString("oldest_cursor")).isEqualTo("m3")
        assertThat(page.getString("newest_cursor")).isEqualTo("m4")

        val missing = actions.handle("messages.list", payload(conversation = true, query = mapOf("before" to "m404")))
        assertThat(missing.status).isEqualTo(422)
        assertThat((missing.body as JSONObject).getString("code")).isEqualTo("INVALID_CURSOR")

        val invalidLimit = actions.handle("messages.list", payload(conversation = true, query = mapOf("limit" to "0")))
        assertThat(invalidLimit.status).isEqualTo(422)
    }

    @Test
    fun approvalsAnswerOnlyTheLivePendingInstance() = runTest {
        val pending = AgentEvent(
            type = "permission",
            id = "req-1",
            title = "写文件",
            text = "请求写入",
            options = listOf(AgentOption("allow-once", "允许本次"), AgentOption("reject-once", "拒绝本次")),
            raw = buildJsonObject {
                put("sessionId", "session-a")
                put(
                    "toolCall",
                    buildJsonObject {
                        put("toolCallId", "tool-1")
                        put("title", "写文件")
                    },
                )
            },
        )
        val source = FakeSource().apply {
            session = this@RendererActionsTest.session
            active = true
            events = listOf(pending)
        }
        val actions = RendererActions(source)

        val listed = ((actions.handle("approvals.list", payload(conversation = true)).body as JSONObject).getJSONArray("data")).getJSONObject(0)
        assertThat(listed.getString("request_id")).isEqualTo("req-1")
        assertThat(listed.getString("conversation_id")).isEqualTo(RENDERER_CONVERSATION)
        assertThat(listed.getString("epoch")).isEqualTo("epoch-1")
        assertThat(listed.getJSONArray("options").getJSONObject(0).getString("option_id")).isEqualTo("allow-once")
        assertThat(listed.getJSONObject("tool_call").getString("tool_call_id")).isEqualTo("tool-1")
        assertThat(listed.getBoolean("readonly")).isFalse()

        // A stale reference or an option the agent never offered must not reach the agent.
        val stale = actions.handle("approvals.review", payload(conversation = true, params = mapOf("requestRef" to "tool-1"), body = mapOf("data" to "allow-once")))
        assertThat(stale.status).isEqualTo(409)
        assertThat((stale.body as JSONObject).getString("code")).isEqualTo("NOT_PENDING")
        val wrongOption = actions.handle("approvals.review", payload(conversation = true, params = mapOf("requestRef" to "req-1"), body = mapOf("data" to "allow-always")))
        assertThat(wrongOption.status).isEqualTo(422)
        assertThat(source.responded).isEmpty()

        val accepted = actions.handle("approvals.review", payload(conversation = true, params = mapOf("requestRef" to "req-1"), body = mapOf("data" to "allow-once", "epoch" to "epoch-1")))
        assertThat(accepted.status).isEqualTo(200)
        assertThat(((accepted.body as JSONObject).getJSONObject("data").getString("state"))).isEqualTo("answered")
        assertThat(source.responded.single().first).isSameAs(pending)
        assertThat(source.responded.single().second).isEqualTo("allow-once")
    }

    @Test
    fun sendRefusesUntilTheConversationIsOpenAndThenRegistersTheTurn() = runTest {
        val source = FakeSource().apply { session = this@RendererActionsTest.session }
        val actions = RendererActions(source)
        val closed = actions.handle("messages.send", payload(conversation = true, body = mapOf("content" to "你好", "loading_id" to "loading-1", "files" to JSONArray())))
        assertThat(closed.status).isEqualTo(409)
        assertThat((closed.body as JSONObject).getString("code")).isEqualTo("CONVERSATION_NOT_READY")

        source.active = true
        val empty = actions.handle("messages.send", payload(conversation = true, body = mapOf("content" to "   ")))
        assertThat(empty.status).isEqualTo(422)
        val files = actions.handle("messages.send", payload(conversation = true, body = mapOf("content" to "你好", "files" to JSONArray().put(jsonOf("kind" to "upload")))))
        assertThat(files.status).isEqualTo(422)
        assertThat((files.body as JSONObject).getString("code")).isEqualTo("ATTACHMENT_UNSUPPORTED")

        val accepted = actions.handle("messages.send", payload(conversation = true, body = mapOf("content" to "你好", "loading_id" to "loading-1")))
        assertThat(accepted.status).isEqualTo(202)
        val data = (accepted.body as JSONObject).getJSONObject("data")
        assertThat(data.getString("msg_id")).isEqualTo("m9")
        assertThat(data.getString("turn_id")).isEqualTo("turn-1")
        assertThat(data.getJSONObject("runtime").getString("state")).isEqualTo("running")
        assertThat(data.getJSONObject("runtime").getBoolean("can_send_message")).isFalse()

        val busy = actions.handle("messages.send", payload(conversation = true, body = mapOf("content" to "再来")))
        assertThat(busy.status).isEqualTo(409)
        assertThat((busy.body as JSONObject).getString("code")).isEqualTo("TURN_BUSY")
    }

    @Test
    fun identityCarriesTheHostUserNameAndNothingElse() = runTest {
        val source = FakeSource().apply {
            session = this@RendererActionsTest.session
            host = this@RendererActionsTest.host
        }
        val body = RendererActions(source).handle("auth.read", payload()).body as JSONObject
        assertThat(body.getBoolean("success")).isTrue()
        val user = body.getJSONObject("user")
        assertThat(user.getString("id")).isEqualTo("host-7")
        assertThat(user.getString("username")).isEqualTo("colle")
        assertThat(user.keys().asSequence().toList()).containsExactlyInAnyOrder("id", "username")

        val anonymous = RendererActions(FakeSource()).handle("auth.read", payload()).body as JSONObject
        assertThat(anonymous.getBoolean("success")).isFalse()
        assertThat(anonymous.getString("code")).isEqualTo("HOST_NOT_CONNECTED")
    }

    private fun payload(conversation: Boolean = false, params: Map<String, String> = emptyMap(), query: Map<String, String> = emptyMap(), body: Map<String, Any?> = emptyMap()): JSONObject {
        val resolved = if (conversation) params + ("conversationId" to RENDERER_CONVERSATION) else params
        return JSONObject()
            .put("params", JSONObject().apply { resolved.forEach { (key, value) -> put(key, value) } })
            .put("query", JSONObject().apply { query.forEach { (key, value) -> put(key, value) } })
            .put("body", JSONObject().apply { body.forEach { (key, value) -> put(key, value) } })
    }

    private fun jsonOf(vararg fields: Pair<String, Any?>): JSONObject = JSONObject().apply {
        fields.forEach { (key, value) -> put(key, value) }
    }

    /** In-memory stand-in for [RendererSource]; every fact a test needs is set explicitly. */
    private class FakeSource : RendererSource {
        var session: AgentSession? = null
        var host: Host? = null
        var indexed: AgentSession? = null
        var events: List<AgentEvent> = emptyList()
        var active = false
        var loading = false
        var turnId: String? = null
        var cancelling = false
        var hasEarlier = false
        var hosts: List<Host> = emptyList()
        var availability: List<AgentAvailability> = emptyList()
        var currentTheme = "light"
        val responded = mutableListOf<Pair<AgentEvent, String>>()
        var cancelCount = 0

        override suspend fun snapshot(): RendererSnapshot = RendererSnapshot(
            session = session,
            host = host,
            indexed = indexed,
            events = events,
            active = active,
            loading = loading,
            turnId = turnId,
            cancelling = cancelling,
            hasEarlier = hasEarlier,
            hosts = hosts,
            availability = availability,
            epoch = "epoch-1",
            seq = 4,
            theme = currentTheme,
        )

        override suspend fun send(text: String): String? {
            turnId = "turn-1"
            return "m9"
        }

        override suspend fun cancel() {
            cancelCount++
            turnId = null
        }

        override suspend fun respondPermission(event: AgentEvent, option: String): Boolean {
            responded += event to option
            return true
        }

        override fun setTheme(value: String) {
            currentTheme = value
        }
    }
}
