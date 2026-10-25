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

import org.connectbot.data.entity.Host
import org.rmagentma.core.AgentSession

/** One saved host's link as the foreground notification may state it. */
enum class AgentNoticeHostState { CONNECTED, RECONNECTING, NEEDS_USER, DISCONNECTED, UNKNOWN }

/** One conversation as the foreground notification may state it. */
enum class AgentNoticeSessionState { RUNNING, OPENING, OPEN, DISCONNECTED, UNKNOWN }

data class AgentNoticeHost(val name: String, val state: AgentNoticeHostState)

data class AgentNoticeSession(val name: String, val state: AgentNoticeSessionState)

/** The session-level facts the foreground notification may state, and nothing beyond them. */
data class AgentSessionNoticeFacts(
    val sessions: List<AgentNoticeSession> = emptyList(),
    val hosts: List<AgentNoticeHost> = emptyList(),
)

/**
 * What this app may state about its sessions, derived from facts it holds and nothing else.
 *
 * A conversation is running only while its own turn is executing and is said to be opening while it is still
 * loading; a host the pool speaks about is reported with that fact, a host only an earlier run recorded is not
 * reported as connected by this run, and a host this app holds no fact and no record for is reported as unknown
 * instead of being left out or guessed at.
 *
 * The names are the host's nickname and the conversation's own title, cleaned and bounded: both come from remote
 * text and neither may carry a control character into a notification.
 */
internal fun agentSessionNoticeFacts(
    hosts: List<Host>,
    session: AgentSession?,
    conversation: AgentConversationState,
    busy: Boolean,
    live: Map<Long, AgentLinkState>,
    recorded: List<AgentLinkRecord>,
): AgentSessionNoticeFacts = AgentSessionNoticeFacts(
    sessions = session?.let { listOf(AgentNoticeSession(noticeName(it.agent.wireName, it.title.ifBlank { it.cwd }), noticeSessionState(conversation, busy))) }.orEmpty(),
    hosts = hosts.map { host ->
        val state = live[host.id]?.let(::liveHostState)
            ?: recorded.firstOrNull { it.hostId == host.id && it.agent == null }?.let { recordedHostState(it.state) }
            ?: AgentNoticeHostState.UNKNOWN
        AgentNoticeHost(noticeName("", host.nickname.ifBlank { host.hostname }), state)
    },
)

private fun noticeSessionState(conversation: AgentConversationState, busy: Boolean): AgentNoticeSessionState = when {
    conversation == AgentConversationState.Failed -> AgentNoticeSessionState.DISCONNECTED
    conversation == AgentConversationState.Ready && busy -> AgentNoticeSessionState.RUNNING
    conversation == AgentConversationState.Ready -> AgentNoticeSessionState.OPEN
    conversation == AgentConversationState.Loading -> AgentNoticeSessionState.OPENING
    else -> AgentNoticeSessionState.UNKNOWN
}

private fun liveHostState(state: AgentLinkState): AgentNoticeHostState = when (state) {
    AgentLinkState.CONNECTED -> AgentNoticeHostState.CONNECTED
    AgentLinkState.RETRYING -> AgentNoticeHostState.RECONNECTING
    AgentLinkState.NEEDS_USER -> AgentNoticeHostState.NEEDS_USER
    AgentLinkState.DISCONNECTED -> AgentNoticeHostState.DISCONNECTED
}

/**
 * A row another process left behind is not a running link for this one: this app is not connected to that host,
 * so a recorded CONNECTED is stated as disconnected rather than as a connection that no longer exists.
 */
private fun recordedHostState(state: AgentLinkState): AgentNoticeHostState =
    if (state == AgentLinkState.CONNECTED) AgentNoticeHostState.DISCONNECTED else liveHostState(state)

/** One text a notification can carry: control characters removed, whitespace collapsed and length bounded. */
internal fun noticeName(prefix: String, value: String): String {
    val cleaned = value.map { if (it.isISOControl()) ' ' else it }
        .joinToString("")
        .split(' ')
        .filter { it.isNotEmpty() }
        .joinToString(" ")
        .take(NOTICE_NAME_LIMIT)
    return listOf(prefix, cleaned).filter { it.isNotEmpty() }.joinToString(" · ")
}

/**
 * The status line of the Agent notification: what the conversations and hosts above mean, in one text, or null
 * when this app has nothing to state at all. Only facts that are held are named, and a state this app cannot
 * state is named as unknown - never as a running conversation and never as a connection that is still up.
 */
internal fun agentSessionNoticeText(facts: AgentSessionNoticeFacts): String? {
    val sessions = facts.sessions.map { session ->
        when (session.state) {
            AgentNoticeSessionState.RUNNING -> "Running: ${session.name}"
            AgentNoticeSessionState.OPENING -> "Opening: ${session.name}"
            AgentNoticeSessionState.OPEN -> "Open: ${session.name}"
            AgentNoticeSessionState.DISCONNECTED -> "Disconnected: ${session.name}"
            AgentNoticeSessionState.UNKNOWN -> "State unknown: ${session.name}"
        }
    }
    val hosts = HOST_STATE_NAMES.mapNotNull { (state, name) ->
        facts.hosts.count { it.state == state }.takeIf { it > 0 }?.let { "$it $name" }
    }
    val text = buildList {
        addAll(sessions)
        if (hosts.isNotEmpty()) add("Hosts: " + hosts.joinToString(", "))
    }.joinToString(" · ")
    return text.take(NOTICE_TEXT_LIMIT).takeIf { it.isNotEmpty() }
}

private val HOST_STATE_NAMES = listOf(
    AgentNoticeHostState.CONNECTED to "connected",
    AgentNoticeHostState.RECONNECTING to "reconnecting",
    AgentNoticeHostState.NEEDS_USER to "waiting for you",
    AgentNoticeHostState.DISCONNECTED to "disconnected",
    AgentNoticeHostState.UNKNOWN to "state unknown",
)

/** One session or host name in a notification, bounded because the text comes from the host or the Agent. */
private const val NOTICE_NAME_LIMIT = 64

/** The whole status line, bounded because a notification is one line of text on a locked screen. */
private const val NOTICE_TEXT_LIMIT = 400
