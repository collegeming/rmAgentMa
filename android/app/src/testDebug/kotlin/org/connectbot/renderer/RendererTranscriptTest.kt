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
import org.assertj.core.api.Assertions.assertThat
import org.connectbot.agent.AgentTranscriptRead
import org.connectbot.data.entity.Host
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.rmagentma.core.AgentEvent
import org.rmagentma.core.AgentKind
import org.rmagentma.core.AgentSession
import org.rmagentma.rendererhost.ProbeBridge
import org.rmagentma.rendererhost.ProbeResult
import org.rmagentma.rendererhost.json
import java.io.IOException

/**
 * The read-only transcript route: it answers another conversation's own history without ever making that
 * conversation the open one, and every way it cannot read a page fails as itself instead of as an empty history.
 */
@RunWith(AndroidJUnit4::class)
class RendererTranscriptTest {
    private val opened = AgentSession(hostId = 7, agent = AgentKind.KIMI, sessionId = "session-a", cwd = "/workspace/app", updatedAt = 900)
    private val other = AgentSession(hostId = 8, agent = AgentKind.DSH, sessionId = "session-b", cwd = "/srv/other", updatedAt = 500)
    private val openHost = Host(id = 7, nickname = "工作机", username = "colle", hostname = "first.test")
    private val otherHost = Host(id = 8, nickname = "另一台", username = "colle", hostname = "second.test")

    /** Two conversations of two hosts, with the first one open; the second is the one this route reads. */
    private fun twoHosts(): FakeSource = FakeSource().apply {
        session = opened
        indexed = opened
        active = true
        hosts = listOf(openHost, otherHost)
        sessions = listOf(opened, other)
    }

    @Test
    fun readingAnotherConversationNeverSelectsOrOpensIt() = runTest {
        val source = twoHosts().apply {
            transcriptRead = AgentTranscriptRead(items = listOf(AgentEvent(type = "user", text = "你好", id = "u1")), nextCursor = null)
        }
        val result = RendererActions(source).handle("conversation.transcript", payload(RendererConversationId.of(other)))
        assertThat(result.status).isEqualTo(200)
        // The page was really read from the target's own host, and the open conversation was left untouched.
        assertThat(source.transcriptReads.single().host).isEqualTo(otherHost)
        assertThat(source.transcriptReads.single().session).isEqualTo(other)
        assertThat(source.transcriptReads.single().limit).isEqualTo(50)
        assertThat(source.transcriptReads.single().cursor).isNull()
        assertThat(source.opens).isZero()
        assertThat(source.opening).isNull()
        assertThat(source.session).isEqualTo(opened)
        assertThat(source.active).isTrue()
    }

    /** The open conversation is read remotely too, so one route never has two meanings for the same request. */
    @Test
    fun theOpenConversationIsReadTheSameWayAsAnyOther() = runTest {
        val source = twoHosts()
        val result = RendererActions(source).handle("conversation.transcript", payload(RendererConversationId.of(opened)))
        assertThat(result.status).isEqualTo(200)
        assertThat(source.transcriptReads.single().session).isEqualTo(opened)
        assertThat(source.opens).isZero()
    }

    @Test
    fun anUnknownConversationIsNotFoundAndNothingIsRead() = runTest {
        val source = twoHosts()
        val absent = AgentSession(hostId = 9, agent = AgentKind.ZCODE, sessionId = "session-z", cwd = "/srv/z")
        val unknown = RendererActions(source).handle("conversation.transcript", payload(RendererConversationId.of(absent)))
        assertThat(unknown.status).isEqualTo(404)
        assertThat(codeOf(unknown)).isEqualTo("CONVERSATION_NOT_FOUND")
        // A session the index holds but whose host row is gone has no host to read it from either.
        val hostless = RendererActions(source.apply { hosts = listOf(openHost) })
            .handle("conversation.transcript", payload(RendererConversationId.of(other)))
        assertThat(hostless.status).isEqualTo(404)
        assertThat(source.transcriptReads).isEmpty()
    }

