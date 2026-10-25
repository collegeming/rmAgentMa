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
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.connectbot.data.HostRepository
import org.connectbot.di.CoroutineDispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.rmagentma.core.AgentEvent
import org.rmagentma.core.AgentKind
import org.rmagentma.core.AgentSession
import java.io.File

/** What this app answers about one conversation's stored history, its cleanup, and a session it cannot place. */
@RunWith(AndroidJUnit4::class)
class AgentRepositoryHistoryTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun context(): Context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
        override fun getNoBackupFilesDir(): File = temporary.root
    }

    private suspend fun repository(context: Context, dispatcher: CoroutineDispatcher, pool: AgentSshPool): AgentRepository {
        val hosts = mock(HostRepository::class.java)
        `when`(hosts.observeSshHosts()).thenReturn(emptyFlow())
        val index = mock(AgentIndexStore::class.java)
        `when`(index.read()).thenReturn(emptyList())
        return AgentRepository(
            context,
            hosts,
            pool,
            index,
            AgentChallenges(),
            AgentLinkStateStore(context, dispatcher),
            AgentDetectionStore(context, dispatcher),
            CoroutineDispatchers(dispatcher, dispatcher, dispatcher),
        )
    }

    @Test
    fun historyStatusAnswersUnavailableWhileNoConversationIsOpen() = runTest {
        val repository = repository(context(), StandardTestDispatcher(testScheduler), mock(AgentSshPool::class.java))
        val status = repository.historyStatus()
        assertThat(status.state).isEqualTo(HISTORY_UNAVAILABLE)
        assertThat(status.note).isNotNull()
    }

    @Test
    fun recoveryStateAnswersUnknownWhileNoConversationIsOpen() = runTest {
        val repository = repository(context(), StandardTestDispatcher(testScheduler), mock(AgentSshPool::class.java))
        val recovery = repository.recoveryState()
        assertThat(recovery.state).isEqualTo(AgentRecoveryState.UNKNOWN)
        assertThat(recovery.note).isNotNull()
    }

    @Test
    fun clearHistoryRemovesEveryStoredSessionAndAnswersHowManyWereCleared() = runTest {
        val context = context()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val stored = listOf(1L to "s-1", 2L to "s-2").map { (hostId, sessionId) ->
            val store = AgentEventHistory(
                directory = File(context.noBackupFilesDir, "agent-history"),
                name = AgentEventHistory.storeName(hostId, AgentKind.KIMI, sessionId),
                source = HISTORY_SOURCE,
            )
            store.attach()
            store.append(AgentEvent("content", "answer", id = "m1"))
            store.markComplete()
            store.close()
            sessionId
        }
        assertThat(stored).hasSize(2)
        // The cleanup is the user's own action and removes history pages alone: every other stored file stays.
        val neighbour = File(context.noBackupFilesDir, "agent-session-index-v1.json").apply { writeText("[]") }

        val repository = repository(context, dispatcher, mock(AgentSshPool::class.java))
        assertThat(repository.clearHistory()).isEqualTo(2)
        assertThat(File(context.noBackupFilesDir, "agent-history").listFiles()).isEmpty()
        assertThat(neighbour.readText()).isEqualTo("[]")
        // Nothing is left to clean, and nothing is claimed that did not happen.
        assertThat(repository.clearHistory()).isZero()
    }

    @Test
    fun anOpenShowsTheStoredPagesAndADroppedLinkKeepsThemInsteadOfClearingThem() = runTest {
        val context = context()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val session = AgentSession(hostId = 1L, agent = AgentKind.KIMI, sessionId = "s-1", cwd = "/work")
        val store = AgentEventHistory(
            directory = File(context.noBackupFilesDir, "agent-history"),
            name = AgentEventHistory.storeName(1L, AgentKind.KIMI, "s-1"),
            source = HISTORY_SOURCE,
        )
        store.attach()
        store.append(AgentEvent("content", "stored answer", id = "m1"))
        store.markComplete()
        store.close()

        val repository = repository(context, dispatcher, mock(AgentSshPool::class.java))
        attachHistory(repository, session)
        assertThat(repository.events.value.map { it.text }).containsExactly("stored answer")
        assertThat(repository.historyStatus().state).isEqualTo(HISTORY_COMPLETE)
        assertThat(repository.hasEarlier.value).isFalse()

        // A link that drops while this conversation is open: its pages stay exactly where they are, and this
        // history reports itself incomplete with the reason instead of being emptied.
        failConversation(repository, java.io.IOException("Agent connection closed"))
        assertThat(repository.events.value.map { it.text }).containsExactly("stored answer")
        val status = repository.historyStatus()
        assertThat(status.state).isEqualTo(HISTORY_PARTIAL)
        assertThat(status.note).contains("Agent connection closed")
        assertThat(File(context.noBackupFilesDir, "agent-history").listFiles()).isNotEmpty()
    }

    /** Binds a conversation the way an open does, without driving a driver this test does not need. */
    private fun attachHistory(repository: AgentRepository, session: AgentSession) {
        val method = AgentRepository::class.java.getDeclaredMethod("attachHistory", AgentSession::class.java)
        method.isAccessible = true
        method.invoke(repository, session)
    }

    private fun failConversation(repository: AgentRepository, error: Exception) {
        val method = AgentRepository::class.java.getDeclaredMethod(
            "failConversation",
            Long::class.javaPrimitiveType,
            Exception::class.java,
        )
        method.isAccessible = true
        method.invoke(repository, 0L, error)
    }

    private companion object {
        /** The source of a stored page the repository itself writes, so it is read back as its own. */
        const val HISTORY_SOURCE = "agent-history:kimi"
    }
}

    @Test
    fun aConversationWithoutAKnownDirectoryIsRefusedWithoutReachingTheHost() = runTest {
        val pool = mock(AgentSshPool::class.java)
        val repository = repository(context(), StandardTestDispatcher(testScheduler), pool)
        val session = AgentSession(hostId = 7L, agent = AgentKind.KIMI, sessionId = "s-1", cwd = "relative/path")

        val refusedOpen = runCatching { repository.open(session) }.exceptionOrNull()
        assertThat(refusedOpen).isInstanceOf(AgentDirectoryUnknownException::class.java)
        assertThat(refusedOpen).hasMessageContaining("not an absolute path")
        assertThat(repository.activeSession.value).isNull()
        verify(pool, never()).hostSession(anyLong(), anyBoolean())

        // Starting a conversation beside an unknown directory is refused the same way.
        val refusedNew = runCatching { repository.newSession(7L, AgentKind.KIMI, "") }.exceptionOrNull()
        assertThat(refusedNew).isInstanceOf(AgentDirectoryUnknownException::class.java)
        assertThat((refusedNew as AgentDirectoryUnknownException).reason).contains("no working directory")
        verify(pool, never()).hostSession(anyLong(), anyBoolean())
    }
}
