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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import org.mockito.Mockito.`when`
import org.rmagentma.core.AgentAvailability
import org.rmagentma.core.AgentKind
import org.rmagentma.core.ExecChannel
import org.rmagentma.core.HostSession
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class AgentRepositoryWorkingDirectoryTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun context(): Context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
        override fun getNoBackupFilesDir(): File = temporary.root
    }

    @Test
    fun aDirectoryTheHostDoesNotHaveFailsBeforeTheAgentStarts() = runBlocking {
        withTimeout(30000) {
            val hostId = 7L
            val context = context()
            val hosts = mock(HostRepository::class.java)
            `when`(hosts.observeSshHosts()).thenReturn(emptyFlow())
            val index = mock(AgentIndexStore::class.java)
            `when`(index.read()).thenReturn(emptyList())
            val commands = CopyOnWriteArrayList<String>()
            val pool = mock(AgentSshPool::class.java)
            `when`(pool.hostSession(anyLong(), anyBoolean())).thenReturn(DirectoryCheckHost(commands, "cwd_missing"))
            val repository = AgentRepository(
                context,
                hosts,
                pool,
                index,
                AgentChallenges(),
                AgentLinkStateStore(context, Dispatchers.IO),
                AgentDetectionStore(context, Dispatchers.IO),
                CoroutineDispatchers(Dispatchers.IO, Dispatchers.IO, Dispatchers.IO),
            )
            seedAvailability(repository, hostId)
            try {
                repository.newSession(hostId, AgentKind.ZCODE, "/gone")

                // The failure names the directory so the user can correct it, and stays a retryable failed open.
                assertThat(repository.conversationState.value).isEqualTo(AgentConversationState.Failed)
                assertThat(repository.errors.value)
                    .anyMatch { it.contains("cwd_missing") && it.contains("/gone") }

                // Only the directory check ran: no Agent process was started for the session.
                assertThat(commands).hasSize(1)
                assertThat(commands.single()).contains("[ -d ").contains("/gone").doesNotContain("app-server")

                // The cached probe verdict is untouched: the host is still known to have this Agent.
                assertThat(repository.availability.value.filter { it.kind == AgentKind.ZCODE }.single().available).isTrue()
                assertThat(repository.activeSession.value).isNull()
            } finally {
                repository.disconnectAll()
            }
        }
    }

    /** The probe cache answers for this host, so a probe would never run and no probe command reaches the host. */
    private fun seedAvailability(repository: AgentRepository, hostId: Long) {
        val field = AgentRepository::class.java.getDeclaredField("mutableAvailability").apply { isAccessible = true }

        @Suppress("UNCHECKED_CAST")
        val availability = field.get(repository) as MutableStateFlow<List<AgentAvailability>>
        availability.value = AgentKind.entries.map { kind ->
            AgentAvailability(hostId, kind, "/usr/bin/${kind.wireName}", kind == AgentKind.ZCODE, "", "1.0.0", null)
        }
    }
}

private class DirectoryCheckHost(private val commands: MutableList<String>, private val answer: String) : HostSession {
    override val hostId = 7L

    override suspend fun exec(command: String): ExecChannel {
        commands += command
        return object : ExecChannel {
            override val stdout = ByteArrayInputStream(answer.toByteArray())
            override val stdin = ByteArrayOutputStream()
            override val stderr = ByteArrayInputStream(ByteArray(0))

            override fun close() = Unit
        }
    }
}