    @Test
    fun aLimitOutsideTheBoundedRangeIsRefusedAndNothingIsRead() = runTest {
        val source = twoHosts()
        val actions = RendererActions(source)
        for (query in listOf(mapOf("limit" to "0"), mapOf("limit" to "201"), mapOf("limit" to "-1"), mapOf("limit" to "many"), mapOf("limit" to ""))) {
            val refused = actions.handle("conversation.transcript", payload(RendererConversationId.of(other), query))
            assertThat(refused.status).describedAs(query.toString()).isEqualTo(422)
            assertThat(codeOf(refused)).describedAs(query.toString()).isEqualTo("INVALID_LIMIT")
        }
        assertThat(source.transcriptReads).isEmpty()

        // The bounds themselves are accepted, and the requested page size really reaches the host.
        for (limit in listOf(1, 200)) {
            val accepted = actions.handle("conversation.transcript", payload(RendererConversationId.of(other), mapOf("limit" to "$limit")))
            assertThat(accepted.status).describedAs("limit $limit").isEqualTo(200)
        }
        assertThat(source.transcriptReads.map { it.limit }).containsExactly(1, 200)
    }

    @Test
    fun thePageIsAnsweredAsTheHostSentIt() = runTest {
        val items = listOf(
            AgentEvent(type = "user", text = "部署到生产", id = "u1"),
            AgentEvent(type = "tool", text = "bash deploy.sh", id = "t1", title = "运行部署", status = "historical"),
        )
        val source = twoHosts().apply { transcriptRead = AgentTranscriptRead(items, nextCursor = "cursor-2") }
        val data = dataOf(RendererActions(source).handle("conversation.transcript", payload(RendererConversationId.of(other))))
        assertThat(data.getString("conversation_id")).isEqualTo(RendererConversationId.of(other))
        assertThat(data.getInt("count")).isEqualTo(2)
        assertThat(data.getBoolean("has_more")).isTrue()
        // The cursor is the host's own text and travels back unchanged, which is what makes the next page settle.
        assertThat(data.getString("next_cursor")).isEqualTo("cursor-2")
        val rows = data.getJSONArray("items")
        assertThat(rows.length()).isEqualTo(2)
        assertThat(rows.getJSONObject(0).getString("type")).isEqualTo("user")
        assertThat(rows.getJSONObject(0).getString("text")).isEqualTo("部署到生产")
        assertThat(rows.getJSONObject(0).getString("id")).isEqualTo("u1")
        assertThat(rows.getJSONObject(1).getString("title")).isEqualTo("运行部署")
        assertThat(rows.getJSONObject(1).getString("status")).isEqualTo("historical")

        // A page the host closed answers no cursor at all, and has_more must not invent one.
        source.transcriptRead = AgentTranscriptRead(items.take(1), nextCursor = null)
        val last = dataOf(RendererActions(source).handle("conversation.transcript", payload(RendererConversationId.of(other), mapOf("cursor" to "cursor-2"))))
        assertThat(last.getBoolean("has_more")).isFalse()
        assertThat(last.isNull("next_cursor")).isTrue()
        assertThat(source.transcriptReads.last().cursor).isEqualTo("cursor-2")
    }

    @Test
    fun aConversationWithoutAUsableDirectoryIsRefusedBeforeAnyHostIsRead() = runTest {
        val source = twoHosts().apply { sessions = listOf(opened, other.copy(cwd = "relative/path")) }
        val refused = RendererActions(source).handle("conversation.transcript", payload(RendererConversationId.of(other)))
        assertThat(refused.status).isEqualTo(409)
        assertThat(codeOf(refused)).isEqualTo("CWD_MISSING")
        assertThat((refused.body as JSONObject).getString("error")).contains("relative/path")
        assertThat(source.transcriptReads).isEmpty()
    }

    /** A page the host could not deliver is a failure of its own, never an empty history. */
    @Test
    fun anUnreadablePageAnswersTheRemoteReasonInsteadOfAnEmptyList() = runTest {
        val failed = twoHosts().apply { transcriptRead = AgentTranscriptRead(error = "session_identity_mismatch") }
        val unreachable = RendererActions(failed).handle("conversation.transcript", payload(RendererConversationId.of(other)))
        assertThat(unreachable.status).isEqualTo(502)
        assertThat(codeOf(unreachable)).isEqualTo("TRANSCRIPT_UNREADABLE")
        assertThat((unreachable.body as JSONObject).getString("error")).contains("session_identity_mismatch")

        val broken = twoHosts().apply { transcriptReadFailure = IOException("Host 8 is not connected") }
        val transport = RendererActions(broken).handle("conversation.transcript", payload(RendererConversationId.of(other)))
        assertThat(transport.status).isEqualTo(502)
        assertThat(codeOf(transport)).isEqualTo("TRANSCRIPT_UNREADABLE")
        assertThat((transport.body as JSONObject).getString("error")).contains("Host 8 is not connected")
    }

