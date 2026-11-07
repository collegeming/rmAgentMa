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
import org.rmagentma.core.AgentAvailability
import org.rmagentma.core.AgentKind
import org.rmagentma.core.ExecChannel
import org.rmagentma.core.HOST_OS_WINDOWS
import org.rmagentma.core.HostSession
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * What the app does with a Windows host: ZCode's desktop build has a Windows launch text, every other Agent has
 * none, and no launch happens on a host whose probe named no executable.
 */
@RunWith(AndroidJUnit4::class)
class WindowsZcodeStartTest {
    @get:Rule val temporary = TemporaryFolder()

    private val hostId = 11L
    private val executable = "E:\\SoftWare\\ZCode\\ZCode.exe"
    private val windowsHost = Host(
        id = hostId,
        nickname = "windows-box",
        protocol = "ssh",
        hostname = "192.0.2.10",
        osType = HOST_OS_WINDOWS,
        agentTarget = "native",
    )

    private fun context(): Context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
        override fun getNoBackupFilesDir(): File = temporary.root
    }

    private suspend fun newRepository(context: Context, pool: AgentSshPool): AgentRepository {
        val hosts = mock(HostRepository::class.java)
        `when`(hosts.observeSshHosts()).thenReturn(emptyFlow())
        `when`(hosts.findHostById(hostId)).thenReturn(windowsHost)
        val index = mock(AgentIndexStore::class.java)
        `when`(index.read()).thenReturn(emptyList())
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

    private fun pool(commands: MutableList<String>): AgentSshPool {
        val pool = mock(AgentSshPool::class.java)
        `when`(pool.hostSession(anyLong(), anyBoolean())).thenReturn(RecordingHost(hostId, commands))
        return pool
    }

    /**
     * A Windows ZCode conversation hands the Windows launch text to the host, and only that text: the desktop
     * installation's own command reaches the session once, unwrapped, because the target's relay wraps it again.
     * The conversation then fails on the protocol handshake this stand-in never answers, which is the real path:
     * nothing here claims a ready session before the Agent itself said so.
     */
    @Test
    fun aWindowsZcodeConversationHandsTheWindowsLaunchTextToTheHost() = runBlocking {
        withTimeout(30000) {
            val commands = mutableListOf<String>()
            val context = context()
            val repository = newRepository(context, pool(commands))
            seedAvailability(repository, executable)
            try {
                repository.newSession(hostId, AgentKind.ZCODE, "C:\\work\\project")

                assertThat(repository.conversationState.value).isEqualTo(AgentConversationState.Failed)
                // Exactly one launch reached the host, and it is this build's Windows launch text.
                assertThat(commands).hasSize(1)
                val launch = commands.single()
                assertThat(launch).contains("ELECTRON_RUN_AS_NODE")
                assertThat(launch).contains("app-server")
                assertThat(launch).contains("C:\\work\\project")
                // It is not the POSIX line this build writes for a POSIX host.
                assertThat(launch).doesNotContain("/bin/sh -c")
                assertThat(repository.activeSession.value).isNull()
            } finally {
                repository.disconnectAll()
            }
        }
    }

    /** Every other Agent keeps the refusal this build already stated, word for word. */
    @Test
    fun everyOtherAgentOnAWindowsTargetKeepsItsExistingRefusal() = runBlocking {
        withTimeout(30000) {
            for (agent in AgentKind.entries.filter { it != AgentKind.ZCODE }) {
                val commands = mutableListOf<String>()
                val context = context()
                val repository = newRepository(context, pool(commands))
                seedAvailability(repository, executable)
                try {
                    repository.newSession(hostId, agent, "C:\\work\\project")

                    assertThat(repository.conversationState.value).isEqualTo(AgentConversationState.Failed)
                    assertThat(repository.errors.value).anyMatch {
                        it.contains("${agent.wireName} cannot start on windows-native: this build has no launch command for that target")
                    }
                    assertThat(commands).isEmpty()
                } finally {
                    repository.disconnectAll()
                }
            }
        }
    }

    /** A probe that named no executable is answered as that, before any target rule is applied. */
    @Test
    fun zcodeWithNoDetectedExecutableIsRefusedAsNotInstalled() = runBlocking {
        withTimeout(30000) {
            val commands = mutableListOf<String>()
            val context = context()
            val repository = newRepository(context, pool(commands))
            seedAvailability(repository, "")
            try {
                repository.newSession(hostId, AgentKind.ZCODE, "C:\\work\\project")

                assertThat(repository.conversationState.value).isEqualTo(AgentConversationState.Failed)
                assertThat(repository.errors.value).anyMatch {
                    it.contains("zcode executable not found: the probe detected no installation")
                }
                assertThat(commands).isEmpty()
            } finally {
                repository.disconnectAll()
            }
        }
    }

    /** A Windows directory is judged by the Windows rule, so it reaches the launch decision instead of failing. */
    @Test
    fun aPosixDirectoryOnAWindowsHostIsRefusedBeforeAnyProbeLaunch() = runBlocking {
        withTimeout(30000) {
            val commands = mutableListOf<String>()
            val context = context()
            val repository = newRepository(context, pool(commands))
            seedAvailability(repository, executable)
            try {
                // The directory gate runs before the conversation is switched, so the refusal is thrown there
                // and no half-ready conversation is reported.
                assertThat(runCatching { repository.newSession(hostId, AgentKind.ZCODE, "/work/project") }.isFailure).isTrue()
                assertThat(repository.conversationState.value).isEqualTo(AgentConversationState.Closed)
                assertThat(repository.errors.value).anyMatch { it.contains("not an absolute path") }
                assertThat(commands).isEmpty()
            } finally {
                repository.disconnectAll()
            }
        }
    }

    private fun seedAvailability(repository: AgentRepository, path: String) {
        val field = AgentRepository::class.java.getDeclaredField("mutableAvailability").apply { isAccessible = true }

        @Suppress("UNCHECKED_CAST")
        val availability = field.get(repository) as MutableStateFlow<List<AgentAvailability>>
        // Every verdict is already cached, so no probe command reaches the host for any Agent in these tests.
        availability.value = AgentKind.entries.map { kind ->
            AgentAvailability(hostId, kind, path, true, "", "1.0.0", null)
        }
    }
}

/** A host session that records every command it is asked to run and never runs one. */
private class RecordingHost(private val id: Long, private val commands: MutableList<String>) : HostSession {
    override val hostId: Long get() = id

    override suspend fun exec(command: String): ExecChannel {
        commands += command
        // A channel that answers nothing: the protocol handshake this launch starts never completes, which is the
        // failure a conversation really meets when the Agent on the other end is not there.
        return object : ExecChannel {
            override val stdout: InputStream = ByteArrayInputStream(ByteArray(0))
            override val stdin: OutputStream = ByteArrayOutputStream()
            override val stderr: InputStream = ByteArrayInputStream(ByteArray(0))
            override fun close() = Unit
        }
    }
}
