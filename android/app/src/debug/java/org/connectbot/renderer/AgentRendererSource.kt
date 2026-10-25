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

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import org.connectbot.agent.AgentChallenge
import org.connectbot.agent.AgentConfigAccess
import org.connectbot.agent.AgentConversationState
import org.connectbot.agent.AgentDraftStore
import org.connectbot.agent.AgentHistoryStatus
import org.connectbot.agent.AgentIndexStore
import org.connectbot.agent.AgentLinkState
import org.connectbot.agent.AgentMessageKind
import org.connectbot.agent.AgentMessageQueue
import org.connectbot.agent.AgentMessageQueueStats
import org.connectbot.agent.AgentQueuedMessage
import org.connectbot.agent.AgentRecovery
import org.connectbot.agent.AgentRepository
import org.connectbot.agent.AgentSshPool
import org.connectbot.agent.AgentTranscriptOutcome
import org.connectbot.data.HostRepository
import org.connectbot.data.PubkeyRepository
import org.connectbot.data.entity.Host
import org.connectbot.di.CoroutineDispatchers
import org.connectbot.util.PreferenceConstants
import org.connectbot.util.ThemeMode
import org.json.JSONArray
import org.json.JSONObject
import org.rmagentma.core.AgentAvailability
import org.rmagentma.core.AgentEvent
import org.rmagentma.core.AgentKind
import org.rmagentma.core.AgentSession
import org.rmagentma.core.DirectoryListing
import org.rmagentma.core.RemoteDirectoryListing
import org.rmagentma.core.RemoteFileWriter
import org.rmagentma.rendererhost.json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Binds the renderer host to the product's own agent data. It reads and drives the same singletons the Compose
 * chat uses and opens a conversation only through the repository's exclusive switch, so both surfaces show one
 * conversation with one writer. A terminal command is the same text that switch would hand to the app's own
 * terminal route — the session index, the host row and the probe verdicts it already holds — so no terminal is
 * started here and no credential is read out.
 */
