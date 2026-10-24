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
import org.junit.Test
import org.rmagentma.core.AgentKind
import org.rmagentma.core.AgentSession

class AgentAttentionInboxTest {
    @Test
    fun urgencyOrdersTheListBeforeAnyTimestamp() {
        val approval = signal(session(sessionId = "approval", updatedAt = 10), pendingApprovals = 1)
        val question = signal(session(sessionId = "question", updatedAt = 20), pendingQuestions = 1)
        val failure = signal(session(sessionId = "failure", updatedAt = 30), lastError = "agent exited before it was ready")
        val busy = signal(session(sessionId = "busy", updatedAt = 40), busy = true)

        val inbox = projectAgentAttentionInbox(listOf(busy, failure, question, approval))

        assertThat(inbox.map { it.key.sessionId }).containsExactly("approval", "question", "failure", "busy")
        assertThat(inbox.map { it.reasons.single() }).containsExactly(
            AgentAttentionReason.APPROVAL,
            AgentAttentionReason.QUESTION,
            AgentAttentionReason.FAILURE,
            AgentAttentionReason.BUSY,
        )
    }

    @Test
    fun newestUpdateLeadsWithinOneReason() {
        val older = signal(session(sessionId = "old", updatedAt = 10), busy = true)
        val newer = signal(session(sessionId = "new", updatedAt = 20), busy = true)

        assertThat(projectAgentAttentionInbox(listOf(older, newer)).map { it.key.sessionId }).containsExactly("new", "old")
    }

    @Test
    fun oneRowPerSessionKeepsEveryReasonAndTheNewestError() {
        val approvals = signal(session(sessionId = "s1", updatedAt = 10), pendingApprovals = 2, lastError = "old failure")
        val renamed = signal(session(sessionId = "s1", updatedAt = 20, title = "renamed"), pendingQuestions = 1, lastError = "new failure")

        val items = projectAgentAttentionInbox(listOf(approvals, renamed))

        assertThat(items).hasSize(1)
        val item = items.single()
        assertThat(item.key).isEqualTo(AgentAttentionKey(1, AgentKind.KIMI, "s1"))
        assertThat(item.reasons).containsExactly(AgentAttentionReason.APPROVAL, AgentAttentionReason.QUESTION, AgentAttentionReason.FAILURE)
        assertThat(item.pendingApprovals).isEqualTo(2)
        assertThat(item.pendingQuestions).isEqualTo(1)
        assertThat(item.lastError).isEqualTo("new failure")
        assertThat(item.updatedAt).isEqualTo(20L)
        assertThat(item.summary).contains("2 approvals pending", "1 question pending", "new failure")
    }

    @Test
    fun unreportedTimeSortsAfterKnownTimesAndStaysUnknown() {
        val known = signal(session(sessionId = "known", updatedAt = 10), busy = true)
        val unknown = signal(session(sessionId = "unknown"), busy = true)
        val unknownOnAnotherHost = signal(session(sessionId = "unknown-b", hostId = 2), busy = true)

        val items = projectAgentAttentionInbox(listOf(unknownOnAnotherHost, unknown, known))

        assertThat(items.map { it.key.sessionId }).containsExactly("known", "unknown", "unknown-b")
        assertThat(items.map { it.updatedAt }).containsExactly(10L, null, null)
    }

    @Test
    fun quietSessionAndEmptyInputProduceNoRows() {
        val blankError = signal(session(sessionId = "quiet", updatedAt = 99), lastError = "   ")
        val negativeCounts = signal(session(sessionId = "negative"), pendingApprovals = -1, pendingQuestions = -2)

        assertThat(projectAgentAttentionInbox(listOf(blankError, negativeCounts))).isEmpty()
        assertThat(projectAgentAttentionInbox(emptyList())).isEmpty()
    }

    @Test
    fun oneLongErrorCannotGrowOneRow() {
        val item = projectAgentAttentionInbox(listOf(signal(session(sessionId = "s1"), lastError = "x".repeat(500)))).single()

        assertThat(item.summary.length).isLessThanOrEqualTo(AGENT_ATTENTION_SUMMARY_LIMIT)
        assertThat(item.lastError.orEmpty().length).isEqualTo(AGENT_ATTENTION_SUMMARY_LIMIT)
    }

    private fun session(
        sessionId: String = "s1",
        hostId: Long = 1,
        agent: AgentKind = AgentKind.KIMI,
        updatedAt: Long = 0,
        title: String = "",
    ) = AgentSession(
        hostId = hostId,
        agent = agent,
        sessionId = sessionId,
        cwd = "/work",
        title = title,
        updatedAt = updatedAt,
    )

    private fun signal(
        session: AgentSession,
        busy: Boolean = false,
        pendingApprovals: Int = 0,
        pendingQuestions: Int = 0,
        lastError: String? = null,
    ) = AgentAttentionSignal(
        session = session,
        busy = busy,
        pendingApprovals = pendingApprovals,
        pendingQuestions = pendingQuestions,
        lastError = lastError,
    )
}
