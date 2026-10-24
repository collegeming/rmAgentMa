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

package org.rmagentma.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicInteger

class StartupStderrTest {
    @Test
    fun agentExitingBeforeReadyCarriesABoundedSanitizedStderrTail() = runBlocking {
        withTimeout(10000) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val failure = runCatching { ZcodeDriver(scope).prepare(ExitHost(exitingStderr()), null, "/work") }.exceptionOrNull()
                assertTrue("Expected the exit to fail the session: $failure", failure is EOFException)
                val message = failure?.message.orEmpty()
                assertTrue(message.startsWith("ZCode stdout reached EOF; stderr tail: "))
                assertTrue(message.contains("line-38"))
                assertTrue(message.contains("line-39"))
                assertFalse(message, message.contains("line-37"))
                assertTrue(message.contains("red reason"))
                assertFalse(message, message.contains("\u001B"))
                assertTrue(message.contains("x".repeat(200)))
                assertFalse(message, message.contains("x".repeat(201)))
                assertTrue(message.contains("bell"))
                assertTrue("Tail must stay bounded: ${message.length}", message.length < 1500)
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun acpAgentExitingWithoutAnsweringCarriesTheSameBoundedTail() = runBlocking {
        withTimeout(10000) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val host = AnsweringExitHost("provider config is missing\n".toByteArray())
            try {
                val server = async(Dispatchers.IO) {
                    // The agent reads the activation frame and exits without answering it.
                    NdjsonReader(host.agentInput).readLine()
                    host.exit()
                }
                val failure = runCatching { AcpDriver(AgentKind.OPENCODE, scope).prepare(host, null, "/tmp") }.exceptionOrNull()
                server.await()
                assertTrue("Expected the exit to fail the session: $failure", failure is EOFException)
                assertEquals("ACP stdout reached EOF; stderr tail: provider config is missing", failure?.message)
            } finally {
                host.dispose()
                scope.cancel()
            }
        }
    }

    @Test
    fun aQuietExitKeepsThePlainTransportError() = runBlocking {
        withTimeout(10000) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val failure = runCatching { ZcodeDriver(scope).prepare(ExitHost(ByteArray(0)), null, "/work") }.exceptionOrNull()
                assertEquals("ZCode stdout reached EOF", failure?.message)
            } finally {
                scope.cancel()
            }
        }
    }
}

private class StaticChannel(private val diagnostics: ByteArray, private val hostStdin: ByteArrayOutputStream, private val closes: AtomicInteger) : ExecChannel {
    override val stdout: InputStream = ByteArrayInputStream(ByteArray(0))
    override val stdin = hostStdin
    override val stderr: InputStream = ByteArrayInputStream(diagnostics)

    override fun close() {
        closes.incrementAndGet()
    }
}

/** A host whose Agent writes its diagnostics to stderr and then exits without answering anything. */
private class ExitHost(private val diagnostics: ByteArray) : HostSession {
    override val hostId = 42L
    private val stdin = ByteArrayOutputStream()
    private val closes = AtomicInteger()

    override suspend fun exec(command: String): ExecChannel = StaticChannel(diagnostics, stdin, closes)
}

/** A host whose Agent exits only after it read the first frame, as an Agent that rejects its own startup does. */
private class AnsweringExitHost(diagnostics: ByteArray) : HostSession {
    override val hostId = 42L
    private val stdout = PipedInputStream(65536)
    private val agentOutput = PipedOutputStream(stdout)
    val agentInput = PipedInputStream(65536)
    private val channel = object : ExecChannel {
        override val stdout: InputStream = this@AnsweringExitHost.stdout
        override val stdin = PipedOutputStream(this@AnsweringExitHost.agentInput)
        override val stderr: InputStream = ByteArrayInputStream(diagnostics)

        override fun close() = Unit
    }

    override suspend fun exec(command: String): ExecChannel = channel

    fun exit() = agentOutput.close()

    fun dispose() {
        listOf(agentOutput, channel.stdin, stdout, agentInput).forEach { runCatching { it.close() } }
    }
}

private fun exitingStderr(): ByteArray {
    val text = StringBuilder()
    for (index in 0 until 40) text.append("line-$index\n")
    text.append("\u001B[31mred reason\u001B[0m\n")
    text.append("x".repeat(5000)).append('\n')
    text.append("\u0007bell")
    return text.toString().toByteArray(Charsets.UTF_8)
}
