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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.rmagentma.core.AgentEvent
import org.rmagentma.core.AgentKind
import org.rmagentma.core.AgentSession

class AgentPresentationTest {
    @Test
    fun rejectsEveryAsciiControlCharacterInWorkingDirectory() {
        (0..31).plus(127).forEach { code ->
            assertFalse("Accepted control character $code", validAgentCwd("/work/${code.toChar()}project"))
        }
    }

    @Test
    fun requiresAbsolutePathWithoutRestrictingQuotedShellArguments() {
        listOf("", "relative/path", "~/project", "C:/project").forEach { assertFalse(validAgentCwd(it)) }
        listOf("/", "/work/project", "/work/my project", "/work/项目", "/work/it's-safe;\$(pwd)").forEach {
            assertTrue(validAgentCwd(it))
        }
    }

    @Test
    fun combinesHostAgentAndCaseInsensitiveTitleOrPreviewSearch() {
        val target = AgentSession(1, AgentKind.KIMI, "target", "/work", title = "Build UI", preview = "Fix permissions", updatedAt = 20)
        val sameHost = target.copy(agent = AgentKind.OMP, sessionId = "other-agent")
        val otherHost = target.copy(hostId = 2, sessionId = "other-host")
        val sessions = listOf(sameHost, otherHost, target)
        assertEquals(listOf(target), filterAgentSessions(sessions, 1, AgentKind.KIMI, " BUILD "))
        assertEquals(listOf(target), filterAgentSessions(sessions, 1, AgentKind.KIMI, "PERMISSIONS"))
        assertTrue(filterAgentSessions(sessions, 1, AgentKind.KIMI, "missing").isEmpty())
    }

    @Test
    fun unfilteredResultsAreNewestFirstWithoutMutatingInput() {
        val older = AgentSession(1, AgentKind.KIMI, "old", "/work", updatedAt = 10)
        val newer = older.copy(sessionId = "new", updatedAt = 100)
        val sessions = listOf(older, newer)
        assertEquals(listOf(newer, older), filterAgentSessions(sessions, null, null, "  "))
        assertEquals(listOf(older, newer), sessions)
    }

    @Test
    fun onlyThreeAgentsSupportStructuredConversation() {
        assertEquals(
            setOf(AgentKind.KIMI, AgentKind.OPENCODE, AgentKind.OMP),
            AgentKind.entries.filter(::supportsStructuredConversation).toSet(),
        )
    }

    @Test
    fun interactionKeysRejectSameIdsFromReopenedSessionOrReplacementEvent() {
        val session = AgentSession(1, AgentKind.KIMI, "session", "/work")
        val event = AgentEvent("permission", id = "1")
        val key = AgentInteractionKey(session, event)
        assertTrue(isCurrentAgentInteraction(key, session, listOf(event)))
        assertEquals(key, AgentInteractionKey(session, event))
        assertNotEquals(key, AgentInteractionKey(session.copy(), event))
        assertFalse(isCurrentAgentInteraction(key, session.copy(), listOf(event)))
        assertFalse(isCurrentAgentInteraction(key, session, listOf(event.copy())))
        assertFalse(isCurrentAgentInteraction(key, session, emptyList()))
        assertFalse(isCurrentAgentInteraction(key, null, listOf(event)))
    }

    @Test
    fun onlyIdentifiedInteractiveEventsCanReceiveResponses() {
        val session = AgentSession(1, AgentKind.KIMI, "session", "/work")
        listOf(AgentEvent("permission"), AgentEvent("tool", id = "1")).forEach { event ->
            assertFalse(isCurrentAgentInteraction(AgentInteractionKey(session, event), session, listOf(event)))
        }
    }
}
