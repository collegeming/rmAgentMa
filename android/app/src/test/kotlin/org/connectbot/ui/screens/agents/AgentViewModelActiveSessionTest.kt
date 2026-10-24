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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.connectbot.agent.AgentConfigAccess
import org.connectbot.agent.AgentConversationState
import org.connectbot.agent.AgentRepository
import org.connectbot.di.CoroutineDispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.rmagentma.core.AgentEvent
import org.rmagentma.core.AgentKind
import org.rmagentma.core.AgentSession
import org.rmagentma.core.AgentSessionConfig
import org.robolectric.RobolectricTestRunner

/** The picker as it follows a conversation that only the repository opened, which is how the renderer switches. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AgentViewModelActiveSessionTest {
    private val dispatcher = StandardTestDispatcher()
    private val dispatchers = CoroutineDispatchers(dispatcher, dispatcher, dispatcher)
    private val selected = MutableStateFlow<AgentSession?>(null)
    private val active = MutableStateFlow<AgentSession?>(null)
    private val conversation = MutableStateFlow(AgentConversationState.Closed)
    private lateinit var repository: AgentRepository
    private lateinit var viewModel: AgentViewModel

    @Before
    fun setUp() = runTest {
        Dispatchers.setMain(dispatcher)
        repository = mock(AgentRepository::class.java)
        `when`(repository.hosts).thenReturn(emptyFlow())
        `when`(repository.addressChanges).thenReturn(MutableSharedFlow())
        `when`(repository.sessions).thenReturn(MutableStateFlow(emptyList()))
        `when`(repository.events).thenReturn(MutableStateFlow<List<AgentEvent>>(emptyList()))
        `when`(repository.busy).thenReturn(MutableStateFlow(false))
        `when`(repository.errors).thenReturn(MutableStateFlow(emptyList()))
        `when`(repository.scannedSources).thenReturn(MutableStateFlow(emptySet()))
        `when`(repository.challenge).thenReturn(MutableStateFlow(null))
        `when`(repository.activeSession).thenReturn(active)
        `when`(repository.selectedSession).thenReturn(selected)
        `when`(repository.conversationState).thenReturn(conversation)
        `when`(repository.hasEarlier).thenReturn(MutableStateFlow(false))
        `when`(repository.hasLatest).thenReturn(MutableStateFlow(false))
        `when`(repository.availability).thenReturn(MutableStateFlow(emptyList()))
        `when`(repository.refreshingHosts).thenReturn(MutableStateFlow(emptySet()))
        `when`(repository.linkRecords).thenReturn(MutableStateFlow(emptyList()))
        `when`(repository.liveLinkStates).thenReturn(MutableStateFlow(emptyMap()))
        // A conversation that reaches Ready makes the ViewModel read its configuration.
        `when`(repository.config()).thenReturn(AgentConfigAccess.Available(AgentSessionConfig()))
        viewModel = AgentViewModel(repository, dispatchers, SavedStateHandle())
        advanceUntilIdle()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun followsTheConversationTheRepositoryOpened() = runTest {
        backgroundScope.launch(dispatchers.default) { viewModel.visibleSession.collect {} }
        val renderer = session(hostId = 7, agent = AgentKind.ZCODE, id = "renderer")
        selected.value = renderer
        active.value = renderer
        advanceUntilIdle()

        assertEquals(7L, viewModel.selectedHost.value)
        assertEquals(AgentKind.ZCODE, viewModel.selectedAgent.value)
        assertEquals(renderer, viewModel.visibleSession.value)
    }

    @Test
    fun movesAScreenThatWasBuiltForAnotherHostOntoTheLiveConversation() = runTest {
        val screen = AgentViewModel(repository, dispatchers, SavedStateHandle(mapOf("hostId" to 1L)))
        advanceUntilIdle()
        assertEquals(1L, screen.selectedHost.value)

        val renderer = session(hostId = 7, agent = AgentKind.ZCODE, id = "renderer")
        selected.value = renderer
        active.value = renderer
        advanceUntilIdle()

        assertEquals(7L, screen.selectedHost.value)
        assertEquals(AgentKind.ZCODE, screen.selectedAgent.value)
    }

    @Test
    fun keepsThePickerWhenTheLiveConversationGoesAway() = runTest {
        val renderer = session(hostId = 7, agent = AgentKind.ZCODE, id = "renderer")
        selected.value = renderer
        active.value = renderer
        advanceUntilIdle()

        active.value = null
        conversation.value = AgentConversationState.Closed
        advanceUntilIdle()

        assertEquals(7L, viewModel.selectedHost.value)
        assertEquals(AgentKind.ZCODE, viewModel.selectedAgent.value)
    }

    @Test
    fun keepsTheConversationTheScreenChoseOverTheOlderLiveOne() = runTest {
        val renderer = session(hostId = 7, agent = AgentKind.ZCODE, id = "renderer")
        selected.value = renderer
        active.value = renderer
        conversation.value = AgentConversationState.Ready
        advanceUntilIdle()

        val chosen = session(hostId = 3, agent = AgentKind.KIMI, id = "chosen")
        viewModel.open(chosen)
        viewModel.confirmSelection()
        advanceUntilIdle()
        // The screen's own pick stands while the repository still reports the older live conversation.
        assertEquals(3L, viewModel.selectedHost.value)
        assertEquals(AgentKind.KIMI, viewModel.selectedAgent.value)

        selected.value = chosen
        conversation.value = AgentConversationState.Ready
        active.value = chosen
        advanceUntilIdle()

        assertEquals(3L, viewModel.selectedHost.value)
        assertEquals(AgentKind.KIMI, viewModel.selectedAgent.value)
    }

    private fun session(hostId: Long, agent: AgentKind, id: String) = AgentSession(hostId = hostId, agent = agent, sessionId = id, cwd = "/work")
}
