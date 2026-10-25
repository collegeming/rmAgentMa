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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.io.InputStream
import java.util.Base64
import java.util.UUID

/** Bound one write command keeps, so a silent host cannot hold an upload open forever. */
const val REMOTE_WRITE_TIMEOUT_MILLIS: Long = 20_000

/** One payload travels in chunks this size: base64 of this many bytes still fits one command line. */
const val REMOTE_WRITE_CHUNK_BYTES: Int = 12_288

/** The largest single file one write carries, which is the bound the upload protocol states. */
const val REMOTE_WRITE_MAX_BYTES: Long = 8L * 1024 * 1024

/** How many suffixed names one write tries before it reports the name as taken. */
private const val REMOTE_WRITE_NAME_ATTEMPTS = 20

/** Why one remote file write did not complete; the code travels with the failure. */
enum class RemoteWriteProblem(val code: String) {
    DIRECTORY_MISSING("write_directory_missing"),
    NAME_TAKEN("write_name_taken"),
    NOT_WRITABLE("write_not_writable"),
    INCOMPLETE("write_incomplete"),
    TIMEOUT("write_timeout"),
    FAILED("write_failed"),
}

/** A write this app could not complete on the host, named by the step that failed. */
class RemoteWriteFailedException(val problem: RemoteWriteProblem) :
    IOException("Remote file write failed (${problem.code})")

/**
 * Writes one payload into a directory on the host over the host session every other remote command uses, as a
 * sequence of bounded commands: the directory is tested, a free name is chosen, the payload is appended in base64
 * chunks, and one last command moves the finished file into place.
 *
 * A file the host already holds is never replaced: each candidate name is tested once, the name changes to a
 * suffixed one while a candidate is taken, and the name is tested again before the move. Every step is bounded by
 * [timeoutMillis], and a step that fails removes the half-written file it created, so a failed write leaves the
 * directory as it found it.
 *
 * The commands are POSIX shell text, so this writer addresses a host whose target runs them; a target that does
 * not run POSIX commands is refused by the caller before a write starts.
 */
