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

package org.connectbot.ui.screens.agents

import org.assertj.core.api.Assertions.assertThat
import org.connectbot.R
import org.connectbot.agent.AgentAttentionItem
import org.connectbot.agent.AgentAttentionKey
import org.connectbot.agent.AgentAttentionReason
import org.connectbot.agent.AgentContextPack
import org.connectbot.agent.AgentContextPackStatus
import org.connectbot.agent.AgentMessageKind
import org.connectbot.agent.AgentMessageState
import org.connectbot.agent.AgentQueuedMessage
import org.connectbot.agent.AgentTranscriptHit
import org.connectbot.agent.AgentTranscriptSkipReason
import org.connectbot.agent.agentAttentionSignals
import org.connectbot.agent.newestAgentError
import org.connectbot.data.entity.Host
import org.junit.Test
import org.rmagentma.core.AgentEvent
import org.rmagentma.core.AgentKind
import org.rmagentma.core.AgentSession
import org.rmagentma.core.TranscriptSearchHit
import org.rmagentma.core.TranscriptSearchProblem

class AgentCollaborationTest {
    @Test
    fun deliversInQueueOrderAndWaitsWhileTheConversationRuns() {
        val older = message(createdAtMillis = 10, kind = AgentMessageKind.NOTIFY)
        val newer = message(createdAtMillis = 20, kind = AgentMessageKind.REQUEST)
        val waiting = listOf(newer, older)

        assertThat(nextAgentDelivery(waiting, busy = false)).isEqualTo(AgentDeliveryStep.Notify(older))
        assertThat(nextAgentDelivery(waiting, busy = true)).isEqualTo(AgentDeliveryStep.Waiting)
        assertThat(nextAgentDelivery(listOf(newer), busy = false)).isEqualTo(AgentDeliveryStep.Request(newer))
        assertThat(nextAgentDelivery(emptyList(), busy = false)).isEqualTo(AgentDeliveryStep.Empty)
    }

    @Test
    fun countsOnlyWhatWaitsForThatConversation() {
        val target = session(sessionId = "target")
        val other = message(sessionId = "other")
        val mine = message(sessionId = "target")

        assertThat(queuedFor(target, listOf(other, mine))).containsExactly(mine)
        assertThat(queuedFor(target, listOf(other))).isEmpty()
    }

    @Test
    fun inboxRowsKeepTheirOrderAndNameTheSessionTheyReturnTo() {
        val opened = session(sessionId = "opened")
        val gone = session(sessionId = "gone", hostId = 2)
        val items = listOf(item(opened, AgentAttentionReason.APPROVAL), item(gone, AgentAttentionReason.BUSY))

        val rows = agentInboxRows(items, listOf(opened), listOf(Host(id = 1, nickname = "Build box")))

        assertThat(rows.map { it.item.key.sessionId }).containsExactly("opened", "gone")
        assertThat(rows.map { it.session?.sessionId }).containsExactly("opened", null)
        assertThat(rows.map { it.hostLabel }).containsExactly("Build box", "#2")
    }

    @Test
    fun aTranscriptHitResolvesToTheLoadedSessionOrToNothing() {
        val loaded = session(sessionId = "session-1")
        val hit = AgentTranscriptHit(1, transcriptHit())

        assertThat(transcriptHitSession(hit, listOf(loaded))).isEqualTo(loaded)
        assertThat(transcriptHitSession(hit, emptyList())).isNull()
        assertThat(transcriptHitSession(AgentTranscriptHit(2, transcriptHit()), listOf(loaded))).isNull()
    }

    @Test
    fun attentionSignalsCountLiveApprovalsAndRecordTheNewestFailure() {
        val opened = session()
        val events = listOf(
            event("user", "hello", historyKey = 0),
            event("error", "old failure", historyKey = 1),
            event("permission", "Approve rm", historyKey = 2),
            event("question", "Which branch?", historyKey = 3),
            event("error", "agent exited before it was ready", historyKey = 4),
        )

        val active = agentAttentionSignals(opened, events, busy = true, conversationActive = true, hasEarlier = false)
        assertThat(active).hasSize(1)
        assertThat(active.single().pendingApprovals).isEqualTo(1)
        assertThat(active.single().pendingQuestions).isEqualTo(1)
        assertThat(active.single().busy).isTrue()
        assertThat(active.single().lastError).isEqualTo("agent exited before it was ready")

        // History replay is not a live approval, so a closed conversation reports none of them.
        val closed = agentAttentionSignals(opened, events, busy = false, conversationActive = false, hasEarlier = false)
        assertThat(closed.single().pendingApprovals).isZero()
        assertThat(closed.single().pendingQuestions).isZero()
        assertThat(closed.single().busy).isFalse()
        assertThat(closed.single().lastError).isEqualTo("agent exited before it was ready")
    }

