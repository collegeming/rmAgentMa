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

import org.rmagentma.core.AgentAvailability
import org.rmagentma.core.AgentEvent
import org.rmagentma.core.AgentKind
import org.rmagentma.core.AgentSession

fun supportsStructuredConversation(agent: AgentKind): Boolean = agent in AgentKind.entries

internal fun agentAvailability(
    availability: List<AgentAvailability>,
    hostId: Long?,
    kind: AgentKind,
): AgentAvailability? = availability.firstOrNull { it.hostId == hostId && it.kind == kind }

internal fun canOpenAgent(availability: List<AgentAvailability>, hostId: Long?, kind: AgentKind): Boolean = agentAvailability(availability, hostId, kind)?.available != false

fun validAgentCwd(cwd: String): Boolean = cwd.startsWith('/') && cwd.none { it.code < 32 || it.code == 127 }

fun filterAgentSessions(
    sessions: List<AgentSession>,
    hostId: Long?,
    agent: AgentKind?,
    query: String,
): List<AgentSession> {
    val search = query.trim()
    return sessions.filter {
        (hostId == null || it.hostId == hostId) &&
            (agent == null || it.agent == agent) &&
            (search.isEmpty() || listOf(it.title, it.preview, it.cwd).any { value -> value.contains(search, ignoreCase = true) })
    }.sortedByDescending { it.updatedAt }
}

internal data class AgentWorkspaceKey(val hostId: Long, val cwd: String)

internal data class AgentWorkspace(val key: AgentWorkspaceKey, val sessions: List<AgentSession>)

internal fun groupAgentWorkspaces(
    sessions: List<AgentSession>,
    query: String,
    hostId: Long? = null,
    agent: AgentKind? = null,
): List<AgentWorkspace> = filterAgentSessions(sessions, hostId, agent, query)
    .groupBy { AgentWorkspaceKey(it.hostId, it.cwd) }
    .map { (key, items) -> AgentWorkspace(key, items) }

internal fun sameAgentSession(first: AgentSession?, second: AgentSession?): Boolean = first != null && second != null &&
    first.hostId == second.hostId && first.agent == second.agent && first.sessionId == second.sessionId && first.cwd == second.cwd

internal fun defaultAgentCwd(hostId: Long?, active: AgentSession?, sessions: List<AgentSession>): String = active?.takeIf {
    it.hostId == hostId && validAgentCwd(it.cwd)
}?.cwd ?: sessions.filter { it.hostId == hostId && validAgentCwd(it.cwd) }.maxByOrNull { it.updatedAt }?.cwd.orEmpty()

internal sealed interface AgentSelection {
    data class Host(val id: Long?) : AgentSelection
    data class Kind(val agent: AgentKind) : AgentSelection
    data class Open(val session: AgentSession) : AgentSelection
    data class New(val hostId: Long, val agent: AgentKind, val cwd: String) : AgentSelection
    data object Close : AgentSelection
}

internal fun selectionNeedsConfirmation(active: AgentSession?, busy: Boolean, working: Boolean): Boolean = active != null || busy || working

internal class AgentInteractionKey(val session: AgentSession, val event: AgentEvent) {
    override fun equals(other: Any?): Boolean = other is AgentInteractionKey && session === other.session && event === other.event

    override fun hashCode(): Int = 31 * System.identityHashCode(session) + System.identityHashCode(event)
}

internal fun isCurrentAgentInteraction(
    key: AgentInteractionKey,
    activeSession: AgentSession?,
    events: List<AgentEvent>,
): Boolean = key.session === activeSession && key.event.id.isNotBlank() &&
    key.event.type in setOf("permission", "question") && events.any { it === key.event }
