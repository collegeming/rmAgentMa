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
import org.junit.Assert.assertThrows
import org.junit.Test
import org.rmagentma.core.AgentEvent
import org.rmagentma.core.AgentKind

class AgentSessionReferenceTest {
    @Test
    fun namesTheSourceSessionOfTheQuote() {
        val pack = agentSessionContextPack(reference(), listOf(event("user", "hello", 0)))
        assertThat(pack.text).contains("[session context] host=Build box agent=kimi session=session-1 cwd=/srv/app")
        assertThat(pack.text).contains("[end of session context]")
        assertThat(pack.status).isEqualTo(AgentContextPackStatus.COMPLETE)
    }

    @Test
    fun blankHostLabelFallsBackToTheHostId() {
        val pack = agentSessionContextPack(reference().copy(hostLabel = "   "), listOf(event("user", "hello", 0)))
        assertThat(pack.text).contains("host=#7")
    }

    @Test
    fun writesTheWindowOldestFirstWhateverOrderTheEventsArriveIn() {
        // `second` arrived first but carries the newest history key, so it is quoted last.
        val events = listOf(event("content", "second", 2), event("user", "first", 0), event("content", "third", 1))
        val pack = agentSessionContextPack(reference(), events)
        assertThat(pack.text).containsSubsequence("user: first", "assistant: third", "assistant: second")
        assertThat(pack.quotedCount).isEqualTo(3)
        assertThat(pack.omittedCount).isZero()
    }

    @Test
    fun keepsTheNewestMessagesAndReportsTheEarlierOnes() {
        val events = (0 until 5).map { event("user", "message-$it", it.toLong()) }
        val pack = agentSessionContextPack(reference(), events, maxMessages = 2)
        assertThat(pack.quotedCount).isEqualTo(2)
        assertThat(pack.omittedCount).isEqualTo(3)
        assertThat(pack.status).isEqualTo(AgentContextPackStatus.TRIMMED)
        assertThat(pack.text).contains("Quoted 2 of 5 messages, oldest first.")
        assertThat(pack.text).contains("Earlier messages omitted: 3.")
        assertThat(pack.text).contains("message-3", "message-4")
        assertThat(pack.text).doesNotContain("message-0")
    }

    @Test
    fun dropsTheOldestMessagesThatDoNotFitTheCharacterBudget() {
        val events = (0 until 6).map { event("content", "line-$it ".repeat(80), it.toLong()) }
        val pack = agentSessionContextPack(reference(), events, charBudget = 1_200)
        assertThat(pack.text.length).isLessThanOrEqualTo(1_200)
        assertThat(pack.quotedCount).isLessThan(6)
        assertThat(pack.status).isEqualTo(AgentContextPackStatus.TRIMMED)
        assertThat(pack.text).contains("Messages over the character budget: ${6 - pack.quotedCount}.")
        assertThat(pack.text).contains("line-5")
        assertThat(pack.text).doesNotContain("line-0")
    }

    @Test
    fun anEmptyHistoryHasNothingToQuote() {
        val pack = agentSessionContextPack(reference(), emptyList())
        assertThat(pack.status).isEqualTo(AgentContextPackStatus.EMPTY)
        assertThat(pack.quotedCount).isZero()
        assertThat(pack.omittedCount).isZero()
        assertThat(pack.text).contains("host=Build box")
        assertThat(pack.text).contains("No messages to quote")
    }

    @Test
    fun cutsOneOversizedMessageAndSaysWhatItDropped() {
        val pack = agentSessionContextPack(reference(), listOf(event("tool", "x".repeat(5_000), 0)), itemLimit = 200)
        val line = pack.text.lines().single { it.startsWith("tool: ") }
        assertThat(line.length).isLessThanOrEqualTo(200)
        assertThat(line).endsWith("[5000 chars, rest omitted]")
        assertThat(pack.cutCount).isEqualTo(1)
        assertThat(pack.quotedCount).isEqualTo(1)
        assertThat(pack.status).isEqualTo(AgentContextPackStatus.TRIMMED)
        assertThat(pack.text).contains("Messages cut to 200 characters: 1.")
    }

    @Test
    fun quotesOnlyConversationTextAndSkipsApprovalsBlankRowsAndBookkeeping() {
        val events = listOf(
            event("permission", "Allow the command?", 0),
            event("question", "Which branch?", 1),
            event("complete", "done", 2),
            event("user", "   ", 3),
            event("content", "answer", 4),
            event("tool_update", "output", 5),
        )
        val pack = agentSessionContextPack(reference(), events)
        assertThat(pack.quotedCount).isEqualTo(2)
        assertThat(pack.text).contains("assistant: answer", "tool: output")
        assertThat(pack.text).doesNotContain("Allow the command?")
        assertThat(pack.text).doesNotContain("Which branch?")
    }

    @Test
    fun holdsTheBudgetAndTheItemLimitAtEverySize() {
        val events = (0 until 20).map {
            event(if (it % 2 == 0) "user" else "content", "message-$it ".repeat(it + 1), it.toLong())
        }
        val longNames = reference().copy(hostLabel = "h".repeat(300), sessionId = "s".repeat(300), cwd = "/".repeat(300))
        listOf(512, 600, 1_000, 4_000, 20_000).forEach { budget ->
            val pack = agentSessionContextPack(longNames, events, charBudget = budget)
            assertThat(pack.text.length).describedAs("pack at budget $budget").isLessThanOrEqualTo(budget)
        }
        val tightItems = agentSessionContextPack(reference(), events, itemLimit = 64, charBudget = 20_000)
        val quotes = tightItems.text.lines().filter { it.startsWith("user: ") || it.startsWith("assistant: ") }
        // A tighter item limit narrows every quoted line and drops none of them while the character budget holds.
        assertThat(quotes).hasSize(AGENT_CONTEXT_MESSAGES)
        assertThat(quotes.all { it.length <= 64 }).isTrue()
        // The smallest legal budget cannot hold even the newest message of this history, and the pack says so
        // instead of borrowing room from the source label, the notices or the closing marker.
        val smallest = agentSessionContextPack(longNames, events, charBudget = AGENT_CONTEXT_MIN_BUDGET)
        assertThat(smallest.status).isEqualTo(AgentContextPackStatus.EMPTY)
        assertThat(smallest.text).contains("No message of this session fits the character budget.")
    }

    @Test
    fun rejectsBudgetsThatCouldNotNameTheSourceOrTheMessage() {
        val events = listOf(event("user", "hello", 0))
        assertThrows(IllegalArgumentException::class.java) {
            agentSessionContextPack(reference(), events, charBudget = AGENT_CONTEXT_MIN_BUDGET - 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            agentSessionContextPack(reference(), events, itemLimit = AGENT_CONTEXT_MIN_ITEM_LIMIT - 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            agentSessionContextPack(reference(), events, maxMessages = 0)
        }
    }

    private fun reference() = AgentSessionReference(
        hostId = 7,
        agent = AgentKind.KIMI,
        sessionId = "session-1",
        cwd = "/srv/app",
        hostLabel = "Build box",
    )

    private fun event(type: String, text: String = "", key: Long = 0) = AgentEvent(type = type, text = text, historyKey = key)
}
