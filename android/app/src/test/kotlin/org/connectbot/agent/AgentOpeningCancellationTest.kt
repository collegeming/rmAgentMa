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

package org.connectbot.agent

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.connectbot.data.HostRepository
import org.connectbot.di.CoroutineDispatchers
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AgentOpeningCancellationTest {
    @Test
    fun closeCancelsOpeningBeforeWaitingForTheSwitchLock() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val hosts = mock(HostRepository::class.java)
        val index = mock(AgentIndexStore::class.java)
        `when`(hosts.observeSshHosts()).thenReturn(emptyFlow())
        `when`(index.read()).thenReturn(emptyList())
        val repository = AgentRepository(
            mock(Context::class.java),
            hosts,
            mock(AgentSshPool::class.java),
            index,
            AgentChallenges(),
            CoroutineDispatchers(dispatcher, dispatcher, dispatcher),
        )
        fun field(name: String) = AgentRepository::class.java.getDeclaredField(name).apply { isAccessible = true }
        val mutex = field("switchMutex").get(repository) as Mutex
        val conversation = CoroutineScope(SupervisorJob() + dispatcher)
        field("conversationScope").set(repository, conversation)
        var released = false
        val opening = backgroundScope.launch {
            mutex.withLock {
                try {
                    awaitCancellation()
                } finally {
                    released = true
                }
            }
        }
        field("openingJob").set(repository, opening)
        runCurrent()
        withTimeout(1000) { repository.closeConversation() }
        assertThat(opening.isCancelled).isTrue()
        assertThat(released).isTrue()
        assertThat(conversation.coroutineContext[kotlinx.coroutines.Job]!!.isCancelled).isTrue()
    }
}