    /** An Agent or a host target with no readable file history is unsupported, not a remote failure. */
    @Test
    fun aConversationWithNoReadableHistoryIsUnsupported() = runTest {
        val source = twoHosts().apply { transcriptReadFailure = UnsupportedOperationException("no readable history for kimi") }
        val refused = RendererActions(source).handle("conversation.transcript", payload(RendererConversationId.of(other)))
        assertThat(refused.status).isEqualTo(501)
        assertThat(codeOf(refused)).isEqualTo("TRANSCRIPT_UNSUPPORTED")
        assertThat((refused.body as JSONObject).getString("error")).isNotBlank()
    }

    @Test
    fun theBridgeAcceptsTheTranscriptRouteForAConversationItCouldDerive() {
        val bridge = ProbeBridge(projectId = null, acceptsConversation = { RendererConversationId.parse(it) != null })
        bridge.commit(ProbeBridge.ENTRY)
        val seen = mutableListOf<String>()
        val execute = { action: String, payload: JSONObject ->
            seen += "$action:${payload.getJSONObject("params").optString("conversationId")}:${payload.getJSONObject("query").optString("limit")}"
            ProbeResult(body = json("success" to true))
        }
        val handshake = bridge.receive(ProbeBridge.ORIGIN, true, request("host.handshake", "", "renderer-1", JSONObject()), execute)!!
        val document = handshake.getJSONObject("body").getString("documentId")
        val id = RendererConversationId.of(other)
        val accepted = bridge.receive(
            ProbeBridge.ORIGIN,
            true,
            request("conversation.transcript", document, "renderer-2", JSONObject().put("conversationId", id), JSONObject().put("limit", "20")),
            execute,
        )!!
        assertThat(accepted.getInt("status")).isEqualTo(200)
        assertThat(seen).containsExactly("conversation.transcript:$id:20")

        // An id this product could not have derived names no conversation, and no read reaches the action layer.
        val refused = bridge.receive(
            ProbeBridge.ORIGIN,
            true,
            request("conversation.transcript", document, "renderer-3", JSONObject().put("conversationId", "conv-other")),
            execute,
        )!!
        assertThat(refused.getInt("status")).isEqualTo(404)
        assertThat(refused.getJSONObject("body").getString("code")).isEqualTo("CONVERSATION_NOT_FOUND")
        assertThat(seen).hasSize(1)
    }

    @Test
    fun theRouteIsAReadOnTheChannelThatLeavesTheOrderedWorker() {
        assertThat(RendererActionChannels.channelOf("conversation.transcript")).isEqualTo(RendererActionChannels.Channel.CONCURRENT)
        // It reaches a host over the link like a content search does, so the same grant gates it.
        assertThat(localNetworkRefused("conversation.transcript", networkPermissions(37)) { false }).isTrue()
    }

    private fun payload(conversationId: String, query: Map<String, String> = emptyMap()): JSONObject = JSONObject()
        .put("params", JSONObject().put("conversationId", conversationId))
        .put("query", JSONObject().apply { query.forEach { (key, value) -> put(key, value) } })
        .put("body", JSONObject.NULL)

    private fun request(action: String, document: String, id: String, params: JSONObject, query: JSONObject = JSONObject()): String = JSONObject()
        .put("requestId", id)
        .put("documentId", document)
        .put("action", action)
        .put("payload", JSONObject().put("params", params).put("query", query).put("body", JSONObject.NULL))
        .toString()

    private fun dataOf(result: ProbeResult): JSONObject = (result.body as JSONObject).getJSONObject("data")

    private fun codeOf(result: ProbeResult): String = (result.body as JSONObject).getString("code")
}
