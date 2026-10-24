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

import org.connectbot.R
import org.connectbot.agent.AgentAttentionReason
import org.connectbot.agent.AgentAttentionSignal
import org.connectbot.agent.AgentContextPack
import org.connectbot.agent.AgentContextPackStatus
import org.connectbot.agent.AgentMessageKind
import org.connectbot.agent.AgentQueuedMessage
import org.connectbot.agent.AgentTranscriptSkipReason
import org.rmagentma.core.AgentEvent
import org.rmagentma.core.AgentSession
import org.rmagentma.core.TranscriptSearchProblem

/** One delivery decision for the next queued message; a caller follows these in order until [Empty] or [Waiting]. */
internal sealed interface AgentDeliveryStep {
    /** A hint to show in the receiving conversation; it is never sent to an Agent. */
    data class Notify(val message: AgentQueuedMessage) : AgentDeliveryStep

    /** A prompt to send to the receiving conversation as its own turn. */
    data class Request(val message: AgentQueuedMessage) : AgentDeliveryStep

    /** The conversation is running a turn; nothing is handed over now and the queue waits for the next moment. */
    data object Waiting : AgentDeliveryStep

    /** Nothing waits for this conversation. */
    data object Empty : AgentDeliveryStep
}

/**
 * The next message to hand to a conversation, in queue order. A running turn holds delivery back instead of dropping
 * anything: the message stays queued, so the next idle moment or the next open delivers it.
 */
internal fun nextAgentDelivery(pending: List<AgentQueuedMessage>, busy: Boolean): AgentDeliveryStep {
    val next = pending.minByOrNull { it.createdAtMillis } ?: return AgentDeliveryStep.Empty
    if (busy) return AgentDeliveryStep.Waiting
    return when (next.kind) {
        AgentMessageKind.NOTIFY -> AgentDeliveryStep.Notify(next)
        AgentMessageKind.REQUEST -> AgentDeliveryStep.Request(next)
    }
}

/** What is queued for one conversation, so its row can say how much waits for it before it is opened. */
internal fun queuedFor(session: AgentSession, queued: List<AgentQueuedMessage>): List<AgentQueuedMessage> = queued.filter {
    it.hostId == session.hostId && it.agent == session.agent.wireName && it.sessionId == session.sessionId
}

/**
 * What the chat shell says about the last queued-message action, as data. Every field reports something that
 * happened: a message that was queued, one the queue refused, what a delivery did, and what still waits.
 */
internal data class AgentQueueStatus(
    val queued: AgentQueuedMessage? = null,
    val refused: Boolean = false,
    val deliveryFailure: String? = null,
    val notices: List<AgentQueuedMessage> = emptyList(),
    val deliveredRequests: Int = 0,
    val pending: Int = 0,
)

/** A quote-to-new-session action: the text waiting for a conversation, or that the open one held nothing to quote. */
internal sealed interface AgentQuoteState {
    data class Pending(val text: String, val trimmed: Boolean, val source: AgentSession) : AgentQuoteState

    data object Empty : AgentQuoteState
}

/** The text a quote puts in the input box, or null when the pack has nothing worth pasting. */
internal fun agentQuotePrefill(pack: AgentContextPack): String? =
    pack.text.takeIf { pack.status != AgentContextPackStatus.EMPTY && it.isNotBlank() }

/** Whether the pack left messages or characters out, which the input box cannot show by itself. */
internal fun agentQuoteTrimmed(pack: AgentContextPack): Boolean = pack.status == AgentContextPackStatus.TRIMMED

/**
 * The attention signals the app can honestly report. Only the loaded conversation is live, so it is the only one
 * that gets a row: another conversation's approvals and questions are unknown here, and an unknown error stays null
 * instead of borrowing whatever failure was reported last.
 */
