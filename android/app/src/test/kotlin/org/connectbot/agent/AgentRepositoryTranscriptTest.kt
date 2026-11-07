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
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.connectbot.data.HostRepository
import org.connectbot.data.entity.Host
import org.connectbot.di.CoroutineDispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.rmagentma.core.AgentKind
import org.rmagentma.core.AgentSession
import org.rmagentma.core.ExecChannel
import org.rmagentma.core.HostSession
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The repository's one read-only transcript page: it reads the target's own host, answers the page exactly as the
 * remote did, and refuses a target this build has no reader for instead of reporting an empty history.
 */
@RunWith(AndroidJUnit4::class)
class AgentRepositoryTranscriptTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun context(): Context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
        override fun getNoBackupFilesDir(): File = temporary.root
    }

    @Test
    fun aPageIsReadFromTheHostAndAnsweredAsTheRemoteSentIt(): Unit = runBlocking {
        withTimeout(30_000) {
            val remote = RecordingHostSession(
                """{"record":"event","hostId":7,"agent":"dsh","sessionId":"s-1","cwd":"/work","type":"user","text":"你好","id":"u1","title":"","status":""}""" + "\n" +
                    """{"record":"page","nextCursor":"cursor-2","count":1}""",
            )
            val repository = repository(host(7L, "linux", "native"), remote)
            val page = repository.readTranscriptPage(
                hostId = 7L,
                session = AgentSession(hostId = 7L, agent = AgentKind.DSH, sessionId = "s-1", cwd = "/work"),
                cursor = null,
                limit = 1,
            )
            assertThat(page.error).isNull()
            assertThat(page.items.single().text).isEqualTo("你好")
            assertThat(page.nextCursor).isEqualTo("cursor-2")
            // The page really reached this host on its own session, and that channel was closed afterwards.
            assertThat(remote.commands).hasSize(1)
            assertThat(remote.commands.single()).contains("--session-id").contains("s-1")
            assertThat(remote.closed).isTrue()
        }
    }

    @Test
    fun aRemoteFailureTravelsAsThatFailureInsteadOfAnEmptyPage(): Unit = runBlocking {
        withTimeout(30_000) {
            val remote = RecordingHostSession("""{"record":"error","error":"session_not_found"}""")
            val page = repository(host(7L, "linux", "native"), remote).readTranscriptPage(
                7L,
                AgentSession(hostId = 7L, agent = AgentKind.DSH, sessionId = "s-1", cwd = "/work"),
                null,
                10,
            )
            assertThat(page.items).isEmpty()
            assertThat(page.error).isEqualTo("session_not_found")
        }
    }

    /** An Agent whose history is not a file this build names, and a target with no POSIX shell, are unsupported. */
    @Test
    fun aTargetThisBuildCannotReadAnswersUnsupported(): Unit = runBlocking {
        val remote = RecordingHostSession("")
        val windows = repository(host(7L, "windows", "windows"), remote)
        val windowsFailure = runCatching {
            windows.readTranscriptPage(7L, AgentSession(hostId = 7L, agent = AgentKind.DSH, sessionId = "s-1", cwd = "C:\\work"), null, 10)
        }.exceptionOrNull()
        assertThat(windowsFailure).isInstanceOf(UnsupportedOperationException::class.java)
        assertThat(remote.commands).isEmpty()

        val omp = repository(host(7L, "linux", "native"), remote)
        val ompFailure = runCatching {
            omp.readTranscriptPage(7L, AgentSession(hostId = 7L, agent = AgentKind.OMP, sessionId = "s-2", cwd = "/work"), null, 10)
        }.exceptionOrNull()
        assertThat(ompFailure).isInstanceOf(UnsupportedOperationException::class.java)
        assertThat(remote.commands).isEmpty()
    }

    /** A host this product cannot reach is reported as a page that did not arrive, never as an empty history. */
    @Test
    fun aHostThatCannotBeReachedIsReportedAsAnUnreadablePage(): Unit = runBlocking {
        val repository = repository(host(7L, "linux", "native"), UnreachableHostSession)
        val page = repository.readTranscriptPage(7L, AgentSession(hostId = 7L, agent = AgentKind.DSH, sessionId = "s-1", cwd = "/work"), null, 10)
        assertThat(page.items).isEmpty()
        assertThat(page.error).isEqualTo("history_transport_failed")
    }

    private fun host(id: Long, osType: String, agentTarget: String) = Host(
        id = id,
        nickname = "工作机",
        username = "colle",
        hostname = "example.test",
        osType = osType,
        agentTarget = agentTarget,
    )

    private suspend fun repository(host: Host, remote: HostSession): AgentRepository {
        val hosts = mock(HostRepository::class.java)
        `when`(hosts.observeSshHosts()).thenReturn(emptyFlow())
        `when`(hosts.findHostById(host.id)).thenReturn(host)
        val index = mock(AgentIndexStore::class.java)
        `when`(index.read()).thenReturn(emptyList())
        val pool = mock(AgentSshPool::class.java)
        `when`(pool.hostSession(anyLong(), anyBoolean())).thenReturn(remote)
        val context = context()
        return AgentRepository(
            context,
            hosts,
            pool,
            index,
            AgentChallenges(),
            AgentLinkStateStore(context, Dispatchers.IO),
            AgentDetectionStore(context, Dispatchers.IO),
            CoroutineDispatchers(Dispatchers.IO, Dispatchers.IO, Dispatchers.IO),
        )
    }
}

/** A host that answers one canned NDJSON transcript payload and remembers what it was asked to run. */
private class RecordingHostSession(private val payload: String) : HostSession {
    override val hostId = 7L
    val commands = mutableListOf<String>()
    var closed = false

    override suspend fun exec(command: String): ExecChannel {
        commands += command
        return object : ExecChannel {
            override val stdout = ByteArrayInputStream(payload.toByteArray())
            override val stdin = ByteArrayOutputStream()
            override val stderr = ByteArrayInputStream(ByteArray(0))
            override fun close() {
                closed = true
            }
        }
    }
}

/** A host this product cannot reach: every command fails the way the connection layer reports it. */
private object UnreachableHostSession : HostSession {
    override val hostId = 7L

    override suspend fun exec(command: String): ExecChannel = throw java.io.IOException("Host 7 is not connected")
}
