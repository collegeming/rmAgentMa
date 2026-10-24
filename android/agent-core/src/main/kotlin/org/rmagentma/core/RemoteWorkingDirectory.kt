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

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException

/** Bound a working directory check keeps, so a silent host cannot hold a session open forever. */
const val WORKING_DIRECTORY_CHECK_TIMEOUT_MILLIS: Long = 10_000

/** Why a session working directory could not be used; the codes follow the probe code vocabulary. */
enum class WorkingDirectoryProblem(val code: String) {
    NOT_ABSOLUTE("cwd_not_absolute"),
    MISSING("cwd_missing"),
    NOT_A_DIRECTORY("cwd_not_a_directory"),
    CHECK_TIMEOUT("cwd_check_timeout"),
    CHECK_FAILED("cwd_check_failed"),
}

/**
 * A session working directory the host cannot use, reported before any Agent process is started for it, so a
 * wrong directory fails as itself instead of as an Agent that exits right after it started.
 */
class WorkingDirectoryUnreachableException(
    val cwd: String,
    val problem: WorkingDirectoryProblem,
) : IOException("Agent working directory unreachable (${problem.code}): $cwd")

/**
 * Read-only check that the working directory of a session is an absolute path the host still has as a directory.
 * It runs one bounded POSIX `test`, never writes to the host and never guesses a substitute directory. A failure
 * to reach the host at all keeps its own error: only an answer from the host is a verdict about the directory.
 */
class RemoteWorkingDirectoryCheck(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val timeoutMillis: Long = WORKING_DIRECTORY_CHECK_TIMEOUT_MILLIS,
) {
    init {
        require(timeoutMillis > 0) { "Timeout must be positive" }
    }

    suspend fun verify(host: HostSession, cwd: String) {
        if (!cwd.startsWith('/') || cwd.any { it.code < 32 || it.code == 127 }) {
            throw WorkingDirectoryUnreachableException(cwd, WorkingDirectoryProblem.NOT_ABSOLUTE)
        }
        // Opening the channel is bounded by the connection layer's own timeouts; its failure is a connection
        // failure and is reported as such rather than as a verdict about the directory.
        val channel = withContext(ioDispatcher) { host.exec(workingDirectoryCheckCommand(cwd)) }
        val answer = try {
            coroutineScope {
                val stderr = launch { runCatching { runInterruptible { drainStderr(channel.stderr) } } }
                try {
                    withTimeoutOrNull(timeoutMillis) {
                        withContext(ioDispatcher) { runInterruptible { readAnswer(channel.stdout) } }
                    }
                } catch (_: InterruptedIOException) {
                    // The bound interrupted a read that was still waiting; a cancelled caller still wins over that.
                    currentCoroutineContext().ensureActive()
                    null
                } catch (_: IOException) {
                    // The answer never arrived, so the directory keeps no verdict and the check itself failed.
                    throw WorkingDirectoryUnreachableException(cwd, WorkingDirectoryProblem.CHECK_FAILED)
                } finally {
                    stderr.cancel()
                }
            }
        } finally {
            runCatching { channel.close() }
        }
        when (answer) {
            DIRECTORY_ANSWER -> Unit
            null -> throw WorkingDirectoryUnreachableException(cwd, WorkingDirectoryProblem.CHECK_TIMEOUT)
            NOT_A_DIRECTORY_ANSWER -> throw WorkingDirectoryUnreachableException(cwd, WorkingDirectoryProblem.NOT_A_DIRECTORY)
            MISSING_ANSWER -> throw WorkingDirectoryUnreachableException(cwd, WorkingDirectoryProblem.MISSING)
            else -> throw WorkingDirectoryUnreachableException(cwd, WorkingDirectoryProblem.CHECK_FAILED)
        }
    }
}

// One POSIX test per state, printed on stdout: the command reads the path and writes nothing on the host.
internal fun workingDirectoryCheckCommand(cwd: String): String {
    require(cwd.startsWith('/')) { "Working directory must be absolute" }
    requireRemoteArgument(cwd)
    val path = ShellCommands.quote(cwd)
    val payload = "if [ -d $path ]; then printf $DIRECTORY_ANSWER; " +
        "elif [ -e $path ]; then printf $NOT_A_DIRECTORY_ANSWER; else printf $MISSING_ANSWER; fi"
    return "/bin/sh -c ${ShellCommands.quote(payload)}"
}

private const val DIRECTORY_ANSWER = "cwd_ok"
private const val MISSING_ANSWER = "cwd_missing"
private const val NOT_A_DIRECTORY_ANSWER = "cwd_not_directory"

private const val MAX_ANSWER_BYTES = 64

private fun readAnswer(input: InputStream): String {
    val buffer = ByteArray(MAX_ANSWER_BYTES)
    var read = 0
    while (read < buffer.size) {
        val count = input.read(buffer, read, buffer.size - read)
        if (count < 0) break
        read += count
    }
    return String(buffer, 0, read, Charsets.UTF_8).trim()
}
