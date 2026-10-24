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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonPrimitive
import org.connectbot.agent.AgentConfigAccess
import org.connectbot.agent.AgentConversationState
import org.connectbot.agent.AgentMessageQueue
import org.connectbot.agent.AgentRepository
import org.connectbot.di.CoroutineDispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.rmagentma.core.AgentConfigOption
import org.rmagentma.core.AgentConfigValue
import org.rmagentma.core.AgentEvent
import org.rmagentma.core.AgentKind
import org.rmagentma.core.AgentSession
import org.rmagentma.core.AgentSessionConfig
import org.robolectric.RobolectricTestRunner

/** The open conversation's configuration as the ViewModel publishes it, including what a write receipt may claim. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AgentViewModelConfigTest {
    private val dispatcher = StandardTestDispatcher()
    private val dispatchers = CoroutineDispatchers(dispatcher, dispatcher, dispatcher)
    private val selected = MutableStateFlow<AgentSession?>(null)
    private val conversation = MutableStateFlow(AgentConversationState.Closed)
    private lateinit var repository: AgentRepository
    private val messageQueue: AgentMessageQueue = mock(AgentMessageQueue::class.java)
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
        `when`(repository.activeSession).thenReturn(MutableStateFlow(null))
        `when`(repository.selectedSession).thenReturn(selected)
        `when`(repository.conversationState).thenReturn(conversation)
        `when`(repository.hasEarlier).thenReturn(MutableStateFlow(false))
        `when`(repository.hasLatest).thenReturn(MutableStateFlow(false))
        `when`(repository.availability).thenReturn(MutableStateFlow(emptyList()))
        `when`(repository.refreshingHosts).thenReturn(MutableStateFlow(emptySet()))
        `when`(repository.linkRecords).thenReturn(MutableStateFlow(emptyList()))
        `when`(repository.liveLinkStates).thenReturn(MutableStateFlow(emptyMap()))
        viewModel = AgentViewModel(repository, messageQueue, dispatchers, SavedStateHandle())
        advanceUntilIdle()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun showsTheSnapshotTheAgentConfirmedForTheOpenConversation() = runTest {
        val first = session("first")
        val firstConfig = modelConfig("a", listOf("a", "c"))
        val applied = modelConfig("c", listOf("a", "c"))
        `when`(repository.config()).thenReturn(AgentConfigAccess.Available(firstConfig))
        `when`(repository.setConfigOption("model", "c")).thenReturn(AgentConfigAccess.Available(applied))
        open(first)

        viewModel.setConfigOption("model", "c")
        advanceUntilIdle()

        assertEquals(AgentConfigUiState.Ready(applied), viewModel.config.value)
        assertEquals(AgentConfigNotice(AgentConfigWriteOutcome.CONFIRMED), viewModel.configNotice.value)
        assertNull(viewModel.configWriting.value)
    }

    @Test
    fun keepsTheLastConfirmedValueAndTheReasonWhenAWriteFails() = runTest {
        val first = session("first")
        val firstConfig = modelConfig("a", listOf("a", "c"))
        `when`(repository.config()).thenReturn(AgentConfigAccess.Available(firstConfig))
        `when`(repository.setConfigOption("model", "c")).thenThrow(IllegalStateException("Agent rejected the value"))
        open(first)

        viewModel.setConfigOption("model", "c")
        advanceUntilIdle()

        assertEquals(AgentConfigUiState.Ready(firstConfig), viewModel.config.value)
        assertEquals(AgentConfigNotice(AgentConfigWriteOutcome.FAILED, "Agent rejected the value"), viewModel.configNotice.value)
        assertNull(viewModel.configWriting.value)
    }

    @Test
    fun aWriteAnsweredAfterTheConversationChangedDoesNotReachTheNewOne() = runTest {
        val first = session("first")
        val second = session("second")
        val firstConfig = modelConfig("a", listOf("a", "c"))
        val secondConfig = modelConfig("b", listOf("b", "c"))
        `when`(repository.config()).thenAnswer {
            if (selected.value === second) AgentConfigAccess.Available(secondConfig) else AgentConfigAccess.Available(firstConfig)
        }
        `when`(repository.setConfigOption("model", "c")).thenReturn(AgentConfigAccess.Available(modelConfig("c", listOf("a", "c"))))
        open(first)

        viewModel.setConfigOption("model", "c")
        selected.value = second
        conversation.value = AgentConversationState.Loading
        conversation.value = AgentConversationState.Ready
        advanceUntilIdle()

        assertEquals(AgentConfigUiState.Ready(secondConfig), viewModel.config.value)
        assertNull(viewModel.configNotice.value)
        assertNull(viewModel.configWriting.value)
    }

    private fun TestScope.open(session: AgentSession) {
        selected.value = session
        conversation.value = AgentConversationState.Ready
        advanceUntilIdle()
    }

    private fun session(id: String) = AgentSession(hostId = 1, agent = AgentKind.KIMI, sessionId = id, cwd = "/work")

    private fun modelConfig(current: String, values: List<String>) = AgentSessionConfig(
        options = listOf(
            AgentConfigOption(
                id = "model",
                name = "Model",
                category = "model",
                type = "select",
                currentValue = JsonPrimitive(current),
                values = values.map { AgentConfigValue(it, "Model ${it.uppercase()}") },
            ),
        ),
    )
}
