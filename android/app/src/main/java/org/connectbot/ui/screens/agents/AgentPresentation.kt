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

import org.rmagentma.core.AgentEvent
import org.rmagentma.core.AgentKind
import org.rmagentma.core.AgentSession

fun supportsStructuredConversation(agent: AgentKind): Boolean = agent != AgentKind.DSH && agent != AgentKind.ZCODE

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
            (search.isEmpty() || it.title.contains(search, ignoreCase = true) || it.preview.contains(search, ignoreCase = true))
    }.sortedByDescending { it.updatedAt }
}

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
