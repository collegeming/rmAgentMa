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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.assertj.core.api.Assertions.assertThat
import org.connectbot.agent.AgentConfigAccess
import org.connectbot.data.entity.Host
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.rmagentma.core.AgentAvailability
import org.rmagentma.core.AgentConfigOption
import org.rmagentma.core.AgentConfigValue
import org.rmagentma.core.AgentEvent
import org.rmagentma.core.AgentKind
import org.rmagentma.core.AgentOption
import org.rmagentma.core.AgentSession
import org.rmagentma.core.AgentSessionConfig

@RunWith(AndroidJUnit4::class)
class RendererActionsTest {
    private val session = AgentSession(hostId = 7, agent = AgentKind.KIMI, sessionId = "session-a", cwd = "/workspace/app", updatedAt = 1_000)
    private val host = Host(id = 7, nickname = "工作机", username = "colle", hostname = "example.test")

    @Test
    fun unsupportedRoutesFailWithAStableCodeAndReason() = runTest {
        val actions = RendererActions(FakeSource())
        val expected = mapOf(
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

    @Test
    fun configReadPublishesTheAgentsOwnCatalogueUnderTheDataEnvelope() = runTest {
        val source = openSource().apply {
            configAccess = AgentConfigAccess.Available(
                AgentSessionConfig(options = listOf(selectOption("model", "model", "gpt-5", "gpt-5", "gpt-4"))),
            )
        }
        val result = RendererActions(source).handle("config.read", payload(conversation = true))
        assertThat(result.status).isEqualTo(200)
        val data = (result.body as JSONObject).getJSONObject("data")
        val model = data.getJSONArray("config_options").getJSONObject(0)
        assertThat(model.getString("id")).isEqualTo("model")
        assertThat(model.getString("category")).isEqualTo("model")
        assertThat(model.getString("type")).isEqualTo("select")
        assertThat(model.getString("current_value")).isEqualTo("gpt-5")
        assertThat(model.getJSONArray("options").getJSONObject(0).getString("value")).isEqualTo("gpt-5")
        assertThat(model.getJSONArray("options").getJSONObject(0).getString("name")).isEqualTo("gpt-5 名称")
    }

    @Test
    fun anOptionWithoutACurrentValueIsNotShownAsChosen() = runTest {
        val source = openSource().apply {
            configAccess = AgentConfigAccess.Available(
                AgentSessionConfig(options = listOf(selectOption("model", "model", null, "gpt-5", "gpt-4"))),
            )
        }
        val model = configOptions(source).getJSONObject(0)
        assertThat(model.isNull("current_value")).isTrue()
        assertThat(model.getJSONArray("options").length()).isEqualTo(2)
    }

    @Test
    fun anUnknownOptionTypeStaysReadableInsteadOfBeingDropped() = runTest {
        val source = openSource().apply {
            configAccess = AgentConfigAccess.Available(
                AgentSessionConfig(
                    options = listOf(
                        AgentConfigOption(id = "telemetry", name = "遥测", type = "boolean", values = listOf(AgentConfigValue("true", "开"))),
                        AgentConfigOption(id = "future", name = "未来选项", type = "hologram"),
                    ),
                ),
            )
        }
        val options = configOptions(source)
        assertThat(options.length()).isEqualTo(2)
        assertThat(options.getJSONObject(0).getString("type")).isEqualTo("boolean")
        assertThat(options.getJSONObject(1).getString("type")).isEqualTo("hologram")
        // No catalogue and no current value are invented for it, and only a `select` option is drawn as a picker.
        assertThat(options.getJSONObject(1).isNull("current_value")).isTrue()
        assertThat(options.getJSONObject(1).getJSONArray("options").length()).isZero()
    }

    @Test
    fun modesAndModelsArePublishedUnderTheRoleTheRendererReads() = runTest {
        val source = openSource().apply {
            configAccess = AgentConfigAccess.Available(
                AgentSessionConfig(
                    modes = listOf(AgentConfigValue("plan", "计划"), AgentConfigValue("code", "代码")),
                    currentModeId = "code",
                    models = listOf(AgentConfigValue("gpt-5", "GPT-5")),
                ),
            )
        }
        val options = configOptions(source)
        assertThat(options.length()).isEqualTo(2)
        val mode = optionById(options, "mode")
        assertThat(mode.getString("category")).isEqualTo("mode")
        assertThat(mode.getString("type")).isEqualTo("select")
        assertThat(mode.getString("current_value")).isEqualTo("code")
        assertThat(mode.getJSONArray("options").getJSONObject(1).getString("value")).isEqualTo("code")
        val model = optionById(options, "model")
        assertThat(model.getString("category")).isEqualTo("model")
        assertThat(model.isNull("current_value")).isTrue()
    }

    @Test
    fun setterAnswersWithTheAgentsOwnNewSnapshot() = runTest {
        val source = openSource().apply {
            configAccess = AgentConfigAccess.Available(
                AgentSessionConfig(options = listOf(selectOption("model", "model", "gpt-5", "gpt-5", "gpt-4"))),
            )
            setter = { _, value ->
                AgentConfigAccess.Available(AgentSessionConfig(options = listOf(selectOption("model", "model", value, "gpt-5", "gpt-4"))))
            }
        }
        val result = RendererActions(source).handle("config.set", payload(conversation = true, params = mapOf("optionId" to "model"), body = mapOf("value" to "gpt-4")))
        assertThat(result.status).isEqualTo(200)
        val data = (result.body as JSONObject).getJSONObject("data")
        assertThat(data.getString("confirmation")).isEqualTo("observed")
        assertThat(data.getJSONArray("config_options").getJSONObject(0).getString("current_value")).isEqualTo("gpt-4")
        assertThat(source.writes).containsExactly("model" to "gpt-4")
    }

    @Test
    fun setterDistinguishesAnUnconfirmedChangeFromARejection() = runTest {
        val source = openSource().apply {
            configAccess = AgentConfigAccess.Available(
                AgentSessionConfig(options = listOf(selectOption("model", "model", "gpt-5", "gpt-5", "gpt-4"))),
            )
            // A switch the Agent takes but applies only from the next turn must not be reported as in use.
            setter = { _, _ ->
                AgentConfigAccess.Available(AgentSessionConfig(options = listOf(selectOption("model", "model", "gpt-5", "gpt-5", "gpt-4"))))
            }
        }
        val actions = RendererActions(source)
        val pending = actions.handle("config.set", payload(conversation = true, params = mapOf("optionId" to "model"), body = mapOf("value" to "gpt-4")))
        val pendingData = (pending.body as JSONObject).getJSONObject("data")
        assertThat(pendingData.getString("confirmation")).isEqualTo("pending_next_turn")
        assertThat(pendingData.getJSONArray("config_options").getJSONObject(0).getString("current_value")).isEqualTo("gpt-5")

        source.setter = { _, _ -> AgentConfigAccess.Unconfirmed((source.configAccess as AgentConfigAccess.Available).config) }
        val acked = actions.handle("config.set", payload(conversation = true, params = mapOf("optionId" to "model"), body = mapOf("value" to "gpt-4")))
        val ackedData = (acked.body as JSONObject).getJSONObject("data")
        assertThat(ackedData.getString("confirmation")).isEqualTo("command_ack")
        assertThat(ackedData.getJSONArray("config_options").getJSONObject(0).getString("current_value")).isEqualTo("gpt-5")
    }

    @Test
    fun aFailedWriteIsReportedAsAFailureAndNeverAsAChange() = runTest {
        val source = openSource().apply {
            configAccess = AgentConfigAccess.Available(
                AgentSessionConfig(options = listOf(selectOption("model", "model", "gpt-5", "gpt-5", "gpt-4"))),
            )
            setter = { _, _ -> throw IllegalStateException("ACP request failed (code -32602): unknown configId") }
        }
        val result = RendererActions(source).handle("config.set", payload(conversation = true, params = mapOf("optionId" to "model"), body = mapOf("value" to "gpt-4")))
        assertThat(result.status).isEqualTo(500)
        val body = result.body as JSONObject
        assertThat(body.getBoolean("success")).isFalse()
        assertThat(body.getString("code")).isEqualTo("HANDLE_FAILED")
        assertThat(body.getString("error")).contains("unknown configId")
        assertThat(source.writes).containsExactly("model" to "gpt-4")
    }

    @Test
    fun configurationRoutesRefuseIdsAndValuesTheCatalogueNeverOffered() = runTest {
        val source = openSource().apply {
            configAccess = AgentConfigAccess.Available(
                AgentSessionConfig(options = listOf(selectOption("model", "model", "gpt-5", "gpt-5", "gpt-4"))),
            )
        }
        val actions = RendererActions(source)
        val unknown = actions.handle("config.set", payload(conversation = true, params = mapOf("optionId" to "temperature"), body = mapOf("value" to "0.5")))
        assertThat(unknown.status).isEqualTo(404)
        assertThat((unknown.body as JSONObject).getString("code")).isEqualTo("OPTION_NOT_FOUND")
        val value = actions.handle("config.set", payload(conversation = true, params = mapOf("optionId" to "model"), body = mapOf("value" to "gpt-6")))
        assertThat(value.status).isEqualTo(422)
        assertThat((value.body as JSONObject).getString("code")).isEqualTo("INVALID_CONFIG")
        val blank = actions.handle("config.set", payload(conversation = true, params = mapOf("optionId" to "model"), body = mapOf("value" to "")))
        assertThat(blank.status).isEqualTo(422)
        assertThat(source.writes).isEmpty()
    }

    @Test
    fun configurationRoutesFailExplicitlyWithoutAnOpenAcpConversation() = runTest {
        val closed = FakeSource()
        val actions = RendererActions(closed)
        for (action in listOf("config.read", "config.set")) {
            val result = actions.handle(action, payload(conversation = true, params = mapOf("optionId" to "model"), body = mapOf("value" to "gpt-5")))
            assertThat(result.status).describedAs(action).isEqualTo(404)
            assertThat((result.body as JSONObject).getString("code")).isEqualTo("CONVERSATION_NOT_FOUND")
        }
        val loading = openSource().apply { active = false }
        val loadingActions = RendererActions(loading)
        for (action in listOf("config.read", "config.set")) {
            val result = loadingActions.handle(action, payload(conversation = true, params = mapOf("optionId" to "model"), body = mapOf("value" to "gpt-5")))
            assertThat(result.status).describedAs(action).isEqualTo(409)
        }
        // A session whose Agent has no ACP configuration surface must keep failing, not answer with an empty picker.
        val unsupported = openSource()
        for (action in listOf("config.read", "config.set")) {
            val result = RendererActions(unsupported).handle(action, payload(conversation = true, params = mapOf("optionId" to "model"), body = mapOf("value" to "gpt-5")))
            assertThat(result.status).describedAs(action).isEqualTo(501)
            assertThat((result.body as JSONObject).getString("code")).describedAs(action).isEqualTo("CONFIG_UNSUPPORTED")
        }
        // Runtime ensure answers an empty catalogue for it instead of refusing the whole conversation.
        val ensured = (RendererActions(unsupported).handle("runtime.ensure", payload(conversation = true)).body as JSONObject).getJSONObject("data")
        assertThat(ensured.getJSONArray("config_options").length()).isZero()
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

    /** The open conversation the renderer is bound to, with whatever configuration access a test hands it. */
    private fun openSource(): FakeSource = FakeSource().apply {
        session = this@RendererActionsTest.session
        active = true
    }

    private fun selectOption(id: String, category: String, current: String?, vararg values: String): AgentConfigOption = AgentConfigOption(
        id = id,
        name = id,
        category = category,
        type = "select",
        currentValue = current?.let(::JsonPrimitive),
        values = values.map { AgentConfigValue(it, "$it 名称") },
    )

    private fun optionById(catalog: JSONArray, id: String): JSONObject = (0 until catalog.length()).map { catalog.getJSONObject(it) }.first { it.getString("id") == id }

    private suspend fun configOptions(source: FakeSource): JSONArray {
        val result = RendererActions(source).handle("config.read", payload(conversation = true))
        assertThat(result.status).isEqualTo(200)
        return (result.body as JSONObject).getJSONObject("data").getJSONArray("config_options")
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
        var configAccess: AgentConfigAccess = AgentConfigAccess.Unsupported
        var setter: (String, String) -> AgentConfigAccess = { _, _ -> AgentConfigAccess.Closed }
        val writes = mutableListOf<Pair<String, String>>()
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

        override suspend fun config(): AgentConfigAccess = configAccess

        override suspend fun setConfigOption(optionId: String, value: String): AgentConfigAccess {
            writes += optionId to value
            return setter(optionId, value)
        }
    }
}
