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
import org.connectbot.data.entity.Host
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.rmagentma.core.AgentKind
import org.rmagentma.core.AgentSession

@RunWith(AndroidJUnit4::class)
class RendererConversationSwitchTest {
    private val savedHost = Host(id = 7, nickname = "工作机", username = "colle", hostname = "example.test")
    private val a = AgentSession(hostId = 7, agent = AgentKind.KIMI, sessionId = "session-a", cwd = "/work/a", title = "会话 A", updatedAt = 100)
    private val b = AgentSession(hostId = 7, agent = AgentKind.DSH, sessionId = "session-b", cwd = "/work/b", title = "会话 B", updatedAt = 200)
    private val removedHost = AgentSession(hostId = 8, agent = AgentKind.KIMI, sessionId = "session-c", cwd = "/work/c", title = "已删主机", updatedAt = 300)

    @Test
    fun theListIsTheRealIndexOfSavedHostsWithDerivedIds() = runTest {
        val source = FakeSource().apply {
            hosts = listOf(savedHost)
            sessions = listOf(a, b, removedHost)
        }
        val data = (RendererActions(source).handle("conversations.list", payload()).body as JSONObject).getJSONObject("data")
        val items = data.getJSONArray("items")
        assertThat(data.getInt("total")).isEqualTo(2)
        // Newest first, and a row whose host was deleted is never offered for opening.
        assertThat(items.getJSONObject(0).getString("id")).isEqualTo(RendererConversationId.of(b))
        assertThat(items.getJSONObject(0).getString("name")).isEqualTo("会话 B")
        assertThat(items.getJSONObject(0).getString("project_id")).isEqualTo("host-7")
        assertThat(items.getJSONObject(0).getLong("modified_at")).isEqualTo(200)
        assertThat(items.getJSONObject(0).getJSONObject("extra").getString("backend")).isEqualTo("dsh")
        assertThat(items.getJSONObject(0).getJSONObject("extra").getString("agent_name")).isEqualTo("dsh")
        assertThat(items.getJSONObject(1).getString("id")).isEqualTo(RendererConversationId.of(a))
        assertThat(items.getJSONObject(1).getJSONObject("runtime").getBoolean("can_send_message")).isFalse()
        assertThat(source.opens).isZero()
    }

    @Test
    fun readingAListedConversationOpensItThroughTheExclusiveEntry() = runTest {
        val source = openSource()
        val id = RendererConversationId.of(b)
        val result = RendererActions(source).handle("conversation.read", payload(id))
        assertThat(result.status).isEqualTo(200)
        assertThat(source.opening).isEqualTo(b)
        assertThat(source.opens).isEqualTo(1)
        val conversation = (result.body as JSONObject).getJSONObject("data")
        assertThat(conversation.getString("id")).isEqualTo(id)
        assertThat(conversation.getJSONObject("extra").getString("backend")).isEqualTo("dsh")

        // The replaced conversation's routes answer nothing, so its history can never be drawn as the new one.
        assertThat(RendererActions(source).handle("messages.list", payload(RendererConversationId.of(a))).status).isEqualTo(404)
        assertThat(RendererActions(source).handle("approvals.list", payload(RendererConversationId.of(a))).status).isEqualTo(404)
        assertThat(RendererActions(source).handle("messages.list", payload(id)).status).isEqualTo(200)

        // Selecting the already open conversation must not tear it down and open it again.
        assertThat(RendererActions(source).handle("runtime.ensure", payload(id)).status).isEqualTo(200)
        assertThat(source.opens).isEqualTo(1)
    }

    @Test
    fun aFailedOpenReportsTheRepositoryReasonInsteadOfLookingOpen() = runTest {
        val source = openSource()
        source.openFailure = "kimi unavailable: agent startup probe failed"
        val result = RendererActions(source).handle("runtime.ensure", payload(RendererConversationId.of(b)))
        assertThat(result.status).isEqualTo(409)
        val body = result.body as JSONObject
        assertThat(body.getBoolean("success")).isFalse()
        assertThat(body.getString("code")).isEqualTo("CONVERSATION_OPEN_FAILED")
        assertThat(body.getString("error")).contains("kimi unavailable")
        assertThat(source.active).isFalse()
    }

