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

import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.assertj.core.api.Assertions.assertThat
import org.connectbot.agent.AgentConversationState
import org.connectbot.agent.AgentDraftStore
import org.connectbot.agent.AgentIndexStore
import org.connectbot.agent.AgentLinkState
import org.connectbot.agent.AgentMessageQueue
import org.connectbot.agent.AgentRepository
import org.connectbot.agent.AgentSshPool
import org.connectbot.data.HostRepository
import org.connectbot.di.CoroutineDispatchers
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.rmagentma.core.AgentEvent
import org.rmagentma.core.AgentKind
import org.rmagentma.core.AgentOption
import org.rmagentma.core.AgentSession
import org.rmagentma.core.DIRECTORY_LISTING_MAX_ENTRIES
import org.rmagentma.core.DirectoryListing
import org.rmagentma.core.ExecChannel
import org.rmagentma.core.HostSession
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** The published runtime generation of the live source: a switch must start a new one, not continue the old. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AgentRendererSourceTest {
    private val dispatcher = StandardTestDispatcher()
    private val dispatchers = CoroutineDispatchers(dispatcher, dispatcher, dispatcher)
    private val active = MutableStateFlow<AgentSession?>(null)
    private val selected = MutableStateFlow<AgentSession?>(null)
    private val liveLinks = MutableStateFlow<Map<Long, AgentLinkState>>(emptyMap())
    private val events = MutableStateFlow<List<AgentEvent>>(emptyList())
    private lateinit var repository: AgentRepository
    private lateinit var pool: AgentSshPool
    private lateinit var source: AgentRendererSource

    @Before
    fun setUp() = runTest {
        Dispatchers.setMain(dispatcher)
        repository = mock(AgentRepository::class.java)
        `when`(repository.activeSession).thenReturn(active)
        `when`(repository.selectedSession).thenReturn(selected)
        `when`(repository.events).thenReturn(events)
        `when`(repository.busy).thenReturn(MutableStateFlow(false))
        `when`(repository.errors).thenReturn(MutableStateFlow(emptyList()))
        `when`(repository.conversationState).thenReturn(MutableStateFlow(AgentConversationState.Closed))
        `when`(repository.hasEarlier).thenReturn(MutableStateFlow(false))
        `when`(repository.availability).thenReturn(MutableStateFlow(emptyList()))
        `when`(repository.liveLinkStates).thenReturn(liveLinks)
        `when`(repository.linkRecords).thenReturn(MutableStateFlow(emptyList()))
        `when`(repository.scannedSources).thenReturn(MutableStateFlow(emptySet()))
        val hosts = mock(HostRepository::class.java)
        `when`(hosts.getSshHosts()).thenReturn(emptyList())
        val index = mock(AgentIndexStore::class.java)
        `when`(index.read()).thenReturn(emptyList())
        val drafts = mock(AgentDraftStore::class.java)
        val queue = mock(AgentMessageQueue::class.java)
        val preferences = mock(SharedPreferences::class.java)
        pool = mock(AgentSshPool::class.java)
        source = AgentRendererSource(repository, hosts, index, pool, drafts, queue, preferences, dispatchers)
    }

    @After
    fun tearDown() {
        source.close()
        Dispatchers.resetMain()
    }

    @Test
    fun switchingSessionStartsANewGenerationThatDropsThePreviousWindow() = runTest {
        val a = AgentSession(hostId = 7, agent = AgentKind.KIMI, sessionId = "session-a", cwd = "/work/a")
        val b = AgentSession(hostId = 7, agent = AgentKind.DSH, sessionId = "session-b", cwd = "/work/b")
        selected.value = a
        active.value = a
        val first = source.snapshot()
        assertThat(source.conversationId()).isEqualTo(RendererConversationId.of(a))

        // The repository clears the active session and selects the replacement in one state change.
        active.value = null
        selected.value = b
        val switched = source.snapshot()
        assertThat(switched.epoch).isNotEqualTo(first.epoch)
        assertThat(switched.seq).isZero()
        assertThat(source.conversationId()).isEqualTo(RendererConversationId.of(b))

        // Reading the same session again keeps one generation; only a real switch starts another.
        active.value = b
        assertThat(source.snapshot().epoch).isEqualTo(switched.epoch)
    }

    @Test
    fun workspaceListingReadsTheHostSessionWithoutAskingTheUserForCredentials() = runTest {
        // The panel's one read is the listing itself: NDJSON rows, one per entry plus the summary row that closes it.
        val answer = """{"record":"entry","name":"a.kt","kind":"file","size":3}""" + "\n" +
            """{"record":"listing","directory":"/work","count":1,"truncated":false}"""
        var sentCommand: String? = null
        `when`(pool.hostSession(7L, false)).thenReturn(
            object : HostSession {
                override val hostId = 7L
                override suspend fun exec(command: String): ExecChannel {
                    sentCommand = command
                    return object : ExecChannel {
                        override val stdout = ByteArrayInputStream(answer.toByteArray())
                        override val stdin = ByteArrayOutputStream()
                        override val stderr = ByteArrayInputStream(byteArrayOf())
                        override fun close() = Unit
                    }
                }
            },
        )
        val listing = source.listWorkspace(7L, "/work")
        val listed = listing as DirectoryListing.Listed
        assertThat(listed.entries.single().name).isEqualTo("a.kt")
        assertThat(listed.entries.single().size).isEqualTo(3L)
        assertThat(listed.truncated).isFalse()
        val sent = sentCommand.orEmpty()
        assertThat(sent).startsWith("/bin/sh -c ")
        assertThat(sent).contains("| base64 -d | python3 - ")
        // Directory and window reach python3 as arguments of the payload, so they are asserted in the spelling the
        // host really receives rather than in the payload's own spelling.
        assertThat(sent).contains(payloadArgument("--directory", "/work"))
        assertThat(sent).contains(payloadArgument("--limit", "$DIRECTORY_LISTING_MAX_ENTRIES"))
    }

    /**
     * A lost link and its recovery travel on the event channel the renderer already reads, and only a real change of
     * that state publishes a frame, so the product's own reconnect bookkeeping cannot turn into a stream of notices.
     */
    @Test
    fun aLostLinkAndItsRecoveryArePublishedWithoutAnyPollingOfTheirOwn() = runTest {
        val session = AgentSession(hostId = 7, agent = AgentKind.KIMI, sessionId = "session-a", cwd = "/work/a")
        active.value = session
        val frames = mutableListOf<Pair<String, JSONObject>>()
        source.open { name, data, _, _ -> frames += name to data }
        liveLinks.value = mapOf(7L to AgentLinkState.CONNECTED)
        advanceUntilIdle()
        assertThat(frames.map { it.first }).contains("realtime.reconnected")
        val reconnected = frames.last { it.first == "realtime.reconnected" }.second
        assertThat(reconnected.getString("host_id")).isEqualTo("host-7")
        assertThat(reconnected.getString("state")).isEqualTo("connected")

        frames.clear()
        liveLinks.value = mapOf(7L to AgentLinkState.DISCONNECTED)
        advanceUntilIdle()
        assertThat(frames.map { it.first }).containsExactly("realtime.disconnected")
        assertThat(frames.single().second.getString("state")).isEqualTo("offline")
        assertThat(frames.single().second.getString("link_state")).isEqualTo("disconnected")

        // Another state of the same kind is not a second notice: only connected, offline and needs_user differ.
        frames.clear()
        liveLinks.value = mapOf(7L to AgentLinkState.RETRYING)
        advanceUntilIdle()
        assertThat(frames).isEmpty()

        frames.clear()
        liveLinks.value = mapOf(7L to AgentLinkState.NEEDS_USER)
        advanceUntilIdle()
        assertThat(frames.map { it.first }).containsExactly("realtime.disconnected")
        assertThat(frames.single().second.getBoolean("needs_user")).isTrue()

        frames.clear()
        liveLinks.value = mapOf(7L to AgentLinkState.CONNECTED)
        advanceUntilIdle()
        assertThat(frames.map { it.first }).containsExactly("realtime.reconnected")
        assertThat(frames.single().second.getBoolean("reconnected")).isTrue()
    }

    /**
     * A live frame is the renderer's own stream message, whose payload it reads from `data`; the rows this host
     * answers as history keep that payload under `content`, because the conversation list draws those rows as they
     * are. One event of each kind therefore reaches the renderer in the shape its own reader expects — and never in
     * the other one.
     */
    @Test
    fun liveFramesCarryTheirPayloadUnderDataWhileHistoryRowsKeepContent() = runTest {
        val session = AgentSession(hostId = 7, agent = AgentKind.KIMI, sessionId = "session-a", cwd = "/work/a")
        active.value = session
        val frames = mutableListOf<Pair<String, JSONObject>>()
        source.open { name, data, _, _ -> frames += name to data }
        advanceUntilIdle()

        events.value = listOf(
            AgentEvent(type = "user", text = "你好", historyKey = 1),
            AgentEvent(type = "thinking", text = "先读代码", historyKey = 2),
            AgentEvent(
                type = "tool",
                id = "call-1",
                title = "读取文件",
                raw = buildJsonObject {
                    put("toolCallId", "call-1")
                    put("title", "读取文件")
                    put("status", "running")
                },
                historyKey = 3,
            ),
            AgentEvent(
                type = "permission",
                id = "req-1",
                title = "写文件",
                text = "请求写入",
                options = listOf(AgentOption("allow_once", "允许一次")),
                historyKey = 4,
            ),
            AgentEvent(
                type = "usage",
                raw = buildJsonObject {
                    put("used", 12)
                    put("size", 100)
                },
                historyKey = 5,
            ),
            AgentEvent(type = "complete", status = "end_turn", historyKey = 6),
        )
        advanceUntilIdle()

        val stream = frames.filter { it.first == "message.stream" }.map { it.second }
        // The open conversation's own catalogue is published on the same channel in the same frame shape; it is not
        // an event row, so the event frames here are the ones carrying the history-key message id.
        val catalog = stream.single { it.getString("type") == "acp_config_option" }
        assertThat(catalog.has("data")).isTrue()
        assertThat(catalog.has("content")).isFalse()
        val eventFrames = stream.filter { it.optString("msg_id").isNotEmpty() }
        assertThat(eventFrames).hasSize(6)
        eventFrames.forEach { frame ->
            assertThat(frame.has("data")).describedAs(frame.toString()).isTrue()
            assertThat(frame.has("content")).describedAs(frame.toString()).isFalse()
            assertThat(frame.getString("conversation_id")).isEqualTo(RendererConversationId.of(session))
            assertThat(frame.getString("msg_id")).startsWith("m")
            assertThat(frame.getString("type")).isNotBlank()
        }
        fun frameOf(key: Long): JSONObject = eventFrames.single { it.getString("msg_id") == "m$key" }
        // A text row keeps the renderer's replace flag, which it reads from inside the frame's data.
        assertThat(frameOf(1).getString("type")).isEqualTo("text")
        assertThat(frameOf(1).getString("position")).isEqualTo("right")
        assertThat(frameOf(1).getJSONObject("data").getString("content")).isEqualTo("你好")
        assertThat(frameOf(1).getJSONObject("data").getBoolean("replace")).isTrue()
        assertThat(frameOf(2).getString("type")).isEqualTo("thinking")
        assertThat(frameOf(2).getJSONObject("data").getString("content")).isEqualTo("先读代码")
        assertThat(frameOf(3).getString("type")).isEqualTo("acp_tool_call")
        assertThat(frameOf(3).getJSONObject("data").getJSONObject("update").getString("tool_call_id")).isEqualTo("call-1")
        assertThat(frameOf(4).getString("type")).isEqualTo("acp_permission")
        assertThat(frameOf(4).getJSONObject("data").getString("request_id")).isEqualTo("req-1")
        assertThat(frameOf(5).getString("type")).isEqualTo("acp_context_usage")
        assertThat(frameOf(5).getJSONObject("data").getInt("used")).isEqualTo(12)
        assertThat(frameOf(5).getJSONObject("data").getInt("size")).isEqualTo(100)
        assertThat(frameOf(6).getString("type")).isEqualTo("finish")
        assertThat(frameOf(6).getJSONObject("data").getString("outcome")).isEqualTo("completed")

        // The same events as history rows are the shape messages.list answers: the payload under `content`.
        val rows = RendererProjection.messages(events.value, RendererConversationId.of(session), "epoch-1", null)
        assertThat(rows).hasSize(6)
        rows.forEach { row ->
            assertThat(row.has("content")).describedAs(row.toString()).isTrue()
            assertThat(row.has("data")).describedAs(row.toString()).isFalse()
        }
        assertThat(rows[0].getJSONObject("content").getString("content")).isEqualTo("你好")
        assertThat(rows[5].getString("type")).isEqualTo("finish")
    }
}

/**
 * One argument as the host really receives it: the payload single-quotes every argument, and the command hands that
 * whole payload to `sh -c` as one quoted word, so each of those quotes is escaped once more on the way out.
 */
private fun payloadArgument(name: String, value: String): String {
    val quote = "'\"'\"'"
    return "$quote$name$quote $quote$value$quote"
}
