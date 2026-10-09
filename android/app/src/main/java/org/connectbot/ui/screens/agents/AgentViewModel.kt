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

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.connectbot.agent.AgentConversationState
import org.connectbot.agent.AgentRepository
import org.connectbot.di.CoroutineDispatchers
import org.rmagentma.core.AgentEvent
import org.rmagentma.core.AgentKind
import org.rmagentma.core.AgentSession
import javax.inject.Inject

@HiltViewModel
class AgentViewModel @Inject constructor(
    private val repository: AgentRepository,
    private val dispatchers: CoroutineDispatchers,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val initialHostId: Long? = savedStateHandle.get<Long>("hostId")?.takeIf { it >= 0 }
    val hosts = repository.hosts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val sessions = repository.sessions
    val availability = repository.availability
    val events = repository.events
    val busy = repository.busy
    val errors = repository.errors
    val challenge = repository.challenge
    val activeSession = repository.activeSession
    val selectedSession = repository.selectedSession
    val conversationState = repository.conversationState
    val hasEarlier = repository.hasEarlier
    val hasLatest = repository.hasLatest
    private val mutableFullText = MutableStateFlow<String?>(null)
    val fullText = mutableFullText.asStateFlow()
    private val mutableReading = MutableStateFlow(false)
    val reading = mutableReading.asStateFlow()
    private val mutableOperationError = MutableStateFlow<String?>(null)
    val operationError = mutableOperationError.asStateFlow()
    private val mutableWorking = MutableStateFlow(false)
    val working = mutableWorking.asStateFlow()
    private val mutableResponding = MutableStateFlow<Set<AgentInteractionKey>>(emptySet())
    internal val responding = mutableResponding.asStateFlow()
    private val mutableSelectedHost = MutableStateFlow(selectedSession.value?.hostId ?: initialHostId)
    val selectedHost = mutableSelectedHost.asStateFlow()
    private val mutableSelectedAgent = MutableStateFlow(selectedSession.value?.agent ?: AgentKind.KIMI)
    val selectedAgent = mutableSelectedAgent.asStateFlow()
    private val mutablePendingSelection = MutableStateFlow<AgentSelection?>(null)
    internal val pendingSelection = mutablePendingSelection.asStateFlow()
    private var operationJob: Job? = null
    private var switching = false
    val visibleSession = combine(selectedSession, selectedHost, selectedAgent) { session, hostId, agent ->
        session?.takeIf { it.hostId == hostId && it.agent == agent }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), selectedSession.value)

    init {
        viewModelScope.launch(dispatchers.default) {
            combine(selectedSession, events) { _, _ -> Unit }.collect {
                mutableResponding.update { pending ->
                    pending.filterTo(mutableSetOf()) { isCurrentAgentInteraction(it, selectedSession.value, events.value) }
                }
            }
        }
    }

    private fun execute(exclusive: Boolean = false, operation: suspend () -> Unit) {
        if (exclusive && (mutableWorking.value || switching)) return
        if (exclusive) mutableWorking.value = true
        mutableOperationError.value = null
        val job = viewModelScope.launch(dispatchers.io) {
            try {
                operation()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableOperationError.value = error.localizedMessage ?: error.javaClass.simpleName
            } finally {
                if (exclusive) mutableWorking.value = false
            }
        }
        if (exclusive) operationJob = job
    }

    fun refresh(hostId: Long?) = execute(exclusive = true) { repository.refresh(hostId) }

    fun selectHost(hostId: Long?) {
        if (hostId != selectedHost.value) requestSelection(AgentSelection.Host(hostId))
    }

    fun selectAgent(agent: AgentKind) {
        if (agent == selectedAgent.value || switching) return
        val current = selectedSession.value.takeIf { conversationState.value != AgentConversationState.Closed }
        if (current != null || busy.value) {
            requestSelection(AgentSelection.Kind(agent))
        } else {
            mutableSelectedAgent.value = agent
        }
    }

    fun open(session: AgentSession) {
        if (!validAgentCwd(session.cwd) || !canOpenAgent(availability.value, session.hostId, session.agent)) return
        if (sameAgentSession(session, selectedSession.value) && conversationState.value in setOf(AgentConversationState.Loading, AgentConversationState.Ready)) return
        requestSelection(AgentSelection.Open(session))
    }

    fun newSession(hostId: Long, agent: AgentKind, cwd: String) {
        if (!validAgentCwd(cwd) || !canOpenAgent(availability.value, hostId, agent)) return
        requestSelection(AgentSelection.New(hostId, agent, cwd))
    }

    private fun requestSelection(selection: AgentSelection) {
        if (switching) return
        val current = selectedSession.value.takeIf { conversationState.value != AgentConversationState.Closed }
        if (selectionNeedsConfirmation(current, busy.value, working.value)) {
            mutablePendingSelection.value = selection
        } else {
            execute(exclusive = true) { applySelection(selection) }
        }
    }

    fun retryConversation() {
        val selected = selectedSession.value ?: return
        if (busy.value || working.value || !canOpenAgent(availability.value, selected.hostId, selected.agent)) return
        execute(exclusive = true) { repository.open(selected) }
    }

    fun loadEarlier() {
        if (mutableReading.value || !hasEarlier.value) return
        mutableReading.value = true
        execute {
            try {
                repository.loadEarlier()
            } finally {
                mutableReading.value = false
            }
        }
    }

    fun loadLatest() {
        if (mutableReading.value || !hasLatest.value) return
        mutableReading.value = true
        execute {
            try {
                repository.loadLatest()
            } finally {
                mutableReading.value = false
            }
        }
    }

    fun readFullText(event: AgentEvent) {
        if (mutableReading.value) return
        val selected = selectedSession.value
        mutableReading.value = true
        execute {
            try {
                val full = repository.fullText(event)
                if (selected === selectedSession.value) {
                    mutableFullText.value = listOf(full.text, full.raw?.toString().orEmpty()).filter { it.isNotEmpty() }.joinToString("\n\n")
                }
            } finally {
                mutableReading.value = false
            }
        }
    }

    fun closeFullText() {
        mutableFullText.value = null
    }

    fun keepConversation() {
        mutablePendingSelection.value = null
    }

    fun confirmSelection() {
        if (switching) return
        val selection = mutablePendingSelection.value ?: return
        val allowed = when (selection) {
            is AgentSelection.Open -> canOpenAgent(availability.value, selection.session.hostId, selection.session.agent)
            is AgentSelection.New -> canOpenAgent(availability.value, selection.hostId, selection.agent)
            else -> true
        }
        if (!allowed) {
            mutablePendingSelection.value = null
            return
        }
        mutablePendingSelection.value = null
        val previousJob = operationJob
        switching = true
        mutableOperationError.value = null
        viewModelScope.launch(dispatchers.io) {
            try {
                previousJob?.cancelAndJoin()
                mutableWorking.value = true
                mutableFullText.value = null
                repository.closeConversation()
                applySelection(selection)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableOperationError.value = error.localizedMessage ?: error.javaClass.simpleName
            } finally {
                mutableWorking.value = false
                switching = false
            }
        }
    }

    private suspend fun applySelection(selection: AgentSelection) {
        mutableFullText.value = null
        when (selection) {
            is AgentSelection.Host -> {
                mutableSelectedHost.value = selection.id
                repository.refresh(selection.id)
            }

            is AgentSelection.Kind -> {
                mutableSelectedAgent.value = selection.agent
            }

            is AgentSelection.Open -> {
                mutableSelectedHost.value = selection.session.hostId
                mutableSelectedAgent.value = selection.session.agent
                repository.open(selection.session)
            }

            is AgentSelection.New -> {
                mutableSelectedHost.value = selection.hostId
                mutableSelectedAgent.value = selection.agent
                repository.newSession(selection.hostId, selection.agent, selection.cwd)
            }

            AgentSelection.Close -> repository.closeConversation()
        }
    }

    fun send(text: String): Boolean {
        if (text.isBlank() || busy.value || working.value || switching || conversationState.value != AgentConversationState.Ready) return false
        execute(exclusive = true) { repository.send(text) }
        return true
    }
    fun cancel() = execute { repository.cancel() }

    internal fun respondPermission(key: AgentInteractionKey, option: String) {
        if (key.event.type != "permission" || key.event.options.none { it.id == option }) return
        respond(key) { repository.respondPermission(key.session, key.event, key.event.id, option) }
    }

    internal fun respondQuestion(key: AgentInteractionKey, answer: String) {
        if (key.event.type != "question" || !validQuestionAnswer(projectQuestion(key.event.raw), answer)) return
        respond(key) { repository.respondQuestion(key.session, key.event, key.event.id, answer) }
    }

    private fun respond(key: AgentInteractionKey, operation: suspend () -> Unit) {
        if (conversationState.value !in setOf(AgentConversationState.Loading, AgentConversationState.Ready) ||
            !isCurrentAgentInteraction(key, selectedSession.value, events.value) || key in mutableResponding.value
        ) {
            return
        }
        mutableResponding.update { it + key }
        mutableOperationError.value = null
        viewModelScope.launch(dispatchers.io) {
            try {
                if (conversationState.value !in setOf(AgentConversationState.Loading, AgentConversationState.Ready) ||
                    !isCurrentAgentInteraction(key, selectedSession.value, events.value)
                ) {
                    mutableResponding.update { it - key }
                    return@launch
                }
                operation()
            } catch (error: CancellationException) {
                mutableResponding.update { it - key }
                throw error
            } catch (error: Exception) {
                mutableResponding.update { it - key }
                if (selectedSession.value === key.session) {
                    mutableOperationError.value = error.localizedMessage ?: error.javaClass.simpleName
                }
            }
        }
    }

    fun respondChallenge(id: String, response: String?) = execute { repository.respondChallenge(id, response) }
    fun closeConversation() = requestSelection(AgentSelection.Close)
    fun disconnectAll() = execute { repository.disconnectAll() }
}
