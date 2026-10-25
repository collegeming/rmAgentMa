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

import org.connectbot.agent.AgentChallenge
import org.connectbot.agent.AgentConfigAccess
import org.connectbot.data.entity.Host
import org.rmagentma.core.AgentAvailability
import org.rmagentma.core.AgentEvent
import org.rmagentma.core.AgentKind
import org.rmagentma.core.AgentSession
import org.rmagentma.core.DirectoryListing
import org.rmagentma.rendererhost.ProbeBridge

/**
 * Conversation identity the frozen bridge accepts. The packaged renderer boots into
 * `/conversation/<this id>`, so the one product session the host exposes answers to it.
 */
const val RENDERER_CONVERSATION: String = ProbeBridge.CONVERSATION

/** Everything one renderer request reads; a plain value so the action layer stays testable without Android. */
data class RendererSnapshot(
    /** Product session the renderer sees, or null when the product has no conversation selected. */
    val session: AgentSession?,
    /** Host row behind [session]; null once that host was deleted. */
    val host: Host?,
    /** Index row of [session] from the session index snapshot; null when the index has no row for it. */
    val indexed: AgentSession?,
    /** Loaded event window of the open conversation, oldest first. */
    val events: List<AgentEvent>,
    /** True while [session] is the open, ready conversation. */
    val active: Boolean,
    /** True while the product is still opening [session]. */
    val loading: Boolean,
    /** Host-generated identity of the turn accepted by this host, or null when no turn is running. */
    val turnId: String?,
    /** True between an accepted cancel request and its result. */
    val cancelling: Boolean,
    /** True while the product still holds earlier pages of this conversation on disk. */
    val hasEarlier: Boolean,
    /** Every saved SSH host, in the product's own order. */
    val hosts: List<Host>,
    /** Last probe result per host and agent kind; rows are absent until a probe ran. */
    val availability: List<AgentAvailability>,
    /** Runtime generation stamped on every event this host emits. */
    val epoch: String,
    /** Last event sequence this host published in [epoch]. */
    val seq: Long,
    /** Theme mode of the product as the renderer's own theme id. */
    val theme: String,
    /** Every indexed session of a saved host, as the product's session index holds it now. */
    val indexedSessions: List<AgentSession> = emptyList(),
) {
    fun pending(): AgentEvent? = events.lastOrNull { it.type == "permission" }

    /**
     * The newest question of the loaded window. The product keeps its pending interactions in this window, so this
     * is the instance that may still be pending; whether it really is one the repository decides before answering.
     */
    fun pendingQuestion(): AgentEvent? = events.lastOrNull { it.type == "question" }
}

/**
 * Agent data and commands the renderer host needs, implemented by the product and faked in tests.
 *
 * A terminal command is a projection of the snapshot alone — the host row, its Agent target, the probe verdicts
 * and the conversation's own session id — handed over as text for the caller to run; this seam therefore starts
 * no terminal and needs no launch capability of its own.
 */
interface RendererSource {
    suspend fun snapshot(): RendererSnapshot

    /**
     * Opens [session] through the repository's exclusive conversation switch — the same entry the Compose chat
     * uses, so the renderer can never attach a second writer. Answers null once it is the ready conversation,
     * or the reason it did not become one.
     */
    suspend fun open(session: AgentSession): String?

    /** Sends one text-only prompt through [expected]'s conversation; null when it was replaced or busy. */
    suspend fun send(text: String, expected: AgentSession): String?

    /** Cancels the running turn of [expected]'s conversation, never of whichever conversation replaced it. */
    suspend fun cancel(expected: AgentSession)

    /** Answers the live permission request behind [event]; false when it was already resolved. */
    suspend fun respondPermission(event: AgentEvent, option: String): Boolean

    /**
     * Answers the live question behind [event] through the repository's own entry; false when that exact event is
     * no longer the pending question of the selected conversation, so a question read from history stays history.
     */
    suspend fun respondQuestion(event: AgentEvent, answer: String): Boolean

    /**
     * The challenge the product is waiting on right now — host key confirmation, password or key passphrase — or
     * null while no connection asks for input. The challenge carries no answer: only what the user has to be asked.
     */
    fun challenge(): AgentChallenge?

    /** Hands [response] to the pending challenge behind [id]; false when that id is no longer the pending one. */
    fun respondChallenge(id: String, response: String?): Boolean

    /**
     * Loads the page of history that lies before the window the repository holds for the open conversation, through
     * the repository's own paging entry. Throws when that page cannot be read; a conversation that has no earlier
     * page is not loaded here at all.
     */
    suspend fun loadEarlier()

    /**
     * Creates and opens a new conversation on [hostId] through the repository's own entry, which checks the
     * directory on the host before any Agent process exists. Answers null once that conversation is ready, or the
     * repository's own reason it did not become one.
     */
    suspend fun createSession(hostId: Long, agent: AgentKind, cwd: String): String?

    /** Persists the whitelisted theme setting; throws ProbeFailure for values the product cannot store. */
    fun setTheme(value: String)

    /** Agent configuration the open conversation reports; [AgentConfigAccess.Unsupported] when its Agent has none. */
    suspend fun config(): AgentConfigAccess

    /** Sets one configuration option through the same repository the Compose chat uses; throws when rejected. */
    suspend fun setConfigOption(optionId: String, value: String, expected: AgentSession): AgentConfigAccess

    /** The stored draft of the conversation behind [key], or null when nothing was typed for it yet. */
    suspend fun draft(key: String): String?

    /** Stores [text] as the draft of the conversation behind [key]. */
    suspend fun saveDraft(key: String, text: String)

    /**
     * Lists [directory] on [hostId] read-only through one bounded remote command. An answer from the host is a
     * verdict about that directory; a host this app cannot reach throws, keeping its own error instead of taking a
     * verdict about a directory nobody read.
     */
    suspend fun listWorkspace(hostId: Long, directory: String): DirectoryListing

    /**
     * The saved host whose nickname is [nickname], whatever protocol it carries, or null. The nickname column is
     * unique across every host, so this is the conflict a create or a rename has to be checked against.
     */
    suspend fun hostByNickname(nickname: String): Host?

    /**
     * Inserts a host whose id is 0 and updates one whose id is set, through the product's own repository, so a
     * changed address rewrites that row alone: its id, key association, profile and known hosts stay untouched.
     */
    suspend fun saveHost(host: Host): Host

    /** Deletes [host] through the product's own repository, which also drops its saved credential. */
    suspend fun deleteHost(host: Host)

    /**
     * Probes [hostId]'s Agent target once through the repository's own refresh entry — the same run the Agents
     * screen performs, which writes what it verified into `AgentDetectionStore` and rescans that host's session
     * index — and answers with the verdicts this process now holds for that host. A probe that never reached the
     * host keeps the path it detected before instead of inventing one.
     */
    suspend fun refreshDetection(hostId: Long): List<AgentAvailability>
}
