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
import org.connectbot.agent.AgentLinkState
import org.connectbot.agent.AgentMessageKind
import org.connectbot.agent.AgentMessageQueueStats
import org.connectbot.agent.AgentMessageState
import org.connectbot.agent.AgentQueuedMessage
import org.connectbot.agent.AgentTranscriptOutcome
import org.connectbot.data.entity.Host
import org.rmagentma.core.AgentAvailability
import org.rmagentma.core.AgentEvent
import org.rmagentma.core.AgentKind
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

    /** Whether the product is running another operation on the open conversation, as the repository reports it. */
    var busy = false

    /** Link state per host the product holds, live or recorded; a host missing here was never observed. */
    var linkStates: Map<Long, AgentLinkState> = emptyMap()

    /** Host/agent pairs whose rows were really re-read from the remote, so every other row is cache-only. */
    var scannedSources: Set<Pair<Long, AgentKind>> = emptySet()

    /** The reason [open] reports; null means it switched successfully. */
    var openFailure: String? = null

    /** Session the last [open] was asked for, so a test can prove the exclusive entry really ran. */
    var opening: AgentSession? = null

    /** How many times [open] ran, so a test can prove an already open conversation is not opened twice. */
    var opens = 0

    /** A newer switch that wins while [open] is still returning; the requested session is then stale. */
    var replaceDuringOpen: AgentSession? = null
    val writes = mutableListOf<Triple<String, String, AgentSession>>()

    /** Every prompt a route really handed to the repository, so a test can prove what the Agent would receive. */
    val sends = mutableListOf<Pair<String, AgentSession>>()
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

    /** Every host a renderer route asked the repository to store, and every one it asked to delete. */
    val savedHosts = mutableListOf<Host>()
    val deletedHosts = mutableListOf<Host>()

    /** Every host a refresh was asked to probe, so a test can prove the probe really ran. */
    val probedHosts = mutableListOf<Long>()

    /** The failure a host write or a probe reports instead of succeeding. */
    var hostFailure: Exception? = null
    var detectionFailure: Exception? = null

    /** The rows one probe produces, keyed by host id; a host missing here keeps the rows it already had. */
    var probeRows: Map<Long, List<AgentAvailability>> = emptyMap()

    /** The id an inserted host is assigned, as a real insert assigns one. */
    var nextHostId = 100L

    override suspend fun snapshot(): RendererSnapshot = RendererSnapshot(
        session = session,
        host = host,
        indexed = indexed,
        events = events,
        active = active,
        loading = loading,
        busy = busy,
        turnId = turnId,
        cancelling = cancelling,
        hasEarlier = hasEarlier,
        hosts = hosts,
        availability = availability,
        linkStates = linkStates,
        scannedSources = scannedSources,
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
        sends += text to expected
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

    /** Every question answer a route handed over, and whether the repository still holds that instance pending. */
    val answeredQuestions = mutableListOf<Pair<AgentEvent, String>>()
    var questionAnswerable = true

    override suspend fun respondQuestion(event: AgentEvent, answer: String): Boolean {
        answeredQuestions += event to answer
        return questionAnswerable
    }

    /** The challenge the product is waiting on, every answer handed over, and whether the id is still pending. */
    var pendingChallenge: AgentChallenge? = null
    var challengeAnswerable = true
    val challengeAnswers = mutableListOf<Pair<String, String?>>()

    override fun challenge(): AgentChallenge? = pendingChallenge

    override fun respondChallenge(id: String, response: String?): Boolean {
        challengeAnswers += id to response
        if (!challengeAnswerable) return false
        // A challenge that was answered stops being pending, exactly as the real one clears its flow.
        if (pendingChallenge?.id == id) pendingChallenge = null
        return true
    }

    /** How many times the earlier page was read, and what that read answers with instead of the loaded window. */
    var earlierLoads = 0
    var earlierEvents: List<AgentEvent>? = null
    var earlierFailure: Exception? = null

    /** What the window reports as still holding an even earlier page once [loadEarlier] ran. */
    var hasEarlierAfterLoad = false

    override suspend fun loadEarlier() {
        earlierLoads++
        earlierFailure?.let { throw it }
        earlierEvents?.let { events = it }
        hasEarlier = hasEarlierAfterLoad
    }

    /** The failure a conversation create reports, and the session the repository really came up with. */
    var createFailure: String? = null
    var created: AgentSession? = null
    val createdConversations = mutableListOf<Triple<Long, AgentKind, String>>()

    override suspend fun createSession(hostId: Long, agent: AgentKind, cwd: String): String? {
        createdConversations += Triple(hostId, agent, cwd)
        createFailure?.let { return it }
        val opened = created ?: AgentSession(hostId = hostId, agent = agent, sessionId = "native-1", cwd = cwd)
        session = opened
        indexed = opened
        active = true
        return null
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

    /** Every upload a route asked to land on a host, and the path the write answers with. */
    val writtenFiles = mutableListOf<Triple<Long, String, String>>()
    var writeFailure: Exception? = null
    var writtenPath: String? = null

    override suspend fun writeWorkspaceFile(hostId: Long, directory: String, name: String, content: ByteArray): String {
        wroteContent = content
        writeFailure?.let { throw it }
        writtenFiles += Triple(hostId, directory, name)
        return writtenPath ?: "$directory/$name"
    }

    /** The bytes the last upload handed over, so a test can prove the file really travelled whole. */
    var wroteContent: ByteArray? = null

    override suspend fun hostByNickname(nickname: String): Host? = hosts.firstOrNull { it.nickname == nickname }

    override suspend fun saveHost(host: Host): Host {
        hostFailure?.let { throw it }
        val saved = if (host.id > 0L) host else host.copy(id = nextHostId++)
        savedHosts += saved
        // The row a real repository stores is the row its next read answers with.
        hosts = hosts.filterNot { it.id == saved.id } + saved
        return saved
    }

    override suspend fun deleteHost(host: Host) {
        hostFailure?.let { throw it }
        deletedHosts += host
        hosts = hosts.filterNot { it.id == host.id }
    }

    override suspend fun refreshDetection(hostId: Long): List<AgentAvailability> {
        detectionFailure?.let { throw it }
        probedHosts += hostId
        probeRows[hostId]?.let { rows -> availability = availability.filterNot { it.hostId == hostId } + rows }
        return availability.filter { it.hostId == hostId }
    }

    /** What one content search answers with, the failure it throws instead, and every selection it was asked about. */
    var transcriptOutcome = AgentTranscriptOutcome(query = "")
    var transcriptFailure: Exception? = null
    val transcriptSearches = mutableListOf<Pair<String, List<AgentSession>>>()

    override suspend fun searchTranscripts(query: String, sessions: List<AgentSession>): AgentTranscriptOutcome {
        transcriptSearches += query to sessions
        transcriptFailure?.let { throw it }
        return transcriptOutcome
    }

    /** What the queue holds, what it was asked to queue, and what a withdrawal is answered with. */
    var queuedMessages: List<AgentQueuedMessage> = emptyList()
    var queuedStats = AgentMessageQueueStats(stored = 0, pending = 0, delivered = 0, dropped = 0)
    var queueRefuses = false
    var withdrawAccepted = true
    var queueFailure: Exception? = null
    val queuedWrites = mutableListOf<Triple<AgentSession, AgentMessageKind, String>>()
    val withdrawn = mutableListOf<String>()

    /** Every row the queue still holds, exactly as the real queue answers it: each one with its own state. */
    override suspend fun queued(): List<AgentQueuedMessage> {
        queueFailure?.let { throw it }
        return queuedMessages
    }

    override suspend fun queueStats(): AgentMessageQueueStats {
        queueFailure?.let { throw it }
        return queuedStats
    }

    override suspend fun queue(target: AgentSession, kind: AgentMessageKind, text: String): String? {
        queueFailure?.let { throw it }
        if (queueRefuses) return null
        queuedWrites += Triple(target, kind, text)
        val id = "queue-${queuedWrites.size}"
        queuedMessages += AgentQueuedMessage(
            id = id,
            hostId = target.hostId,
            agent = target.agent.wireName,
            sessionId = target.sessionId,
            kind = kind,
            text = text,
            createdAtMillis = 1,
            state = AgentMessageState.PENDING,
        )
        return id
    }

    /**
     * Withdrawing records the row under the queue's own terminal state, exactly as the real queue does, and only a
     * row still pending can be withdrawn.
     */
    override suspend fun withdrawQueued(id: String): Boolean {
        queueFailure?.let { throw it }
        if (!withdrawAccepted) return false
        val row = queuedMessages.firstOrNull { it.id == id } ?: return false
        if (row.state != AgentMessageState.PENDING) return false
        queuedMessages = queuedMessages.map { if (it.id == id) it.copy(state = AgentMessageState.WITHDRAWN) else it }
        withdrawn += id
        return true
    }

    /** How many times the index cache was cleared, and what it holds afterwards. */
    var indexClears = 0
    var indexFailure: Exception? = null

    /** Clearing really drops the rows the fake holds, so a route that reports a cleanup proves one happened. */
    override suspend fun clearIndex(): Int {
        indexFailure?.let { throw it }
        indexClears++
        sessions = emptyList()
        indexed = null
        return sessions.size
    }
}

/**
 * Stands in for the product's own terminal surface: every hand-over is recorded with the host and the command it
 * carried, and the answer is what a test set — a started console, a build without one, or a refused start.
 */
internal class FakeTerminal : RendererTerminal {
    var result: RendererTerminal.Result = RendererTerminal.Result.Started
    val starts = mutableListOf<Pair<Long, String>>()

    override suspend fun start(hostId: Long, command: String): RendererTerminal.Result {
        starts += hostId to command
        return result
    }
}
