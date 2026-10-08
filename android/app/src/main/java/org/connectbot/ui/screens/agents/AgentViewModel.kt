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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.connectbot.agent.AgentRepository
import org.connectbot.di.CoroutineDispatchers
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
    val events = repository.events
    val busy = repository.busy
    val errors = repository.errors
    val challenge = repository.challenge
    val activeSession = repository.activeSession
    private val mutableOperationError = MutableStateFlow<String?>(null)
    val operationError = mutableOperationError.asStateFlow()
    private val mutableWorking = MutableStateFlow(false)
    val working = mutableWorking.asStateFlow()
    private val mutableResponding = MutableStateFlow<Set<AgentInteractionKey>>(emptySet())
    internal val responding = mutableResponding.asStateFlow()

    init {
        viewModelScope.launch(dispatchers.default) {
            combine(activeSession, events) { _, _ -> Unit }.collect {
                mutableResponding.update { pending ->
                    pending.filterTo(mutableSetOf()) { isCurrentAgentInteraction(it, activeSession.value, events.value) }
                }
            }
        }
    }

    private fun execute(exclusive: Boolean = false, operation: suspend () -> Unit) {
        if (exclusive && mutableWorking.value) return
        if (exclusive) mutableWorking.value = true
        mutableOperationError.value = null
        viewModelScope.launch(dispatchers.io) {
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
    }

    fun refresh(hostId: Long?) = execute(exclusive = true) { repository.refresh(hostId) }
    fun open(session: AgentSession) = execute(exclusive = true) {
        require(validAgentCwd(session.cwd))
        require(supportsStructuredConversation(session.agent))
        repository.open(session)
    }
    fun newSession(hostId: Long, agent: AgentKind, cwd: String) = execute(exclusive = true) {
        require(validAgentCwd(cwd))
        repository.newSession(hostId, agent, cwd)
    }
    fun send(text: String) {
        if (text.isBlank() || busy.value || working.value) return
        execute(exclusive = true) { repository.send(text) }
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
        if (!isCurrentAgentInteraction(key, activeSession.value, events.value) || key in mutableResponding.value) return
        mutableResponding.update { it + key }
        mutableOperationError.value = null
        viewModelScope.launch(dispatchers.io) {
            try {
                if (!isCurrentAgentInteraction(key, activeSession.value, events.value)) {
                    mutableResponding.update { it - key }
                    return@launch
                }
                operation()
            } catch (error: CancellationException) {
                mutableResponding.update { it - key }
                throw error
            } catch (error: Exception) {
                mutableResponding.update { it - key }
                if (activeSession.value === key.session) {
                    mutableOperationError.value = error.localizedMessage ?: error.javaClass.simpleName
                }
            }
        }
    }

    fun respondChallenge(id: String, response: String?) = execute { repository.respondChallenge(id, response) }
    fun closeConversation() = execute { repository.closeConversation() }
    fun disconnectAll() = execute { repository.disconnectAll() }
}
