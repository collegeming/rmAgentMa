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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.rmagentma.core.AgentKind
import org.rmagentma.core.AgentSession
import org.rmagentma.core.ExecChannel
import org.rmagentma.core.HostSession
import org.rmagentma.core.RemoteTarget
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * The existence check reads the Agent's own list and nothing else: it starts no conversation of its own, sends
 * no prompt, and answers unknown - with the reason - for a list it could not read.
 */
class AgentRecoveryCheckTest {
    private val session = AgentSession(hostId = 7L, agent = AgentKind.KIMI, sessionId = "s-1", cwd = "/work")
    private val commands = mutableListOf<String>()

    @Test
    fun aSessionTheAgentsOwnListNamesIsAttached() {
        runBlocking {
            val answer = withTimeout(10_000) {
                AgentRecoveryCheck(Dispatchers.IO).verify(host("""{"agent":"kimi","sessionId":"s-1","cwd":"/work"}"""), session)
            }
            assertThat(answer.state).isEqualTo(AgentRecoveryState.ATTACHED)
            assertThat(answer.note).isNull()
            // The session's own list is what was read: no prompt, no resume and no second conversation.
            assertThat(commands).hasSize(1)
            assertThat(commands.single()).contains("--agent").doesNotContain("session/prompt", "app-server")
        }
    }

    @Test
    fun aSessionACompleteListDoesNotNameIsGone() {
        runBlocking {
            val answer = withTimeout(10_000) {
                AgentRecoveryCheck(Dispatchers.IO).verify(host("""{"agent":"kimi","sessionId":"s-2","cwd":"/work"}"""), session)
            }
            assertThat(answer.state).isEqualTo(AgentRecoveryState.GONE)
            assertThat(answer.note).contains("does not hold this one")
        }
    }

    @Test
    fun aListThatCouldNotBeReadIsUnknownWithItsOwnReason() {
        runBlocking {
            val answer = withTimeout(10_000) {
                AgentRecoveryCheck(Dispatchers.IO).verify(
                    host("""{"level":"error","agent":"kimi","message":"private-content"}"""),
                    session,
                )
            }
            // A listing that failed is never answered as a session that is gone, and its own text stays out.
            assertThat(answer.state).isEqualTo(AgentRecoveryState.UNKNOWN)
            assertThat(answer.note).isNotNull()
            assertThat(answer.note).doesNotContain("private-content")
        }
    }

    @Test
    fun aListThatStoppedAtItsBoundIsUnknownRatherThanGone() {
        runBlocking {
            val answer = withTimeout(10_000) {
                AgentRecoveryCheck(Dispatchers.IO).verify(
                    host(
                        """
                        {"agent":"kimi","sessionId":"s-2","cwd":"/work"}
                        {"agent":"kimi","sessionId":"s-3","cwd":"/work"}
                        """.trimIndent(),
                    ),
                    session,
                    limit = 2,
                )
            }
            assertThat(answer.state).isEqualTo(AgentRecoveryState.UNKNOWN)
            assertThat(answer.note).contains("bound of 2 sessions")
        }
    }

    @Test
    fun aWindowsTargetIsCheckedThroughItsOwnRelayToo() {
        runBlocking {
            val answer = withTimeout(10_000) {
                AgentRecoveryCheck(Dispatchers.IO).verify(
                    host("""{"agent":"zcode","sessionId":"s-1","cwd":"C:\\work"}""" + "\n{\"level\":\"complete\"}"),
                    AgentSession(hostId = 7L, agent = AgentKind.ZCODE, sessionId = "s-1", cwd = "C:\\work"),
                    target = RemoteTarget.WindowsNative,
                )
            }
            // A Windows host lists its sessions now, so its verdict is the one its own list answered.
            assertThat(answer.state).isEqualTo(AgentRecoveryState.ATTACHED)
            assertThat(commands.single()).contains("py -3").doesNotContain("/bin/sh")
        }
    }

    @Test
    fun aWindowsTargetWithoutPythonIsUnknownWithItsOwnReasonInsteadOfGone() {
        runBlocking {
            val answer = withTimeout(10_000) {
                AgentRecoveryCheck(Dispatchers.IO).verify(
                    host("{\"level\":\"error\",\"category\":\"py_missing\"}\n{\"level\":\"complete\"}"),
                    AgentSession(hostId = 7L, agent = AgentKind.ZCODE, sessionId = "s-1", cwd = "C:\\work"),
                    target = RemoteTarget.WindowsNative,
                )
            }
            // A host this build could not read is never answered as a session that is gone.
            assertThat(answer.state).isEqualTo(AgentRecoveryState.UNKNOWN)
            assertThat(answer.note).contains("Python launcher")
        }
    }

    @Test
    fun anOmpSessionOnATargetWithoutPosixCommandsIsUnknownAndStartsNoAgent() {
        runBlocking {
            val answer = withTimeout(10_000) {
                AgentRecoveryCheck(Dispatchers.IO).verify(
                    // OMP has no ACP channel this target could open, so reaching the host here would be a failure.
                    object : HostSession {
                        override val hostId = 7L
                        override suspend fun exec(command: String): ExecChannel = throw AssertionError("No command may run for an OMP session on a Windows target")
                    },
                    AgentSession(hostId = 7L, agent = AgentKind.OMP, sessionId = "s-1", cwd = "C:\\work"),
                    target = RemoteTarget.WindowsNative,
                )
            }
            assertThat(answer.state).isEqualTo(AgentRecoveryState.UNKNOWN)
            assertThat(answer.note).contains("cannot read the session list")
        }
    }

    private fun host(output: String) = object : HostSession {
        override val hostId = 7L

        override suspend fun exec(command: String): ExecChannel {
            commands += command
            return object : ExecChannel {
                override val stdout = ByteArrayInputStream(output.toByteArray())
                override val stdin = ByteArrayOutputStream()
                override val stderr = ByteArrayInputStream(ByteArray(0))

                override fun close() = Unit
            }
        }
    }
}
