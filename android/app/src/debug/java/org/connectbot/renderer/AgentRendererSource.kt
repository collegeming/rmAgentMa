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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import org.connectbot.agent.AgentConversationState
import org.connectbot.agent.AgentIndexStore
import org.connectbot.agent.AgentRepository
import org.connectbot.data.HostRepository
import org.connectbot.di.CoroutineDispatchers
import org.connectbot.util.PreferenceConstants
import org.connectbot.util.ThemeMode
import org.json.JSONObject
import org.rmagentma.core.AgentEvent
import org.rmagentma.rendererhost.json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Binds the renderer host to the product's own agent data. It reads and drives the same singletons the Compose
 * chat uses, never opens a conversation on its own, and publishes the loaded event window as the renderer
 * stream so both surfaces show one conversation with one writer.
 */
@Singleton
class AgentRendererSource @Inject constructor(
    private val repository: AgentRepository,
    private val hosts: HostRepository,
    private val index: AgentIndexStore,
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
        synchronized(lock) {
            val current = session?.let { "${it.hostId}:${it.agent.wireName}:${it.sessionId}:${it.cwd}" }.orEmpty()
            if (current != identity) {
                identity = current
                epoch = UUID.randomUUID().toString()
                sequence = 0
                published.clear()
                announcedPermissions.clear()
                lastRuntime = ""
            }
        }
        val allHosts = try {
            hosts.getSshHosts()
        } catch (_: Exception) {
            emptyList()
        }
        val indexed = session?.let { opened ->
            try {
                index.read().firstOrNull {
                    it.hostId == opened.hostId && it.agent == opened.agent && it.sessionId == opened.sessionId
                }
            } catch (_: Exception) {
                // An unreadable index holds no metadata; the session's own fields still identify the conversation.
                null
            }
        }
        return RendererSnapshot(
            session = session,
            host = session?.let { opened -> allHosts.firstOrNull { it.id == opened.hostId } },
            indexed = indexed,
            events = repository.events.value,
            active = active(),
            loading = repository.conversationState.value == AgentConversationState.Loading,
            turnId = turn,
            cancelling = cancelling,
            hasEarlier = repository.hasEarlier.value,
            hosts = allHosts,
            availability = repository.availability.value,
            epoch = synchronized(lock) { epoch },
            seq = synchronized(lock) { sequence },
            theme = theme(),
        )
    }

    override suspend fun send(text: String): String? {
        val previous = repository.events.value.lastOrNull { it.type == "user" }?.historyKey ?: -1L
        turn = UUID.randomUUID().toString()
        cancelling = false
        try {
            repository.send(text)
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

    override suspend fun cancel() {
        cancelling = true
        val cancelled = turn
        try {
            repository.cancel()
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

    override fun setTheme(value: String) {
        val mode = ThemeMode.entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: ThemeMode.SYSTEM
        preferences.edit { putString(PreferenceConstants.THEME_MODE, mode.name) }
    }

    private fun theme(): String = ThemeMode.fromString(preferences.getString(PreferenceConstants.THEME_MODE, null)).name.lowercase()

    private fun active(): Boolean = repository.conversationState.value == AgentConversationState.Ready && repository.activeSession.value != null

    private suspend fun collect() = supervisorScope {
        launch { repository.events.collect { publishEvents(it) } }
        launch { repository.conversationState.collect { finishOnDisconnect(it) } }
        launch { repository.activeSession.collect { publishRuntime() } }
        launch { repository.busy.collect { publishRuntime() } }
    }

    /** A conversation that stopped being ready can never keep an accepted turn, or the renderer would look busy forever. */
    private fun finishOnDisconnect(state: AgentConversationState) {
        if (state != AgentConversationState.Ready && state != AgentConversationState.Loading) turn = null
        publishRuntime()
    }

    private fun publishEvents(events: List<AgentEvent>) {
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
            val frame = RendererProjection.message(event, currentEpoch, currentTurn) ?: continue
            publish("message.stream", frame)
        }
        // A permission card that left the window was answered or invalidated; the renderer must drop it.
        val live = events.filter { it.type == "permission" }.map { it.id }.toSet()
        val gone = synchronized(lock) { announcedPermissions.filter { it !in live } }
        for (reference in gone) {
            synchronized(lock) { announcedPermissions.remove(reference) }
            publish(
                "confirmation.remove",
                json("conversation_id" to RENDERER_CONVERSATION, "id" to reference, "request_id" to reference, "call_id" to reference),
            )
        }
        synchronized(lock) {
            announcedPermissions.addAll(live)
            if (published.size > PUBLISHED_LIMIT) published.keys.retainAll(events.map { it.historyKey }.toSet())
        }
        val last = events.lastOrNull()
        if (last?.type == "complete" && turn != null) {
            val cancelled = last.status == "cancelled"
            val finished = turn
            turn = null
            if (finished != null) {
                publishTurnCompleted(finished, if (cancelled) "cancelled" else "completed", if (cancelled) "stopped" else "ai_waiting_input")
            }
        }
    }

    private fun publishRuntime() {
        val events = repository.events.value
        val runtime = RendererProjection.runtime(state(events), events.lastOrNull { it.type == "permission" })
        val serialized = runtime.toString()
        if (serialized == synchronized(lock) { lastRuntime }) return
        synchronized(lock) { lastRuntime = serialized }
        publish("runtime.updated", json("conversation_id" to RENDERER_CONVERSATION, "runtime" to runtime))
    }

    private fun publishRuntimeSnapshot() {
        val events = repository.events.value
        val runtime = RendererProjection.runtime(state(events), events.lastOrNull { it.type == "permission" })
        publish("runtime.snapshot", json("conversation_id" to RENDERER_CONVERSATION, "runtime" to runtime))
    }

    private fun publishTurnCompleted(turnId: String, outcome: String, state: String) {
        val events = repository.events.value
        val runtime = RendererProjection.runtime(state(events), events.lastOrNull { it.type == "permission" })
        publish(
            "turn.completed",
            json(
                "conversation_id" to RENDERER_CONVERSATION,
                "session_id" to RENDERER_CONVERSATION,
                "turn_id" to turnId,
                "status" to "finished",
                "outcome" to outcome,
                "state" to state,
                "workspace" to (repository.selectedSession.value?.cwd ?: ""),
                "runtime" to runtime,
            ),
        )
        publishRuntime()
    }

    private fun state(events: List<AgentEvent>): RendererSnapshot = RendererSnapshot(
        session = repository.activeSession.value ?: repository.selectedSession.value,
        host = null,
        indexed = null,
        events = events,
        active = active(),
        loading = repository.conversationState.value == AgentConversationState.Loading,
        turnId = turn,
        cancelling = cancelling,
        hasEarlier = repository.hasEarlier.value,
        hosts = emptyList(),
        availability = emptyList(),
        epoch = synchronized(lock) { epoch },
        seq = synchronized(lock) { sequence },
        theme = theme(),
    )

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
    }
}
