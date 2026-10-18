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
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.connectbot.data.HostRepository
import org.connectbot.di.CoroutineDispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File

@RunWith(AndroidJUnit4::class)
class AgentRepositoryLinkStateTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun context(): Context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
        override fun getNoBackupFilesDir(): File = temporary.root
    }

    private suspend fun repository(context: Context, dispatcher: CoroutineDispatcher, pool: AgentSshPool, store: AgentLinkStateStore): AgentRepository {
        val hosts = mock(HostRepository::class.java)
        `when`(hosts.observeSshHosts()).thenReturn(emptyFlow())
        val index = mock(AgentIndexStore::class.java)
        `when`(index.read()).thenReturn(emptyList())
        return AgentRepository(context, hosts, pool, index, AgentChallenges(), store, CoroutineDispatchers(dispatcher, dispatcher, dispatcher))
    }

    private suspend fun pool(statuses: List<AgentLinkStatus>): AgentSshPool {
        val pool = mock(AgentSshPool::class.java)
        `when`(pool.linkStatus()).thenReturn(statuses)
        return pool
    }

    @Test
    fun recordsLiveFactsAndReplaysThemAsHistoryAfterARestart() = runTest {
        val context = context()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = AgentLinkStateStore(context, dispatcher)
        val running = repository(context, dispatcher, pool(listOf(AgentLinkStatus(1L, AgentLinkState.CONNECTED, 0))), store)
        running.persistLinkStates()
        val recorded = store.all().single()
        assertThat(recorded.hostId).isEqualTo(1L)
        assertThat(recorded.agent).isNull()
        assertThat(recorded.state).isEqualTo(AgentLinkState.CONNECTED)
        assertThat(recorded.updatedAtMillis).isPositive()

        val restarted = repository(context, dispatcher, mock(AgentSshPool::class.java), store)
        advanceUntilIdle()
        assertThat(restarted.liveLinkStates.value).isEmpty()
        assertThat(restarted.linkRecords.value.map { it.state }).containsExactly(AgentLinkState.CONNECTED)
    }

    @Test
    fun keepsTheAttemptCountOfABackoffRetryAndTheBlockedReason() = runTest {
        val context = context()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = AgentLinkStateStore(context, dispatcher)
        val pool = pool(listOf(AgentLinkStatus(7L, AgentLinkState.RETRYING, 3), AgentLinkStatus(8L, AgentLinkState.NEEDS_USER, 0)))
        repository(context, dispatcher, pool, store).persistLinkStates()
        val rows = store.all()
        assertThat(rows.map { it.hostId }).containsExactlyInAnyOrder(7L, 8L)
        assertThat(rows.first { it.hostId == 7L }.state).isEqualTo(AgentLinkState.RETRYING)
        assertThat(rows.first { it.hostId == 7L }.attempts).isEqualTo(3)
        assertThat(rows.first { it.hostId == 8L }.state).isEqualTo(AgentLinkState.NEEDS_USER)
    }

    @Test
    fun aHostThePoolCannotSpeakAboutKeepsNoLiveState() = runTest {
        val context = context()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = AgentLinkStateStore(context, dispatcher)
        store.record(AgentLinkRecord(4L, null, AgentLinkState.CONNECTED, 0, 1_000))
        val repository = repository(context, dispatcher, pool(emptyList()), store)
        advanceUntilIdle()
        assertThat(repository.linkRecords.value.map { it.hostId }).containsExactly(4L)
        repository.persistLinkStates()
        assertThat(repository.liveLinkStates.value).isEmpty()
        assertThat(repository.linkRecords.value.map { it.state }).containsExactly(AgentLinkState.CONNECTED)
    }
}
