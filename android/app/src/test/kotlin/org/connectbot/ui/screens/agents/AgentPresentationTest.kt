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
import org.rmagentma.core.AgentAvailability
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
    fun workspacesAggregateAgentsButNeverMixHostsOrDirectories() {
        val kimi = AgentSession(1, AgentKind.KIMI, "k", "/work", updatedAt = 20)
        val omp = kimi.copy(agent = AgentKind.OMP, sessionId = "o", updatedAt = 30)
        val otherHost = kimi.copy(hostId = 2, updatedAt = 40)
        val otherDirectory = kimi.copy(cwd = "/elsewhere", updatedAt = 10)
        val groups = groupAgentWorkspaces(listOf(kimi, omp, otherHost, otherDirectory), "")
        assertEquals(listOf(AgentWorkspaceKey(2, "/work"), AgentWorkspaceKey(1, "/work"), AgentWorkspaceKey(1, "/elsewhere")), groups.map { it.key })
        assertEquals(listOf(omp, kimi), groups[1].sessions)
    }

    @Test
    fun workspaceSearchFindsCwdTitleAndPreviewAcrossAllHostsAndAgents() {
        val session = AgentSession(1, AgentKind.KIMI, "k", "/Work/Project", title = "Release", preview = "permissions")
        val other = session.copy(hostId = 2, agent = AgentKind.OMP)
        listOf("project", "RELEASE", " PERMISSIONS ").forEach { query ->
            assertEquals(2, groupAgentWorkspaces(listOf(session, other), query).size)
        }
        assertTrue(groupAgentWorkspaces(listOf(session, other), "absent").isEmpty())
    }

    @Test
    fun drawerCurrentScopeFiltersImmediatelyWhileAllScopeRetainsEveryAgent() {
        val kimi = AgentSession(1, AgentKind.KIMI, "kimi", "/work")
        val dsh = kimi.copy(agent = AgentKind.DSH, sessionId = "dsh")
        val otherHost = dsh.copy(hostId = 2)
        val sessions = listOf(kimi, dsh, otherHost)
        assertEquals(listOf(dsh), groupAgentWorkspaces(sessions, "", 1, AgentKind.DSH).single().sessions)
        assertEquals(listOf(kimi), groupAgentWorkspaces(sessions, "", 1, AgentKind.KIMI).single().sessions)
        assertEquals(3, groupAgentWorkspaces(sessions, "").sumOf { it.sessions.size })
        assertEquals(2, groupAgentWorkspaces(sessions, "", null, AgentKind.DSH).sumOf { it.sessions.size })
    }

    @Test
    fun newSessionUsesActiveDirectoryThenMostRecentOnSelectedHostRegardlessOfAgent() {
        val recent = AgentSession(1, AgentKind.OMP, "recent", "/recent", updatedAt = 50)
        val older = recent.copy(agent = AgentKind.KIMI, cwd = "/old", updatedAt = 10)
        val otherHost = recent.copy(hostId = 2, cwd = "/other", updatedAt = 100)
        val active = older.copy(cwd = "/active")
        assertEquals("/active", defaultAgentCwd(1, active, listOf(recent, older, otherHost)))
        assertEquals("/recent", defaultAgentCwd(1, null, listOf(recent, older, otherHost)))
        assertEquals("/recent", defaultAgentCwd(1, otherHost, listOf(recent, older, otherHost)))
        assertEquals("", defaultAgentCwd(null, null, listOf(recent)))
    }

    @Test
    fun highlightsOnlyTheSameHostAgentSessionAndWorkspace() {
        val session = AgentSession(1, AgentKind.KIMI, "shared", "/work")
        assertTrue(sameAgentSession(session, session.copy(title = "updated")))
        assertFalse(sameAgentSession(session, session.copy(hostId = 2)))
        assertFalse(sameAgentSession(session, session.copy(agent = AgentKind.OMP)))
        assertFalse(sameAgentSession(session, session.copy(sessionId = "other")))
        assertFalse(sameAgentSession(session, session.copy(cwd = "/other")))
        assertFalse(sameAgentSession(session, null))
    }

    @Test
    fun selectionRequiresExplicitCloseForEveryActiveOrBusyState() {
        val session = AgentSession(1, AgentKind.KIMI, "k", "/work")
        assertTrue(selectionNeedsConfirmation(session, false, false))
        assertTrue(selectionNeedsConfirmation(null, true, false))
        assertTrue(selectionNeedsConfirmation(null, false, true))
        assertFalse(selectionNeedsConfirmation(null, false, false))
    }

    @Test
    fun allFiveIntegratedAgentsUseNativeChat() {
        assertEquals(AgentKind.entries.toSet(), AgentKind.entries.filter(::supportsStructuredConversation).toSet())
    }

    @Test
    fun unknownAvailabilityAllowsRealProbeWithoutClaimingInstallation() {
        assertEquals(null, agentAvailability(emptyList(), 1, AgentKind.ZCODE))
        assertTrue(canOpenAgent(emptyList(), 1, AgentKind.ZCODE))
    }

    @Test
    fun availabilityIsBoundToHostAndAgentNotTheCachedHistory() {
        val unavailable = AgentAvailability(1, AgentKind.DSH, "/bin/dsh", false, error = "probe failed")
        val installed = AgentAvailability(2, AgentKind.DSH, "/bin/dsh", true, version = "1.0")
        val rows = listOf(unavailable, installed)
        assertEquals(unavailable, agentAvailability(rows, 1, AgentKind.DSH))
        assertFalse(canOpenAgent(rows, 1, AgentKind.DSH))
        assertTrue(canOpenAgent(rows, 2, AgentKind.DSH))
        assertTrue(canOpenAgent(rows, 1, AgentKind.ZCODE))
        assertTrue(canOpenAgent(rows, null, AgentKind.DSH))
        val cached = AgentSession(1, AgentKind.DSH, "old", "/work", preview = "retained history")
        assertEquals(listOf(cached), filterAgentSessions(listOf(cached), 1, AgentKind.DSH, ""))
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
