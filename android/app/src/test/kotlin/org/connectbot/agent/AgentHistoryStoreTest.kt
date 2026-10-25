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

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.rmagentma.core.AgentEvent
import org.rmagentma.core.AgentKind
import java.io.File
import java.io.IOException

/**
 * The stored pages of one session: they outlive the process, an open keeps them, and only a completed read with
 * an explicit cleanup by the user changes what is stored.
 */
class AgentHistoryStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun store(directory: File, key: String, capacityBytes: Long = 256L * 1024 * 1024) = AgentEventHistory(
        directory = directory,
        name = AgentEventHistory.storeName(1L, AgentKind.KIMI, key),
        source = "test",
        capacityBytes = capacityBytes,
    )

    private fun directory() = File(temporary.root, "agent-history")

    @Test
    fun pagesSurviveTheProcessAndAreReadAgainByTheNextOpen() {
        val directory = directory()
        val first = store(directory, "session-1")
        first.attach()
        first.append(AgentEvent("user", "hello", id = "local-1"))
        first.append(AgentEvent("content", "first answer", id = "m1"))
        first.markComplete()
        first.close()
        val files = directory.listFiles().orEmpty().map { it.name }
        assertThat(files).isNotEmpty()

        // A new process opens the same session: the pages are read back instead of the session starting empty.
        val reopened = store(directory, "session-1")
        val window = reopened.attach()
        assertThat(window.map { it.id }).containsExactly("local-1", "m1")
        assertThat(reopened.integrityState).isEqualTo(HISTORY_COMPLETE)
        reopened.append(AgentEvent("content", "second answer", id = "m2"))
        assertThat(reopened.loadLatest().map { it.id }).containsExactly("local-1", "m1", "m2")
        reopened.close()
        assertThat(directory.listFiles().orEmpty().map { it.name }).containsAll(files)
    }

    @Test
    fun aSecondOpenKeepsThePagesTheFirstOneStored() {
        val directory = directory()
        val first = store(directory, "session-2")
        first.attach()
        repeat(3) { index -> first.append(AgentEvent("content", "message-$index", id = "m$index")) }
        first.close()

        val reopened = store(directory, "session-2")
        assertThat(reopened.attach().map { it.text }).containsExactly("message-0", "message-1", "message-2")
        reopened.close()
    }

    @Test
    fun onlyACompletedReadMarksTheStoredHistoryComplete() {
        val directory = directory()
        val first = store(directory, "session-3")
        assertThat(first.attach()).isEmpty()
        assertThat(first.integrityState).isEqualTo(HISTORY_PARTIAL)
        first.append(AgentEvent("content", "answer", id = "m1"))
        assertThat(first.integrityState).isEqualTo(HISTORY_PARTIAL)
        first.markComplete()
        assertThat(first.integrityState).isEqualTo(HISTORY_COMPLETE)
        assertThat(first.integrityNote).isNull()
        first.close()

        // The completed state is what the next run reads, and a failure in it keeps complete from being claimed.
        val reopened = store(directory, "session-3")
        reopened.attach()
        assertThat(reopened.integrityState).isEqualTo(HISTORY_COMPLETE)
        reopened.markIncomplete("the Agent connection closed before this history was complete")
        assertThat(reopened.integrityNote).contains("Agent connection closed")
        reopened.markComplete()
        assertThat(reopened.integrityState).isEqualTo(HISTORY_PARTIAL)
        reopened.close()
    }

    @Test
    fun aFullStoreKeepsItsPagesAndReportsItselfIncompleteInsteadOfDroppingThem() {
        val directory = directory()
        val history = store(directory, "session-4", capacityBytes = 1024)
        history.attach()
        val first = history.append(AgentEvent("user", "first", id = "1")).single()
        assertThatThrownBy { history.append(AgentEvent("user", "x".repeat(4000), id = "2")) }
            .isInstanceOf(IOException::class.java)
            .hasMessageContaining("capacity")
        assertThat(history.integrityState).isEqualTo(HISTORY_PARTIAL)
        assertThat(history.integrityNote).contains("storage budget")
        assertThat(history.fullText(first).text).isEqualTo("first")
        history.markComplete()
        assertThat(history.integrityState).isEqualTo(HISTORY_PARTIAL)
        history.close()
    }

    @Test
    fun anUnreadableTailIsLeftOutAndReportedInsteadOfDeletingEarlierPages() {
        val directory = directory()
        val first = store(directory, "session-5")
        first.attach()
        repeat(3) { index -> first.append(AgentEvent("content", "message-$index", id = "m$index")) }
        first.close()
        val log = directory.listFiles()!!.single { it.name.endsWith(".log") }
        java.io.RandomAccessFile(log, "rw").use { file -> file.setLength(file.length() - 4) }

        val reopened = store(directory, "session-5")
        val window = reopened.attach()
        assertThat(window.map { it.text }).containsExactly("message-0", "message-1")
        assertThat(reopened.integrityState).isEqualTo(HISTORY_PARTIAL)
        assertThat(reopened.integrityNote).contains("unreadable")
        reopened.close()
    }

    @Test
    fun aReplayDoesNotStoreTheRecordsTheStoreAlreadyHolds() {
        val directory = directory()
        val first = store(directory, "session-6")
        first.attach()
        first.append(AgentEvent("user", "hello", id = "local-1"))
        first.append(AgentEvent("content", "first answer", id = "m1"))
        first.append(AgentEvent("content", "second answer", id = "m2"))
        first.markComplete()
        first.close()

        val reopened = store(directory, "session-6")
        assertThat(reopened.attach().map { it.id }).containsExactly("local-1", "m1", "m2")
        // The Agent replays the same conversation: every record it repeats is matched by the identity its own
        // source gave it - and the prompt this app wrote itself stands for the Agent's copy of it.
        reopened.append(AgentEvent("user", "hello", id = "m0"))
        reopened.append(AgentEvent("content", "first answer", id = "m1"))
        reopened.append(AgentEvent("content", "second answer", id = "m2"))
        assertThat(reopened.loadLatest().map { it.id }).containsExactly("local-1", "m1", "m2")
        // A record the replay has not delivered before is stored beside them.
        reopened.append(AgentEvent("content", "third answer", id = "m3"))
        assertThat(reopened.loadLatest().map { it.id }).containsExactly("local-1", "m1", "m2", "m3")

        // Once the history barrier passed, every later event is the live conversation.
        reopened.endReplay()
        reopened.append(AgentEvent("content", "fourth answer", id = "m4"))
        assertThat(reopened.loadLatest().map { it.id }).containsExactly("local-1", "m1", "m2", "m3", "m4")
        reopened.close()
    }

    @Test
    fun anExplicitCleanupRemovesEveryStoredSessionAndCountsThem() {
        val directory = directory()
        listOf("session-7", "session-8").forEach { key ->
            val store = store(directory, key)
            store.attach()
            store.append(AgentEvent("content", "text", id = "m1"))
            store.markComplete()
            store.close()
        }
        assertThat(AgentEventHistory.clearAll(directory)).isEqualTo(2)
        assertThat(directory.listFiles()).isEmpty()
        assertThat(AgentEventHistory.clearAll(directory)).isZero()
    }
}
