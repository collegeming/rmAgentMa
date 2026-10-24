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

import org.rmagentma.core.AgentKind
import org.rmagentma.core.AgentSession

/** One row's whole text budget, so a reported Agent error cannot stretch a drawer row past the screen. */
internal const val AGENT_ATTENTION_SUMMARY_LIMIT = 160

/** Why a conversation waits for the user, declared most urgent first so ranking can follow the declaration order. */
internal enum class AgentAttentionReason {
    APPROVAL,
    QUESTION,
    FAILURE,
    BUSY,
}

/** The conversation a row returns to; a retitled or moved session must still merge into the same row. */
internal data class AgentAttentionKey(
    val hostId: Long,
    val agent: AgentKind,
    val sessionId: String,
)

/**
 * What a caller knows about one conversation. Every field is reported, never derived: an unreported
 * [AgentSession.updatedAt] stays zero and an unknown error stays null instead of being guessed at.
 */
internal data class AgentAttentionSignal(
    val session: AgentSession,
    val busy: Boolean = false,
    val pendingApprovals: Int = 0,
    val pendingQuestions: Int = 0,
    val lastError: String? = null,
)

/** One conversation to open, keeping every reason it asked for attention as detail. */
internal data class AgentAttentionItem(
    val key: AgentAttentionKey,
    /** Reasons of this conversation, most urgent first; never empty for an item this file builds. */
    val reasons: List<AgentAttentionReason>,
    val pendingApprovals: Int,
    val pendingQuestions: Int,
    val lastError: String?,
    val summary: String,
    val updatedAt: Long?,
)

/**
 * Aggregates [signals] into one row per conversation: most urgent reason first, newest first inside one reason.
 * A conversation reporting nothing is left out instead of being rendered as "nothing to do", and a conversation
 * whose time was never reported keeps a null [AgentAttentionItem.updatedAt] and sorts after the reported ones.
 * Rows that tie on both fall back to the session key, so the order stays stable across repeated emissions.
 *
 * This is a projection over its argument alone: it opens no repository, network or Agent call, and it answers
 * missing information with an explicit null rather than a guess.
 */
internal fun projectAgentAttentionInbox(signals: List<AgentAttentionSignal>): List<AgentAttentionItem> {
    val items = signals
        .groupBy { AgentAttentionKey(it.session.hostId, it.session.agent, it.session.sessionId) }
        .mapNotNull { (key, reported) -> attentionItem(key, reported) }
    return items.sortedWith(
        compareBy<AgentAttentionItem> { it.reasons.first().ordinal }
            .thenByDescending { it.updatedAt ?: Long.MIN_VALUE }
            .thenBy { it.key.hostId }
            .thenBy { it.key.agent.wireName }
            .thenBy { it.key.sessionId },
    )
}

/** Merges every signal of one conversation; a conversation that reported nothing has no row at all. */
private fun attentionItem(key: AgentAttentionKey, reported: List<AgentAttentionSignal>): AgentAttentionItem? {
    val approvals = reported.sumOf { it.pendingApprovals.coerceAtLeast(0) }
    val questions = reported.sumOf { it.pendingQuestions.coerceAtLeast(0) }
    val error = newestError(reported)
    val busy = reported.any { it.busy }
    // Reasons are added in declaration order, which is what makes reasons.first() the most urgent one.
    val reasons = mutableListOf<AgentAttentionReason>()
    if (approvals > 0) reasons += AgentAttentionReason.APPROVAL
    if (questions > 0) reasons += AgentAttentionReason.QUESTION
    if (error != null) reasons += AgentAttentionReason.FAILURE
    if (busy) reasons += AgentAttentionReason.BUSY
    if (reasons.isEmpty()) return null
    return AgentAttentionItem(
        key = key,
        reasons = reasons,
        pendingApprovals = approvals,
        pendingQuestions = questions,
        lastError = error,
        summary = attentionSummary(approvals, questions, error, busy),
        updatedAt = reported.map { it.session.updatedAt }.filter { it > 0 }.maxOrNull(),
    )
}

/** The newest reported error wins; without a reported time the first one seen is kept instead of inventing an order. */
private fun newestError(reported: List<AgentAttentionSignal>): String? {
    val reportedErrors = reported.mapNotNull { signal ->
        val text = signal.lastError?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        text to signal.session.updatedAt
    }
    return reportedErrors.maxByOrNull { it.second }?.first?.take(AGENT_ATTENTION_SUMMARY_LIMIT)
}

/** Reads as what waits for the user; a localized row is the UI's job, this line only reports the data it was given. */
private fun attentionSummary(approvals: Int, questions: Int, error: String?, busy: Boolean): String {
    val parts = mutableListOf<String>()
    if (approvals > 0) parts += "${counted(approvals, "approval")} pending"
    if (questions > 0) parts += "${counted(questions, "question")} pending"
    if (error != null) parts += "failed: $error"
    if (busy) parts += "running"
    return parts.joinToString(", ").take(AGENT_ATTENTION_SUMMARY_LIMIT)
}

private fun counted(count: Int, noun: String): String = "$count $noun${if (count == 1) "" else "s"}"
