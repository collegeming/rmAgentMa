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
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.assertj.core.api.Assertions.assertThat
import org.connectbot.agent.AgentConversationState
import org.connectbot.agent.AgentDraftStore
import org.connectbot.agent.AgentIndexStore
import org.connectbot.agent.AgentRepository
import org.connectbot.data.HostRepository
import org.connectbot.di.CoroutineDispatchers
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.rmagentma.core.AgentEvent
import org.rmagentma.core.AgentKind
import org.rmagentma.core.AgentSession

/** The published runtime generation of the live source: a switch must start a new one, not continue the old. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AgentRendererSourceTest {
    private val dispatcher = StandardTestDispatcher()
    private val dispatchers = CoroutineDispatchers(dispatcher, dispatcher, dispatcher)
    private val active = MutableStateFlow<AgentSession?>(null)
    private val selected = MutableStateFlow<AgentSession?>(null)
    private lateinit var repository: AgentRepository
    private lateinit var source: AgentRendererSource

    @Before
    fun setUp() = runTest {
        Dispatchers.setMain(dispatcher)
        repository = mock(AgentRepository::class.java)
        `when`(repository.activeSession).thenReturn(active)
        `when`(repository.selectedSession).thenReturn(selected)
        `when`(repository.events).thenReturn(MutableStateFlow<List<AgentEvent>>(emptyList()))
        `when`(repository.busy).thenReturn(MutableStateFlow(false))
        `when`(repository.errors).thenReturn(MutableStateFlow(emptyList()))
        `when`(repository.conversationState).thenReturn(MutableStateFlow(AgentConversationState.Closed))
        `when`(repository.hasEarlier).thenReturn(MutableStateFlow(false))
        `when`(repository.availability).thenReturn(MutableStateFlow(emptyList()))
        val hosts = mock(HostRepository::class.java)
        `when`(hosts.getSshHosts()).thenReturn(emptyList())
        val index = mock(AgentIndexStore::class.java)
        `when`(index.read()).thenReturn(emptyList())
        val drafts = mock(AgentDraftStore::class.java)
        val preferences = mock(SharedPreferences::class.java)
        source = AgentRendererSource(repository, hosts, index, drafts, preferences, dispatchers)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun switchingSessionStartsANewGenerationThatDropsThePreviousWindow() = runTest {
        val a = AgentSession(hostId = 7, agent = AgentKind.KIMI, sessionId = "session-a", cwd = "/work/a")
        val b = AgentSession(hostId = 7, agent = AgentKind.DSH, sessionId = "session-b", cwd = "/work/b")
        selected.value = a
        active.value = a
        val first = source.snapshot()
        assertThat(source.conversationId()).isEqualTo(RendererConversationId.of(a))

        // The repository clears the active session and selects the replacement in one state change.
        active.value = null
        selected.value = b
        val switched = source.snapshot()
        assertThat(switched.epoch).isNotEqualTo(first.epoch)
        assertThat(switched.seq).isZero()
        assertThat(source.conversationId()).isEqualTo(RendererConversationId.of(b))

        // Reading the same session again keeps one generation; only a real switch starts another.
        active.value = b
        assertThat(source.snapshot().epoch).isEqualTo(switched.epoch)
    }
}