@Singleton
class AgentRendererSource @Inject constructor(
    private val repository: AgentRepository,
    private val hosts: HostRepository,
    private val index: AgentIndexStore,
    private val pool: AgentSshPool,
    private val drafts: AgentDraftStore,
    private val messages: AgentMessageQueue,
    private val keys: PubkeyRepository,
    private val preferences: SharedPreferences,
    private val dispatchers: CoroutineDispatchers,
) : RendererSource {
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
    private val lock = Any()
    private val published = HashMap<Long, String>()
    private val announcedPermissions = mutableSetOf<String>()
    private var epoch = UUID.randomUUID().toString()
    private var identity = ""
    private var sequence = 0L
    private var lastRuntime = ""
    private var lastBoundary = ""
    private var lastLinkKind = ""
    private var collector: Job? = null

    @Volatile
    private var turn: String? = null

    @Volatile
    private var cancelling = false

    @Volatile
    private var listener: ((String, JSONObject, String, Long) -> Unit)? = null

    /** Starts publishing conversation events to [target]; the host owns one target per live renderer document. */
    fun open(target: (String, JSONObject, String, Long) -> Unit) {
        listener = target
        synchronized(lock) {
            if (collector == null) collector = scope.launch { collect() }
        }
    }

    fun close() {
        listener = null
        synchronized(lock) {
            collector?.cancel()
            collector = null
        }
    }

    /** Re-announces the runtime once the renderer subscribes, so it re-reads history instead of assuming continuity. */
    fun announce() = publishRuntimeSnapshot()

    override suspend fun snapshot(): RendererSnapshot {
        val session = repository.activeSession.value ?: repository.selectedSession.value
        syncIdentity()
        val allHosts = try {
            hosts.getSshHosts()
        } catch (_: Exception) {
            emptyList()
        }
        val indexedSessions = try {
            index.read()
        } catch (_: Exception) {
            // An unreadable index holds no rows; the session's own fields still identify the conversation.
            emptyList()
        }
        val indexed = session?.let { opened ->
            indexedSessions.firstOrNull {
                it.hostId == opened.hostId && it.agent == opened.agent && it.sessionId == opened.sessionId
            }
        }
        return RendererSnapshot(
            session = session,
            host = session?.let { opened -> allHosts.firstOrNull { it.id == opened.hostId } },
            indexed = indexed,
            events = repository.events.value,
            active = active(),
            loading = repository.conversationState.value == AgentConversationState.Loading,
            busy = repository.busy.value,
            turnId = turn,
            cancelling = cancelling,
            hasEarlier = repository.hasEarlier.value,
            hosts = allHosts,
            availability = repository.availability.value,
            linkStates = linkStates(),
            scannedSources = repository.scannedSources.value,
            epoch = synchronized(lock) { epoch },
            seq = synchronized(lock) { sequence },
            theme = theme(),
            indexedSessions = indexedSessions,
        )
    }

    /**
     * What the product holds about each host's link: the repository's own bookkeeping answers it, where the live
     * state of this process wins over the recorded one and a host neither names stays unknown rather than
     * reported as reachable.
     */
    private fun linkStates(): Map<Long, AgentLinkState> = repository.hostLinkStates()

    /** The conversation the renderer should load first: whatever the product has open or selected right now. */
    fun conversationId(): String? = currentSession()?.let(RendererConversationId::of)

    override suspend fun open(session: AgentSession): String? {
        val reported = repository.errors.value.size
        // The repository serializes this behind its own switch lock and generation, exactly as the Compose chat
        // does; it reports a failed open through its state instead of throwing, so the state decides the answer.
        repository.open(session)
        val current = repository.activeSession.value
        if (current != null && current.sameConversation(session) && repository.conversationState.value == AgentConversationState.Ready) return null
        return repository.errors.value.drop(reported).lastOrNull()?.takeIf { it.isNotBlank() }
            ?: "会话未能进入就绪状态（可能已被后续的会话切换替代）。"
    }

    override suspend fun send(text: String, expected: AgentSession): String? {
        val previous = repository.events.value.lastOrNull { it.type == "user" }?.historyKey ?: -1L
        turn = UUID.randomUUID().toString()
        cancelling = false
        try {
            repository.send(text, expected)
        } catch (error: Exception) {
            turn = null
            throw error
        }
        val user = repository.events.value.lastOrNull { it.type == "user" }
        // The repository declines silently while another operation holds the conversation; reporting that as
        // accepted would let the renderer believe a message was sent that never left the phone.
        if (user == null || user.historyKey < 0 || user.historyKey == previous) {
            turn = null
            return null
        }
        return RendererProjection.messageId(user.historyKey)
    }

    override suspend fun cancel(expected: AgentSession) {
        cancelling = true
        val cancelled = turn
        try {
            repository.cancel(expected)
        } finally {
            cancelling = false
            turn = null
        }
        if (cancelled != null) publishTurnCompleted(cancelled, "cancelled", "stopped")
    }

    override suspend fun respondPermission(event: AgentEvent, option: String): Boolean {
        val session = repository.selectedSession.value ?: return false
        // The repository re-checks this exact session instance and this exact pending event before answering,
        // so a stale card can only end here as false.
        repository.respondPermission(session, event, event.id, option)
        return repository.events.value.none { it === event }
    }

    override suspend fun respondQuestion(event: AgentEvent, answer: String): Boolean {
        val session = repository.selectedSession.value ?: return false
        // Same gate as an approval: only the instance the repository still holds as pending is answered, so a
        // question that came back from history can never become actionable here.
        repository.respondQuestion(session, event, event.id, answer)
        return repository.events.value.none { it === event }
    }

    /** The pending challenge as the product holds it: an identity, the host it belongs to and the prompt text. */
    override fun challenge(): AgentChallenge? = repository.challenge.value

    /**
     * Hands the answer to the challenge the repository is still waiting on. An id that is no longer pending is not
     * answered at all, and the answer itself is passed through: this seam neither stores, logs nor echoes it.
     */
    override fun respondChallenge(id: String, response: String?): Boolean {
        if (repository.challenge.value?.id != id) return false
        repository.respondChallenge(id, response)
        return true
    }

    override suspend fun loadEarlier() {
        repository.loadEarlier()
    }

    /**
     * A new conversation through the repository's own create entry, which checks the directory read-only on the
     * host before an Agent process exists. Success is the repository's own Ready state for exactly this host and
     * Agent; anything else reports the reason it failed instead of looking created.
     */
    override suspend fun createSession(hostId: Long, agent: AgentKind, cwd: String): String? {
        val reported = repository.errors.value.size
        repository.newSession(hostId, agent, cwd)
        val opened = repository.activeSession.value
        if (opened != null && opened.hostId == hostId && opened.agent == agent && repository.conversationState.value == AgentConversationState.Ready) return null
        return repository.errors.value.drop(reported).lastOrNull()?.takeIf { it.isNotBlank() }
            ?: "会话未能进入就绪状态（可能已被后续的会话切换替代）。"
    }

    override fun setTheme(value: String) {
        val mode = ThemeMode.entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: ThemeMode.SYSTEM
        preferences.edit { putString(PreferenceConstants.THEME_MODE, mode.name) }
    }

    /** The renderer reads and writes configuration through the repository singleton, never a channel of its own. */
    override suspend fun config(): AgentConfigAccess = repository.config()

    override suspend fun setConfigOption(optionId: String, value: String, expected: AgentSession): AgentConfigAccess = repository.setConfigOption(optionId, value, expected)

    /** Drafts are local user content: they come and go through the store alone, never through the ACP channel. */
    override suspend fun draft(key: String): String? = drafts.read(key)

    override suspend fun saveDraft(key: String, text: String) {
        drafts.write(key, text)
    }

    /**
     * The file panel's one remote read: a bounded, read-only directory listing on the host's own session. It runs
     * non-interactively, so a host holding no saved credential fails here as itself instead of prompting a user
     * who only looked at a panel, and every remote command still passes the pool's single target wrapper.
     */
    override suspend fun listWorkspace(hostId: Long, directory: String): DirectoryListing =
        RemoteDirectoryListing(dispatchers.io).list(pool.hostSession(hostId, interactive = false), directory)

    /**
     * One upload's own write: the same bounded remote commands a listing uses, on the same host session, so a
     * host this app cannot reach fails here with its own error and a file the host already holds is never
     * replaced. It runs non-interactively like the listing, because a file picked in a chat is not a reason to
     * prompt for a credential.
     */
    override suspend fun writeWorkspaceFile(hostId: Long, directory: String, name: String, content: ByteArray): String =
        RemoteFileWriter(dispatchers.io).write(pool.hostSession(hostId, interactive = false), directory, name, content)

    /** Hosts are created, renamed and removed through the same repository the Compose host editor writes to. */
    override suspend fun hostByNickname(nickname: String): Host? = hosts.findHost(mapOf("nickname" to nickname))

    override suspend fun saveHost(host: Host): Host = hosts.saveHost(host)

    override suspend fun deleteHost(host: Host) = hosts.deleteHost(host)

    /**
     * One probe through the repository's own refresh entry. That run writes each executable it verified into
     * `AgentDetectionStore` for the host's current target and keeps the previously detected path when the probe
     * never reached the host, so the rows answered here are exactly the verdicts the product now holds.
     */
    override suspend fun refreshDetection(hostId: Long): List<AgentAvailability> {
        repository.refresh(hostId)
        return repository.availability.value.filter { it.hostId == hostId }
    }

    /**
     * The repository's own cross-conversation content search: it names each session's transcript root and file with
     * bounded read-only listings and then reads only those files, so a host this app cannot reach or a history that
     * is not a file keeps its own reason inside the outcome instead of reading as history without that text.
     */
    override suspend fun searchTranscripts(query: String, sessions: List<AgentSession>): AgentTranscriptOutcome =
        repository.searchTranscripts(query, sessions)

    /**
     * Queued messages are read from, and written to, the product's own queue file: this seam sends nothing, and the
     * queue hands a request to its conversation only when that conversation is next opened. Every row is answered
     * with its own state, so a message the conversation took and one the sender withdrew stay readable as what they
     * are rather than as still waiting.
     */
    override suspend fun queued(): List<AgentQueuedMessage> = messages.held()

    override suspend fun queueStats(): AgentMessageQueueStats = messages.stats()

    override suspend fun queue(target: AgentSession, kind: AgentMessageKind, text: String): String? =
        messages.enqueue(target.hostId, target.agent.wireName, target.sessionId, kind, text)

    /**
     * Withdraws one queued message before its conversation can take it. The queue records it under its own
     * terminal state, `withdrawn`, so it is never handed to that conversation and never counted as delivered;
     * nothing here reports a delivery, because none happened.
     */
    override suspend fun withdrawQueued(id: String): Boolean = messages.withdraw(id)

    /**
     * The session index cache is dropped through the repository's own entry, which also updates the in-memory index
     * the renderer lists conversations from, and the count answered here is read back from the store itself.
     */
    override suspend fun clearIndex(): Int {
        repository.clearIndex()
        return index.read().size
    }

    /** What the repository's own history store holds for the loaded conversation, exactly as that store states it. */
    override suspend fun historyStatus(): AgentHistoryStatus = repository.historyStatus()

    /**
     * The retained history pages are dropped through the repository's own entry, which is a different store from
     * the session index: clearing this one removes the messages those conversations showed, never the rows a caller
     * browses. The count answered here is the repository's own number of cleared conversations.
     */
    override suspend fun clearHistory(): Int = repository.clearHistory()

    /**
     * Whether this process is still attached to the recorded session is the repository's own verdict; this seam
     * never upgrades an unverified session to attached, because a caller acts on that difference.
     */
    override suspend fun recoveryState(): AgentRecovery = repository.recoveryState()

    /**
     * The key's own label, read from the product's key repository; no key material is ever handed out. A read that
     * fails answers no label instead of failing the page that only draws a row: the row still carries the key's own
     * id, and a label nobody could read is not a reason to hide the host.
     */
    override suspend fun keyName(keyId: Long): String? = try {
        keys.getById(keyId)?.nickname
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    /**
     * A saved login password is written through the product's own credential store, which wraps it at rest. The
     * value only ever travels in that direction: nothing here reads a stored password back out.
     */
    override suspend fun setHostPassword(hostId: Long, password: String?) = hosts.setHostPassword(hostId, password)

    override suspend fun hasHostPassword(hostId: Long): Boolean = hosts.hasHostPassword(hostId)

    private fun theme(): String = ThemeMode.fromString(preferences.getString(PreferenceConstants.THEME_MODE, null)).name.lowercase()

    private fun active(): Boolean = repository.conversationState.value == AgentConversationState.Ready && repository.activeSession.value != null

    private suspend fun collect() = supervisorScope {
        launch { repository.events.collect { publishEvents(it) } }
        launch { repository.conversationState.collect { finishOnDisconnect(it) } }
        launch {
            repository.activeSession.collect {
                publishRuntime()
                publishConfigOptions()
            }
        }
        launch { repository.busy.collect { publishRuntime() } }
        // The product's own link bookkeeping drives the disconnect notice: no poll of its own is started here.
        launch { repository.liveLinkStates.collect { publishLinkState(it) } }
    }

    /**
     * Re-announces the open conversation's configuration whenever the product opens or closes a session, so a
     * picker the renderer already drew cannot keep showing the models and modes of the session it replaced. The
     * frame is the renderer's own `acp_config_option` message; an empty catalogue is the truth of having none.
     */
    private suspend fun publishConfigOptions() {
        val conversation = syncIdentity() ?: return
        val access = repository.config()
        val catalog = (access as? AgentConfigAccess.Available)?.let { RendererProjection.configOptions(it.config) } ?: JSONArray()
        publish(
            "message.stream",
            json(
                "conversation_id" to conversation,
                "type" to "acp_config_option",
                "data" to json("config_options" to catalog),
            ),
        )
    }

    /** A conversation that stopped being ready can never keep an accepted turn, or the renderer would look busy forever. */
    private fun finishOnDisconnect(state: AgentConversationState) {
        if (state != AgentConversationState.Ready && state != AgentConversationState.Loading) turn = null
        publishRuntime()
    }

    private fun publishEvents(events: List<AgentEvent>) {
        val conversation = syncIdentity() ?: return
        val currentTurn = turn
        val currentEpoch = synchronized(lock) { epoch }
        for (event in events) {
            val fingerprint = fingerprint(event)
            val fresh = synchronized(lock) {
                if (published[event.historyKey] == fingerprint) {
                    false
                } else {
                    published[event.historyKey] = fingerprint
                    true
                }
            }
            if (!fresh) continue
            // A live frame is the renderer's own stream message, whose payload it reads from `data`; the row
            // shape stays what `messages.list` answers, because the history list is drawn from those rows.
            val frame = RendererProjection.streamFrame(event, conversation, currentEpoch, currentTurn) ?: continue
            publish("message.stream", frame)
        }
        // A permission card that left the window was answered or invalidated; the renderer must drop it.
        val live = events.filter { it.type == "permission" }.map { it.id }.toSet()
        val gone = synchronized(lock) { announcedPermissions.filter { it !in live } }
        for (reference in gone) {
            synchronized(lock) { announcedPermissions.remove(reference) }
            publish(
                "confirmation.remove",
                json("conversation_id" to conversation, "id" to reference, "request_id" to reference, "call_id" to reference),
            )
        }
        synchronized(lock) {
            announcedPermissions.addAll(live)
            if (published.size > PUBLISHED_LIMIT) published.keys.retainAll(events.map { it.historyKey }.toSet())
        }
        // The turn boundary the Agent itself stated is announced once per boundary, whether or not this host
        // accepted the turn: a completion that arrived while no turn was tracked is then not lost either.
        val boundary = events.lastOrNull { it.type == "complete" }?.takeIf { event ->
            val fingerprint = fingerprint(event)
            synchronized(lock) {
                if (lastBoundary == fingerprint) {
                    false
                } else {
                    lastBoundary = fingerprint
                    true
                }
            }
        }
        if (boundary != null) {
            val cancelled = boundary.status == "cancelled"
            val finished = turn ?: RendererProjection.messageId(boundary.historyKey)
            turn = null
            publishTurnCompleted(finished, if (cancelled) "cancelled" else "completed", if (cancelled) "stopped" else "ai_waiting_input")
        }
    }

    /**
     * Reports the open conversation's host link as the renderer reads it: the product's own reconnect bookkeeping
     * decides the state, and a frame is published only when the kind of that state changes — connected, offline, or
     * waiting for the user — so a retry loop cannot turn into a stream of notices. `realtime.reconnected` is the name
     * the renderer already reconciles its history and approvals on; the offline side is published as itself, and a
     * host the product holds no state for is left alone because nothing was observed about it.
     */
    private fun publishLinkState(states: Map<Long, AgentLinkState>) {
        val conversation = syncIdentity() ?: return
        val session = currentSession() ?: return
        val state = states[session.hostId] ?: return
        val kind = when (state) {
            AgentLinkState.CONNECTED -> LINK_CONNECTED
            AgentLinkState.NEEDS_USER -> LINK_NEEDS_USER
            else -> LINK_OFFLINE
        }
        if (kind == synchronized(lock) { lastLinkKind }) return
        synchronized(lock) { lastLinkKind = kind }
        publish(
            if (kind == LINK_CONNECTED) "realtime.reconnected" else "realtime.disconnected",
            json(
                "conversation_id" to conversation,
                "host_id" to RendererProjection.projectId(session.hostId),
                "state" to kind,
                "link_state" to state.name.lowercase(),
                "reconnected" to (kind == LINK_CONNECTED),
                "needs_user" to (kind == LINK_NEEDS_USER),
            ),
        )
    }

    private fun publishRuntime() {
        val conversation = syncIdentity() ?: return
        val events = repository.events.value
        val runtime = RendererProjection.runtime(state(events), events.lastOrNull { it.type == "permission" })
        val serialized = runtime.toString()
        if (serialized == synchronized(lock) { lastRuntime }) return
        synchronized(lock) { lastRuntime = serialized }
        publish("runtime.updated", json("conversation_id" to conversation, "runtime" to runtime))
    }

    private fun publishRuntimeSnapshot() {
        val conversation = syncIdentity() ?: return
        val events = repository.events.value
        val runtime = RendererProjection.runtime(state(events), events.lastOrNull { it.type == "permission" })
        publish("runtime.snapshot", json("conversation_id" to conversation, "runtime" to runtime))
    }

    private fun publishTurnCompleted(turnId: String, outcome: String, state: String) {
        val conversation = syncIdentity() ?: return
        val events = repository.events.value
        val runtime = RendererProjection.runtime(state(events), events.lastOrNull { it.type == "permission" })
        publish(
            "turn.completed",
            json(
                "conversation_id" to conversation,
                "session_id" to conversation,
                "turn_id" to turnId,
                "status" to "finished",
                "outcome" to outcome,
                "state" to state,
                "workspace" to (currentSession()?.cwd ?: ""),
                "runtime" to runtime,
            ),
        )
        publishRuntime()
    }

    private fun state(events: List<AgentEvent>): RendererSnapshot = RendererSnapshot(
        session = currentSession(),
        host = null,
        indexed = null,
        events = events,
        active = active(),
        loading = repository.conversationState.value == AgentConversationState.Loading,
        busy = repository.busy.value,
        turnId = turn,
        cancelling = cancelling,
        hasEarlier = repository.hasEarlier.value,
        hosts = emptyList(),
        availability = emptyList(),
        epoch = synchronized(lock) { epoch },
        seq = synchronized(lock) { sequence },
        theme = theme(),
    )

    private fun currentSession(): AgentSession? = repository.activeSession.value ?: repository.selectedSession.value

    /**
     * A new conversation starts a new runtime generation and drops every frame fact of the previous one: a
     * turn accepted for the session that was open can never be reported as the replacement's running turn, and
     * replayed history of the replacement is never mistaken for an already published row.
     */
    private fun syncIdentity(): String? = synchronized(lock) {
        val session = currentSession()
        val current = session?.let { "${it.hostId}:${it.agent.wireName}:${it.sessionId}:${it.cwd}" }.orEmpty()
        if (current != identity) {
            identity = current
            epoch = UUID.randomUUID().toString()
            sequence = 0
            published.clear()
            announcedPermissions.clear()
            lastRuntime = ""
            lastBoundary = ""
            lastLinkKind = ""
            turn = null
            cancelling = false
        }
        session?.let(RendererConversationId::of)
    }

    private fun fingerprint(event: AgentEvent): String = listOf(event.type, event.text, event.status, event.raw?.toString().orEmpty()).joinToString("|")

    private fun publish(name: String, data: JSONObject) {
        val target = listener ?: return
        val stamp = synchronized(lock) {
            sequence += 1
            epoch to sequence
        }
        target(name, data, stamp.first, stamp.second)
    }

    private companion object {
        const val PUBLISHED_LIMIT = 4096

        /** The three kinds of link state a disconnect notice reports, collapsed from the product's own states. */
        const val LINK_CONNECTED = "connected"
        const val LINK_OFFLINE = "offline"
        const val LINK_NEEDS_USER = "needs_user"
    }
}