    @Test
    fun nothingIsReportedForAConversationThatIsNotOpen() {
        assertThat(agentAttentionSignals(null, listOf(event("permission", "Approve rm", 1)), busy = false, conversationActive = true, hasEarlier = false)).isEmpty()
        assertThat(newestAgentError(listOf(event("user", "hello", 0), event("error", "   ", 1)))).isNull()
        assertThat(newestAgentError(emptyList())).isNull()
    }

    @Test
    fun aQuoteOnlyPrefillsWhenThePackHoldsSomething() {
        assertThat(agentQuotePrefill(pack(AgentContextPackStatus.COMPLETE, "[session context] [user] hello"))).isEqualTo("[session context] [user] hello")
        assertThat(agentQuoteTrimmed(pack(AgentContextPackStatus.TRIMMED, "[session context] [user] hello"))).isTrue()
        assertThat(agentQuoteTrimmed(pack(AgentContextPackStatus.COMPLETE, "[session context] [user] hello"))).isFalse()
        assertThat(agentQuotePrefill(pack(AgentContextPackStatus.EMPTY, "[session context] No messages to quote"))).isNull()
    }

    @Test
    fun everyKindReasonAndCodeKeepsItsOwnWording() {
        assertThat(agentMessageKindText(AgentMessageKind.REQUEST)).isEqualTo(R.string.agent_message_kind_request)
        assertThat(agentMessageKindText(AgentMessageKind.NOTIFY)).isEqualTo(R.string.agent_message_kind_notify)
        assertThat(agentMessageKindHint(AgentMessageKind.REQUEST)).isEqualTo(R.string.agent_message_kind_request_hint)
        assertThat(agentMessageKindHint(AgentMessageKind.NOTIFY)).isEqualTo(R.string.agent_message_kind_notify_hint)
        assertThat(AgentAttentionReason.entries.map(::agentAttentionReasonText)).containsExactly(
            R.string.agent_inbox_approval,
            R.string.agent_inbox_question,
            R.string.agent_inbox_failure,
            R.string.agent_inbox_busy,
        )
        assertThat(AgentTranscriptSkipReason.entries.map(::agentTranscriptSkipText)).containsExactly(
            R.string.agent_transcript_skip_history,
            R.string.agent_transcript_skip_root,
            R.string.agent_transcript_skip_directory,
            R.string.agent_transcript_skip_file,
            R.string.agent_transcript_skip_limit,
        )
        assertThat(TranscriptSearchProblem.entries.map(::agentTranscriptProblemText)).containsExactly(
            R.string.agent_transcript_problem_query_empty,
            R.string.agent_transcript_problem_query_invalid,
            R.string.agent_transcript_problem_scope,
            R.string.agent_transcript_problem_tool,
            R.string.agent_transcript_problem_timeout,
            R.string.agent_transcript_problem_failed,
            R.string.agent_transcript_problem_incomplete,
        )
    }

    private fun session(sessionId: String = "session-1", hostId: Long = 1) = AgentSession(hostId, AgentKind.DSH, sessionId, "/home/dev/app", title = "Build")

    private fun message(
        sessionId: String = "target",
        createdAtMillis: Long = 10,
        kind: AgentMessageKind = AgentMessageKind.REQUEST,
    ) = AgentQueuedMessage(
        id = "message-$sessionId-$createdAtMillis",
        hostId = 1,
        agent = AgentKind.DSH.wireName,
        sessionId = sessionId,
        kind = kind,
        text = "please review the diff",
        createdAtMillis = createdAtMillis,
        state = AgentMessageState.PENDING,
    )

    private fun item(session: AgentSession, reason: AgentAttentionReason) = AgentAttentionItem(
        key = AgentAttentionKey(session.hostId, session.agent, session.sessionId),
        reasons = listOf(reason),
        pendingApprovals = if (reason == AgentAttentionReason.APPROVAL) 1 else 0,
        pendingQuestions = 0,
        lastError = null,
        summary = "1 approval pending",
        updatedAt = session.updatedAt,
    )

    private fun transcriptHit() = TranscriptSearchHit(
        sessionKey = "dsh:session-1",
        agent = AgentKind.DSH,
        sessionId = "session-1",
        directory = "/home/dev/.dsh/sessions/--home-dev-app--/session-1",
        file = "session.v4.jsonl.zstd",
        line = 12,
        offset = 4,
        snippet = "keyword here",
        clipped = false,
    )

    private fun event(type: String, text: String, historyKey: Long) = AgentEvent(
        type = type,
        text = text,
        id = "event-$historyKey",
        historyKey = historyKey,
    )

    private fun pack(status: AgentContextPackStatus, text: String) = AgentContextPack(
        status = status,
        text = text,
        quotedCount = if (status == AgentContextPackStatus.EMPTY) 0 else 1,
        omittedCount = 0,
        cutCount = 0,
    )
}
