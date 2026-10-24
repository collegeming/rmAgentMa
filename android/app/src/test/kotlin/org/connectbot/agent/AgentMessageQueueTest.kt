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

package org.connectbot.agent

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AgentMessageQueueTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun context(): Context {
        val root = temporary.root
        return object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getNoBackupFilesDir(): File = root
        }
    }

    private fun queue(context: Context, scheduler: TestCoroutineScheduler) = AgentMessageQueue(context, StandardTestDispatcher(scheduler))

    private fun file(context: Context) = File(context.noBackupFilesDir, "agent-message-queue-v1.json")

    private fun row(id: String, kind: String, state: String) = JSONObject()
        .put("id", id)
        .put("hostId", 1)
        .put("agent", "kimi")
        .put("sessionId", "session-a")
        .put("kind", kind)
        .put("text", "来自 $id 的消息")
        .put("createdAtMillis", 10)
        .put("state", state)

    private fun storedStates(context: Context): List<String> {
        val array = JSONObject(file(context).readText()).getJSONArray("messages")
        return (0 until array.length()).map { array.getJSONObject(it).getString("state") }
    }

    @Test
    fun roundTripsEveryMessageAndPersistsOnlyTheQueue() = runTest {
        val context = context()
        val queue = queue(context, testScheduler)
        val notifyId = queue.enqueue(1, "kimi", "session-a", AgentMessageKind.NOTIFY, "第一行\n第二行 😀", 1_700_000_000_000)
        val requestId = queue.enqueue(2, "zcode", "session-b", AgentMessageKind.REQUEST, "构建结束后把结果贴回来。", 1_700_000_000_500)
        assertThat(notifyId).isNotNull()
        assertThat(requestId).isNotNull()

        val reopened = queue(context, testScheduler)
        assertThat(reopened.pending()).isEqualTo(
            listOf(
                AgentQueuedMessage(notifyId!!, 1, "kimi", "session-a", AgentMessageKind.NOTIFY, "第一行\n第二行 😀", 1_700_000_000_000, AgentMessageState.PENDING),
                AgentQueuedMessage(requestId!!, 2, "zcode", "session-b", AgentMessageKind.REQUEST, "构建结束后把结果贴回来。", 1_700_000_000_500, AgentMessageState.PENDING),
            ),
        )
        assertThat(reopened.pendingFor(2, "zcode", "session-b").map { it.id }).containsExactly(requestId!!)

        val persisted = JSONObject(file(context).readText()).getJSONArray("messages")
        assertThat(persisted.length()).isEqualTo(2)
        val keys = persisted.getJSONObject(0).keys().asSequence().toSet()
        assertThat(keys).containsExactlyInAnyOrder("id", "hostId", "agent", "sessionId", "kind", "text", "createdAtMillis", "state")
    }

    @Test
    fun keepsTheOrderOfOneSessionAndSortsTheQueueBySendTime() = runTest {
        val context = context()
        val queue = queue(context, testScheduler)
        val first = queue.enqueue(1, "kimi", "session-a", AgentMessageKind.NOTIFY, "第一条", 100)!!
        val second = queue.enqueue(1, "kimi", "session-a", AgentMessageKind.REQUEST, "第二条", 100)!!
        val otherHost = queue.enqueue(2, "kimi", "session-a", AgentMessageKind.NOTIFY, "另一个主机", 50)!!
        val third = queue.enqueue(1, "kimi", "session-a", AgentMessageKind.NOTIFY, "第三条", 200)!!

        assertThat(queue.pending().map { it.id }).containsExactly(otherHost, first, second, third)
        assertThat(queue.pendingFor(1, "kimi", "session-a").map { it.id }).containsExactly(first, second, third)
        assertThat(queue.pendingFor(1, "zcode", "session-a")).isEmpty()

        val reopened = queue(context, testScheduler)
        assertThat(reopened.pending().map { it.id }).containsExactly(otherHost, first, second, third)
        assertThat(reopened.pendingFor(1, "kimi", "session-a").map { it.id }).containsExactly(first, second, third)
    }

    @Test
    fun acceptsTextAtTheCapAndStoresItWithoutTruncating() = runTest {
        val queue = queue(context(), testScheduler)
        val text = "长".repeat(16384)

        val id = queue.enqueue(1, "kimi", "session-a", AgentMessageKind.REQUEST, text, 10)!!

        val stored = queue.pending().single()
        assertThat(stored.id).isEqualTo(id)
        assertThat(stored.text).isEqualTo(text)
    }

    @Test
    fun refusesOversizedBlankAndInvalidMessagesWithoutTouchingTheFile() = runTest {
        val context = context()
        val queue = queue(context, testScheduler)
        val kept = queue.enqueue(1, "kimi", "session-a", AgentMessageKind.NOTIFY, "保留的消息", 10)!!
        val before = file(context).readBytes()

        assertThat(queue.enqueue(1, "kimi", "session-a", AgentMessageKind.NOTIFY, "长".repeat(16385), 11)).isNull()
        assertThat(queue.enqueue(1, "kimi", "session-a", AgentMessageKind.NOTIFY, "   ", 11)).isNull()
        assertThat(queue.enqueue(0, "kimi", "session-a", AgentMessageKind.NOTIFY, "主机无效", 11)).isNull()
        assertThat(queue.enqueue(1, "  ", "session-a", AgentMessageKind.NOTIFY, "Agent 无效", 11)).isNull()
        assertThat(queue.enqueue(1, "ki\u0000mi", "session-a", AgentMessageKind.NOTIFY, "Agent 含控制字符", 11)).isNull()
        assertThat(queue.enqueue(1, "kimi", "s".repeat(201), AgentMessageKind.NOTIFY, "会话键过长", 11)).isNull()
        assertThat(queue.enqueue(1, "kimi", "session-a", AgentMessageKind.NOTIFY, "时间无效", -1)).isNull()

        assertThat(file(context).readBytes()).isEqualTo(before)
        assertThat(queue.pending().map { it.id }).containsExactly(kept)
        assertThat(queue(context, testScheduler).pending().map { it.id }).containsExactly(kept)
    }

    @Test
    fun dropsTheOldestMessageAtTheCapAndReportsTheDrop() = runTest {
        val context = context()
        val queue = queue(context, testScheduler)
        val ids = mutableListOf<String>()
        for (index in 1..101) {
            ids += queue.enqueue(1, "kimi", "session-a", AgentMessageKind.NOTIFY, "第 $index 条", index.toLong())!!
        }

        assertThat(queue.pending().map { it.id }).containsExactlyElementsOf(ids.drop(1))
        assertThat(queue.stats()).isEqualTo(AgentMessageQueueStats(stored = 100, pending = 100, delivered = 0, dropped = 1))

        val reopened = queue(context, testScheduler)
        assertThat(reopened.pending().map { it.id }).containsExactlyElementsOf(ids.drop(1))
        assertThat(reopened.pending().first().text).isEqualTo("第 2 条")
        assertThat(reopened.stats()).isEqualTo(AgentMessageQueueStats(stored = 100, pending = 100, delivered = 0, dropped = 1))
    }

    @Test
    fun marksOnlyTheTakenMessageDeliveredAndOnlyOnce() = runTest {
        val context = context()
        val queue = queue(context, testScheduler)
        val first = queue.enqueue(1, "kimi", "session-a", AgentMessageKind.NOTIFY, "第一条", 10)!!
        val second = queue.enqueue(1, "kimi", "session-a", AgentMessageKind.REQUEST, "第二条", 20)!!
        val third = queue.enqueue(1, "kimi", "session-a", AgentMessageKind.NOTIFY, "第三条", 30)!!

        assertThat(queue.delivered("")).isFalse()
        assertThat(queue.delivered("no-such-message")).isFalse()
        assertThat(queue.delivered(first)).isTrue()
        assertThat(queue.delivered(first)).isFalse()

        assertThat(queue.pending().map { it.id }).containsExactly(second, third)
        assertThat(queue.pendingFor(1, "kimi", "session-a").map { it.id }).containsExactly(second, third)
        assertThat(queue.stats()).isEqualTo(AgentMessageQueueStats(stored = 3, pending = 2, delivered = 1, dropped = 0))

        val reopened = queue(context, testScheduler)
        assertThat(reopened.pending().map { it.id }).containsExactly(second, third)
        assertThat(reopened.stats().delivered).isEqualTo(1)
        assertThat(storedStates(context)).containsExactlyInAnyOrder("DELIVERED", "PENDING", "PENDING")
    }

    @Test
    fun forgetDropsEveryMessageOfTheHostAndLeavesUntouchedFilesAlone() = runTest {
        val context = context()
        val queue = queue(context, testScheduler)
        val delivered = queue.enqueue(1, "kimi", "session-a", AgentMessageKind.NOTIFY, "已投递的旧消息", 10)!!
        assertThat(queue.delivered(delivered)).isTrue()
        queue.enqueue(1, "zcode", "session-b", AgentMessageKind.REQUEST, "同一主机的另一个 Agent", 20)
        val kept = queue.enqueue(2, "kimi", "session-c", AgentMessageKind.NOTIFY, "另一个主机的消息", 30)!!

        queue.forget(1)
        assertThat(queue.pending().map { it.id }).containsExactly(kept)
        assertThat(queue.stats()).isEqualTo(AgentMessageQueueStats(stored = 1, pending = 1, delivered = 0, dropped = 0))
        assertThat(queue(context, testScheduler).pending().map { it.id }).containsExactly(kept)

        val before = file(context).readBytes()
        queue.forget(7)
        queue.forget(0)
        assertThat(file(context).readBytes()).isEqualTo(before)
        assertThat(queue(context, testScheduler).pending().map { it.id }).containsExactly(kept)
    }

    @Test
    fun damagedFileReportsARealReasonThenRebuildsWithTheEvidencePreserved() = runTest {
        val context = context()
        file(context).writeText("broken-json")
        val queue = queue(context, testScheduler)
        var reason: String? = null
        try {
            queue.pending()
        } catch (e: IOException) {
            reason = e.message
        }
        assertThat(reason).contains("unreadable").contains("preserved")
        assertThat(queue.pending()).isEmpty()

        assertThat(queue.delivered("unknown")).isFalse()
        queue.forget(1)
        assertThat(file(context).readText()).isEqualTo("broken-json")

        val rebuilt = queue.enqueue(1, "kimi", "session-a", AgentMessageKind.NOTIFY, "重写后的消息", 900)
        assertThat(queue.pending().map { it.id }).containsExactly(rebuilt!!)
        assertThat(queue(context, testScheduler).pending().single().text).isEqualTo("重写后的消息")
        val backups = context.noBackupFilesDir.listFiles()!!.filter { it.name.contains(".corrupt-") }
        assertThat(backups).hasSize(1)
        assertThat(backups.single().readText()).isEqualTo("broken-json")
    }

    @Test
    fun ignoresRowsThatNameAnUnknownKindOrStateAndKeepsTheDroppedCount() = runTest {
        val context = context()
        file(context).writeText(
            JSONObject()
                .put("dropped", 4)
                .put(
                    "messages",
                    JSONArray()
                        .put(row("id-1", "NOTIFY", "PENDING"))
                        .put(row("id-2", "SHOUT", "PENDING"))
                        .put(row("id-3", "REQUEST", "SENT")),
                )
                .toString(),
        )

        val queue = queue(context, testScheduler)
        assertThat(queue.pending().map { it.id }).containsExactly("id-1")
        assertThat(queue.stats()).isEqualTo(AgentMessageQueueStats(stored = 1, pending = 1, delivered = 0, dropped = 4))
    }

    @Test
    fun writeFailureKeepsTheLastKnownQueueOnDiskAndInMemory() = runTest {
        val context = context()
        val queue = queue(context, testScheduler)
        val saved = queue.enqueue(1, "kimi", "session-a", AgentMessageKind.NOTIFY, "已经排队的消息", 10)!!
        val before = file(context).readBytes()
        val obstruction = File(context.noBackupFilesDir, file(context).name + ".new")
        obstruction.mkdir()
        File(obstruction, "keep-directory-nonempty").writeText("test")

        var failed = false
        try {
            queue.enqueue(1, "kimi", "session-a", AgentMessageKind.NOTIFY, "写不进去的消息", 11)
        } catch (_: IOException) {
            failed = true
        }
        assertThat(failed).isTrue()
        assertThat(queue.pending().map { it.id }).containsExactly(saved)
        assertThat(file(context).readBytes()).isEqualTo(before)
    }
}
