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

import org.connectbot.agent.AGENT_SESSION_PAGE_LIMIT
import org.connectbot.agent.AgentLinkRecord
import org.connectbot.agent.AgentLinkState
import org.connectbot.agent.AgentProbeState
import org.connectbot.agent.probeStateOf
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

/** What one host holds of one Agent: its probe found it, its probe ruled it out, or nothing has asked yet. */
internal enum class AgentHostState { INSTALLED, NOT_INSTALLED, UNPROBED }

internal data class AgentHostChoice(val kind: AgentKind, val state: AgentHostState)

/**
 * The Agents to offer for one host, host first: an Agent is only ever addressed on a host that holds it, so a host
 * the user has not chosen yet offers nothing, and the Agents its probe found come before the rest in enum order.
 * A kind the probe ruled out stays selectable, because selecting it is what shows why it did not answer.
 */
internal fun agentHostChoices(availability: List<AgentAvailability>, hostId: Long?): List<AgentHostChoice> {
    if (hostId == null) return emptyList()
    val choices = AgentKind.entries.map { kind ->
        val row = agentAvailability(availability, hostId, kind)
        AgentHostChoice(
            kind,
            when {
                row == null -> AgentHostState.UNPROBED
                row.available -> AgentHostState.INSTALLED
                else -> AgentHostState.NOT_INSTALLED
            },
        )
    }
    return choices.filter { it.state == AgentHostState.INSTALLED } + choices.filterNot { it.state == AgentHostState.INSTALLED }
}

/**
 * The Agent a host switch lands on: the user's current choice while that host holds it, otherwise the first Agent
 * this host's probe found, and the previous choice when the host holds none, so its verdict stays readable.
 */
internal fun preferredAgentForHost(availability: List<AgentAvailability>, hostId: Long?, current: AgentKind): AgentKind {
    if (hostId == null) return current
    val choices = agentHostChoices(availability, hostId)
    return choices.firstOrNull { it.kind == current && it.state == AgentHostState.INSTALLED }?.kind
        ?: choices.firstOrNull { it.state == AgentHostState.INSTALLED }?.kind
        ?: current
}

internal data class AgentProbeStatus(val state: AgentProbeState, val availability: AgentAvailability?)

/**
 * Full probe status for one host and agent. A running refresh is reported as such even when no row exists yet,
 * and the last row of a failed refresh stays readable next to it instead of being replaced by a blank state.
 */
internal fun agentProbeStatus(
    availability: List<AgentAvailability>,
    refreshingHosts: Set<Long>,
    hostId: Long?,
    kind: AgentKind,
): AgentProbeStatus? {
    val row = agentAvailability(availability, hostId, kind)
    val refreshing = hostId != null && hostId in refreshingHosts
    return when {
        row != null -> AgentProbeStatus(probeStateOf(row, refreshing), row)
        refreshing -> AgentProbeStatus(AgentProbeState.REFRESHING, null)
        else -> null
    }
}

internal data class AgentLinkPresentation(
    val state: AgentLinkState,
    val attempts: Int,
    val sinceMillis: Long,
    val live: Boolean,
)

/**
 * Link state of one host: the pool's live fact wins; without one, the last recorded row is history. Callers must
 * treat [live] false as "not connected now", so a stored CONNECTED can never be rendered as a running link.
 */
internal fun agentLinkPresentation(
    live: Map<Long, AgentLinkState>,
    records: List<AgentLinkRecord>,
    hostId: Long?,
): AgentLinkPresentation? {
    val id = hostId ?: return null
    live[id]?.let { return AgentLinkPresentation(it, 0, 0, live = true) }
    val record = records.firstOrNull { it.hostId == id && it.agent == null } ?: return null
    return AgentLinkPresentation(record.state, record.attempts, record.updatedAtMillis, live = false)
}

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

/**
 * A host/agent pair holding as many sessions as one scan page can carry may have more history behind it, so search
 * must treat the page as unloaded instead of a complete history.
 */
internal fun agentHistoryMayBeTruncated(sessions: List<AgentSession>): Boolean = sessions.groupBy { it.hostId to it.agent }.values.any { it.size >= AGENT_SESSION_PAGE_LIMIT }

internal data class AgentWorkspaceKey(val hostId: Long, val cwd: String)

internal data class AgentWorkspace(val key: AgentWorkspaceKey, val sessions: List<AgentSession>)

internal data class AgentGroup(val agent: AgentKind, val sessions: List<AgentSession>)

internal data class AgentWorkspaceTree(val key: AgentWorkspaceKey, val sessions: List<AgentSession>, val groups: List<AgentGroup>)

internal fun groupAgentWorkspaces(
    sessions: List<AgentSession>,
    query: String,
    hostId: Long? = null,
    agent: AgentKind? = null,
): List<AgentWorkspace> = filterAgentSessions(sessions, hostId, agent, query)
    .groupBy { AgentWorkspaceKey(it.hostId, it.cwd) }
    .map { (key, items) -> AgentWorkspace(key, items) }

/** Nests each workspace by agent so the drawer can render workspace → agent → session without mixing hosts or directories. */
internal fun agentWorkspaceTree(
    sessions: List<AgentSession>,
    query: String,
    hostId: Long? = null,
    agent: AgentKind? = null,
): List<AgentWorkspaceTree> = filterAgentSessions(sessions, hostId, agent, query)
    .groupBy { AgentWorkspaceKey(it.hostId, it.cwd) }
    .map { (key, items) ->
        AgentWorkspaceTree(
            key,
            items,
            items.groupBy { it.agent }
                .map { (kind, grouped) -> AgentGroup(kind, grouped.sortedByDescending { it.updatedAt }) }
                .sortedWith(compareByDescending<AgentGroup> { it.sessions.firstOrNull()?.updatedAt ?: 0L }.thenBy { it.agent.wireName }),
        )
    }

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
