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

package org.connectbot.renderer

import org.rmagentma.core.AgentKind
import org.rmagentma.core.AgentSession

/**
 * Renderer identity of one product session: `c1.<hostId>.<hex agent>.<hex sessionId>`. Hex keeps every id inside
 * the URL unreserved set, so neither the renderer's route builder nor the bridge ever has to percent-encode it,
 * and the same tuple always derives the same id after a restart.
 */
internal object RendererConversationId {
    private const val PREFIX = "c1."
    private const val MAX_LENGTH = 4096

    fun of(session: AgentSession): String = PREFIX + session.hostId + "." + hex(session.agent.wireName) + "." + hex(session.sessionId)

    /** The session an id names, or null when it is not one this host could have derived. */
    fun parse(value: String): AgentSession? {
        if (value.length > MAX_LENGTH || !value.startsWith(PREFIX)) return null
        val parts = value.removePrefix(PREFIX).split('.')
        if (parts.size != 3) return null
        val hostId = parts[0].toLongOrNull()?.takeIf { it > 0 } ?: return null
        val agent = unhex(parts[1])?.let(AgentKind::fromWire) ?: return null
        val sessionId = unhex(parts[2])?.takeIf { it.isNotBlank() } ?: return null
        return AgentSession(hostId = hostId, agent = agent, sessionId = sessionId, cwd = "")
    }

    private fun hex(value: String): String = buildString {
        value.toByteArray(Charsets.UTF_8).forEach { append("%02x".format(it)) }
    }

    private fun unhex(value: String): String? {
        if (value.isEmpty() || value.length % 2 != 0) return null
        val bytes = ByteArray(value.length / 2)
        for (index in bytes.indices) {
            val pair = value.substring(index * 2, index * 2 + 2)
            if (pair.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
            bytes[index] = pair.toInt(16).toByte()
        }
        // A payload that is not valid UTF-8 would decode to replacement characters; demanding the round trip
        // keeps one id from naming two different sessions.
        val text = String(bytes, Charsets.UTF_8)
        return text.takeIf { hex(it) == value }
    }
}

/** True when both name the same product session; titles, previews and timestamps do not take part. */
internal fun AgentSession.sameConversation(other: AgentSession): Boolean = hostId == other.hostId && agent == other.agent && sessionId == other.sessionId
