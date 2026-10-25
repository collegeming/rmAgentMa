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

import org.connectbot.ui.screens.agents.applyPendingApprovals
import org.connectbot.ui.screens.agents.projectSessionDetails
import org.rmagentma.core.AgentEvent
import org.rmagentma.core.AgentSession

/**
 * The attention signals the app can honestly report. Only the loaded conversation is live, so it is the only one
 * that gets a row: another conversation's approvals and questions are unknown here, and an unknown error stays null
 * instead of borrowing whatever failure was reported last.
 *
 * This lives in the agent model rather than in the chat screen because the Compose chat and the renderer's inbox
 * route both read it: the two surfaces report the same conversation's attention, from one projection.
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

private const val PERMISSION_EVENT = "permission"
private const val QUESTION_EVENT = "question"
private const val ERROR_EVENT = "error"
