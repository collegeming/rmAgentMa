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

import org.connectbot.agent.AgentConfigAccess
import org.connectbot.data.entity.Host
import org.rmagentma.core.AgentAvailability
import org.rmagentma.core.AgentEvent
import org.rmagentma.core.AgentSession
import org.rmagentma.core.DirectoryListing

/** In-memory stand-in for [RendererSource]; every fact a test needs is set explicitly. */
internal class FakeSource : RendererSource {
    var session: AgentSession? = null
    var host: Host? = null
    var indexed: AgentSession? = null
    var sessions: List<AgentSession> = emptyList()
    var events: List<AgentEvent> = emptyList()
    var active = false
    var loading = false
    var turnId: String? = null
    var cancelling = false
    var hasEarlier = false
    var hosts: List<Host> = emptyList()
    var availability: List<AgentAvailability> = emptyList()
    var currentTheme = "light"
    var configAccess: AgentConfigAccess = AgentConfigAccess.Unsupported
    var setter: (String, String) -> AgentConfigAccess = { _, _ -> AgentConfigAccess.Closed }

    /** The reason [open] reports; null means it switched successfully. */
    var openFailure: String? = null

    /** Session the last [open] was asked for, so a test can prove the exclusive entry really ran. */
    var opening: AgentSession? = null

    /** How many times [open] ran, so a test can prove an already open conversation is not opened twice. */
    var opens = 0

    /** A newer switch that wins while [open] is still returning; the requested session is then stale. */
    var replaceDuringOpen: AgentSession? = null
    val writes = mutableListOf<Triple<String, String, AgentSession>>()
    val responded = mutableListOf<Pair<AgentEvent, String>>()
    val draftKeys = mutableListOf<String>()
    val storedDrafts = mutableMapOf<String, String>()
    var cancelCount = 0
    var cancelledSession: AgentSession? = null

    /** The listing [listWorkspace] answers with, and the failure it throws instead once one is set. */
    var workspace: DirectoryListing = DirectoryListing.Listed(emptyList(), truncated = false)
    var workspaceFailure: Exception? = null

    /** Every (host, directory) pair a project read asked to list, so a test can prove the listing really ran. */
    val workspaceCalls = mutableListOf<Pair<Long, String>>()

    override suspend fun snapshot(): RendererSnapshot = RendererSnapshot(
        session = session,
        host = host,
        indexed = indexed,
        events = events,
        active = active,
        loading = loading,
        turnId = turnId,
        cancelling = cancelling,
        hasEarlier = hasEarlier,
        hosts = hosts,
        availability = availability,
        epoch = "epoch-1",
        seq = 4,
        theme = currentTheme,
        indexedSessions = sessions,
    )

    override suspend fun open(session: AgentSession): String? {
        opening = session
        opens++
        this.session = session
        active = openFailure == null
        turnId = null
        replaceDuringOpen?.let {
            this.session = it
            active = false
        }
        return openFailure
    }

    override suspend fun send(text: String, expected: AgentSession): String? {
        if (session?.sameConversation(expected) != true) return null
        turnId = "turn-1"
        return "m9"
    }

    override suspend fun cancel(expected: AgentSession) {
        cancelCount++
        cancelledSession = expected
        turnId = null
    }

    override suspend fun respondPermission(event: AgentEvent, option: String): Boolean {
        responded += event to option
        return true
    }

    override fun setTheme(value: String) {
        currentTheme = value
    }

    override suspend fun config(): AgentConfigAccess = configAccess

    override suspend fun setConfigOption(optionId: String, value: String, expected: AgentSession): AgentConfigAccess {
        writes += Triple(optionId, value, expected)
        return setter(optionId, value)
    }

    override suspend fun draft(key: String): String? {
        draftKeys += key
        return storedDrafts[key]
    }

    override suspend fun saveDraft(key: String, text: String) {
        draftKeys += key
        storedDrafts[key] = text
    }

    override suspend fun listWorkspace(hostId: Long, directory: String): DirectoryListing {
        workspaceCalls += hostId to directory
        workspaceFailure?.let { throw it }
        return workspace
    }
}
