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

import android.Manifest
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.assertj.core.api.Assertions.assertThat
import org.connectbot.agent.AgentChallenge
import org.connectbot.agent.AgentConfigAccess
import org.connectbot.agent.AgentConfigBusyException
import org.connectbot.agent.AgentLinkState
import org.connectbot.agent.AgentMessageKind
import org.connectbot.agent.AgentMessageQueueStats
import org.connectbot.agent.AgentMessageState
import org.connectbot.agent.AgentQueuedMessage
import org.connectbot.agent.AgentTranscriptFailure
import org.connectbot.agent.AgentTranscriptHit
import org.connectbot.agent.AgentTranscriptOutcome
import org.connectbot.agent.AgentTranscriptSkip
import org.connectbot.agent.AgentTranscriptSkipReason
import org.connectbot.agent.AgentZcodeConfig
import org.connectbot.agent.ZCODE_MODEL_OPTION
import org.connectbot.data.entity.Host
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.rmagentma.core.AgentAvailability
import org.rmagentma.core.AgentConfigOption
import org.rmagentma.core.AgentConfigValue
import org.rmagentma.core.AgentEvent
import org.rmagentma.core.AgentKind
import org.rmagentma.core.AgentOption
import org.rmagentma.core.AgentSession
import org.rmagentma.core.AgentSessionConfig
import org.rmagentma.core.DirectoryEntryKind
import org.rmagentma.core.DirectoryListing
import org.rmagentma.core.DirectoryListingProblem
import org.rmagentma.core.RemoteDirectoryEntry
import org.rmagentma.core.TranscriptSearchHit
import org.rmagentma.core.TranscriptSearchProblem
import org.rmagentma.core.WINDOWS_NATIVE_UNSUPPORTED
import org.rmagentma.core.ZcodeConfigChange
import org.rmagentma.core.ZcodeConfigStatus
import org.rmagentma.core.ZcodeModelOption
import org.rmagentma.core.ZcodeModelSelection
import org.rmagentma.core.ZcodeSessionConfig
import org.rmagentma.rendererhost.ProbeBridge
import org.rmagentma.rendererhost.ProbeLimits
import org.rmagentma.rendererhost.ProbeResult
import org.rmagentma.rendererhost.json
import java.io.IOException
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class RendererActionsTest {
    @get:Rule val temporary = TemporaryFolder()

    private val session = AgentSession(hostId = 7, agent = AgentKind.KIMI, sessionId = "session-a", cwd = "/workspace/app", updatedAt = 1_000)
    private val host = Host(id = 7, nickname = "工作机", username = "colle", hostname = "example.test")

    @Test
    fun unsupportedRoutesFailWithAStableCodeAndReason() = runTest {
        val actions = RendererActions(FakeSource())
        val expected = mapOf(
            "runtime.visible" to "LEASE_UNSUPPORTED",
            "attachment.begin" to "ATTACHMENT_UNSUPPORTED",
            "attachment.chunk" to "ATTACHMENT_UNSUPPORTED",
            "attachment.finish" to "ATTACHMENT_UNSUPPORTED",
            "attachment.abort" to "ATTACHMENT_UNSUPPORTED",
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
            sessions = listOf(indexed!!)
            active = true
        }
        val body = (RendererActions(source).handle("conversations.list", payload()).body as JSONObject)
        assertThat(body.getBoolean("success")).isTrue()
        val conversation = body.getJSONObject("data").getJSONArray("items").getJSONObject(0)
        assertThat(conversation.getString("id")).isEqualTo(RendererConversationId.of(opened))
        assertThat(conversation.getString("name")).isEqualTo("索引标题")
        assertThat(conversation.getString("project_id")).isEqualTo("host-7")
        // The sidebar's Agent filter reads the row's own `agent`, so the row carries the wire value itself.
        assertThat(conversation.getString("agent")).isEqualTo("kimi")
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
        // The turn boundary is a row of its own, so the window holds its three events in their own order.
        assertThat(page.getJSONArray("items").length()).isEqualTo(3)
        assertThat(page.getJSONArray("items").getJSONObject(0).getString("msg_id")).isEqualTo("m3")
        assertThat(page.getJSONArray("items").getJSONObject(0).getString("position")).isEqualTo("right")
        assertThat(page.getJSONArray("items").getJSONObject(0).getJSONObject("content").getString("content")).isEqualTo("你好")
        assertThat(page.getJSONArray("items").getJSONObject(1).getString("id")).isEqualTo("m4")
        assertThat(page.getJSONArray("items").getJSONObject(1).getJSONObject("content").getBoolean("replace")).isTrue()
        assertThat(page.getJSONArray("items").getJSONObject(2).getString("id")).isEqualTo("m5")
        assertThat(page.getJSONArray("items").getJSONObject(2).getString("type")).isEqualTo("finish")
        assertThat(page.getString("oldest_cursor")).isEqualTo("m3")
        assertThat(page.getString("newest_cursor")).isEqualTo("m5")

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
        // A file reference this host cannot hand to the Agent is refused as its own reason, not as the send path.
        val files = actions.handle("messages.send", payload(conversation = true, body = mapOf("content" to "你好", "files" to JSONArray().put(jsonOf("kind" to "upload")))))
        assertThat(files.status).isEqualTo(422)
        assertThat((files.body as JSONObject).getString("code")).isEqualTo("INVALID_FILE_REF")

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

    /**
     * A message that names uploaded files carries their absolute paths into the prompt itself, because that is the
     * only place this host's text-only send path can put them: the files stay where the upload landed, the Agent
     * reads the paths, and a file the caller named twice reaches it once.
     */
    @Test
    fun sendCarriesTheUploadedFilePathsIntoThePromptTheAgentReceives() = runTest {
        val source = openSource()
        val actions = RendererActions(source)
        val files = JSONArray()
            .put(jsonOf("kind" to "upload", "path" to "/workspace/app/shot.png"))
            .put("/workspace/app/data.csv")
            .put(jsonOf("kind" to "upload", "path" to "/workspace/app/shot.png"))
        val accepted = actions.handle("messages.send", payload(conversation = true, body = mapOf("content" to "看看这两个文件", "files" to files)))
        assertThat(accepted.status).isEqualTo(202)
        assertThat(source.sends.single().second).isEqualTo(session)
        assertThat(source.sends.single().first).isEqualTo(
            "看看这两个文件\n\n附件路径（已上传到本会话目录，可直接读取）：\n/workspace/app/shot.png\n/workspace/app/data.csv",
        )
    }

    /** A message without files still sends exactly the text the user typed, with no block added to it. */
    @Test
    fun sendWithoutFilesSendsTheTextAlone() = runTest {
        val bodies = listOf(mapOf("content" to "只有文字"), mapOf("content" to "只有文字", "files" to JSONArray()))
        for (body in bodies) {
            val source = openSource()
            val accepted = RendererActions(source).handle("messages.send", payload(conversation = true, body = body))
            assertThat(accepted.status).describedAs(body.toString()).isEqualTo(202)
            assertThat(source.sends.single().first).isEqualTo("只有文字")
        }
    }

    /**
     * Every file reference this host cannot hand to the Agent is refused as itself, and nothing is sent: a path the
     * Agent could not read, or a reference naming a file in a workspace this host does not hold.
     */
    @Test
    fun sendRefusesFileReferencesThatNameNoAbsolutePath() = runTest {
        val source = openSource()
        val actions = RendererActions(source)
        val refusals = mapOf(
            "relative path" to JSONArray().put(jsonOf("kind" to "upload", "path" to "shot.png")),
            "control character" to JSONArray().put(jsonOf("kind" to "upload", "path" to "/workspace/a\u0001b.txt")),
            "over-long path" to JSONArray().put(jsonOf("kind" to "upload", "path" to "/" + "a".repeat(1024))),
            "project reference" to JSONArray().put(jsonOf("kind" to "project", "pe_id" to "p1", "relative_path" to "src/a.kt")),
            "upload reference without a path" to JSONArray().put(jsonOf("kind" to "upload")),
            "path that is not a string" to JSONArray().put(jsonOf("kind" to "upload", "path" to 7)),
            "empty path" to JSONArray().put(jsonOf("kind" to "upload", "path" to "")),
            "element that is not a reference" to JSONArray().put(JSONObject.NULL),
        )
        for ((label, files) in refusals) {
            val refused = actions.handle("messages.send", payload(conversation = true, body = mapOf("content" to "你好", "files" to files)))
            assertThat(refused.status).describedAs(label).isEqualTo(422)
            assertThat(codeOf(refused)).describedAs(label).isEqualTo("INVALID_FILE_REF")
        }
        assertThat(source.sends).isEmpty()
        assertThat(source.turnId).isNull()
    }

    /** One message carries a bounded number of files; more than that is refused instead of being trimmed. */
    @Test
    fun sendRefusesMoreFilesThanOneMessageMayCarry() = runTest {
        val source = openSource()
        val actions = RendererActions(source)
        val tooMany = JSONArray()
        repeat(17) { tooMany.put(jsonOf("kind" to "upload", "path" to "/workspace/app/f$it.txt")) }
        val refused = actions.handle("messages.send", payload(conversation = true, body = mapOf("content" to "你好", "files" to tooMany)))
        assertThat(refused.status).isEqualTo(422)
        assertThat(codeOf(refused)).isEqualTo("TOO_MANY_FILES")
        assertThat(source.sends).isEmpty()

        // Sixteen files are still one message, and each of their paths really reaches the Agent.
        val sixteen = JSONArray()
        repeat(16) { sixteen.put(jsonOf("kind" to "upload", "path" to "/workspace/app/f$it.txt")) }
        val accepted = actions.handle("messages.send", payload(conversation = true, body = mapOf("content" to "你好", "files" to sixteen)))
        assertThat(accepted.status).isEqualTo(202)
        assertThat(source.sends.single().first).contains("/workspace/app/f0.txt").contains("/workspace/app/f15.txt")
    }

    /**
     * The attachment block counts against the message bound, and a prompt that no longer fits is refused whole:
     * cutting the user's text to make room would send the Agent words the user never wrote.
     */
    @Test
    fun sendRefusesAPromptTheAttachmentBlockNoLongerFitsWithin() = runTest {
        val source = openSource()
        val actions = RendererActions(source)
        val file = jsonOf("kind" to "upload", "path" to "/workspace/app/shot.png")
        // The text alone fits exactly; the file block is what no longer does.
        val text = "长".repeat(ProbeLimits.CONTENT_UNITS)
        val tooLarge = actions.handle("messages.send", payload(conversation = true, body = mapOf("content" to text, "files" to JSONArray().put(file))))
        assertThat(tooLarge.status).isEqualTo(413)
        assertThat(codeOf(tooLarge)).isEqualTo("MESSAGE_TOO_LARGE")
        assertThat(source.sends).isEmpty()

        val accepted = actions.handle("messages.send", payload(conversation = true, body = mapOf("content" to "短文本", "files" to JSONArray().put(file))))
        assertThat(accepted.status).isEqualTo(202)
        assertThat(source.sends.single().first).startsWith("短文本").contains("/workspace/app/shot.png")
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
    fun projectReadNamesTheConversationsWorkingDirectoryAsItsWorkspaceRoot() = runTest {
        val source = FakeSource().apply {
            hosts = listOf(this@RendererActionsTest.host)
            session = this@RendererActionsTest.session
            indexed = this@RendererActionsTest.session
            active = true
            workspace = DirectoryListing.Listed(
                listOf(RemoteDirectoryEntry("main.kt", DirectoryEntryKind.FILE, 12)),
                truncated = true,
            )
        }
        val actions = RendererActions(source)

        val listed = (actions.handle("projects.list", payload()).body as JSONObject).getJSONArray("data")
        assertThat(listed.length()).isEqualTo(1)
        assertThat(listed.getJSONObject(0).getString("project_id")).isEqualTo("host-7")

        val read = actions.handle("project.read", payload(params = mapOf("projectId" to "host-7")))
        assertThat(read.status).isEqualTo(200)
        val project = (read.body as JSONObject).getJSONObject("data")
        assertThat(project.getString("project_id")).isEqualTo("host-7")
        assertThat(project.getString("name")).isEqualTo("工作机")
        val explorer = project.getJSONObject("explorer")
        // The renderer resolves the workspace root by this id, so it must be the entry's own pe_id, derived
        // from the host rather than from any state of the directory that was listed.
        assertThat(explorer.getString("workspace_pe_id")).isEqualTo("pe-host-7")
        val root = explorer.getJSONArray("entries").getJSONObject(0)
        assertThat(root.getString("pe_id")).isEqualTo("pe-host-7")
        assertThat(root.getString("role")).isEqualTo("workspace")
        assertThat(root.getString("display_name")).isEqualTo("app")
        assertThat(root.getString("display_path")).isEqualTo("/workspace/app")
        assertThat(root.getInt("order_index")).isZero()
        assertThat(root.getString("runtime_status")).isEqualTo("available")
        assertThat(root.has("runtime_problem")).isFalse()
        // The directory really was listed, on the host behind the project id.
        assertThat(source.workspaceCalls).containsExactly(7L to "/workspace/app")

        val missing = actions.handle("project.read", payload(params = mapOf("projectId" to "host-99")))
        assertThat(missing.status).isEqualTo(404)
        assertThat((missing.body as JSONObject).getString("code")).isEqualTo("PROJECT_NOT_FOUND")
    }

    @Test
    fun workspaceProblemsAreReportedAsThemselvesInsteadOfAnEmptyListing() = runTest {
        val expected = mapOf(
            DirectoryListingProblem.MISSING to "missing",
            DirectoryListingProblem.NOT_A_DIRECTORY to "missing",
            DirectoryListingProblem.NOT_READABLE to "permission_denied",
            DirectoryListingProblem.TIMEOUT to "disconnected",
            DirectoryListingProblem.TOOL_MISSING to "disconnected",
            DirectoryListingProblem.FAILED to "disconnected",
        )
        for ((problem, status) in expected) {
            val source = openSourceWithWorkspace { workspace = DirectoryListing.Unavailable(problem) }
            val root = projectRoot(RendererActions(source), "host-7")
            assertThat(root.getString("runtime_status")).describedAs(problem.code).isEqualTo(status)
            assertThat(root.getString("runtime_problem")).describedAs(problem.code).isEqualTo(problem.code)
        }

        // A host this app cannot reach at all never read a directory, so the root says exactly that.
        val unreachable = openSourceWithWorkspace { workspaceFailure = IOException("Host 7 is not connected") }
        val root = projectRoot(RendererActions(unreachable), "host-7")
        assertThat(root.getString("runtime_status")).isEqualTo("disconnected")
        assertThat(root.getString("runtime_problem")).isEqualTo("host_unreachable")
        assertThat(root.getString("display_path")).isEqualTo("/workspace/app")
    }

    @Test
    fun projectWithoutAKnownWorkspaceFailsInsteadOfAnsweringAnEmptyFolder() = runTest {
        // No open conversation and no indexed row: the product holds no directory for this host.
        val source = FakeSource().apply { hosts = listOf(this@RendererActionsTest.host) }
        val result = RendererActions(source).handle("project.read", payload(params = mapOf("projectId" to "host-7")))
        assertThat(result.status).isEqualTo(409)
        assertThat((result.body as JSONObject).getString("code")).isEqualTo("WORKSPACE_UNKNOWN")
        assertThat(source.workspaceCalls).isEmpty()

        // The newest indexed session of the host is a directory the product really holds, and it is listed.
        val indexedOnly = FakeSource().apply {
            hosts = listOf(this@RendererActionsTest.host)
            sessions = listOf(
                this@RendererActionsTest.session.copy(cwd = "/workspace/old", updatedAt = 10),
                this@RendererActionsTest.session.copy(cwd = "/workspace/new", updatedAt = 20),
            )
        }
        assertThat(projectRoot(RendererActions(indexedOnly), "host-7").getString("display_path")).isEqualTo("/workspace/new")
        assertThat(indexedOnly.workspaceCalls).containsExactly(7L to "/workspace/new")
    }

    // ==================== Hosts and Agent detection ====================

    @Test
    fun theHostListIsTheProductsOwnHostsWithTheProbeVerdictEachOfThemHolds() = runTest {
        val windows = savedHost.copy(id = 8, nickname = "windows-box", osType = "windows", agentTarget = "native")
        val source = FakeSource().apply {
            hosts = listOf(savedHost, windows)
            availability = listOf(availableRow(7), missingRow(8))
        }
        val result = RendererActions(source).handle("hosts.list", payload())
        assertThat(result.status).isEqualTo(200)
        val data = dataOf(result)
        assertThat(data.getInt("total")).isEqualTo(2)
        val rows = data.getJSONArray("items")
        val linux = rows.getJSONObject(0)
        assertThat(linux.getString("id")).isEqualTo("host-7")
        assertThat(linux.getString("nickname")).isEqualTo("工作机")
        assertThat(linux.getString("hostname")).isEqualTo("example.test")
        assertThat(linux.getInt("port")).isEqualTo(22)
        assertThat(linux.getString("username")).isEqualTo("colle")
        assertThat(linux.getString("protocol")).isEqualTo("ssh")
        assertThat(linux.getString("os_type")).isEqualTo("linux")
        assertThat(linux.getString("agent_target")).isEqualTo("native")
        assertThat(linux.getString("target")).isEqualTo("posix")
        assertThat(linux.getBoolean("target_supported")).isTrue()
        val detection = linux.getJSONObject("detection")
        assertThat(detection.getBoolean("checked")).isTrue()
        val kimi = detection.getJSONArray("agents").getJSONObject(0)
        assertThat(kimi.getString("id")).isEqualTo("kimi")
        assertThat(kimi.getString("state")).isEqualTo("available")
        assertThat(kimi.getString("executable")).isEqualTo("/usr/local/bin/kimi")
        assertThat(kimi.getString("version")).isEqualTo("1.0.0")
        assertThat(kimi.getJSONObject("capabilities").getBoolean("acp")).isTrue()

        // A Windows host whose Agent runs on Windows itself is entered on that host: the row names the shell
        // context it addresses, and what the last probe found there.
        val native = rows.getJSONObject(1)
        assertThat(native.getString("os_type")).isEqualTo("windows")
        assertThat(native.getString("agent_target")).isEqualTo("native")
        assertThat(native.getString("target")).isEqualTo("windows-native")
        assertThat(native.getBoolean("target_supported")).isTrue()
        val notInstalled = native.getJSONObject("detection").getJSONArray("agents").getJSONObject(0)
        assertThat(notInstalled.getString("state")).isEqualTo("not_installed")
        assertThat(notInstalled.getString("error")).isEqualTo("executable_missing")
        assertThat(notInstalled.getString("executable")).isEmpty()
    }

    @Test
    fun anUnprobedHostIsListedAsUncheckedInsteadOfInstalled() = runTest {
        val source = FakeSource().apply { hosts = listOf(savedHost) }
        val row = dataOf(RendererActions(source).handle("hosts.list", payload())).getJSONArray("items").getJSONObject(0)
        val detection = row.getJSONObject("detection")
        assertThat(detection.getBoolean("checked")).isFalse()
        assertThat(detection.getJSONArray("agents").length()).isZero()
    }

    @Test
    fun creatingAHostStoresEveryFieldInTheBodyAndAnswersTheStoredRow() = runTest {
        val source = FakeSource()
        val result = RendererActions(source).handle(
            "hosts.create",
            payload(
                body = mapOf(
                    "nickname" to "新机",
                    "hostname" to "192.0.2.10",
                    "port" to 2222,
                    "username" to "colle",
                    "protocol" to "ssh",
                    "os_type" to "windows",
                    "agent_target" to "wsl:Ubuntu-24.04",
                ),
            ),
        )
        assertThat(result.status).isEqualTo(200)
        val stored = source.savedHosts.single()
        // The insert is the product's own SSH host helper: a new id, no key association, every other column default.
        assertThat(stored.id).isEqualTo(100L)
        assertThat(stored.nickname).isEqualTo("新机")
        assertThat(stored.hostname).isEqualTo("192.0.2.10")
        assertThat(stored.port).isEqualTo(2222)
        assertThat(stored.username).isEqualTo("colle")
        assertThat(stored.protocol).isEqualTo("ssh")
        assertThat(stored.osType).isEqualTo("windows")
        assertThat(stored.agentTarget).isEqualTo("wsl:Ubuntu-24.04")
        assertThat(stored.pubkeyId).isEqualTo(-1L)
        val answered = dataOf(result)
        assertThat(answered.getString("id")).isEqualTo("host-100")
        assertThat(answered.getString("target")).isEqualTo("wsl:Ubuntu-24.04")
        assertThat(answered.getBoolean("target_supported")).isTrue()
        assertThat(answered.getJSONObject("detection").getBoolean("checked")).isFalse()

        // Only the nickname and the address are required; everything else is what a new SSH host carries.
        val minimal = RendererActions(source).handle("hosts.create", payload(body = mapOf("nickname" to "默认机", "hostname" to "default.test")))
        assertThat(minimal.status).isEqualTo(200)
        val defaults = source.savedHosts.last()
        assertThat(defaults.port).isEqualTo(22)
        assertThat(defaults.username).isEmpty()
        assertThat(defaults.osType).isEqualTo("linux")
        assertThat(defaults.agentTarget).isEqualTo("native")

        // A Windows host that holds its Agent on Windows itself stores the literal the renderer sends for it.
        val windows = RendererActions(source).handle(
            "hosts.create",
            payload(body = mapOf("nickname" to "windows-box", "hostname" to "windows.test", "os_type" to "windows", "agent_target" to "windows")),
        )
        assertThat(windows.status).isEqualTo(200)
        assertThat(source.savedHosts.last().agentTarget).isEqualTo("windows")
        assertThat(dataOf(windows).getString("target")).isEqualTo("windows-native")
        assertThat(dataOf(windows).getBoolean("target_supported")).isTrue()
    }

    @Test
    fun aHostIsNotCreatedFromATakenNicknameOrAFieldNothingCanUse() = runTest {
        val source = FakeSource().apply { hosts = listOf(savedHost) }
        val taken = RendererActions(source).handle("hosts.create", payload(body = mapOf("nickname" to "工作机", "hostname" to "other.test")))
        assertThat(taken.status).isEqualTo(409)
        assertThat(codeOf(taken)).isEqualTo("HOST_NICKNAME_TAKEN")

        val refused = listOf(
            mapOf("nickname" to " ", "hostname" to "a.test"),
            mapOf("hostname" to "a.test"),
            mapOf("nickname" to "n"),
            mapOf("nickname" to "n", "hostname" to "a.test", "port" to 0),
            mapOf("nickname" to "n", "hostname" to "a.test", "port" to 65536),
            mapOf("nickname" to "n", "hostname" to "a.test", "port" to "22.5"),
            mapOf("nickname" to "n", "hostname" to "a.test", "port" to "ssh"),
            mapOf("nickname" to "n", "hostname" to "a.test", "port" to true),
            mapOf("nickname" to "n", "hostname" to "a.test", "username" to "col\nle"),
            mapOf("nickname" to "n", "hostname" to "a.test", "os_type" to "plan9"),
            mapOf("nickname" to "n", "hostname" to "a.test", "os_type" to "windows", "agent_target" to "Ubuntu"),
            mapOf("nickname" to "n", "hostname" to "a.test", "os_type" to "windows", "agent_target" to "plan9"),
            mapOf("nickname" to "n", "hostname" to "a.test", "protocol" to "telnet"),
        )
        for (body in refused) {
            val result = RendererActions(source).handle("hosts.create", payload(body = body))
            assertThat(result.status).describedAs(body.toString()).isEqualTo(422)
            assertThat(codeOf(result)).describedAs(body.toString()).isEqualTo("HOST_INVALID")
            // The reason travels with the code so the page can tell which field it has to fix.
            assertThat((result.body as JSONObject).getString("error")).describedAs(body.toString()).isNotBlank()
        }
        // Nothing was written for any refused request, and the stored row is still the one that was there.
        assertThat(source.savedHosts).isEmpty()
        assertThat(source.hosts).containsExactly(savedHost)
    }

    @Test
    fun changingAHostsAddressRewritesThatAddressAlone() = runTest {
        val source = FakeSource().apply { hosts = listOf(savedHost) }
        val result = RendererActions(source).handle(
            "hosts.update",
            payload(params = mapOf("hostId" to "host-7"), body = mapOf("hostname" to "198.51.100.7")),
        )
        assertThat(result.status).isEqualTo(200)
        val stored = source.savedHosts.single()
        assertThat(stored.id).isEqualTo(7L)
        assertThat(stored.hostname).isEqualTo("198.51.100.7")
        // Everything else about this host is the row it already was: its key association, profile, jump host, the
        // algorithm its known host was trusted under and the fields the page never sent stay untouched.
        assertThat(stored.nickname).isEqualTo("工作机")
        assertThat(stored.username).isEqualTo("colle")
        assertThat(stored.port).isEqualTo(22)
        assertThat(stored.pubkeyId).isEqualTo(42L)
        assertThat(stored.profileId).isEqualTo(3L)
        assertThat(stored.jumpHostId).isEqualTo(5L)
        assertThat(stored.hostKeyAlgo).isEqualTo("ssh-ed25519")
        assertThat(stored.color).isEqualTo("blue")
        assertThat(stored.lastConnect).isEqualTo(1_700_000_000_000L)
        assertThat(dataOf(result).getString("hostname")).isEqualTo("198.51.100.7")
    }

    @Test
    fun aHostIsNotRenamedOntoAnotherHostsNickname() = runTest {
        val second = savedHost.copy(id = 8, nickname = "备用机")
        val source = FakeSource().apply { hosts = listOf(savedHost, second) }
        val taken = RendererActions(source).handle(
            "hosts.update",
            payload(params = mapOf("hostId" to "host-7"), body = mapOf("nickname" to "备用机")),
        )
        assertThat(taken.status).isEqualTo(409)
        assertThat(codeOf(taken)).isEqualTo("HOST_NICKNAME_TAKEN")
        assertThat(source.savedHosts).isEmpty()

        // Keeping the name it already has is not a conflict with itself.
        val kept = RendererActions(source).handle(
            "hosts.update",
            payload(params = mapOf("hostId" to "host-7"), body = mapOf("nickname" to "工作机", "username" to "root")),
        )
        assertThat(kept.status).isEqualTo(200)
        assertThat(source.savedHosts.single().username).isEqualTo("root")
    }

    @Test
    fun aWindowsHostCanBeGivenOneWslDistributionAndNothingElse() = runTest {
        val windows = savedHost.copy(id = 8, nickname = "windows-box", osType = "windows", agentTarget = "native")
        val source = FakeSource().apply { hosts = listOf(windows) }
        val moved = RendererActions(source).handle(
            "hosts.update",
            payload(params = mapOf("hostId" to "host-8"), body = mapOf("agent_target" to "wsl:Ubuntu-24.04")),
        )
        assertThat(moved.status).isEqualTo(200)
        assertThat(source.savedHosts.single().agentTarget).isEqualTo("wsl:Ubuntu-24.04")
        assertThat(source.savedHosts.single().osType).isEqualTo("windows")
        assertThat(dataOf(moved).getString("target")).isEqualTo("wsl:Ubuntu-24.04")

        // A value that could not survive the Windows shell, or an OS this app cannot address, is refused whole.
        for (body in listOf(
            mapOf("agent_target" to "Ubuntu 24.04; rm -rf /"),
            mapOf("agent_target" to "wsl:"),
            mapOf("os_type" to "windows 11"),
        )) {
            val result = RendererActions(source).handle("hosts.update", payload(params = mapOf("hostId" to "host-8"), body = body))
            assertThat(result.status).describedAs(body.toString()).isEqualTo(422)
            assertThat(codeOf(result)).isEqualTo("HOST_INVALID")
        }
        assertThat(source.savedHosts).hasSize(1)
    }

    @Test
    fun deletingAHostRemovesThatHostAlone() = runTest {
        val source = FakeSource().apply { hosts = listOf(savedHost, savedHost.copy(id = 8, nickname = "备用机")) }
        val result = RendererActions(source).handle("hosts.delete", payload(params = mapOf("hostId" to "host-7")))
        assertThat(result.status).isEqualTo(200)
        assertThat(dataOf(result).getBoolean("deleted")).isTrue()
        assertThat(source.deletedHosts.single().id).isEqualTo(7L)
        assertThat(source.deletedHosts.single().nickname).isEqualTo("工作机")

        // The host that was not named is still there, and the removed one cannot be deleted twice.
        assertThat(source.hosts.map { it.id }).containsExactly(8L)
        val again = RendererActions(source).handle("hosts.delete", payload(params = mapOf("hostId" to "host-7")))
        assertThat(again.status).isEqualTo(404)
        assertThat(codeOf(again)).isEqualTo("HOST_NOT_FOUND")
    }

    @Test
    fun aRefreshAnswersEveryAgentsStateWithThePathVersionAndCapabilitiesItFound() = runTest {
        val source = FakeSource().apply {
            hosts = listOf(savedHost)
            probeRows = mapOf(7L to AgentKind.entries.map { kind -> if (kind == AgentKind.KIMI) availableRow(7) else missingRow(7, kind) })
        }
        val result = RendererActions(source).handle("detection.refresh", payload(params = mapOf("hostId" to "host-7")))
        assertThat(result.status).isEqualTo(200)
        assertThat(source.probedHosts).containsExactly(7L)
        val data = dataOf(result)
        assertThat(data.getString("host_id")).isEqualTo("host-7")
        assertThat(data.getString("target")).isEqualTo("posix")
        assertThat(data.getBoolean("target_supported")).isTrue()
        val detection = data.getJSONObject("detection")
        assertThat(detection.getBoolean("checked")).isTrue()
        val agents = detection.getJSONArray("agents")
        assertThat(agents.length()).isEqualTo(AgentKind.entries.size)
        val kimi = agents.getJSONObject(0)
        assertThat(kimi.getString("id")).isEqualTo("kimi")
        assertThat(kimi.getString("state")).isEqualTo("available")
        assertThat(kimi.getString("executable")).isEqualTo("/usr/local/bin/kimi")
        assertThat(kimi.getString("version")).isEqualTo("1.0.0")
        assertThat(kimi.getJSONObject("capabilities").getBoolean("acp")).isTrue()
        val dsh = agents.getJSONObject(AgentKind.DSH.ordinal)
        assertThat(dsh.getString("state")).isEqualTo("not_installed")
        assertThat(dsh.getString("executable")).isEmpty()
        assertThat(dsh.getString("error")).isEqualTo("executable_missing")
    }

    @Test
    fun aWindowsHostsOwnAgentTargetIsEnteredAndReportsThePathsFoundThere() = runTest {
        val windows = savedHost.copy(id = 8, nickname = "windows-box", osType = "windows", agentTarget = "windows")
        val detected = AgentAvailability(8L, AgentKind.KIMI, "C:\\Users\\colle\\AppData\\Local\\kimi\\kimi.exe", true)
        val source = FakeSource().apply {
            hosts = listOf(windows)
            probeRows = mapOf(8L to listOf(detected) + AgentKind.entries.filterNot { it == AgentKind.KIMI }.map { missingRow(8, it) })
        }
        val data = dataOf(RendererActions(source).handle("detection.refresh", payload(params = mapOf("hostId" to "host-8"))))
        assertThat(data.getBoolean("target_supported")).isTrue()
        assertThat(data.getString("target")).isEqualTo("windows-native")
        val agents = data.getJSONObject("detection").getJSONArray("agents")
        assertThat(agents.length()).isEqualTo(AgentKind.entries.size)
        val kimi = agents.getJSONObject(AgentKind.KIMI.ordinal)
        assertThat(kimi.getString("state")).isEqualTo("available")
        assertThat(kimi.getString("executable")).isEqualTo("C:\\Users\\colle\\AppData\\Local\\kimi\\kimi.exe")
        // An Agent the Windows lookup did not find is reported as not installed, never as a guessed path.
        val dsh = agents.getJSONObject(AgentKind.DSH.ordinal)
        assertThat(dsh.getString("state")).isEqualTo("not_installed")
        assertThat(dsh.getString("executable")).isEmpty()
    }

    @Test
    fun aProbeThatNeverReachedTheHostKeepsTheValuesItDetectedBefore() = runTest {
        val before = availableRow(7)
        val source = FakeSource().apply {
            hosts = listOf(savedHost)
            availability = listOf(before)
            // The repository keeps what it detected before and reports the failure as the row's own error, so the
            // page still knows where this Agent was found and why that path is not confirmed right now.
            probeRows = mapOf(
                7L to listOf(AgentAvailability(7L, AgentKind.KIMI, before.executable, false, "probe_transport_failed", before.version, before.capabilities)),
            )
        }
        val kimi = dataOf(RendererActions(source).handle("detection.refresh", payload(params = mapOf("hostId" to "host-7"))))
            .getJSONObject("detection").getJSONArray("agents").getJSONObject(0)
        assertThat(kimi.getString("state")).isEqualTo("connect_failed")
        assertThat(kimi.getString("executable")).isEqualTo("/usr/local/bin/kimi")
        assertThat(kimi.getString("version")).isEqualTo("1.0.0")
        assertThat(kimi.getJSONObject("capabilities").getBoolean("acp")).isTrue()
        assertThat(kimi.getString("error")).isEqualTo("probe_transport_failed")
    }

    @Test
    fun aHostThisPhoneDoesNotHaveIsNeverSavedDeletedOrProbed() = runTest {
        val source = FakeSource()
        for (action in listOf("hosts.update", "hosts.delete", "detection.refresh")) {
            val result = RendererActions(source).handle(action, payload(params = mapOf("hostId" to "host-404")))
            assertThat(result.status).describedAs(action).isEqualTo(404)
            assertThat(codeOf(result)).describedAs(action).isEqualTo("HOST_NOT_FOUND")
        }
        // An id that is not one this host hands out names no host either, so nothing is written for it.
        val unknown = RendererActions(source).handle("hosts.delete", payload(params = mapOf("hostId" to "7")))
        assertThat(unknown.status).isEqualTo(404)
        assertThat(source.deletedHosts).isEmpty()
        assertThat(source.savedHosts).isEmpty()
        assertThat(source.probedHosts).isEmpty()
    }

    @Test
    fun aRepositoryFailureKeepsItsOwnCodeAndNeverLooksLikeSuccess() = runTest {
        val failingWrite = FakeSource().apply {
            hosts = listOf(savedHost)
            hostFailure = IOException("数据库不可写")
        }
        val create = RendererActions(failingWrite).handle("hosts.create", payload(body = mapOf("nickname" to "n", "hostname" to "a.test")))
        assertThat(create.status).isEqualTo(500)
        assertThat(codeOf(create)).isEqualTo("HOST_SAVE_FAILED")
        val update = RendererActions(failingWrite).handle(
            "hosts.update",
            payload(params = mapOf("hostId" to "host-7"), body = mapOf("hostname" to "b.test")),
        )
        assertThat(update.status).isEqualTo(500)
        assertThat(codeOf(update)).isEqualTo("HOST_SAVE_FAILED")
        val delete = RendererActions(failingWrite).handle("hosts.delete", payload(params = mapOf("hostId" to "host-7")))
        assertThat(delete.status).isEqualTo(500)
        assertThat(codeOf(delete)).isEqualTo("HOST_DELETE_FAILED")

        val failingProbe = FakeSource().apply {
            hosts = listOf(savedHost)
            detectionFailure = IOException("探测通道不可用")
        }
        val refresh = RendererActions(failingProbe).handle("detection.refresh", payload(params = mapOf("hostId" to "host-7")))
        assertThat(refresh.status).isEqualTo(500)
        assertThat(codeOf(refresh)).isEqualTo("DETECTION_FAILED")
        for (result in listOf(create, update, delete, refresh)) {
            assertThat((result.body as JSONObject).getBoolean("success")).isFalse()
            assertThat((result.body as JSONObject).getString("error")).isNotBlank()
        }
    }

    @Test
    fun theSharedBridgeAcceptsTheHostRoutesAndKeepsTheirParametersToHostId() {
        val bridge = ProbeBridge(projectId = null)
        bridge.commit(ProbeBridge.ENTRY)
        val seen = mutableListOf<String>()
        val execute = { action: String, payload: JSONObject ->
            seen += "$action:${payload.getJSONObject("params").optString("hostId")}"
            ProbeResult(body = json("success" to true))
        }
        val handshake = bridge.receive(ProbeBridge.ORIGIN, true, bridgeRequest("host.handshake", "", "renderer-1", JSONObject()), execute)!!
        val document = handshake.getJSONObject("body").getString("documentId")
        var serial = 2
        for (action in listOf("hosts.list", "hosts.create")) {
            val response = bridge.receive(ProbeBridge.ORIGIN, true, bridgeRequest(action, document, "renderer-${serial++}", JSONObject()), execute)!!
            assertThat(response.getInt("status")).describedAs(action).isEqualTo(200)
        }
        for (action in listOf("hosts.update", "hosts.delete", "detection.refresh")) {
            val params = JSONObject().put("hostId", "host-7")
            val response = bridge.receive(ProbeBridge.ORIGIN, true, bridgeRequest(action, document, "renderer-${serial++}", params), execute)!!
            assertThat(response.getInt("status")).describedAs(action).isEqualTo(200)
        }
        assertThat(seen).containsExactly("hosts.list:", "hosts.create:", "hosts.update:host-7", "hosts.delete:host-7", "detection.refresh:host-7")

        // A host route carrying a conversation id instead of a host id is refused by the bridge, not by the action.
        val refused = bridge.receive(
            ProbeBridge.ORIGIN,
            true,
            bridgeRequest("hosts.delete", document, "renderer-${serial++}", JSONObject().put("conversationId", RENDERER_CONVERSATION)),
            execute,
        )!!
        assertThat(refused.getInt("status")).isEqualTo(400)
        assertThat(refused.getJSONObject("body").getString("code")).isEqualTo("INVALID_REQUEST")
    }

    // ==================== Questions, challenges, history paging, terminal and refresh ====================

    @Test
    fun questionMessagesCarryTheAgentsOwnFormAndOnlyTheNewestOneIsAnswered() = runTest {
        val question = questionEvent("ask-1", "使用哪个分支？", 6)
        val source = openSource().apply { events = listOf(question) }
        val actions = RendererActions(source)
        val items = (actions.handle("messages.list", payload(conversation = true)).body as JSONObject).getJSONObject("data").getJSONArray("items")
        assertThat(items.length()).isEqualTo(1)
        val row = items.getJSONObject(0)
        assertThat(row.getString("type")).isEqualTo("acp_question")
        assertThat(row.getString("position")).isEqualTo("left")
        val content = row.getJSONObject("content")
        assertThat(content.getString("question_id")).isEqualTo("ask-1")
        assertThat(content.getString("request_id")).isEqualTo("ask-1")
        assertThat(content.getString("conversation_id")).isEqualTo(RENDERER_CONVERSATION)
        assertThat(content.getString("message")).isEqualTo("使用哪个分支？")
        assertThat(content.getBoolean("single_string")).isTrue()
        assertThat(content.getBoolean("live")).isTrue()
        assertThat(content.getBoolean("readonly")).isFalse()
        assertThat(content.getString("state")).isEqualTo("waiting")
        assertThat(content.getJSONObject("requested_schema").getString("type")).isEqualTo("object")
        val field = content.getJSONArray("fields").getJSONObject(0)
        assertThat(field.getString("name")).isEqualTo("branch")
        assertThat(field.getString("label")).isEqualTo("分支")
        assertThat(field.getString("type")).isEqualTo("string")
        assertThat(field.getBoolean("required")).isTrue()
        assertThat(field.getJSONArray("choices").getString(1)).isEqualTo("dev")

        // A reference that is not the published question never reaches the agent.
        val stale = actions.handle("interactions.review", payload(conversation = true, params = mapOf("requestRef" to "ask-9"), body = mapOf("answer" to "main")))
        assertThat(stale.status).isEqualTo(409)
        assertThat(codeOf(stale)).isEqualTo("NOT_PENDING")
        // An answer the Agent's own schema does not accept is refused before anything is sent.
        val wrong = actions.handle("interactions.review", payload(conversation = true, params = mapOf("requestRef" to "ask-1"), body = mapOf("answer" to "release")))
        assertThat(wrong.status).isEqualTo(422)
        assertThat(codeOf(wrong)).isEqualTo("INVALID_ANSWER")
        // A different epoch means the renderer read a runtime that is no longer the live one.
        val late = actions.handle("interactions.review", payload(conversation = true, params = mapOf("requestRef" to "ask-1"), body = mapOf("answer" to "dev", "epoch" to "epoch-9")))
        assertThat(late.status).isEqualTo(409)
        assertThat(codeOf(late)).isEqualTo("STALE_APPROVAL")
        assertThat(source.answeredQuestions).isEmpty()

        val accepted = actions.handle("interactions.review", payload(conversation = true, params = mapOf("requestRef" to "ask-1"), body = mapOf("answer" to "dev", "epoch" to "epoch-1")))
        assertThat(accepted.status).isEqualTo(200)
        assertThat(dataOf(accepted).getString("state")).isEqualTo("answered")
        assertThat(dataOf(accepted).getString("question_id")).isEqualTo("ask-1")
        assertThat(source.answeredQuestions.single().first).isSameAs(question)
        assertThat(source.answeredQuestions.single().second).isEqualTo("dev")
        // The answer is the user's own content and never travels back inside the answer payload.
        assertThat((accepted.body as JSONObject).toString()).doesNotContain("dev")
    }

    @Test
    fun aQuestionFromHistoryIsNeverAnswerable() = runTest {
        val fromHistory = questionEvent("ask-old", "旧问题", 2)
        // The repository does not hold this instance as pending any more, so its answer must not be reported as sent.
        val refused = RendererActions(openSource().apply { events = listOf(fromHistory); questionAnswerable = false }).handle(
            "interactions.review",
            payload(conversation = true, params = mapOf("requestRef" to "ask-old"), body = mapOf("answer" to "main")),
        )
        assertThat(refused.status).isEqualTo(409)
        assertThat(codeOf(refused)).isEqualTo("NOT_PENDING")

        // Only the newest question is the one the route may answer; an older card is refused before the repository runs.
        val two = openSource().apply { events = listOf(fromHistory, questionEvent("ask-new", "新问题", 9)) }
        val older = RendererActions(two).handle(
            "interactions.review",
            payload(conversation = true, params = mapOf("requestRef" to "ask-old"), body = mapOf("answer" to "main")),
        )
        assertThat(older.status).isEqualTo(409)
        assertThat(two.answeredQuestions).isEmpty()

        // A question whose form this host cannot read is refused as unsupported instead of being answered blind.
        val unreadable = openSource().apply { events = listOf(AgentEvent(type = "question", id = "ask-1", text = "无结构", historyKey = 3)) }
        val unsupported = RendererActions(unreadable).handle(
            "interactions.review",
            payload(conversation = true, params = mapOf("requestRef" to "ask-1"), body = mapOf("answer" to "是的")),
        )
        assertThat(unsupported.status).isEqualTo(501)
        assertThat(codeOf(unsupported)).isEqualTo("QUESTION_UNSUPPORTED")
        assertThat(unreadable.answeredQuestions).isEmpty()

        // Without an open conversation there is nothing pending to answer.
        val missing = RendererActions(FakeSource()).handle(
            "interactions.review",
            payload(conversation = true, params = mapOf("requestRef" to "ask-1"), body = mapOf("answer" to "main")),
        )
        assertThat(missing.status).isEqualTo(404)
        assertThat(codeOf(missing)).isEqualTo("CONVERSATION_NOT_FOUND")
    }

    @Test
    fun anEventTheChatCannotDrawStillReachesTheRendererAsOneRawLine() = runTest {
        val unknown = AgentEvent(
            type = "future_update",
            id = "e1",
            title = "未来事件",
            status = "streaming",
            raw = buildJsonObject { put("payload", "值\u0007带控制字符") },
            historyKey = 8,
        )
        val items = (RendererActions(openSource().apply { events = listOf(unknown) }).handle("messages.list", payload(conversation = true)).body as JSONObject)
            .getJSONObject("data").getJSONArray("items")
        assertThat(items.length()).isEqualTo(1)
        val row = items.getJSONObject(0)
        assertThat(row.getString("type")).isEqualTo("tips")
        val content = row.getJSONObject("content")
        assertThat(content.getString("type")).isEqualTo("info")
        val line = content.getString("content")
        assertThat(line).startsWith("[future_update]")
        assertThat(line).contains("未来事件")
        assertThat(line).contains("streaming")
        assertThat(line).doesNotContain("\u0007")
        assertThat(line.length).isLessThanOrEqualTo(1000)

        // A resolved interaction stays out of the chat stream: the product's own bookkeeping row covers what the
        // chat already drew while it was pending.
        val control = openSource().apply {
            events = listOf(
                AgentEvent(type = "question_resolved", status = "resolved", historyKey = 2),
            )
        }
        assertThat((RendererActions(control).handle("messages.list", payload(conversation = true)).body as JSONObject).getJSONObject("data").getJSONArray("items").length()).isZero()
    }

    /**
     * The three event kinds the chat used to drop now reach the renderer as rows of their own: one usage report
     * under the row type the renderer already reads context usage from, the turn boundary as the row that closes a
     * turn, and the agent's own session fact as a bounded line carrying what it stated.
     */
    @Test
    fun usageTheTurnBoundaryAndAgentSessionFactsEachReachTheRendererAsTheirOwnRow() = runTest {
        val window = listOf(
            AgentEvent(
                type = "usage",
                raw = buildJsonObject {
                    put("sessionUpdate", "usage_update")
                    put("used", 3200)
                    put("size", 200000)
                },
                historyKey = 1,
            ),
            AgentEvent(type = "complete", status = "end_turn", historyKey = 2),
            AgentEvent(type = "session_info", title = "新标题", raw = buildJsonObject { put("title", "新标题") }, historyKey = 3),
        )
        val items = (RendererActions(openSource().apply { events = window }).handle("messages.list", payload(conversation = true)).body as JSONObject)
            .getJSONObject("data").getJSONArray("items")
        assertThat(items.length()).isEqualTo(3)
        assertThat(items.getJSONObject(0).getString("type")).isEqualTo("acp_context_usage")
        assertThat(items.getJSONObject(0).getJSONObject("content").getInt("used")).isEqualTo(3200)
        assertThat(items.getJSONObject(0).getJSONObject("content").getInt("size")).isEqualTo(200000)
        assertThat(items.getJSONObject(1).getString("type")).isEqualTo("finish")
        assertThat(items.getJSONObject(1).getJSONObject("content").getString("stop_reason")).isEqualTo("end_turn")
        assertThat(items.getJSONObject(1).getJSONObject("content").getString("outcome")).isEqualTo("completed")
        assertThat(items.getJSONObject(2).getString("type")).isEqualTo("tips")
        assertThat(items.getJSONObject(2).getJSONObject("content").getString("type")).isEqualTo("info")
        assertThat(items.getJSONObject(2).getJSONObject("content").getString("title")).isEqualTo("新标题")

        // A usage report this build cannot read stays visible as a bounded line instead of vanishing.
        val unreadable = (RendererActions(openSource().apply { events = listOf(AgentEvent(type = "usage", raw = buildJsonObject { put("sessionUpdate", "usage_update") }, historyKey = 1)) })
            .handle("messages.list", payload(conversation = true)).body as JSONObject)
            .getJSONObject("data").getJSONArray("items").getJSONObject(0)
        assertThat(unreadable.getString("type")).isEqualTo("acp_context_usage")
        assertThat(unreadable.getJSONObject("content").getString("content")).startsWith("[usage]")
    }

    // ==================== Cross-conversation search, attention inbox, queue, reference and the index cache ====================

    /**
     * One conversation row carries the link state behind it, whether it is only cached, and the workspace it belongs
     * to, so a surface can group rows and mark the ones whose host cannot be reached right now.
     */
    @Test
    fun conversationRowsCarryTheirLinkStateTheirCacheFlagAndTheirWorkspace() = runTest {
        val other = AgentSession(hostId = 8, agent = AgentKind.DSH, sessionId = "session-b", cwd = "/workspace/other", updatedAt = 500)
        val otherHost = Host(id = 8, nickname = "另一台", username = "colle", hostname = "second.test")
        val source = FakeSource().apply {
            session = this@RendererActionsTest.session
            host = this@RendererActionsTest.host
            indexed = this@RendererActionsTest.session
            active = true
            sessions = listOf(this@RendererActionsTest.session, other)
            hosts = listOf(this@RendererActionsTest.host, otherHost)
            linkStates = mapOf(7L to AgentLinkState.DISCONNECTED)
        }
        val rows = dataOf(RendererActions(source).handle("conversations.list", payload())).getJSONArray("items")
        val open = (0 until rows.length()).map { rows.getJSONObject(it) }.first { it.getString("id") == RendererConversationId.of(session) }
        assertThat(open.getBoolean("offline")).isTrue()
        assertThat(open.getString("link_state")).isEqualTo("disconnected")
        assertThat(open.getJSONObject("workspace").getString("host_id")).isEqualTo("host-7")
        assertThat(open.getJSONObject("workspace").getString("cwd")).isEqualTo("/workspace/app")
        // The open conversation's own window is being read right now, so its row is not merely a cached one.
        assertThat(open.getBoolean("cached")).isFalse()

        // A row of a pair no scan re-read is cache-only, and a host the product never observed is not called offline.
        val cached = (0 until rows.length()).map { rows.getJSONObject(it) }.first { it.getString("id") == RendererConversationId.of(other) }
        assertThat(cached.getBoolean("cached")).isTrue()
        assertThat(cached.getBoolean("offline")).isFalse()
        assertThat(cached.getString("link_state")).isEqualTo("unknown")

        // A pair this pass really re-read from its host is not cached either.
        val rescanned = source.apply { scannedSources = setOf(8L to AgentKind.DSH) }
        val refreshed = dataOf(RendererActions(rescanned).handle("conversations.list", payload())).getJSONArray("items")
        val scanned = (0 until refreshed.length()).map { refreshed.getJSONObject(it) }.first { it.getString("id") == RendererConversationId.of(other) }
        assertThat(scanned.getBoolean("cached")).isFalse()
    }

    /** Every host row counts what the product's own index holds for it, and keeps the capabilities its probe found. */
    @Test
    fun everyHostRowCountsTheSessionsTheIndexHoldsAndKeepsTheProbedCapabilities() = runTest {
        val second = Host(id = 8, nickname = "另一台", username = "colle", hostname = "second.test")
        val far = AgentSession(hostId = 8, agent = AgentKind.KIMI, sessionId = "session-c", cwd = "/srv")
        val source = FakeSource().apply {
            hosts = listOf(this@RendererActionsTest.host, second)
            sessions = listOf(this@RendererActionsTest.session, AgentSession(hostId = 7, agent = AgentKind.DSH, sessionId = "session-b", cwd = "/workspace/other"), far)
            availability = listOf(availableRow(7), missingRow(8, AgentKind.KIMI))
        }
        val rows = dataOf(RendererActions(source).handle("hosts.list", payload())).getJSONArray("items")
        val rowsById = (0 until rows.length()).map { rows.getJSONObject(it) }.associateBy { it.getString("id") }
        assertThat(rowsById.getValue("host-7").getInt("session_count")).isEqualTo(2)
        assertThat(rowsById.getValue("host-8").getInt("session_count")).isEqualTo(1)
        assertThat(rowsById.getValue("host-7").getBoolean("target_supported")).isTrue()
        val detected = rowsById.getValue("host-7").getJSONObject("detection").getJSONArray("agents").getJSONObject(0)
        assertThat(detected.getString("id")).isEqualTo("kimi")
        assertThat(detected.getJSONObject("capabilities").getBoolean("acp")).isTrue()
    }

    /** One search answers what the index and the remote transcripts really held, with the range it covered. */
    @Test
    fun aSearchAnswersIndexAndContentHitsWithTheRangeItReallyCovered() = runTest {
        val dsh = AgentSession(hostId = 7, agent = AgentKind.DSH, sessionId = "session-a", cwd = "/workspace/app", title = "部署脚本", updatedAt = 900)
        val source = FakeSource().apply {
            session = dsh
            indexed = dsh
            active = true
            hosts = listOf(this@RendererActionsTest.host)
            sessions = listOf(dsh)
            scannedSources = setOf(7L to AgentKind.DSH)
            transcriptOutcome = AgentTranscriptOutcome(
                query = "部署",
                hits = listOf(contentHit(7, AgentKind.DSH, "session-a", "/workspace/app", "session.v2.jsonl", 12, "部署到生产")),
                searchedSessions = 1,
                roots = listOf("/workspace/.dsh/sessions"),
            )
        }
        val data = dataOf(RendererActions(source).handle("search.sessions", payload(query = mapOf("q" to "部署"))))
        assertThat(source.transcriptSearches.single().first).isEqualTo("部署")
        assertThat(source.transcriptSearches.single().second.map { it.sessionId }).containsExactly("session-a")
        val items = data.getJSONArray("items")
        assertThat(items.length()).isEqualTo(2)
        val index = items.getJSONObject(0)
        assertThat(index.getString("kind")).isEqualTo("index")
        assertThat(index.getString("field")).isEqualTo("title")
        assertThat(index.getString("source")).isEqualTo("remote")
        assertThat(index.getString("conversation_id")).isEqualTo(RendererConversationId.of(dsh))
        // A search row carries the label the conversation list shows it under, so no hit is drawn untitled.
        assertThat(index.getString("title")).isEqualTo("部署脚本")
        assertThat(index.getBoolean("openable")).isTrue()
        val content = items.getJSONObject(1)
        assertThat(content.getString("kind")).isEqualTo("content")
        assertThat(content.getString("anchor")).isEqualTo("session.v2.jsonl#12")
        assertThat(content.getInt("line")).isEqualTo(12)
        assertThat(content.getString("snippet")).isEqualTo("部署到生产")
        assertThat(content.getString("host_id")).isEqualTo("host-7")
        assertThat(content.getString("title")).isEqualTo("部署脚本")
        assertThat(content.getBoolean("openable")).isTrue()
        val coverage = data.getJSONObject("coverage")
        assertThat(coverage.getBoolean("complete")).isTrue()
        assertThat(coverage.getBoolean("truncated")).isFalse()
        assertThat(coverage.getInt("searched_sessions")).isEqualTo(1)
        assertThat(coverage.getInt("remote_rows")).isEqualTo(1)
        assertThat(coverage.getJSONArray("failed_sources").length()).isZero()
        assertThat(data.getBoolean("has_more")).isFalse()

        // A hit whose conversation the product no longer holds cannot be opened from here, and says so.
        val gone = FakeSource().apply {
            session = null
            active = false
            sessions = emptyList()
            transcriptOutcome = AgentTranscriptOutcome(
                query = "部署",
                hits = listOf(contentHit(9, AgentKind.DSH, "session-gone", "/gone", "session.v1.jsonl", 3, "部署过")),
            )
        }
        val orphan = dataOf(RendererActions(gone).handle("search.sessions", payload(query = mapOf("q" to "部署")))).getJSONArray("items").getJSONObject(0)
        assertThat(orphan.getBoolean("openable")).isFalse()
        assertThat(orphan.getString("host_id")).isEqualTo("host-9")
        // A conversation the product no longer holds has no title to report, so the row names its own session
        // instead of borrowing a title from somewhere else.
        assertThat(orphan.getString("title")).isEqualTo("session-gone")
    }

    /**
     * A search that could not read every part of its range reports the coverage it really had: a clock that cut the
     * scan short, a host that failed, a history that is not a file and a skipped session are each named, and
     * `complete` stays false so no caller can read it as a history without that text.
     */
    @Test
    fun anIncompleteSearchNeverReadsAsAHistoryWithoutThatText() = runTest {
        val source = FakeSource().apply {
            session = this@RendererActionsTest.session
            active = true
            hosts = listOf(this@RendererActionsTest.host)
            sessions = listOf(this@RendererActionsTest.session)
            transcriptOutcome = AgentTranscriptOutcome(
                query = "部署",
                truncated = true,
                problems = listOf(TranscriptSearchProblem.TIMEOUT),
                failures = listOf(AgentTranscriptFailure(hostId = 8, reason = "dir_not_readable")),
                skips = listOf(
                    AgentTranscriptSkip(
                        AgentSession(hostId = 9, agent = AgentKind.KIMI, sessionId = "session-nine", cwd = "/srv"),
                        AgentTranscriptSkipReason.HISTORY_NOT_A_FILE,
                    ),
                ),
                searchedSessions = 0,
            )
        }
        val coverage = dataOf(RendererActions(source).handle("search.sessions", payload(query = mapOf("q" to "部署")))).getJSONObject("coverage")
        assertThat(coverage.getBoolean("complete")).isFalse()
        assertThat(coverage.getBoolean("truncated")).isTrue()
        assertThat(coverage.getJSONArray("problems").getString(0)).isEqualTo("search_timeout")
        val failed = coverage.getJSONArray("failed_sources").getJSONObject(0)
        assertThat(failed.getString("host_id")).isEqualTo("host-8")
        assertThat(failed.getString("code")).isEqualTo("source_unreadable")
        assertThat(failed.getString("reason")).isEqualTo("dir_not_readable")
        val skipped = coverage.getJSONArray("skipped").getJSONObject(0)
        assertThat(skipped.getString("code")).isEqualTo("history_not_a_file")
        assertThat(skipped.getString("session_id")).isEqualTo("session-nine")
        assertThat(coverage.getInt("searched_sessions")).isZero()

        // A search that could not run at all is a failure of its own, not an empty history.
        val broken = source.apply { transcriptFailure = IOException("远端读取失败") }
        val result = RendererActions(broken).handle("search.sessions", payload(query = mapOf("q" to "部署")))
        assertThat(result.status).isEqualTo(500)
        assertThat(codeOf(result)).isEqualTo("SEARCH_FAILED")
    }

    /** A query no host could be asked about, and a filter nothing matches, are refused before any remote read. */
    @Test
    fun aSearchRefusesAnUnusableQueryAndFiltersNothingCouldMatch() = runTest {
        val source = FakeSource().apply {
            session = this@RendererActionsTest.session
            active = true
            hosts = listOf(this@RendererActionsTest.host)
            sessions = listOf(this@RendererActionsTest.session)
        }
        val actions = RendererActions(source)
        for (query in listOf(emptyMap<String, String>(), mapOf("q" to "  "), mapOf("q" to "x".repeat(300)), mapOf("q" to "a\u0007b"), mapOf("q" to "部署", "limit" to "0"))) {
            val result = actions.handle("search.sessions", payload(query = query))
            assertThat(result.status).describedAs(query.toString()).isEqualTo(422)
            assertThat(codeOf(result)).isEqualTo("INVALID_QUERY")
        }
        assertThat(codeOf(actions.handle("search.sessions", payload(query = mapOf("q" to "部署", "agent" to "nope"))))).isEqualTo("AGENT_UNKNOWN")
        assertThat(actions.handle("search.sessions", payload(query = mapOf("q" to "部署", "agent" to "nope"))).status).isEqualTo(422)
        assertThat(actions.handle("search.sessions", payload(query = mapOf("q" to "部署", "host_id" to "host-99"))).status).isEqualTo(404)
        assertThat(source.transcriptSearches).isEmpty()
    }

    /** The inbox is the read-only projection of the open conversation, and nothing waiting is an empty list. */
    @Test
    fun theInboxIsAReadOnlyProjectionOfTheOpenConversationAlone() = runTest {
        val source = openSource().apply {
            events = listOf(
                AgentEvent(type = "permission", id = "req-1", text = "请求写入", historyKey = 1),
                AgentEvent(type = "error", text = "命令失败", historyKey = 2),
            )
            busy = true
        }
        val data = dataOf(RendererActions(source).handle("inbox.list", payload()))
        assertThat(data.getBoolean("read_only")).isTrue()
        assertThat(data.getString("scope")).isEqualTo("open_conversation")
        assertThat(data.getInt("total")).isEqualTo(1)
        val item = data.getJSONArray("items").getJSONObject(0)
        assertThat(item.getString("conversation_id")).isEqualTo(RendererConversationId.of(session))
        assertThat(item.getString("host_id")).isEqualTo("host-7")
        assertThat(item.getJSONArray("reasons").getString(0)).isEqualTo("approval")
        assertThat(item.getInt("pending_approvals")).isEqualTo(1)
        assertThat(item.getInt("pending_questions")).isZero()
        assertThat(item.getString("last_error")).isEqualTo("命令失败")
        assertThat(item.getString("summary")).contains("approval")

        // A conversation with nothing waiting has no row at all, and an inbox without one is not a failure.
        val idle = dataOf(RendererActions(openSource()).handle("inbox.list", payload()))
        assertThat(idle.getInt("total")).isZero()
        assertThat(idle.getJSONArray("items").length()).isZero()
    }

    /** The queue lists what waits and accepts a message without sending anything to any model. */
    @Test
    fun theQueueListsWhatWaitsAndQueuesWithoutSendingAnything() = runTest {
        val target = AgentSession(hostId = 7, agent = AgentKind.DSH, sessionId = "session-b", cwd = "/workspace/other")
        val waiting = AgentQueuedMessage(
            id = "queue-1",
            hostId = 7,
            agent = "dsh",
            sessionId = "session-b",
            kind = AgentMessageKind.REQUEST,
            text = "跑一下测试",
            createdAtMillis = 5,
            state = AgentMessageState.PENDING,
        )
        val source = FakeSource().apply {
            session = this@RendererActionsTest.session
            active = true
            hosts = listOf(this@RendererActionsTest.host)
            sessions = listOf(this@RendererActionsTest.session, target)
            queuedMessages = listOf(waiting)
            queuedStats = AgentMessageQueueStats(stored = 2, pending = 1, delivered = 1, dropped = 3)
        }
        val actions = RendererActions(source)
        val list = dataOf(actions.handle("queue.list", payload()))
        assertThat(list.getInt("total")).isEqualTo(1)
        assertThat(list.getJSONObject("stats").getInt("dropped")).isEqualTo(3)
        val item = list.getJSONArray("items").getJSONObject(0)
        assertThat(item.getString("queue_id")).isEqualTo("queue-1")
        assertThat(item.getString("kind")).isEqualTo("request")
        assertThat(item.getString("state")).isEqualTo("pending")
        assertThat(item.getBoolean("pending")).isTrue()
        assertThat(item.getString("conversation_id")).isEqualTo(RendererConversationId.of(target))

        val byConversation = dataOf(
            actions.handle(
                "queue.send",
                payload(body = mapOf("conversation_id" to RendererConversationId.of(target), "kind" to "notify", "text" to "记得看日志")),
            ),
        )
        assertThat(byConversation.getBoolean("queued")).isTrue()
        assertThat(byConversation.getBoolean("delivered")).isFalse()
        assertThat(byConversation.getString("state")).isEqualTo("pending")
        assertThat(byConversation.getString("session_id")).isEqualTo("session-b")
        assertThat(byConversation.getString("kind")).isEqualTo("notify")
        assertThat(source.queuedWrites.single().first.sameConversation(target)).isTrue()
        assertThat(source.queuedWrites.single().second).isEqualTo(AgentMessageKind.NOTIFY)
        assertThat(source.queuedWrites.single().third).isEqualTo("记得看日志")
        // Nothing was sent to the open conversation: queueing is the only write this route performs.
        assertThat(source.turnId).isNull()

        // The same request names its target by host, Agent and session id instead of a derived conversation id.
        val byIdentity = dataOf(
            actions.handle(
                "queue.send",
                payload(body = mapOf("host_id" to "host-7", "agent" to "dsh", "session_id" to "session-b", "kind" to "request", "text" to "再来一次")),
            ),
        )
        assertThat(byIdentity.getString("conversation_id")).isEqualTo(RendererConversationId.of(target))
        assertThat(source.queuedWrites.size).isEqualTo(2)
    }

    /** A queue request for a target nobody holds, or with content the queue cannot carry, is refused as itself. */
    @Test
    fun queueSendRefusesTargetsNothingHoldsAndContentNothingCanCarry() = runTest {
        val source = FakeSource().apply {
            session = this@RendererActionsTest.session
            active = true
            hosts = listOf(this@RendererActionsTest.host)
            sessions = listOf(this@RendererActionsTest.session)
        }
        val actions = RendererActions(source)
        val unknown = actions.handle("queue.send", payload(body = mapOf("conversation_id" to "c1.9.00.00", "kind" to "notify", "text" to "你好")))
        assertThat(unknown.status).isEqualTo(404)
        assertThat(codeOf(unknown)).isEqualTo("CONVERSATION_NOT_FOUND")
        val unknownHost = actions.handle(
            "queue.send",
            payload(body = mapOf("host_id" to "host-99", "agent" to "dsh", "session_id" to "session-b", "kind" to "notify", "text" to "你好")),
        )
        assertThat(unknownHost.status).isEqualTo(404)
        val badKind = actions.handle(
            "queue.send",
            payload(body = mapOf("conversation_id" to RendererConversationId.of(session), "kind" to "shout", "text" to "你好")),
        )
        assertThat(badKind.status).isEqualTo(422)
        assertThat(codeOf(badKind)).isEqualTo("INVALID_KIND")
        for (text in listOf("", "   ", "x".repeat(ProbeLimits.CONTENT_UNITS + 1))) {
            val refused = actions.handle(
                "queue.send",
                payload(body = mapOf("conversation_id" to RendererConversationId.of(session), "kind" to "notify", "text" to text)),
            )
            assertThat(refused.status).describedAs("text of ${text.length}").isEqualTo(422)
            assertThat(codeOf(refused)).isEqualTo("INVALID_CONTENT")
        }
        assertThat(source.queuedWrites).isEmpty()

        val refusing = source.apply { queueRefuses = true }
        val refusedByQueue = RendererActions(refusing).handle(
            "queue.send",
            payload(body = mapOf("conversation_id" to RendererConversationId.of(session), "kind" to "notify", "text" to "你好")),
        )
        assertThat(refusedByQueue.status).isEqualTo(422)
        assertThat(codeOf(refusedByQueue)).isEqualTo("QUEUE_REFUSED")

        val broken = source.apply { queueFailure = IOException("队列不可读") }
        val failed = RendererActions(broken).handle("queue.list", payload())
        assertThat(failed.status).isEqualTo(500)
        assertThat(codeOf(failed)).isEqualTo("QUEUE_UNAVAILABLE")
    }

    /** Only a message the queue still holds as pending can be withdrawn, and no delivery is ever claimed for it. */
    @Test
    fun queueCancelWithdrawsOnlyAMessageTheQueueStillHolds() = runTest {
        val waiting = AgentQueuedMessage(
            id = "queue-1",
            hostId = 7,
            agent = "kimi",
            sessionId = "session-a",
            kind = AgentMessageKind.NOTIFY,
            text = "记得看日志",
            createdAtMillis = 5,
            state = AgentMessageState.PENDING,
        )
        val source = openSource().apply { queuedMessages = listOf(waiting) }
        val actions = RendererActions(source)
        val cancelled = dataOf(actions.handle("queue.cancel", payload(params = mapOf("queueId" to "queue-1"))))
        assertThat(cancelled.getBoolean("cancelled")).isTrue()
        assertThat(cancelled.getBoolean("delivered")).isFalse()
        // The withdrawal is the message's own terminal state, which the renderer books it under.
        assertThat(cancelled.getString("state")).isEqualTo("withdrawn")
        assertThat(source.withdrawn).containsExactly("queue-1")
        // The row is still the product's own record of what happened; it is only no longer waiting for anything.
        assertThat(source.queuedMessages.map { it.state }).containsExactly(AgentMessageState.WITHDRAWN)
        assertThat(source.queued().map { it.state }).containsExactly(AgentMessageState.WITHDRAWN)

        val missing = actions.handle("queue.cancel", payload(params = mapOf("queueId" to "queue-404")))
        assertThat(missing.status).isEqualTo(404)
        assertThat(codeOf(missing)).isEqualTo("QUEUE_NOT_FOUND")
        val blank = actions.handle("queue.cancel", payload(params = mapOf("queueId" to "")))
        assertThat(blank.status).isEqualTo(422)
        assertThat(codeOf(blank)).isEqualTo("INVALID_QUEUE_ID")

        // A message its conversation already took is not this queue's to undo.
        val taken = openSource().apply {
            queuedMessages = listOf(waiting)
            withdrawAccepted = false
        }
        val refused = RendererActions(taken).handle("queue.cancel", payload(params = mapOf("queueId" to "queue-1")))
        assertThat(refused.status).isEqualTo(409)
        assertThat(codeOf(refused)).isEqualTo("QUEUE_NOT_PENDING")
    }

    /** A withdrawn message reports its own terminal state, is not a delivery, and is not pending again. */
    @Test
    fun aWithdrawnMessageKeepsItsOwnTerminalStateAndIsNeverPendingAgain() = runTest {
        val withdrawn = AgentQueuedMessage(
            id = "queue-2",
            hostId = 7,
            agent = "kimi",
            sessionId = "session-a",
            kind = AgentMessageKind.REQUEST,
            text = "别发了",
            createdAtMillis = 5,
            state = AgentMessageState.WITHDRAWN,
        )
        val source = openSource().apply {
            queuedMessages = listOf(withdrawn)
            queuedStats = AgentMessageQueueStats(stored = 1, pending = 0, delivered = 0, withdrawn = 1, dropped = 0)
        }
        val actions = RendererActions(source)
        val data = dataOf(actions.handle("queue.list", payload()))
        val item = data.getJSONArray("items").getJSONObject(0)
        assertThat(item.getString("state")).isEqualTo("withdrawn")
        assertThat(item.getBoolean("pending")).isFalse()
        assertThat(data.getJSONObject("stats").getInt("withdrawn")).isEqualTo(1)

        // A withdrawn message is not waiting for anything, so it is not a pending row a second withdrawal could take.
        val again = actions.handle("queue.cancel", payload(params = mapOf("queueId" to "queue-2")))
        assertThat(again.status).isEqualTo(409)
        assertThat(codeOf(again)).isEqualTo("QUEUE_NOT_PENDING")
        assertThat(source.queuedMessages.map { it.state }).containsExactly(AgentMessageState.WITHDRAWN)
    }

    /**
     * An upload buffers on this phone and lands in the open conversation's own directory: the same bytes, under the
     * name the renderer sent, answered with the absolute path they ended up at, and the local buffer is gone.
     */
    @Test
    fun anUploadLandsInTheOpenConversationsDirectory() = runTest {
        val uploads = temporary.newFolder()
        val source = openSource().apply { host = this@RendererActionsTest.host }
        val actions = RendererActions(source, RendererAttachments(uploads, source))
        val bytes = "截图内容".toByteArray()
        val started = dataOf(actions.handle("attachment.begin", payload(conversation = true, body = uploadBody(bytes))))
        assertThat(started.getInt("chunkBytes")).isEqualTo(RendererAttachments.CHUNK_BYTES)
        assertThat(started.getInt("expiresInMs")).isEqualTo(RendererAttachments.UPLOAD_TTL_MILLIS.toInt())
        val uploadId = started.getString("uploadId")

        val ack = dataOf(
            actions.handle(
                "attachment.chunk",
                payload(conversation = true, body = mapOf("uploadId" to uploadId, "offset" to 0, "data" to base64Of(bytes))),
            ),
        )
        assertThat(ack.getInt("offset")).isEqualTo(bytes.size)

        val finished = dataOf(actions.handle("attachment.finish", payload(conversation = true, body = mapOf("uploadId" to uploadId))))
        assertThat(finished.getString("attachmentRef")).isEqualTo("/workspace/app/shot.png")
        assertThat(finished.getString("path")).isEqualTo("/workspace/app/shot.png")
        assertThat(finished.getString("name")).isEqualTo("shot.png")
        assertThat(finished.getInt("size")).isEqualTo(bytes.size)
        assertThat(finished.getString("sha256")).isEqualTo(sha256Of(bytes))
        assertThat(finished.getString("source")).isEqualTo("mobile-upload")
        assertThat(source.writtenFiles).containsExactly(Triple(7L, "/workspace/app", "shot.png"))
        assertThat(String(source.wroteContent ?: ByteArray(0), Charsets.UTF_8)).isEqualTo("截图内容")
        assertThat(uploads.listFiles().orEmpty()).isEmpty()
    }

    /** The write answers the path the host chose, so a name the directory already held is reported as it landed. */
    @Test
    fun anUploadAnsweredWithASuffixedNameReportsThatName() = runTest {
        val uploads = temporary.newFolder()
        val source = openSource().apply {
            host = this@RendererActionsTest.host
            writtenPath = "/workspace/app/shot-1.png"
        }
        val actions = RendererActions(source, RendererAttachments(uploads, source))
        val bytes = "second".toByteArray()
        val uploadId = dataOf(actions.handle("attachment.begin", payload(conversation = true, body = uploadBody(bytes)))).getString("uploadId")
        actions.handle("attachment.chunk", payload(conversation = true, body = mapOf("uploadId" to uploadId, "offset" to 0, "data" to base64Of(bytes))))
        val finished = dataOf(actions.handle("attachment.finish", payload(conversation = true, body = mapOf("uploadId" to uploadId))))
        assertThat(finished.getString("attachmentRef")).isEqualTo("/workspace/app/shot-1.png")
        assertThat(source.writtenFiles.single().third).isEqualTo("shot.png")
        assertThat(uploads.listFiles().orEmpty()).isEmpty()
    }

    /** Every field the protocol states is checked before anything is buffered, and each refusal names itself. */
    @Test
    fun uploadBeginRefusesWhatCannotBeTrusted() = runTest {
        val uploads = temporary.newFolder()
        val source = openSource()
        val actions = RendererActions(source, RendererAttachments(uploads, source))
        val bytes = "content".toByteArray()
        val refusals = listOf(
            uploadBody(bytes, name = "a/b.png") to "INVALID_REQUEST",
            uploadBody(bytes, name = "a\\b.png") to "INVALID_REQUEST",
            uploadBody(bytes, name = "bad\u0001.png") to "INVALID_REQUEST",
            uploadBody(bytes, name = ".") to "INVALID_REQUEST",
            uploadBody(bytes, mime = "") to "INVALID_REQUEST",
            uploadBody(bytes, size = 1.5) to "INVALID_REQUEST",
            uploadBody(bytes, size = -1L) to "INVALID_REQUEST",
            uploadBody(bytes, size = 8L * 1024 * 1024 + 1) to "UPLOAD_TOO_LARGE",
            uploadBody(bytes, sha256 = "ABCDEF") to "INVALID_REQUEST",
        )
        for ((body, code) in refusals) {
            val refused = actions.handle("attachment.begin", payload(conversation = true, body = body))
            assertThat(refused.status).describedAs(body.toString()).isEqualTo(if (code == "UPLOAD_TOO_LARGE") 413 else 422)
            assertThat(codeOf(refused)).describedAs(body.toString()).isEqualTo(code)
        }
        assertThat(uploads.listFiles().orEmpty()).isEmpty()
        assertThat(source.writtenFiles).isEmpty()

        // And an upload can only address the conversation that is open: another conversation's id names nothing here.
        val other = AgentSession(hostId = 7, agent = AgentKind.DSH, sessionId = "session-b", cwd = "/workspace/app")
        val refused = actions.handle(
            "attachment.begin",
            payload(params = mapOf("conversationId" to RendererConversationId.of(other)), body = uploadBody(bytes)),
        )
        assertThat(refused.status).isEqualTo(404)
        assertThat(codeOf(refused)).isEqualTo("CONVERSATION_NOT_FOUND")
    }

    /** A chunk has to continue exactly where the upload ended, or the file would hold something nobody sent. */
    @Test
    fun chunksAndTheFinishStepRefuseAnUploadThatIsNotWhereItClaims() = runTest {
        val uploads = temporary.newFolder()
        val source = openSource()
        val actions = RendererActions(source, RendererAttachments(uploads, source))
        val bytes = "0123456789".toByteArray()
        val uploadId = dataOf(actions.handle("attachment.begin", payload(conversation = true, body = uploadBody(bytes)))).getString("uploadId")

        val gap = actions.handle("attachment.chunk", payload(conversation = true, body = mapOf("uploadId" to uploadId, "offset" to 3, "data" to base64Of(bytes))))
        assertThat(gap.status).isEqualTo(409)
        assertThat(codeOf(gap)).isEqualTo("UPLOAD_OFFSET_CONFLICT")
        val notBase64 = actions.handle("attachment.chunk", payload(conversation = true, body = mapOf("uploadId" to uploadId, "offset" to 0, "data" to "***")))
        assertThat(notBase64.status).isEqualTo(422)
        assertThat(codeOf(notBase64)).isEqualTo("INVALID_REQUEST")

        // A chunk longer than the declared file cannot belong to this upload, and the file stays where it was.
        val overlong = actions.handle(
            "attachment.chunk",
            payload(conversation = true, body = mapOf("uploadId" to uploadId, "offset" to 0, "data" to base64Of(bytes + byteArrayOf(1)))),
        )
        assertThat(overlong.status).isEqualTo(413)
        assertThat(codeOf(overlong)).isEqualTo("UPLOAD_TOO_LARGE")
        assertThat(actions.handle("attachment.finish", payload(conversation = true, body = mapOf("uploadId" to uploadId))).status).isEqualTo(409)
        assertThat(codeOf(actions.handle("attachment.finish", payload(conversation = true, body = mapOf("uploadId" to uploadId))))).isEqualTo("UPLOAD_INCOMPLETE")

        val half = bytes.copyOfRange(0, 5)
        val ack = dataOf(actions.handle("attachment.chunk", payload(conversation = true, body = mapOf("uploadId" to uploadId, "offset" to 0, "data" to base64Of(half)))))
        assertThat(ack.getInt("offset")).isEqualTo(5)
        val rest = dataOf(
            actions.handle(
                "attachment.chunk",
                payload(conversation = true, body = mapOf("uploadId" to uploadId, "offset" to 5, "data" to base64Of(bytes.copyOfRange(5, bytes.size)))),
            ),
        )
        assertThat(rest.getInt("offset")).isEqualTo(bytes.size)
        assertThat(dataOf(actions.handle("attachment.finish", payload(conversation = true, body = mapOf("uploadId" to uploadId)))).getString("name")).isEqualTo("shot.png")

        // An id this host never issued, and one it already finished, name no upload at all.
        val unknown = actions.handle("attachment.chunk", payload(conversation = true, body = mapOf("uploadId" to "nope", "offset" to 0, "data" to base64Of(bytes))))
        assertThat(unknown.status).isEqualTo(410)
        assertThat(codeOf(unknown)).isEqualTo("UPLOAD_EXPIRED")
        val expired = actions.handle("attachment.abort", payload(conversation = true, body = mapOf("uploadId" to uploadId)))
        assertThat(expired.status).isEqualTo(410)
        assertThat(codeOf(expired)).isEqualTo("UPLOAD_EXPIRED")
        assertThat(uploads.listFiles().orEmpty()).isEmpty()
    }

    /** Content that does not hash to what was declared is dropped on this phone and never reaches a host. */
    @Test
    fun anUploadWhoseContentDoesNotMatchItsHashIsDiscarded() = runTest {
        val uploads = temporary.newFolder()
        val source = openSource()
        val actions = RendererActions(source, RendererAttachments(uploads, source))
        val sent = "sent".toByteArray()
        val body = uploadBody("declared".toByteArray())
        val uploadId = dataOf(actions.handle("attachment.begin", payload(conversation = true, body = body))).getString("uploadId")
        actions.handle("attachment.chunk", payload(conversation = true, body = mapOf("uploadId" to uploadId, "offset" to 0, "data" to base64Of(sent))))
        val mismatched = actions.handle("attachment.finish", payload(conversation = true, body = mapOf("uploadId" to uploadId)))
        assertThat(mismatched.status).isEqualTo(422)
        assertThat(codeOf(mismatched)).isEqualTo("UPLOAD_HASH_MISMATCH")
        assertThat(source.writtenFiles).isEmpty()
        assertThat(uploads.listFiles().orEmpty()).isEmpty()
    }

    /** A write the host refused is reported as that, and the local buffer is dropped with it. */
    @Test
    fun aWriteThatDidNotLandLeavesNothingBehind() = runTest {
        val uploads = temporary.newFolder()
        val source = openSource().apply { writeFailure = IOException("write_directory_missing") }
        val actions = RendererActions(source, RendererAttachments(uploads, source))
        val bytes = "payload".toByteArray()
        val uploadId = dataOf(actions.handle("attachment.begin", payload(conversation = true, body = uploadBody(bytes)))).getString("uploadId")
        actions.handle("attachment.chunk", payload(conversation = true, body = mapOf("uploadId" to uploadId, "offset" to 0, "data" to base64Of(bytes))))
        val failed = actions.handle("attachment.finish", payload(conversation = true, body = mapOf("uploadId" to uploadId)))
        assertThat(failed.status).isEqualTo(502)
        assertThat(codeOf(failed)).isEqualTo("REMOTE_WRITE_FAILED")
        assertThat((failed.body as JSONObject).getString("error")).contains("write_directory_missing")
        assertThat(uploads.listFiles().orEmpty()).isEmpty()
    }

    /** An upload belongs to the conversation it was started on: a switched conversation discards it. */
    @Test
    fun anUploadIsOnlyWrittenToTheConversationItWasStartedOn() = runTest {
        val uploads = temporary.newFolder()
        val other = AgentSession(hostId = 8, agent = AgentKind.DSH, sessionId = "session-b", cwd = "/srv/other")
        val source = openSource()
        val actions = RendererActions(source, RendererAttachments(uploads, source))
        val bytes = "payload".toByteArray()
        val conversation = RendererConversationId.of(session)
        val uploadId = dataOf(actions.handle("attachment.begin", payload(params = mapOf("conversationId" to conversation), body = uploadBody(bytes))))["uploadId"] as String
        actions.handle("attachment.chunk", payload(conversation = true, body = mapOf("uploadId" to uploadId, "offset" to 0, "data" to base64Of(bytes))))

        source.session = other
        source.active = true
        val switched = actions.handle("attachment.finish", payload(params = mapOf("conversationId" to conversation), body = mapOf("uploadId" to uploadId)))
        assertThat(switched.status).isEqualTo(409)
        assertThat(codeOf(switched)).isEqualTo("CONVERSATION_SWITCHED")
        assertThat(source.writtenFiles).isEmpty()
        assertThat(uploads.listFiles().orEmpty()).isEmpty()
    }

    /** A host whose target does not run POSIX commands has no attachment write yet, and says so instead of trying. */
    @Test
    fun aHostWithoutAPosixShellRefusesTheWrite() = runTest {
        val uploads = temporary.newFolder()
        val windows = savedHost.copy(osType = "windows", agentTarget = "windows")
        val source = openSource().apply { host = windows }
        val actions = RendererActions(source, RendererAttachments(uploads, source))
        val bytes = "payload".toByteArray()
        val uploadId = dataOf(actions.handle("attachment.begin", payload(conversation = true, body = uploadBody(bytes)))).getString("uploadId")
        actions.handle("attachment.chunk", payload(conversation = true, body = mapOf("uploadId" to uploadId, "offset" to 0, "data" to base64Of(bytes))))
        val refused = actions.handle("attachment.finish", payload(conversation = true, body = mapOf("uploadId" to uploadId)))
        assertThat(refused.status).isEqualTo(502)
        assertThat(codeOf(refused)).isEqualTo("REMOTE_WRITE_FAILED")
        assertThat(source.writtenFiles).isEmpty()
        assertThat(uploads.listFiles().orEmpty()).isEmpty()
    }

    /** The in-flight cap keeps the local buffer bounded, and aborting one frees its place again. */
    @Test
    fun theUploadCapIsVisibleAndAbortingFreesAPlace() = runTest {
        val uploads = temporary.newFolder()
        val source = openSource()
        val actions = RendererActions(source, RendererAttachments(uploads, source))
        val bytes = "payload".toByteArray()
        val ids = (1..4).map {
            dataOf(actions.handle("attachment.begin", payload(conversation = true, body = uploadBody(bytes)))).getString("uploadId")
        }
        val overflow = actions.handle("attachment.begin", payload(conversation = true, body = uploadBody(bytes)))
        assertThat(overflow.status).isEqualTo(429)
        assertThat(codeOf(overflow)).isEqualTo("UPLOAD_LIMIT")

        val aborted = dataOf(actions.handle("attachment.abort", payload(conversation = true, body = mapOf("uploadId" to ids.first()))))
        assertThat(aborted.getBoolean("aborted")).isTrue()
        assertThat(actions.handle("attachment.begin", payload(conversation = true, body = uploadBody(bytes))).status).isEqualTo(201)
        assertThat(uploads.listFiles().orEmpty()).hasSize(4)
    }

    /** One upload body as the renderer sends it: the name, the type, the size and the content's own sha256. */
    private fun uploadBody(
        bytes: ByteArray,
        name: String = "shot.png",
        mime: String = "image/png",
        size: Any = bytes.size,
        sha256: String = sha256Of(bytes),
    ): Map<String, Any?> = mapOf("name" to name, "mime" to mime, "size" to size, "sha256" to sha256)

    private fun sha256Of(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun base64Of(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    /** A reference pack quotes the open window as bounded text and is never sent anywhere. */
    @Test
    fun aReferencePackQuotesTheOpenWindowAndSendsNothing() = runTest {
        val source = openSource().apply {
            hosts = listOf(this@RendererActionsTest.host)
            events = listOf(
                AgentEvent(type = "user", text = "部署到生产吧", id = "u1", historyKey = 1),
                AgentEvent(type = "content", text = "好的，先跑测试", id = "a1", historyKey = 2),
            )
        }
        val data = dataOf(RendererActions(source).handle("reference.pack", payload(conversation = true)))
        assertThat(data.getString("status")).isEqualTo("complete")
        assertThat(data.getInt("quoted_count")).isEqualTo(2)
        assertThat(data.getInt("omitted_count")).isZero()
        assertThat(data.getBoolean("sent")).isFalse()
        assertThat(data.getString("host")).isEqualTo("工作机")
        assertThat(data.getString("session_id")).isEqualTo("session-a")
        val text = data.getString("text")
        assertThat(text).contains("host=工作机")
        assertThat(text).contains("部署到生产吧")
        assertThat(text).contains("好的，先跑测试")
        assertThat(text.length).isLessThanOrEqualTo(4000)

        // Only the open conversation has a window to quote; another row is refused as not open, not as missing.
        val other = AgentSession(hostId = 7, agent = AgentKind.KIMI, sessionId = "session-z", cwd = "/workspace/z")
        val switched = openSource().apply {
            sessions = listOf(this@RendererActionsTest.session, other)
            session = other
        }
        val notOpen = RendererActions(switched).handle("reference.pack", payload(params = mapOf("conversationId" to RendererConversationId.of(session))))
        assertThat(notOpen.status).isEqualTo(409)
        assertThat(codeOf(notOpen)).isEqualTo("CONVERSATION_NOT_OPEN")
        val unknown = RendererActions(switched).handle("reference.pack", payload(params = mapOf("conversationId" to "c1.9.00.00")))
        assertThat(unknown.status).isEqualTo(404)
        assertThat(codeOf(unknown)).isEqualTo("CONVERSATION_NOT_FOUND")
        val closed = RendererActions(openSource().apply { active = false }).handle("reference.pack", payload(conversation = true))
        assertThat(closed.status).isEqualTo(409)
        assertThat(codeOf(closed)).isEqualTo("CONVERSATION_NOT_OPEN")
    }

    /** Clearing the index cache really drops the cached rows and reports what the index holds afterwards. */
    @Test
    fun clearingTheIndexReallyDropsTheCacheAndReportsWhatIsLeft() = runTest {
        val source = openSource().apply {
            indexed = this@RendererActionsTest.session
            sessions = listOf(this@RendererActionsTest.session, AgentSession(hostId = 8, agent = AgentKind.KIMI, sessionId = "session-c", cwd = "/srv"))
        }
        val data = dataOf(RendererActions(source).handle("index.clear", payload()))
        assertThat(data.getBoolean("cleared")).isTrue()
        assertThat(data.getInt("sessions_before")).isEqualTo(2)
        assertThat(data.getInt("sessions")).isZero()
        assertThat(source.indexClears).isEqualTo(1)
        // The cache really lost its rows: only the open conversation is listed afterwards.
        assertThat(source.sessions).isEmpty()
        val listed = dataOf(RendererActions(source).handle("conversations.list", payload())).getJSONArray("items")
        assertThat(listed.length()).isEqualTo(1)
        assertThat(listed.getJSONObject(0).getString("id")).isEqualTo(RendererConversationId.of(session))

        // A cache that could not be dropped is a failure, and nothing is reported as cleared.
        val broken = openSource().apply { indexFailure = IOException("索引缓存不可写") }
        val failed = RendererActions(broken).handle("index.clear", payload())
        assertThat(failed.status).isEqualTo(500)
        assertThat(codeOf(failed)).isEqualTo("INDEX_CLEAR_FAILED")
        assertThat(broken.indexClears).isZero()
    }

    /**
     * The nearby/local-network grants are what the current system actually needs, and a refusal degrades the routes
     * that reach a host over the LAN: they answer a stable code instead of running, and a route that reads the
     * product's own stores is never held back by them.
     */
    @Test
    fun aMissingLocalNetworkGrantDegradesOnlyTheRoutesThatReachAHost() {
        val nearby = Manifest.permission.NEARBY_WIFI_DEVICES
        val local = Manifest.permission.ACCESS_LOCAL_NETWORK
        assertThat(networkPermissions(35)).isEmpty()
        assertThat(networkPermissions(36)).containsExactly(nearby)
        assertThat(networkPermissions(37)).containsExactly(nearby, local)

        val denied = { _: String -> false }
        for (action in listOf("detection.refresh", "hosts.refresh", "conversations.create", "project.read", "search.sessions", "attachment.finish", "terminal.open", "terminal.command.start")) {
            assertThat(localNetworkRefused(action, networkPermissions(37), denied)).describedAs(action).isTrue()
        }
        // The upload steps that only buffer a file on this phone reach no host, so no grant holds them back.
        for (action in listOf("conversations.list", "queue.list", "inbox.list", "index.clear", "messages.list", "attachment.begin", "attachment.chunk", "attachment.abort")) {
            assertThat(localNetworkRefused(action, networkPermissions(37), denied)).describedAs(action).isFalse()
        }
        // Once the user granted what the route needs it runs again, and a system that needs no grant is never refused.
        assertThat(localNetworkRefused("detection.refresh", networkPermissions(37)) { true }).isFalse()
        assertThat(localNetworkRefused("detection.refresh", networkPermissions(35), denied)).isFalse()
    }

    @Test
    fun aChallengeIsReadWithoutItsAnswerAndHandedOverOnlyWhileItIsPending() = runTest {
        val password = AgentChallenge(id = "ch-1", hostName = "工作机", kind = "password", message = "colle@example.test 的登录口令", secret = true, hostId = 7)
        val source = FakeSource().apply {
            hosts = listOf(this@RendererActionsTest.host)
            pendingChallenge = password
        }
        val actions = RendererActions(source)
        val read = actions.handle("challenges.read", payload())
        assertThat(read.status).isEqualTo(200)
        val challenge = dataOf(read).getJSONObject("challenge")
        assertThat(challenge.getString("id")).isEqualTo("ch-1")
        assertThat(challenge.getString("host_id")).isEqualTo("host-7")
        assertThat(challenge.getString("host_name")).isEqualTo("工作机")
        assertThat(challenge.getString("kind")).isEqualTo("password")
        assertThat(challenge.getString("message")).isEqualTo("colle@example.test 的登录口令")
        assertThat(challenge.getBoolean("secret")).isTrue()
        // The read carries no credential: only what the user has to be asked and where the answer belongs.
        assertThat(challenge.keys().asSequence().toList()).containsExactlyInAnyOrder("id", "host_id", "host_name", "kind", "message", "secret")

        // No pending challenge is the truth of a connection that asks for nothing, not a failure.
        val idle = RendererActions(FakeSource()).handle("challenges.read", payload())
        assertThat(idle.status).isEqualTo(200)
        assertThat(dataOf(idle).isNull("challenge")).isTrue()

        // A host key confirmation is the same shape, without secret input, and its host id is absent once the host is gone.
        val hostKey = AgentChallenge(id = "ch-2", hostName = "已删主机", kind = "host_key", message = "SHA256:abc", secret = false, hostId = 8)
        val second = RendererActions(FakeSource().apply { pendingChallenge = hostKey }).handle("challenges.read", payload())
        val hostKeyRow = dataOf(second).getJSONObject("challenge")
        assertThat(hostKeyRow.getString("kind")).isEqualTo("host_key")
        assertThat(hostKeyRow.getBoolean("secret")).isFalse()
        assertThat(hostKeyRow.isNull("host_id")).isTrue()

        // An id that is not the pending challenge is refused before anything is handed over.
        val stale = actions.handle("challenges.answer", payload(params = mapOf("challengeId" to "ch-9"), body = mapOf("answer" to "秘密口令")))
        assertThat(stale.status).isEqualTo(409)
        assertThat(codeOf(stale)).isEqualTo("CHALLENGE_NOT_PENDING")
        assertThat(source.challengeAnswers).isEmpty()

        val answered = actions.handle("challenges.answer", payload(params = mapOf("challengeId" to "ch-1"), body = mapOf("answer" to "秘密口令")))
        assertThat(answered.status).isEqualTo(200)
        assertThat(dataOf(answered).getBoolean("answered")).isTrue()
        assertThat(source.challengeAnswers).containsExactly("ch-1" to "秘密口令")
        // The secret never travels back, and the challenge is not pending after it was answered.
        assertThat((answered.body as JSONObject).toString()).doesNotContain("秘密口令")
        assertThat(source.pendingChallenge).isNull()
        val again = actions.handle("challenges.answer", payload(params = mapOf("challengeId" to "ch-1"), body = mapOf("answer" to "秘密口令")))
        assertThat(again.status).isEqualTo(409)
        assertThat(source.challengeAnswers).hasSize(1)

        // A challenge answered while this request was in flight is reported as no longer pending, not as taken.
        val raced = FakeSource().apply {
            pendingChallenge = password
            challengeAnswerable = false
        }
        val lost = RendererActions(raced).handle("challenges.answer", payload(params = mapOf("challengeId" to "ch-1"), body = mapOf("answer" to "秘密口令")))
        assertThat(lost.status).isEqualTo(409)
        assertThat(codeOf(lost)).isEqualTo("CHALLENGE_NOT_PENDING")

        // An omitted or empty answer cancels, exactly as the Compose dialog's own cancel does; anything else is refused.
        val cancelled = FakeSource().apply { pendingChallenge = password }
        assertThat(RendererActions(cancelled).handle("challenges.answer", payload(params = mapOf("challengeId" to "ch-1"))).status).isEqualTo(200)
        assertThat(cancelled.challengeAnswers).containsExactly("ch-1" to null)
        val empty = FakeSource().apply { pendingChallenge = password }
        assertThat(RendererActions(empty).handle("challenges.answer", payload(params = mapOf("challengeId" to "ch-1"), body = mapOf("answer" to ""))).status).isEqualTo(200)
        assertThat(empty.challengeAnswers).containsExactly("ch-1" to null)
        val invalid = RendererActions(FakeSource().apply { pendingChallenge = password }).handle(
            "challenges.answer",
            payload(params = mapOf("challengeId" to "ch-1"), body = mapOf("answer" to 42)),
        )
        assertThat(invalid.status).isEqualTo(422)
        assertThat(codeOf(invalid)).isEqualTo("INVALID_ANSWER")
    }

    @Test
    fun messagesBeforeLoadsOneOlderPageAndRefusesBeyondTheStartOfHistory() = runTest {
        val current = listOf(AgentEvent(type = "user", text = "现在", historyKey = 9), AgentEvent(type = "content", text = "回答", historyKey = 10))
        val older = listOf(AgentEvent(type = "user", text = "更早", historyKey = 3), AgentEvent(type = "content", text = "旧回答", historyKey = 4))
        val source = openSource().apply {
            events = current
            hasEarlier = true
            earlierEvents = older
            hasEarlierAfterLoad = true
        }
        val actions = RendererActions(source)
        // The cursor has to be a row the caller is holding, or the page below it would be a guess.
        val unknown = actions.handle("messages.before", payload(conversation = true, query = mapOf("before" to "m404")))
        assertThat(unknown.status).isEqualTo(422)
        assertThat(codeOf(unknown)).isEqualTo("INVALID_CURSOR")
        assertThat(source.earlierLoads).isZero()

        val page = dataOf(actions.handle("messages.before", payload(conversation = true, query = mapOf("before" to "m9"))))
        assertThat(source.earlierLoads).isEqualTo(1)
        val items = page.getJSONArray("items")
        assertThat(items.length()).isEqualTo(2)
        assertThat(items.getJSONObject(0).getString("msg_id")).isEqualTo("m3")
        assertThat(items.getJSONObject(1).getJSONObject("content").getString("content")).isEqualTo("旧回答")
        // The page really is older history, and the newer part the caller still holds is what `has_more_after` names.
        assertThat(page.getString("oldest_cursor")).isEqualTo("m3")
        assertThat(page.getString("newest_cursor")).isEqualTo("m4")
        assertThat(page.getBoolean("has_more_before")).isTrue()
        assertThat(page.getBoolean("has_more_after")).isTrue()

        // The start of history is a refusal, never an empty page that reads like an answer with no messages.
        val end = RendererActions(openSource().apply { events = current; hasEarlier = false }).handle("messages.before", payload(conversation = true))
        assertThat(end.status).isEqualTo(409)
        assertThat(codeOf(end)).isEqualTo("HISTORY_EXHAUSTED")

        // A page that cannot be read keeps its own failure instead of looking like the end of the conversation.
        val failing = openSource().apply {
            events = current
            hasEarlier = true
            earlierFailure = IOException("历史文件损坏")
        }
        val failed = RendererActions(failing).handle("messages.before", payload(conversation = true))
        assertThat(failed.status).isEqualTo(500)
        assertThat(codeOf(failed)).isEqualTo("HISTORY_LOAD_FAILED")
        assertThat((failed.body as JSONObject).getString("error")).contains("历史文件损坏")
        assertThat(failing.earlierLoads).isEqualTo(1)

        // Only the ready conversation has pages to read, and no conversation at all answers with its own code.
        val unready = RendererActions(openSource().apply { events = current; hasEarlier = true; active = false }).handle("messages.before", payload(conversation = true))
        assertThat(unready.status).isEqualTo(409)
        assertThat(codeOf(unready)).isEqualTo("CONVERSATION_NOT_OPEN")
        assertThat(RendererActions(FakeSource()).handle("messages.before", payload(conversation = true)).status).isEqualTo(404)
    }

    @Test
    fun aNewConversationAnswersTheNativeIdTheAgentAssigned() = runTest {
        val source = FakeSource().apply {
            hosts = listOf(savedHost)
            created = AgentSession(hostId = 7, agent = AgentKind.KIMI, sessionId = "native-42", cwd = "/workspace/new")
        }
        val result = RendererActions(source).handle(
            "conversations.create",
            payload(params = mapOf("hostId" to "host-7"), body = mapOf("agent" to "kimi", "cwd" to "/workspace/new")),
        )
        assertThat(result.status).isEqualTo(200)
        // The product's own create entry ran, with the host, the Agent and the absolute directory of the request.
        assertThat(source.createdConversations).containsExactly(Triple(7L, AgentKind.KIMI, "/workspace/new"))
        val createdRow = dataOf(result)
        assertThat(createdRow.getString("session_id")).isEqualTo("native-42")
        assertThat(createdRow.getString("id")).isEqualTo(RendererConversationId.of(AgentSession(hostId = 7, agent = AgentKind.KIMI, sessionId = "native-42", cwd = "/workspace/new")))
        assertThat(createdRow.getString("project_id")).isEqualTo("host-7")
        assertThat(createdRow.getString("agent")).isEqualTo("kimi")
        assertThat(createdRow.getString("cwd")).isEqualTo("/workspace/new")
        assertThat(createdRow.getBoolean("created")).isTrue()
        assertThat(createdRow.getJSONObject("extra").getString("workspace")).isEqualTo("/workspace/new")
        assertThat(createdRow.getJSONObject("runtime").getString("state")).isEqualTo("idle")

        // The host, the Agent and the directory are each refused as themselves before anything is launched.
        val missingHost = RendererActions(source).handle("conversations.create", payload(params = mapOf("hostId" to "host-9"), body = mapOf("agent" to "kimi", "cwd" to "/w")))
        assertThat(missingHost.status).isEqualTo(404)
        assertThat(codeOf(missingHost)).isEqualTo("HOST_NOT_FOUND")
        val unknownAgent = RendererActions(source).handle("conversations.create", payload(params = mapOf("hostId" to "host-7"), body = mapOf("agent" to "gemini", "cwd" to "/w")))
        assertThat(unknownAgent.status).isEqualTo(422)
        assertThat(codeOf(unknownAgent)).isEqualTo("AGENT_UNKNOWN")
        for (cwd in listOf("work/relative", "", "/w\n按回车")) {
            val invalid = RendererActions(source).handle("conversations.create", payload(params = mapOf("hostId" to "host-7"), body = mapOf("agent" to "kimi", "cwd" to cwd)))
            assertThat(invalid.status).describedAs(cwd).isEqualTo(422)
            assertThat(codeOf(invalid)).describedAs(cwd).isEqualTo("INVALID_CWD")
        }
        assertThat(source.createdConversations).hasSize(1)

        // The renderer's own new-conversation entry names the host in the body — `host_id`, and the camelCase
        // spelling too — so a create that carries it there is served like one that carries it in the parameters.
        for (request in listOf(
            payload(body = mapOf("host_id" to "host-7", "agent" to "kimi", "cwd" to "/workspace/new")),
            payload(body = mapOf("hostId" to "host-7", "agent" to "kimi", "cwd" to "/workspace/new")),
            payload(params = mapOf("hostId" to "host-7", "agent" to "kimi", "cwd" to "/workspace/new")),
        )) {
            val perCase = FakeSource().apply {
                hosts = listOf(savedHost)
                created = AgentSession(hostId = 7, agent = AgentKind.KIMI, sessionId = "native-42", cwd = "/workspace/new")
            }
            val result = RendererActions(perCase).handle("conversations.create", request)
            assertThat(result.status).describedAs(request.toString()).isEqualTo(200)
            assertThat(perCase.createdConversations).describedAs(request.toString()).containsExactly(Triple(7L, AgentKind.KIMI, "/workspace/new"))
            assertThat(dataOf(result).getString("session_id")).describedAs(request.toString()).isEqualTo("native-42")
        }
        // A host named nowhere is still a host nobody saved, whichever spelling a request tried.
        val unnamed = RendererActions(source).handle("conversations.create", payload(body = mapOf("agent" to "kimi", "cwd" to "/workspace/new")))
        assertThat(unnamed.status).isEqualTo(404)
        assertThat(codeOf(unnamed)).isEqualTo("HOST_NOT_FOUND")

        // A directory the host cannot use is reported with the repository's own reason, not as a created session.
        val unreachable = FakeSource().apply {
            hosts = listOf(savedHost)
            createFailure = "目录不可用：working directory is not reachable: /missing"
        }
        val failed = RendererActions(unreachable).handle("conversations.create", payload(params = mapOf("hostId" to "host-7"), body = mapOf("agent" to "kimi", "cwd" to "/missing")))
        assertThat(failed.status).isEqualTo(409)
        assertThat(codeOf(failed)).isEqualTo("SESSION_CREATE_FAILED")
        assertThat((failed.body as JSONObject).getString("error")).contains("/missing")

        // An Agent that assigns no native id leaves the conversation without the identity later requests must name.
        val anonymous = FakeSource().apply {
            hosts = listOf(savedHost)
            created = AgentSession(hostId = 7, agent = AgentKind.KIMI, sessionId = "", cwd = "/workspace/new")
        }
        val unknownId = RendererActions(anonymous).handle("conversations.create", payload(params = mapOf("hostId" to "host-7"), body = mapOf("agent" to "kimi", "cwd" to "/workspace/new")))
        assertThat(unknownId.status).isEqualTo(409)
        assertThat(codeOf(unknownId)).isEqualTo("SESSION_ID_UNKNOWN")
    }

    @Test
    fun terminalOpenHandsOverTheCommandOfTheHostsOwnTarget() = runTest {
        val source = FakeSource().apply {
            hosts = listOf(savedHost)
            sessions = listOf(this@RendererActionsTest.session)
            session = this@RendererActionsTest.session
            active = true
        }
        val surface = FakeTerminal()
        val open = RendererActions(source, terminal = surface).handle("terminal.open", payload(conversation = true))
        assertThat(open.status).isEqualTo(200)
        val data = dataOf(open)
        assertThat(data.getString("host_id")).isEqualTo("host-7")
        assertThat(data.getString("host_name")).isEqualTo("工作机")
        assertThat(data.getString("agent")).isEqualTo("kimi")
        assertThat(data.getString("cwd")).isEqualTo("/workspace/app")
        assertThat(data.getString("session_id")).isEqualTo("session-a")
        assertThat(data.getString("conversation_id")).isEqualTo(RendererConversationId.of(this@RendererActionsTest.session))
        assertThat(data.getString("target")).isEqualTo("posix")
        // The command is the product's own terminal route command for this session, unchanged on a POSIX host.
        assertThat(data.getString("command")).isEqualTo("cd -- '/workspace/app' && exec kimi --session 'session-a'")
        // That command is what the product's own console was really started with, and only then says opened.
        assertThat(data.getBoolean("opened")).isTrue()
        assertThat(surface.starts).containsExactly(7L to "cd -- '/workspace/app' && exec kimi --session 'session-a'")
        // No credential leaves the host: the payload names the command and nothing to log in with.
        assertThat(data.keys().asSequence().toList())
            .doesNotContain("intent", "activity", "component", "password", "key", "secret", "token")

        // A directory the caller typed is the directory the terminal is asked to start in.
        val typed = dataOf(RendererActions(source, terminal = surface).handle("terminal.open", payload(conversation = true, body = mapOf("cwd" to "/srv/app"))))
        assertThat(typed.getString("cwd")).isEqualTo("/srv/app")
        assertThat(typed.getString("command")).isEqualTo("cd -- '/srv/app' && exec kimi --session 'session-a'")
        assertThat(surface.starts.last()).isEqualTo(7L to "cd -- '/srv/app' && exec kimi --session 'session-a'")

        // The TUI route answers the same command for a conversation that is indexed but not open, because the
        // TUI reads its session id from the index.
        val indexedOnly = FakeSource().apply {
            hosts = listOf(savedHost)
            sessions = listOf(this@RendererActionsTest.session)
        }
        val tuiSurface = FakeTerminal()
        val tui = dataOf(
            RendererActions(indexedOnly, terminal = tuiSurface).handle(
                "terminal.command.start",
                payload(params = mapOf("conversationId" to RendererConversationId.of(this@RendererActionsTest.session))),
            ),
        )
        assertThat(tui.getString("session_id")).isEqualTo("session-a")
        assertThat(tui.getString("command")).isEqualTo("cd -- '/workspace/app' && exec kimi --session 'session-a'")
        assertThat(tui.getBoolean("opened")).isTrue()
        assertThat(tuiSurface.starts).containsExactly(7L to "cd -- '/workspace/app' && exec kimi --session 'session-a'")

        // A WSL host runs the same command through the Windows entry of its target.
        val wslSession = this@RendererActionsTest.session.copy(hostId = 8)
        val wsl = FakeSource().apply {
            hosts = listOf(savedHost.copy(id = 8, nickname = "windows-box", osType = "windows", agentTarget = "wsl:Ubuntu-24.04"))
            sessions = listOf(wslSession)
        }
        val wslSurface = FakeTerminal()
        val wrapped = dataOf(RendererActions(wsl, terminal = wslSurface).handle("terminal.open", payload(params = mapOf("conversationId" to RendererConversationId.of(wslSession)))))
        assertThat(wrapped.getString("target")).isEqualTo("wsl:Ubuntu-24.04")
        assertThat(wrapped.getString("command")).startsWith("wsl.exe --distribution \"Ubuntu-24.04\"")
        assertThat(wrapped.getBoolean("opened")).isTrue()
        assertThat(wslSurface.starts.single().first).isEqualTo(8L)

        // A target this app has no shell entry for, and a directory nobody ever learned, each fail as themselves.
        val windowsNative = FakeSource().apply {
            hosts = listOf(savedHost.copy(id = 8, osType = "windows", agentTarget = "native"))
            sessions = listOf(wslSession)
        }
        val refused = FakeTerminal()
        val unsupported = RendererActions(windowsNative, terminal = refused).handle("terminal.open", payload(params = mapOf("conversationId" to RendererConversationId.of(wslSession))))
        assertThat(unsupported.status).isEqualTo(501)
        assertThat(codeOf(unsupported)).isEqualTo("TERMINAL_UNSUPPORTED")
        // A target nothing can open a shell on is never handed to the console either.
        assertThat(refused.starts).isEmpty()
        val noDirectory = FakeSource().apply {
            hosts = listOf(savedHost)
            sessions = listOf(this@RendererActionsTest.session.copy(cwd = ""))
        }
        val unknownWorkspace = RendererActions(noDirectory, terminal = FakeTerminal()).handle(
            "terminal.open",
            payload(params = mapOf("conversationId" to RendererConversationId.of(this@RendererActionsTest.session.copy(cwd = "")))),
        )
        assertThat(unknownWorkspace.status).isEqualTo(409)
        assertThat(codeOf(unknownWorkspace)).isEqualTo("WORKSPACE_UNKNOWN")
        assertThat(RendererActions(source, terminal = FakeTerminal()).handle("terminal.open", payload(params = mapOf("conversationId" to "c1.7.6b696d69.73657373696f6e2d7a"))).status).isEqualTo(404)
    }

    /**
     * A hand-over that did not open a terminal is never answered as opened: a build without any terminal surface
     * names that, and a start the system refused reports its own code with the reason the system gave.
     */
    @Test
    fun aTerminalThatDidNotStartIsNeverReportedAsOpened() = runTest {
        val source = FakeSource().apply {
            hosts = listOf(savedHost)
            sessions = listOf(this@RendererActionsTest.session)
            session = this@RendererActionsTest.session
            active = true
        }
        for (action in listOf("terminal.open", "terminal.command.start")) {
            // No terminal surface at all: the command is still answered with, but nothing claims a console opened.
            val withoutSurface = RendererActions(source).handle(action, payload(conversation = true))
            assertThat(withoutSurface.status).describedAs(action).isEqualTo(501)
            assertThat(codeOf(withoutSurface)).describedAs(action).isEqualTo("TERMINAL_UNSUPPORTED")
            assertThat((withoutSurface.body as JSONObject).getBoolean("success")).describedAs(action).isFalse()

            val failed = FakeTerminal().apply { result = RendererTerminal.Result.Failed("android.content.ActivityNotFoundException: no console") }
            val refused = RendererActions(source, terminal = failed).handle(action, payload(conversation = true))
            assertThat(refused.status).describedAs(action).isEqualTo(500)
            assertThat(codeOf(refused)).describedAs(action).isEqualTo("TERMINAL_START_FAILED")
            assertThat((refused.body as JSONObject).getString("error")).describedAs(action).contains("ActivityNotFoundException")
            // The surface was asked exactly once, with this conversation's own host and command.
            assertThat(failed.starts).describedAs(action).containsExactly(7L to "cd -- '/workspace/app' && exec kimi --session 'session-a'")
        }
    }

    @Test
    fun aTerminalCommandNamesNoConversationTheProductDoesNotHold() = runTest {
        // Nothing is open and the index holds nothing either, so there is no session id a command could resume.
        for (action in listOf("terminal.open", "terminal.command.start")) {
            val result = RendererActions(FakeSource()).handle(action, payload(conversation = true))
            assertThat(result.status).describedAs(action).isEqualTo(404)
            assertThat(codeOf(result)).describedAs(action).isEqualTo("CONVERSATION_NOT_FOUND")
        }
    }

    @Test
    fun aTerminalOnASavedHostUsesThatHostsAgentAndTheTypedDirectory() = runTest {
        val installed = FakeSource().apply {
            hosts = listOf(savedHost)
            // Kimi is the Agent this host's probe found; the second row was ruled out and must not be chosen.
            availability = listOf(availableRow(7, AgentKind.KIMI), missingRow(7, AgentKind.OPENCODE))
        }
        val surface = FakeTerminal()
        for (action in listOf("terminal.open", "terminal.command.start")) {
            val result = RendererActions(installed, terminal = surface).handle(action, payload(params = mapOf("hostId" to "host-7"), body = mapOf("cwd" to "/srv/app")))
            assertThat(result.status).describedAs(action).isEqualTo(200)
            val data = dataOf(result)
            assertThat(data.getString("host_id")).describedAs(action).isEqualTo("host-7")
            assertThat(data.getString("host_name")).describedAs(action).isEqualTo("工作机")
            assertThat(data.getString("agent")).describedAs(action).isEqualTo("kimi")
            assertThat(data.getString("cwd")).describedAs(action).isEqualTo("/srv/app")
            assertThat(data.getString("target")).describedAs(action).isEqualTo("posix")
            // A saved host has no session of its own, so the command starts the Agent and resumes nothing.
            assertThat(data.getString("command")).describedAs(action).isEqualTo("cd -- '/srv/app' && exec kimi")
            assertThat(data.getBoolean("opened")).describedAs(action).isTrue()
            assertThat(data.keys().asSequence().toList())
                .describedAs(action)
                .doesNotContain("session_id", "conversation_id", "password", "key", "secret", "token")
        }
        assertThat(surface.starts).containsExactly(7L to "cd -- '/srv/app' && exec kimi", 7L to "cd -- '/srv/app' && exec kimi")

        // The conversation this product holds for that host is the Agent it already uses there, ahead of the order
        // the probe rows would pick.
        val chosen = AgentSession(hostId = 7, agent = AgentKind.OPENCODE, sessionId = "session-b", cwd = "/workspace/other")
        val withConversation = FakeSource().apply {
            hosts = listOf(savedHost)
            sessions = listOf(chosen)
            session = chosen
            active = true
            availability = listOf(availableRow(7, AgentKind.KIMI), availableRow(7, AgentKind.OPENCODE))
        }
        val chosenSurface = FakeTerminal()
        val opened = dataOf(RendererActions(withConversation, terminal = chosenSurface).handle("terminal.open", payload(params = mapOf("hostId" to "host-7"), body = mapOf("cwd" to "/srv/app"))))
        assertThat(opened.getString("agent")).isEqualTo("opencode")
        assertThat(opened.getString("command")).isEqualTo("cd -- '/srv/app' && exec opencode")
        assertThat(chosenSurface.starts).containsExactly(7L to "cd -- '/srv/app' && exec opencode")

        // A WSL host wraps the same command once for its own target.
        val wsl = FakeSource().apply {
            hosts = listOf(savedHost.copy(id = 8, nickname = "windows-box", osType = "windows", agentTarget = "wsl:Ubuntu-24.04"))
            availability = listOf(availableRow(8))
        }
        val wslSurface = FakeTerminal()
        val wrapped = dataOf(RendererActions(wsl, terminal = wslSurface).handle("terminal.command.start", payload(params = mapOf("hostId" to "host-8"), body = mapOf("cwd" to "/srv/app"))))
        assertThat(wrapped.getString("target")).isEqualTo("wsl:Ubuntu-24.04")
        assertThat(wrapped.getString("command")).startsWith("wsl.exe --distribution \"Ubuntu-24.04\"")
        assertThat(wslSurface.starts.single().first).isEqualTo(8L)
    }

    @Test
    fun aHostWithoutAnAgentOrADirectoryRefusesToInventATerminalCommand() = runTest {
        // Nothing was ever probed here, so no Agent on this host is known to be installed.
        val surface = FakeTerminal()
        val unprobed = FakeSource().apply { hosts = listOf(savedHost) }
        val noAgent = RendererActions(unprobed, terminal = surface).handle("terminal.open", payload(params = mapOf("hostId" to "host-7"), body = mapOf("cwd" to "/srv/app")))
        assertThat(noAgent.status).isEqualTo(409)
        assertThat(codeOf(noAgent)).isEqualTo("AGENT_UNAVAILABLE")

        // A probe that ruled every Agent out leaves no installed Agent either, and a target the app cannot address
        // is not an installed one.
        val ruledOut = FakeSource().apply {
            hosts = listOf(savedHost)
            availability = listOf(missingRow(7, AgentKind.KIMI), targetUnsupportedRow(7, AgentKind.OPENCODE))
        }
        val none = RendererActions(ruledOut, terminal = surface).handle("terminal.command.start", payload(params = mapOf("hostId" to "host-7"), body = mapOf("cwd" to "/srv/app")))
        assertThat(none.status).isEqualTo(409)
        assertThat(codeOf(none)).isEqualTo("AGENT_UNAVAILABLE")

        // A host-scoped command has no conversation to take a directory from, so one has to be named and usable.
        val noDirectory = FakeSource().apply {
            hosts = listOf(savedHost)
            availability = listOf(availableRow(7))
        }
        for (cwd in listOf(null, "", "srv/app", "/srv\napp")) {
            val result = RendererActions(noDirectory, terminal = surface).handle("terminal.open", payload(params = mapOf("hostId" to "host-7"), body = mapOf("cwd" to cwd)))
            assertThat(result.status).describedAs("$cwd").isEqualTo(422)
            assertThat(codeOf(result)).describedAs("$cwd").isEqualTo("INVALID_CWD")
        }
        val notText = RendererActions(noDirectory, terminal = surface).handle("terminal.open", payload(params = mapOf("hostId" to "host-7"), body = mapOf("cwd" to 42)))
        assertThat(notText.status).isEqualTo(422)
        assertThat(codeOf(notText)).isEqualTo("INVALID_CWD")

        // An unnamed target and a host nobody saved are each refused as themselves.
        val noTarget = RendererActions(noDirectory, terminal = surface).handle("terminal.open", payload(body = mapOf("cwd" to "/srv/app")))
        assertThat(noTarget.status).isEqualTo(422)
        assertThat(codeOf(noTarget)).isEqualTo("TERMINAL_TARGET_UNKNOWN")
        val unknownHost = RendererActions(noDirectory, terminal = surface).handle("terminal.open", payload(params = mapOf("hostId" to "host-9"), body = mapOf("cwd" to "/srv/app")))
        assertThat(unknownHost.status).isEqualTo(404)
        assertThat(codeOf(unknownHost)).isEqualTo("HOST_NOT_FOUND")

        // A command this host refused to build never reaches the terminal surface at all.
        assertThat(surface.starts).isEmpty()
    }

    @Test
    fun hostsRefreshProbesTheHostAndReportsTheRefreshedIndex() = runTest {
        val source = FakeSource().apply {
            hosts = listOf(savedHost)
            sessions = listOf(
                this@RendererActionsTest.session,
                this@RendererActionsTest.session.copy(sessionId = "session-b", cwd = "/workspace/b"),
                AgentSession(hostId = 8, agent = AgentKind.KIMI, sessionId = "session-c", cwd = "/workspace/c"),
            )
            probeRows = mapOf(7L to listOf(availableRow(7)))
        }
        val result = RendererActions(source).handle("hosts.refresh", payload(params = mapOf("hostId" to "host-7")))
        assertThat(result.status).isEqualTo(200)
        // The repository's own refresh entry ran for this host, and it is the one that also rescans the index.
        assertThat(source.probedHosts).containsExactly(7L)
        val data = dataOf(result)
        assertThat(data.getString("host_id")).isEqualTo("host-7")
        assertThat(data.getBoolean("refreshed")).isTrue()
        assertThat(data.getInt("sessions")).isEqualTo(2)
        assertThat(data.getJSONObject("detection").getBoolean("checked")).isTrue()
        assertThat(data.getJSONObject("detection").getJSONArray("agents").getJSONObject(0).getString("state")).isEqualTo("available")

        val failing = FakeSource().apply {
            hosts = listOf(savedHost)
            detectionFailure = IOException("探测通道不可用")
        }
        val failed = RendererActions(failing).handle("hosts.refresh", payload(params = mapOf("hostId" to "host-7")))
        assertThat(failed.status).isEqualTo(500)
        assertThat(codeOf(failed)).isEqualTo("HOST_REFRESH_FAILED")
        assertThat((failed.body as JSONObject).getString("error")).contains("探测通道不可用")
        assertThat(RendererActions(FakeSource()).handle("hosts.refresh", payload(params = mapOf("hostId" to "host-7"))).status).isEqualTo(404)
    }

    @Test
    fun theBridgeAcceptsTheNewRoutesAndKeepsTheirParameters() {
        val bridge = ProbeBridge(projectId = null)
        bridge.commit(ProbeBridge.ENTRY)
        val seen = mutableListOf<String>()
        val execute = { action: String, payload: JSONObject ->
            val params = payload.getJSONObject("params")
            seen += action + ":" + params.keys().asSequence().sorted().joinToString(",") { key -> "$key=${params.optString(key)}" }
            ProbeResult(body = json("success" to true))
        }
        val handshake = bridge.receive(ProbeBridge.ORIGIN, true, bridgeRequest("host.handshake", "", "renderer-1", JSONObject()), execute)!!
        val document = handshake.getJSONObject("body").getString("documentId")
        var serial = 2
        fun receive(action: String, params: JSONObject = JSONObject()): JSONObject = bridge.receive(ProbeBridge.ORIGIN, true, bridgeRequest(action, document, "renderer-${serial++}", params), execute)!!

        assertThat(receive("challenges.read").getInt("status")).isEqualTo(200)
        assertThat(receive("challenges.answer", JSONObject().put("challengeId", "ch-1")).getInt("status")).isEqualTo(200)
        assertThat(receive("messages.before", JSONObject().put("conversationId", RENDERER_CONVERSATION)).getInt("status")).isEqualTo(200)
        assertThat(receive("interactions.review", JSONObject().put("conversationId", RENDERER_CONVERSATION).put("requestRef", "ask-1")).getInt("status")).isEqualTo(200)
        assertThat(receive("terminal.open", JSONObject().put("conversationId", RENDERER_CONVERSATION)).getInt("status")).isEqualTo(200)
        assertThat(receive("terminal.command.start", JSONObject().put("conversationId", RENDERER_CONVERSATION)).getInt("status")).isEqualTo(200)
        // The two terminal routes may address a saved host instead, and they travel without a conversation then.
        assertThat(receive("terminal.open", JSONObject().put("hostId", "host-7")).getInt("status")).isEqualTo(200)
        assertThat(receive("terminal.command.start", JSONObject().put("hostId", "host-7")).getInt("status")).isEqualTo(200)
        assertThat(receive("hosts.refresh", JSONObject().put("hostId", "host-7")).getInt("status")).isEqualTo(200)
        assertThat(receive("conversations.create", JSONObject().put("hostId", "host-7").put("agent", "kimi").put("cwd", "/w")).getInt("status")).isEqualTo(200)
        // The cross-conversation routes: three that name no conversation, one that names the one it quotes, and
        // the queue id a withdrawal travels by.
        assertThat(receive("search.sessions").getInt("status")).isEqualTo(200)
        assertThat(receive("inbox.list").getInt("status")).isEqualTo(200)
        assertThat(receive("queue.list").getInt("status")).isEqualTo(200)
        assertThat(receive("queue.send").getInt("status")).isEqualTo(200)
        assertThat(receive("queue.cancel", JSONObject().put("queueId", "queue-1")).getInt("status")).isEqualTo(200)
        assertThat(receive("reference.pack", JSONObject().put("conversationId", RENDERER_CONVERSATION)).getInt("status")).isEqualTo(200)
        assertThat(receive("index.clear").getInt("status")).isEqualTo(200)
        // Every new route reached the action layer with exactly the parameters it documents.
        assertThat(seen).containsExactly(
            "challenges.read:",
            "challenges.answer:challengeId=ch-1",
            "messages.before:conversationId=$RENDERER_CONVERSATION",
            "interactions.review:conversationId=$RENDERER_CONVERSATION,requestRef=ask-1",
            "terminal.open:conversationId=$RENDERER_CONVERSATION",
            "terminal.command.start:conversationId=$RENDERER_CONVERSATION",
            "terminal.open:hostId=host-7",
            "terminal.command.start:hostId=host-7",
            "hosts.refresh:hostId=host-7",
            "conversations.create:agent=kimi,cwd=/w,hostId=host-7",
            "search.sessions:",
            "inbox.list:",
            "queue.list:",
            "queue.send:",
            "queue.cancel:queueId=queue-1",
            "reference.pack:conversationId=$RENDERER_CONVERSATION",
            "index.clear:",
        )

        // A conversation route of the new set still refuses an id the product could not have derived, and a route
        // whose id the bridge checks refuses a parameter it never documented.
        val wrongConversation = receive("terminal.open", JSONObject().put("conversationId", "conv-other"))
        assertThat(wrongConversation.getInt("status")).isEqualTo(404)
        assertThat(wrongConversation.getJSONObject("body").getString("code")).isEqualTo("CONVERSATION_NOT_FOUND")
        val wrongQuote = receive("reference.pack", JSONObject().put("conversationId", "conv-other"))
        assertThat(wrongQuote.getInt("status")).isEqualTo(404)
        assertThat(wrongQuote.getJSONObject("body").getString("code")).isEqualTo("CONVERSATION_NOT_FOUND")
        val wrongQueueParameter = receive("queue.cancel", JSONObject().put("hostId", "host-7"))
        assertThat(wrongQueueParameter.getInt("status")).isEqualTo(400)
        assertThat(wrongQueueParameter.getJSONObject("body").getString("code")).isEqualTo("INVALID_REQUEST")
        val wrongParameter = receive("challenges.answer", JSONObject().put("hostId", "host-7"))
        assertThat(wrongParameter.getInt("status")).isEqualTo(400)
        assertThat(wrongParameter.getJSONObject("body").getString("code")).isEqualTo("INVALID_REQUEST")
        val wrongTerminalParameter = receive("terminal.command.start", JSONObject().put("projectId", "host-7"))
        assertThat(wrongTerminalParameter.getInt("status")).isEqualTo(400)
        assertThat(wrongTerminalParameter.getJSONObject("body").getString("code")).isEqualTo("INVALID_REQUEST")
    }

    /** One saved host with every identity column a rename or an address change must leave alone. */
    private val savedHost = Host(
        id = 7,
        nickname = "工作机",
        protocol = "ssh",
        username = "colle",
        hostname = "example.test",
        port = 22,
        hostKeyAlgo = "ssh-ed25519",
        lastConnect = 1_700_000_000_000L,
        color = "blue",
        pubkeyId = 42L,
        profileId = 3L,
        jumpHostId = 5L,
    )

    /**
     * One question event shaped the way an ACP agent states it: the request id, the prompt and the schema the
     * answer must satisfy. The Compose conversation form and this host project it with the same code.
     */
    private fun questionEvent(id: String, text: String, key: Long): AgentEvent = AgentEvent(
        type = "question",
        id = id,
        text = text,
        raw = buildJsonObject {
            put(
                "requestedSchema",
                buildJsonObject {
                    put("type", "object")
                    put("required", buildJsonArray { add(JsonPrimitive("branch")) })
                    put(
                        "properties",
                        buildJsonObject {
                            put(
                                "branch",
                                buildJsonObject {
                                    put("type", "string")
                                    put("title", "分支")
                                    put(
                                        "enum",
                                        buildJsonArray {
                                            add(JsonPrimitive("main"))
                                            add(JsonPrimitive("dev"))
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            )
        },
        historyKey = key,
    )

    private fun availableRow(hostId: Long, kind: AgentKind = AgentKind.KIMI): AgentAvailability = AgentAvailability(
        hostId = hostId,
        kind = kind,
        executable = "/usr/local/bin/${kind.wireName}",
        available = true,
        version = "1.0.0",
        capabilities = JsonObject(mapOf("acp" to JsonPrimitive(true))),
    )

    private fun missingRow(hostId: Long, kind: AgentKind): AgentAvailability = AgentAvailability(
        hostId = hostId,
        kind = kind,
        executable = "",
        available = false,
        error = "executable_missing",
    )

    private fun targetUnsupportedRow(hostId: Long, kind: AgentKind = AgentKind.KIMI): AgentAvailability = AgentAvailability(
        hostId = hostId,
        kind = kind,
        executable = "",
        available = false,
        error = WINDOWS_NATIVE_UNSUPPORTED,
    )

    private fun codeOf(result: ProbeResult): String = (result.body as JSONObject).getString("code")

    private fun dataOf(result: ProbeResult): JSONObject = (result.body as JSONObject).getJSONObject("data")

    /** One content hit as the remote transcript search states it: the file and the line the match sits at. */
    private fun contentHit(hostId: Long, agent: AgentKind, sessionId: String, directory: String, file: String, line: Int, snippet: String): AgentTranscriptHit =
        AgentTranscriptHit(
            hostId,
            TranscriptSearchHit(
                sessionKey = "${agent.wireName}:$sessionId",
                agent = agent,
                sessionId = sessionId,
                directory = directory,
                file = file,
                line = line,
                offset = 0,
                snippet = snippet,
                clipped = false,
            ),
        )

    /** One open conversation of host 7 whose workspace listing a case decides. */
    private fun openSourceWithWorkspace(configure: FakeSource.() -> Unit): FakeSource = FakeSource().apply {
        hosts = listOf(this@RendererActionsTest.host)
        session = this@RendererActionsTest.session
        indexed = this@RendererActionsTest.session
        active = true
        configure()
    }

    private suspend fun projectRoot(actions: RendererActions, projectId: String): JSONObject {
        val read = actions.handle("project.read", payload(params = mapOf("projectId" to projectId)))
        assertThat(read.status).isEqualTo(200)
        return (read.body as JSONObject).getJSONObject("data").getJSONObject("explorer").getJSONArray("entries").getJSONObject(0)
    }

    @Test
    fun productBridgeForwardsHostProjectIdsInsteadOfPinningTheProbeProject() {
        val bridge = ProbeBridge(projectId = null)
        bridge.commit(ProbeBridge.ENTRY)
        var requested: String? = null
        val execute = { _: String, body: JSONObject ->
            requested = body.getJSONObject("params").optString("projectId")
            ProbeResult(body = json("success" to true))
        }
        val handshake = bridge.receive(ProbeBridge.ORIGIN, true, bridgeRequest("host.handshake", "", "renderer-1", JSONObject()), execute)!!
        val document = handshake.getJSONObject("body").getString("documentId")
        val read = bridge.receive(
            ProbeBridge.ORIGIN,
            true,
            bridgeRequest("project.read", document, "renderer-2", JSONObject().put("projectId", "host-7")),
            execute,
        )!!
        assertThat(read.getInt("status")).isEqualTo(200)
        assertThat(requested).isEqualTo("host-7")
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
        assertThat(source.writes).containsExactly(Triple("model", "gpt-4", this@RendererActionsTest.session))
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
        assertThat(source.writes).containsExactly(Triple("model", "gpt-4", this@RendererActionsTest.session))
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

    @Test
    fun zcodeConfigurationKeepsTheAcpOptionShapeAndItsReadOnlyDiagnostics() = runTest {
        val source = openSource().apply {
            configAccess = AgentConfigAccess.Available(AgentZcodeConfig.project(zcodeSnapshot("m2")))
        }
        val options = configOptions(source)
        val model = optionById(options, ZCODE_MODEL_OPTION)
        assertThat(model.getString("category")).isEqualTo("model")
        assertThat(model.getString("type")).isEqualTo("select")
        assertThat(model.getString("current_value")).isEqualTo("p/m2")
        assertThat(model.getJSONArray("options").length()).isEqualTo(2)
        assertThat(model.getJSONArray("options").getJSONObject(1).getString("value")).isEqualTo("p/m2")
        val mode = optionById(options, "mode")
        assertThat(mode.getString("type")).isEqualTo("readonly")
        assertThat(mode.getString("current_value")).isEqualTo("build")

        val unset = openSource().apply {
            configAccess = AgentConfigAccess.Available(AgentZcodeConfig.project(zcodeSnapshot(null)))
        }
        assertThat(optionById(configOptions(unset), ZCODE_MODEL_OPTION).isNull("current_value")).isTrue()
    }

    @Test
    fun zcodeReceiptsMapToObservedNextTurnAndCommandAck() = runTest {
        val before = zcodeSnapshot("m1")
        val after = zcodeSnapshot("m2")

        fun sourceFor(status: ZcodeConfigStatus, config: ZcodeSessionConfig, applied: String?): FakeSource = openSource().apply {
            configAccess = AgentConfigAccess.Available(AgentZcodeConfig.project(before))
            setter = { _, _ ->
                AgentZcodeConfig.receipt(
                    ZcodeConfigChange(status, requested = "p/m2", applied = applied, revision = 2, busy = false, config = config, raw = JsonObject(emptyMap())),
                )
            }
        }

        suspend fun select(actions: RendererActions) = actions.handle(
            "config.set",
            payload(conversation = true, params = mapOf("optionId" to ZCODE_MODEL_OPTION), body = mapOf("value" to "p/m2")),
        )

        val confirmed = (select(RendererActions(sourceFor(ZcodeConfigStatus.CONFIRMED, after, "p/m2"))).body as JSONObject).getJSONObject("data")
        assertThat(confirmed.getString("confirmation")).isEqualTo("observed")

        val adjustedResult = select(RendererActions(sourceFor(ZcodeConfigStatus.ADJUSTED, before, "p/m1")))
        assertThat(adjustedResult.status).isEqualTo(200)
        val adjusted = (adjustedResult.body as JSONObject).getJSONObject("data")
        assertThat(adjusted.getString("confirmation")).isEqualTo("pending_next_turn")
        assertThat(optionById(adjusted.getJSONArray("config_options"), ZCODE_MODEL_OPTION).getString("current_value")).isEqualTo("p/m1")

        val ackedResult = select(RendererActions(sourceFor(ZcodeConfigStatus.DELIVERED_PENDING, before, null)))
        assertThat(ackedResult.status).isEqualTo(200)
        val acked = (ackedResult.body as JSONObject).getJSONObject("data")
        assertThat(acked.getString("confirmation")).isEqualTo("command_ack")
        assertThat(optionById(acked.getJSONArray("config_options"), ZCODE_MODEL_OPTION).getString("current_value")).isEqualTo("p/m1")
    }

    @Test
    fun aBusyAgentKeepsItsOwnRefusalCodeInsteadOfAGenericFailure() = runTest {
        val source = openSource().apply {
            configAccess = AgentConfigAccess.Available(AgentZcodeConfig.project(zcodeSnapshot("m1")))
            setter = { _, _ -> throw AgentConfigBusyException(-32603, "Permission update is busy; retry mode change") }
        }
        val result = RendererActions(source).handle(
            "config.set",
            payload(conversation = true, params = mapOf("optionId" to ZCODE_MODEL_OPTION), body = mapOf("value" to "p/m2")),
        )
        assertThat(result.status).isEqualTo(409)
        val body = result.body as JSONObject
        assertThat(body.getBoolean("success")).isFalse()
        assertThat(body.getString("code")).isEqualTo("CONFIG_BUSY")
        assertThat(body.getString("error")).contains("稍后重试")
    }

    @Test
    fun draftRoundTripsForTheOpenConversationUnderItsOwnSessionKey() = runTest {
        val source = openSource()
        val actions = RendererActions(source)
        val saved = actions.handle("draft.save", payload(conversation = true, body = mapOf("text" to "未发送的草稿", "files" to JSONArray())))
        assertThat(saved.status).isEqualTo(200)
        val savedDraft = (saved.body as JSONObject).getJSONObject("data")
        assertThat(savedDraft.getString("text")).isEqualTo("未发送的草稿")
        assertThat(savedDraft.getString("conversation_id")).isEqualTo(RENDERER_CONVERSATION)
        assertThat(savedDraft.getBoolean("saved")).isTrue()

        val loaded = actions.handle("draft.load", payload(conversation = true))
        assertThat(loaded.status).isEqualTo(200)
        val draft = (loaded.body as JSONObject).getJSONObject("data")
        assertThat(draft.getString("text")).isEqualTo("未发送的草稿")
        assertThat(draft.getString("conversation_id")).isEqualTo(RENDERER_CONVERSATION)
        // No attachment is ever handed over, so a loaded draft carries an empty file list instead of a fake one.
        assertThat(draft.getJSONArray("files").length()).isZero()
        // One key per conversation: the session identity the product itself resumes the session by.
        assertThat(source.draftKeys).containsExactly("7:kimi:session-a", "7:kimi:session-a")
    }

    @Test
    fun draftFailsWithoutAnOpenConversationInsteadOfAnsweringAnEmptyDraft() = runTest {
        val unopened = FakeSource()
        for (action in listOf("draft.load", "draft.save")) {
            val missing = RendererActions(unopened).handle(action, payload(conversation = true, body = mapOf("text" to "草稿")))
            assertThat(missing.status).describedAs(action).isEqualTo(404)
            assertThat((missing.body as JSONObject).getString("code")).isEqualTo("CONVERSATION_NOT_FOUND")
        }
        val loading = openSource().apply { active = false }
        for (action in listOf("draft.load", "draft.save")) {
            val closed = RendererActions(loading).handle(action, payload(conversation = true, body = mapOf("text" to "草稿")))
            assertThat(closed.status).describedAs(action).isEqualTo(409)
            assertThat((closed.body as JSONObject).getString("code")).isEqualTo("CONVERSATION_NOT_OPEN")
        }
        // Neither failed request touched the store, so a stored draft cannot be overwritten by an empty one.
        assertThat(loading.storedDrafts).isEmpty()
        val other = RendererActions(openSource()).handle("draft.load", payload(params = mapOf("conversationId" to "conv-other")))
        assertThat(other.status).isEqualTo(404)
    }

    @Test
    fun draftRefusesOversizedTextAndAttachmentReferences() = runTest {
        val source = openSource()
        val actions = RendererActions(source)
        val long = actions.handle("draft.save", payload(conversation = true, body = mapOf("text" to "长".repeat(16385))))
        assertThat(long.status).isEqualTo(413)
        assertThat((long.body as JSONObject).getString("code")).isEqualTo("DRAFT_TOO_LARGE")

        // A draft is text alone, so a file reference is still refused — and its reason says where an attachment
        // does travel, because a message now carries exactly this reference's path.
        for (entry in listOf(jsonOf("kind" to "upload", "path" to "/workspace/app/shot.png"), "/workspace/app/shot.png")) {
            val files = actions.handle("draft.save", payload(conversation = true, body = mapOf("text" to "草稿", "files" to JSONArray().put(entry))))
            assertThat(files.status).describedAs(entry.toString()).isEqualTo(422)
            assertThat((files.body as JSONObject).getString("code")).isEqualTo("ATTACHMENT_UNSUPPORTED")
            assertThat((files.body as JSONObject).getString("error")).contains("消息").contains("草稿")
        }

        val invalid = actions.handle("draft.save", payload(conversation = true, body = mapOf("text" to 42)))
        assertThat(invalid.status).isEqualTo(422)
        assertThat((invalid.body as JSONObject).getString("code")).isEqualTo("DRAFT_INVALID")
        assertThat(source.storedDrafts).isEmpty()
    }

    @Test
    fun usageReportsTheAgentsOwnNumbersAndStaysNullWithoutOne() = runTest {
        val silent = openSource().apply { events = listOf(AgentEvent(type = "content", text = "回答", historyKey = 4)) }
        val empty = RendererActions(silent).handle("usage.read", payload(conversation = true)).body as JSONObject
        assertThat(empty.getBoolean("success")).isTrue()
        assertThat(empty.isNull("data")).isTrue()

        val reported = openSource().apply {
            events = listOf(
                AgentEvent(type = "content", text = "回答", historyKey = 4),
                AgentEvent(
                    type = "usage",
                    raw = buildJsonObject {
                        put("sessionUpdate", "usage_update")
                        put("used", 3200)
                        put("size", 200000)
                    },
                ),
            )
        }
        val usage = (RendererActions(reported).handle("usage.read", payload(conversation = true)).body as JSONObject).getJSONObject("data")
        assertThat(usage.getInt("used")).isEqualTo(3200)
        assertThat(usage.getInt("size")).isEqualTo(200000)
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

    private fun bridgeRequest(action: String, document: String, id: String, params: JSONObject): String = jsonOf(
        "requestId" to id,
        "documentId" to document,
        "action" to action,
        "payload" to jsonOf("params" to params, "query" to JSONObject(), "body" to JSONObject.NULL),
    ).toString()

    /** The open conversation the renderer is bound to, with whatever configuration access a test hands it. */
    private fun openSource(): FakeSource = FakeSource().apply {
        session = this@RendererActionsTest.session
        active = true
    }

    /** One ZCode settings snapshot with a real-looking catalogue; the mode is read-only because the driver has no mode list. */
    private fun zcodeSnapshot(current: String?): ZcodeSessionConfig = ZcodeSessionConfig(
        catalog = listOf(
            ZcodeModelOption(ZcodeModelSelection("p", "m1"), "Model One"),
            ZcodeModelOption(ZcodeModelSelection("p", "m2"), "Model Two"),
        ),
        currentModel = current?.let { ZcodeModelSelection("p", it) },
        currentMode = "build",
    )

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
}
