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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import org.connectbot.agent.AgentConversationState
import org.connectbot.agent.AgentRepository
import org.connectbot.agent.HostAddressChange
import org.connectbot.di.CoroutineDispatchers
import org.rmagentma.core.AgentEvent
import org.rmagentma.core.AgentKind
import org.rmagentma.core.AgentSession
import java.util.concurrent.atomic.AtomicLong
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
    val refreshingHosts = repository.refreshingHosts
    val linkRecords = repository.linkRecords
    val liveLinkStates = repository.liveLinkStates
    val events = repository.events
    val busy = repository.busy
    val errors = repository.errors
    val scannedSources = repository.scannedSources
    val challenge = repository.challenge
    val activeSession = repository.activeSession
    val selectedSession = repository.selectedSession
    val conversationState = repository.conversationState
    val hasEarlier = repository.hasEarlier
    val hasLatest = repository.hasLatest
    private val mutableAddressChange = MutableStateFlow<HostAddressChange?>(null)
    val addressChange = mutableAddressChange.asStateFlow()
    private val mutableFullText = MutableStateFlow<String?>(null)
    val fullText = mutableFullText.asStateFlow()
    private val mutableReading = MutableStateFlow(false)
    val reading = mutableReading.asStateFlow()
    private val mutableOperationError = MutableStateFlow<String?>(null)
    val operationError = mutableOperationError.asStateFlow()
    private val mutableConfig = MutableStateFlow<AgentConfigUiState>(AgentConfigUiState.Closed)
    internal val config = mutableConfig.asStateFlow()
    private val mutableConfigWriting = MutableStateFlow<String?>(null)
    internal val configWriting = mutableConfigWriting.asStateFlow()
    private val mutableConfigNotice = MutableStateFlow<AgentConfigNotice?>(null)
    internal val configNotice = mutableConfigNotice.asStateFlow()
    private val configRequest = AtomicLong()
    private val configWriteToken = AtomicLong()
    private var configJob: Job? = null
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
        // A conversation opened behind this ViewModel's back (the renderer switches through the repository alone)
        // has to move the picker with it; a picker-driven open already matches here, and a cleared live session
        // leaves the picker alone.
        viewModelScope.launch(dispatchers.default) {
            activeSession.collect { active ->
                if (active == null) return@collect
                if (active.hostId != mutableSelectedHost.value) mutableSelectedHost.value = active.hostId
                if (active.agent != mutableSelectedAgent.value) mutableSelectedAgent.value = active.agent
            }
        }
        // The repository flow is hot; keep the newest change so it can still be shown after a recomposition.
        viewModelScope.launch(dispatchers.default) {
            repository.addressChanges.collect { mutableAddressChange.value = it }
        }
        viewModelScope.launch(dispatchers.default) {
            combine(selectedSession, events) { _, _ -> Unit }.collect {
                mutableResponding.update { pending ->
                    pending.filterTo(mutableSetOf()) { isCurrentAgentInteraction(it, selectedSession.value, events.value) }
                }
            }
        }
        viewModelScope.launch(dispatchers.default) {
            combine(selectedSession, conversationState) { session, state -> session to state }
                .distinctUntilChanged()
                .collect { (session, state) -> onConversationChanged(session, state) }
        }
        viewModelScope.launch(dispatchers.default) {
            events.collect { current ->
                val latest = current.lastOrNull() ?: return@collect
                if (isAgentConfigUpdate(latest.title) && conversationState.value == AgentConversationState.Ready) {
                    selectedSession.value?.let { loadConfig(it, showLoading = false) }
                }
            }
        }
    }

    private fun onConversationChanged(session: AgentSession?, state: AgentConversationState) {
        when (state) {
            AgentConversationState.Loading -> if (session != null) {
                mutableConfig.value = AgentConfigUiState.Loading
                mutableConfigNotice.value = null
            }

            AgentConversationState.Ready -> if (session != null) loadConfig(session, showLoading = true)

            AgentConversationState.Failed, AgentConversationState.Closed -> {
                mutableConfig.value = AgentConfigUiState.Closed
                mutableConfigWriting.value = null
            }
        }
    }

    /** Reads the open conversation's snapshot; [showLoading] is false for background refreshes of a shown value. */
    private fun loadConfig(target: AgentSession, showLoading: Boolean) {
        val request = configRequest.incrementAndGet()
        if (showLoading && mutableConfig.value !is AgentConfigUiState.Ready) mutableConfig.value = AgentConfigUiState.Loading
        configJob?.cancel()
        configJob = viewModelScope.launch(dispatchers.io) {
            val access = try {
                repository.config()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (isCurrentConfigTarget(target, request)) {
                    mutableConfig.value = AgentConfigUiState.Failed(error.localizedMessage ?: error.javaClass.simpleName)
                }
                return@launch
            }
            if (isCurrentConfigTarget(target, request)) mutableConfig.value = access.toUiState()
        }
    }

    private fun isCurrentConfigTarget(target: AgentSession, request: Long): Boolean = configRequest.get() == request && selectedSession.value === target &&
        conversationState.value in setOf(AgentConversationState.Loading, AgentConversationState.Ready)

    private fun isCurrentWriteTarget(target: AgentSession, token: Long): Boolean = configWriteToken.get() == token && selectedSession.value === target &&
        conversationState.value in setOf(AgentConversationState.Loading, AgentConversationState.Ready)

    private fun resetConfig() {
        configRequest.incrementAndGet()
        configWriteToken.incrementAndGet()
        configJob?.cancel()
        mutableConfig.value = AgentConfigUiState.Closed
        mutableConfigWriting.value = null
        mutableConfigNotice.value = null
    }

    fun retryConfig() {
        val session = selectedSession.value ?: return
        if (conversationState.value != AgentConversationState.Ready) {
            mutableConfig.value = AgentConfigUiState.Closed
            return
        }
        loadConfig(session, showLoading = true)
    }

    fun setConfigOption(optionId: String, value: String) {
        if (optionId.isBlank() || mutableConfigWriting.value != null) return
        val target = selectedSession.value ?: return
        val ready = mutableConfig.value as? AgentConfigUiState.Ready ?: return
        val option = ready.config.options.firstOrNull { it.id == optionId } ?: return
        if (option.values.none { it.value == value } || option.currentValue?.contentOrNull == value) return
        val token = configWriteToken.get()
        mutableConfigNotice.value = null
        mutableConfigWriting.value = optionId
        viewModelScope.launch(dispatchers.io) {
            try {
                val access = repository.setConfigOption(optionId, value)
                if (!isCurrentWriteTarget(target, token)) return@launch
                // A read that started before this result must not overwrite the newer snapshot.
                configRequest.incrementAndGet()
                mutableConfig.value = access.toUiState()
                mutableConfigNotice.value = AgentConfigNotice(agentConfigWriteOutcome(access, optionId, value))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (isCurrentWriteTarget(target, token)) {
                    // Keep the last confirmed snapshot: a failed write must not show the sent value as applied.
                    mutableConfigNotice.value = AgentConfigNotice(
                        AgentConfigWriteOutcome.FAILED,
                        error.localizedMessage ?: error.javaClass.simpleName,
                    )
                }
            } finally {
                // A conversation switch already cleared the indicator; an answer for a replaced conversation must
                // not leave it behind either, so only a write that was reset in between keeps it untouched.
                if (configWriteToken.get() == token) mutableConfigWriting.value = null
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

    fun dismissAddressChange() {
        mutableAddressChange.value = null
    }

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
        resetConfig()
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
        resetConfig()
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