    @Test
    fun aLateOpenNeverAnswersForTheConversationItReplaced() = runTest {
        val source = openSource()
        // While b was opening, another switch to a won; the b request must not report a as its result.
        source.replaceDuringOpen = a
        val result = RendererActions(source).handle("conversation.read", payload(RendererConversationId.of(b)))
        assertThat(result.status).isEqualTo(409)
        val body = result.body as JSONObject
        assertThat(body.getBoolean("success")).isFalse()
        assertThat(body.getString("code")).isEqualTo("CONVERSATION_OPEN_FAILED")
        assertThat(source.session).isEqualTo(a)
    }

    @Test
    fun aSelectedButUnreadyConversationIsRetriedOnlyByEnsure() = runTest {
        val source = openSource()
        source.session = b
        source.active = false
        val read = RendererActions(source).handle("conversation.read", payload(RendererConversationId.of(b)))
        assertThat(read.status).isEqualTo(409)
        assertThat((read.body as JSONObject).getString("code")).isEqualTo("CONVERSATION_NOT_OPEN")
        assertThat(source.opens).isZero()

        val ensured = RendererActions(source).handle("runtime.ensure", payload(RendererConversationId.of(b)))
        assertThat(ensured.status).isEqualTo(200)
        assertThat(source.opens).isEqualTo(1)
    }

    @Test
    fun sendAndCancelNameTheConversationTheyWereWrittenFor() = runTest {
        val source = openSource()
        val bId = RendererConversationId.of(b)
        assertThat(RendererActions(source).handle("conversation.read", payload(bId)).status).isEqualTo(200)

        val sent = RendererActions(source).handle("messages.send", payload(bId, body = mapOf("content" to "你好")))
        assertThat(sent.status).isEqualTo(202)
        assertThat((sent.body as JSONObject).getJSONObject("data").getString("msg_id")).isEqualTo("m9")
        assertThat(RendererActions(source).handle("messages.send", payload(RendererConversationId.of(a), body = mapOf("content" to "你好"))).status).isEqualTo(404)

        assertThat(RendererActions(source).handle("turn.cancel", payload(bId)).status).isEqualTo(200)
        assertThat(source.cancelledSession).isEqualTo(b)
        assertThat(source.cancelCount).isEqualTo(1)
    }

    @Test
    fun theLegacyBridgeIdStillNamesTheOpenConversation() = runTest {
        val source = openSource()
        assertThat(RendererActions(source).handle("conversation.read", payload(RENDERER_CONVERSATION)).status).isEqualTo(200)
        assertThat(RendererActions(source).handle("runtime.ensure", payload(RENDERER_CONVERSATION)).status).isEqualTo(200)
        assertThat(source.opens).isZero()
    }

    @Test
    fun anIdOutsideTheIndexSelectsNothing() = runTest {
        val source = openSource()
        val unindexed = RendererConversationId.of(AgentSession(hostId = 7, agent = AgentKind.KIMI, sessionId = "session-ghost", cwd = "/work"))
        for (action in listOf("conversation.read", "runtime.ensure")) {
            val result = RendererActions(source).handle(action, payload(unindexed))
            assertThat(result.status).describedAs(action).isEqualTo(404)
            assertThat((result.body as JSONObject).getString("code")).isEqualTo("CONVERSATION_NOT_FOUND")
        }
        assertThat(source.opens).isZero()
    }

    private fun openSource(): FakeSource = FakeSource().apply {
        hosts = listOf(savedHost)
        sessions = listOf(a, b)
        session = a
        indexed = a
        active = true
    }

    private fun payload(conversationId: String? = null, params: Map<String, String> = emptyMap(), body: Map<String, Any?> = emptyMap()): JSONObject = JSONObject()
        .put("params", JSONObject().apply { (params + listOfNotNull(conversationId?.let { "conversationId" to it })).forEach { (key, value) -> put(key, value) } })
        .put("query", JSONObject())
        .put("body", JSONObject().apply { body.forEach { (key, value) -> put(key, value) } })
}