class RemoteFileWriter(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val timeoutMillis: Long = REMOTE_WRITE_TIMEOUT_MILLIS,
    private val chunkBytes: Int = REMOTE_WRITE_CHUNK_BYTES,
) {
    init {
        require(timeoutMillis > 0) { "Timeout must be positive" }
        require(chunkBytes in 1..REMOTE_WRITE_CHUNK_BYTES) { "Chunk size is out of range" }
    }

    /** Writes [content] as [name] inside [directory] and answers the absolute path it ended up at. */
    suspend fun write(host: HostSession, directory: String, name: String, content: ByteArray): String = withContext(ioDispatcher) {
        require(directory.startsWith('/')) { "Remote directory must be absolute" }
        requireRemoteArgument(directory)
        require(content.size.toLong() <= REMOTE_WRITE_MAX_BYTES) { "Remote write exceeds $REMOTE_WRITE_MAX_BYTES bytes" }
        val fileName = requireRemoteFileName(name)
        val directoryPath = directory.trimEnd('/')
        val temporary = "$directoryPath/.rmagentma-${UUID.randomUUID()}.part"
        try {
            if (answer(host, "if [ -d ${ShellCommands.quote(directoryPath)} ]; then printf %s $READY; else printf %s $DIRECTORY_MISSING; fi") != READY) {
                throw RemoteWriteFailedException(RemoteWriteProblem.DIRECTORY_MISSING)
            }
            val target = freeName(host, directoryPath, fileName)
            if (answer(host, "if : > ${ShellCommands.quote(temporary)}; then printf %s $READY; else printf %s $FAILED; fi") != READY) {
                throw RemoteWriteFailedException(RemoteWriteProblem.NOT_WRITABLE)
            }
            var offset = 0
            while (offset < content.size) {
                val length = minOf(chunkBytes, content.size - offset)
                val encoded = Base64.getEncoder().encodeToString(content.copyOfRange(offset, offset + length))
                val appended = answer(host, "if printf %s ${ShellCommands.quote(encoded)} | base64 -d >> ${ShellCommands.quote(temporary)}; " +
                    "then wc -c < ${ShellCommands.quote(temporary)}; else printf %s $FAILED; fi")
                if (appended.toLongOrNull() != (offset + length).toLong()) {
                    throw RemoteWriteFailedException(if (appended == FAILED) RemoteWriteProblem.FAILED else RemoteWriteProblem.INCOMPLETE)
                }
                offset += length
            }
            // The name was free when it was chosen; testing it once more here is what keeps a file that appeared
            // in between — a race with the user's own work — from being replaced by this write.
            val moved = answer(host, "if [ -e ${ShellCommands.quote(target)} ]; then printf %s $EXISTS; " +
                "elif mv ${ShellCommands.quote(temporary)} ${ShellCommands.quote(target)}; then printf %s $READY; else printf %s $FAILED; fi")
            if (moved != READY) {
                throw RemoteWriteFailedException(if (moved == EXISTS) RemoteWriteProblem.NAME_TAKEN else RemoteWriteProblem.FAILED)
            }
            target
        } catch (e: RemoteWriteFailedException) {
            discard(host, temporary)
            throw e
        } catch (e: Exception) {
            discard(host, temporary)
            throw RemoteWriteFailedException(RemoteWriteProblem.FAILED).also { it.initCause(e) }
        }
    }

    /** The first name the directory does not hold yet: the requested one, then its suffixed variants. */
    private suspend fun freeName(host: HostSession, directory: String, name: String): String {
        for (attempt in 0 until REMOTE_WRITE_NAME_ATTEMPTS) {
            val candidate = "$directory/${suffixed(name, attempt)}"
            val verdict = answer(host, "if [ -e ${ShellCommands.quote(candidate)} ]; then printf %s $EXISTS; else printf %s $READY; fi")
            if (verdict == READY) return candidate
            if (verdict != EXISTS) throw RemoteWriteFailedException(RemoteWriteProblem.FAILED)
        }
        throw RemoteWriteFailedException(RemoteWriteProblem.NAME_TAKEN)
    }

    /** One bounded command on the host session; a host that does not answer leaves its own reason behind. */
    private suspend fun answer(host: HostSession, command: String): String {
        val channel = bounded { host.exec(command) }
        return try {
            // Only this app's own bound turns into a timeout; a cancelled caller still wins over it.
            try {
                withTimeout(timeoutMillis) { readAnswer(channel.stdout) }
            } catch (_: TimeoutCancellationException) {
                throw RemoteWriteFailedException(RemoteWriteProblem.TIMEOUT)
            }
        } finally {
            runCatching { channel.close() }
        }
    }

    private suspend fun <T> bounded(block: suspend () -> T): T = try {
        withTimeout(timeoutMillis) { block() }
    } catch (_: TimeoutCancellationException) {
        throw RemoteWriteFailedException(RemoteWriteProblem.TIMEOUT)
    }

    /**
     * Removes the half-written file of a failed write. Cleanup is best effort and never replaces the failure that
     * caused it: a host that cannot be reached has nothing left to clean here either.
     */
    private suspend fun discard(host: HostSession, temporary: String) {
        try {
            withTimeout(timeoutMillis) { host.exec("rm -f ${ShellCommands.quote(temporary)}") }.close()
        } catch (_: Exception) {
            // The write already failed; a cleanup that could not run is not a second result to report.
        }
    }

    private companion object {
        const val READY = "write_ready"
        const val EXISTS = "write_exists"
        const val DIRECTORY_MISSING = "write_directory_missing"
        const val FAILED = "write_failed"

        /** `name.ext` becomes `name-1.ext`, and a name without a suffix becomes `name-1`. */
        fun suffixed(name: String, attempt: Int): String {
            if (attempt == 0) return name
            val dot = name.lastIndexOf('.')
            return if (dot > 0) "${name.take(dot)}-$attempt${name.drop(dot)}" else "$name-$attempt"
        }

        fun requireRemoteFileName(name: String): String {
            require(name.isNotBlank() && name.length <= 160) { "Remote file name is out of range" }
            require(name.none { it.code < 32 || it.code == 127 }) { "Remote file name cannot contain control characters" }
            require('/' !in name && '\\' !in name) { "Remote file name cannot contain a path separator" }
            require(name != "." && name != "..") { "Remote file name cannot name a directory" }
            return name
        }

        fun readAnswer(input: InputStream): String {
            val buffer = ByteArray(MAX_ANSWER_BYTES)
            var read = 0
            while (read < buffer.size) {
                val count = input.read(buffer, read, buffer.size - read)
                if (count < 0) break
                read += count
            }
            return String(buffer, 0, read, Charsets.UTF_8).trim()
        }
    }
}

private const val MAX_ANSWER_BYTES = 64