internal fun agentAttentionSignals(
    session: AgentSession?,
    events: List<AgentEvent>,
    busy: Boolean,
    conversationActive: Boolean,
    hasEarlier: Boolean,
): List<AgentAttentionSignal> {
    if (session == null) return emptyList()
    val details = applyPendingApprovals(projectSessionDetails(events, hasEarlier), conversationActive)
    return listOf(
        AgentAttentionSignal(
            session = session,
            busy = busy,
            pendingApprovals = details.approvals.count { it.type == PERMISSION_EVENT && it.pending },
            pendingQuestions = details.approvals.count { it.type == QUESTION_EVENT && it.pending },
            lastError = newestAgentError(events),
        ),
    )
}

/** The conversation's own newest recorded failure; a failure this window never saw is reported as null, not guessed. */
internal fun newestAgentError(events: List<AgentEvent>): String? = events
    .lastOrNull { it.type == ERROR_EVENT }
    ?.text
    ?.trim()
    ?.takeIf { it.isNotEmpty() }

/** How a queued message is named where the user chooses between the two hand-off semantics. */
internal fun agentMessageKindText(kind: AgentMessageKind): Int = when (kind) {
    AgentMessageKind.REQUEST -> R.string.agent_message_kind_request
    AgentMessageKind.NOTIFY -> R.string.agent_message_kind_notify
}

/** What each kind does when the target conversation is opened; this line is the promise the queue keeps. */
internal fun agentMessageKindHint(kind: AgentMessageKind): Int = when (kind) {
    AgentMessageKind.REQUEST -> R.string.agent_message_kind_request_hint
    AgentMessageKind.NOTIFY -> R.string.agent_message_kind_notify_hint
}

/** What waiting for the user means per reason, in the module's own urgency order. */
internal fun agentAttentionReasonText(reason: AgentAttentionReason): Int = when (reason) {
    AgentAttentionReason.APPROVAL -> R.string.agent_inbox_approval
    AgentAttentionReason.QUESTION -> R.string.agent_inbox_question
    AgentAttentionReason.FAILURE -> R.string.agent_inbox_failure
    AgentAttentionReason.BUSY -> R.string.agent_inbox_busy
}

/** Why one conversation stayed out of a transcript search; none of these means the history holds no such text. */
internal fun agentTranscriptSkipText(reason: AgentTranscriptSkipReason): Int = when (reason) {
    AgentTranscriptSkipReason.HISTORY_NOT_A_FILE -> R.string.agent_transcript_skip_history
    AgentTranscriptSkipReason.ROOT_NOT_FOUND -> R.string.agent_transcript_skip_root
    AgentTranscriptSkipReason.SESSION_DIRECTORY_NOT_FOUND -> R.string.agent_transcript_skip_directory
    AgentTranscriptSkipReason.FILE_NOT_FOUND -> R.string.agent_transcript_skip_file
    AgentTranscriptSkipReason.OVER_LIMIT -> R.string.agent_transcript_skip_limit
}

/** Why a remote search did not answer with the hits it was asked for, in the search's own code vocabulary. */
internal fun agentTranscriptProblemText(problem: TranscriptSearchProblem): Int = when (problem) {
    TranscriptSearchProblem.QUERY_EMPTY -> R.string.agent_transcript_problem_query_empty
    TranscriptSearchProblem.QUERY_INVALID -> R.string.agent_transcript_problem_query_invalid
    TranscriptSearchProblem.SCOPE_INVALID -> R.string.agent_transcript_problem_scope
    TranscriptSearchProblem.TOOL_MISSING -> R.string.agent_transcript_problem_tool
    TranscriptSearchProblem.TIMEOUT -> R.string.agent_transcript_problem_timeout
    TranscriptSearchProblem.FAILED -> R.string.agent_transcript_problem_failed
    TranscriptSearchProblem.INCOMPLETE -> R.string.agent_transcript_problem_incomplete
}

private const val PERMISSION_EVENT = "permission"

private const val QUESTION_EVENT = "question"

private const val ERROR_EVENT = "error"
