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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RemoteWorkingDirectoryTest {
    @Test
    fun existingDirectoryIsAcceptedAndTheCheckOnlyReadsThePath() = runBlocking {
        withTimeout(10000) {
            val cwd = "/work dir/项目 目录"
            val host = CheckHost(answer = "cwd_ok")
            RemoteWorkingDirectoryCheck(Dispatchers.IO).verify(host, cwd)
            val command = host.commands.single()
            assertTrue(command.startsWith("/bin/sh -c "))
            assertTrue(command.contains("[ -d "))
            assertTrue(command.contains("[ -e "))
            assertTrue(command.contains(cwd))
            for (write in listOf(">", "rm ", "mkdir", "touch", "tee", "chmod")) {
                assertFalse(command, command.contains(write))
            }
            assertEquals(0, host.channel.stdin.size())
            assertTrue(host.channel.closes.get() > 0)
            host.channel.awaitClosed()
        }
    }

    @Test
    fun missingDirectoryReportsItsOwnReason() = runBlocking {
        withTimeout(10000) {
            val host = CheckHost(answer = "cwd_missing")
            val failure = runCatching { RemoteWorkingDirectoryCheck(Dispatchers.IO).verify(host, "/gone") }.exceptionOrNull()
            assertTrue(failure is WorkingDirectoryUnreachableException)
            assertEquals(WorkingDirectoryProblem.MISSING, (failure as WorkingDirectoryUnreachableException).problem)
            assertEquals("/gone", failure.cwd)
            assertEquals("Agent working directory unreachable (cwd_missing): /gone", failure.message)
        }
    }

    @Test
    fun fileOnThePathIsNotADirectory() = runBlocking {
        withTimeout(10000) {
            val host = CheckHost(answer = "cwd_not_directory")
            val failure = runCatching { RemoteWorkingDirectoryCheck(Dispatchers.IO).verify(host, "/work/file") }.exceptionOrNull()
            assertEquals(WorkingDirectoryProblem.NOT_A_DIRECTORY, (failure as WorkingDirectoryUnreachableException).problem)
        }
    }

    @Test
    fun directoryThatIsNotAbsoluteIsRejectedWithoutTouchingTheHost() = runBlocking {
        withTimeout(10000) {
            for (cwd in listOf("work", "", "/work\nbad")) {
                val host = CheckHost(answer = "cwd_ok")
                val failure = runCatching { RemoteWorkingDirectoryCheck(Dispatchers.IO).verify(host, cwd) }.exceptionOrNull()
                assertEquals(cwd, WorkingDirectoryProblem.NOT_ABSOLUTE, (failure as WorkingDirectoryUnreachableException).problem)
                assertTrue(host.commands.isEmpty())
            }
        }
    }

    @Test
    fun unansweredCheckFailsAsATimeoutAndClosesTheChannel() = runBlocking {
        withTimeout(10000) {
            val host = CheckHost(blockAnswer = true)
            val failure = runCatching {
                RemoteWorkingDirectoryCheck(Dispatchers.IO, timeoutMillis = 200).verify(host, "/silent")
            }.exceptionOrNull()
            assertEquals(WorkingDirectoryProblem.CHECK_TIMEOUT, (failure as WorkingDirectoryUnreachableException).problem)
            assertTrue(failure.message.orEmpty().contains("cwd_check_timeout"))
            host.channel.awaitClosed()
        }
    }

    @Test
    fun nonsenseAnswerIsAFailureInsteadOfAnAcceptedDirectory() = runBlocking {
        withTimeout(10000) {
            for (answer in listOf("", "no such command\n", "cwd_ok.exe")) {
                val host = CheckHost(answer = answer)
                val failure = runCatching { RemoteWorkingDirectoryCheck(Dispatchers.IO).verify(host, "/work") }.exceptionOrNull()
                assertEquals(answer, WorkingDirectoryProblem.CHECK_FAILED, (failure as WorkingDirectoryUnreachableException).problem)
            }
        }
    }

    @Test
    fun unreachableHostKeepsItsOwnConnectionError() = runBlocking {
        withTimeout(10000) {
            val host = CheckHost(execFailure = IOException("Host 42 was deleted"))
            val failure = runCatching { RemoteWorkingDirectoryCheck(Dispatchers.IO).verify(host, "/work") }.exceptionOrNull()
            assertEquals("Host 42 was deleted", failure?.message)
            assertFalse(failure is WorkingDirectoryUnreachableException)
        }
    }
}

private class CheckHost(answer: String = "", blockAnswer: Boolean = false, private val execFailure: IOException? = null) : HostSession {
    override val hostId = 42L
    val commands = mutableListOf<String>()
    val channel = CheckChannel(answer, blockAnswer)

    override suspend fun exec(command: String): ExecChannel {
        execFailure?.let { throw it }
        commands += command
        return channel
    }
}

private class CheckChannel(answer: String, blockAnswer: Boolean) : ExecChannel {
    override val stdout: InputStream = if (blockAnswer) SilentInput() else ByteArrayInputStream(answer.toByteArray())
    override val stdin = ByteArrayOutputStream()
    override val stderr: InputStream = ByteArrayInputStream(byteArrayOf())
    val closes = AtomicInteger()
    private val closed = CompletableDeferred<Unit>()

    suspend fun awaitClosed() = withTimeout(5000) { closed.await() }

    override fun close() {
        closes.incrementAndGet()
        (stdout as? SilentInput)?.release()
        listOf(stdout, stdin, stderr).forEach { runCatching { it.close() } }
        closed.complete(Unit)
    }
}

/** Never answers on its own, so a check without a bound would block here forever. */
private class SilentInput : InputStream() {
    private val released = CountDownLatch(1)

    fun release() = released.countDown()

    override fun read(): Int {
        try {
            released.await(30, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            throw InterruptedIOException("Check was interrupted")
        }
        return -1
    }
}
