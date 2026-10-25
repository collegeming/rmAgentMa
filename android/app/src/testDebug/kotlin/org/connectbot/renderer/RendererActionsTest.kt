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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.assertj.core.api.Assertions.assertThat
import org.connectbot.agent.AgentConfigAccess
import org.connectbot.agent.AgentConfigBusyException
import org.connectbot.agent.AgentZcodeConfig
import org.connectbot.agent.ZCODE_MODEL_OPTION
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
import org.rmagentma.core.DirectoryEntryKind
import org.rmagentma.core.DirectoryListing
import org.rmagentma.core.DirectoryListingProblem
import org.rmagentma.core.RemoteDirectoryEntry
import org.rmagentma.core.WINDOWS_NATIVE_UNSUPPORTED
import org.rmagentma.core.ZcodeConfigChange
import org.rmagentma.core.ZcodeConfigStatus
import org.rmagentma.core.ZcodeModelOption
import org.rmagentma.core.ZcodeModelSelection
import org.rmagentma.core.ZcodeSessionConfig
import org.rmagentma.rendererhost.ProbeBridge
import org.rmagentma.rendererhost.ProbeResult
import org.rmagentma.rendererhost.json
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class RendererActionsTest {
    private val session = AgentSession(hostId = 7, agent = AgentKind.KIMI, sessionId = "session-a", cwd = "/workspace/app", updatedAt = 1_000)
    private val host = Host(id = 7, nickname = "工作机", username = "colle", hostname = "example.test")

    @Test
    fun unsupportedRoutesFailWithAStableCodeAndReason() = runTest {
        val actions = RendererActions(FakeSource())
        val expected = mapOf(
            "runtime.visible" to "LEASE_UNSUPPORTED",
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
            sessions = listOf(indexed!!)
            active = true
        }
        val body = (RendererActions(source).handle("conversations.list", payload()).body as JSONObject)
        assertThat(body.getBoolean("success")).isTrue()
        val conversation = body.getJSONObject("data").getJSONArray("items").getJSONObject(0)
        assertThat(conversation.getString("id")).isEqualTo(RendererConversationId.of(opened))
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
            availability = listOf(availableRow(7), targetUnsupportedRow(8))
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

        // A Windows host whose Agent would run on Windows itself has no entry in this app, and its row says so.
        val native = rows.getJSONObject(1)
        assertThat(native.getString("os_type")).isEqualTo("windows")
        assertThat(native.getString("agent_target")).isEqualTo("native")
        assertThat(native.getString("target")).isEqualTo("windows-native")
        assertThat(native.getBoolean("target_supported")).isFalse()
        val unsupported = native.getJSONObject("detection").getJSONArray("agents").getJSONObject(0)
        assertThat(unsupported.getString("state")).isEqualTo("target_unsupported")
        assertThat(unsupported.getString("executable")).isEmpty()
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
    fun aWindowsHostsOwnAgentTargetIsNeverReportedAsUsable() = runTest {
        val windows = savedHost.copy(id = 8, nickname = "windows-box", osType = "windows", agentTarget = "native")
        val source = FakeSource().apply {
            hosts = listOf(windows)
            probeRows = mapOf(8L to AgentKind.entries.map { targetUnsupportedRow(8, it) })
        }
        val data = dataOf(RendererActions(source).handle("detection.refresh", payload(params = mapOf("hostId" to "host-8"))))
        assertThat(data.getBoolean("target_supported")).isFalse()
        assertThat(data.getString("target")).isEqualTo("windows-native")
        val agents = data.getJSONObject("detection").getJSONArray("agents")
        assertThat(agents.length()).isEqualTo(AgentKind.entries.size)
        for (index in 0 until agents.length()) {
            val agent = agents.getJSONObject(index)
            assertThat(agent.getString("state")).describedAs(agent.getString("id")).isEqualTo("target_unsupported")
            assertThat(agent.getString("executable")).describedAs(agent.getString("id")).isEmpty()
            assertThat(agent.getString("error")).isEqualTo(WINDOWS_NATIVE_UNSUPPORTED)
        }
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

        val reference = jsonOf("kind" to "upload", "path" to "probe-upload:7f00c1c2-1111-2222-3333-444455556666")
        val files = actions.handle("draft.save", payload(conversation = true, body = mapOf("text" to "草稿", "files" to JSONArray().put(reference))))
        assertThat(files.status).isEqualTo(422)
        assertThat((files.body as JSONObject).getString("code")).isEqualTo("ATTACHMENT_UNSUPPORTED")

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
