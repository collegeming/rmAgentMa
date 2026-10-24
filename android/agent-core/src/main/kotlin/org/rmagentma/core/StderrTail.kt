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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * How long a terminal transport failure waits for the Agent's stderr stream to end, so the last lines an exiting
 * process wrote are already read when its error message is built. Standard error ends with the process, which is
 * the same moment standard output reaches EOF, and those two reads race.
 */
internal const val STDERR_TAIL_SETTLE_MILLIS = 200L

/**
 * Keeps the last lines an Agent wrote to stderr for a terminal error message: bounded by lines and bytes, with
 * blank lines dropped, control characters and terminal escape sequences removed. Enough to name why a process
 * exited without dumping its private diagnostics into the chat or the log.
 *
 * shortcut: the tail is bounded and stripped of terminal noise but not redacted, because the error it explains
 * belongs to the user's own host; upgrade to a redaction policy if an Agent starts writing credentials to stderr.
 */
internal class StderrTail(
    private val maxLines: Int = 5,
    private val maxLineBytes: Int = 200,
) {
    private val drained = CompletableDeferred<Unit>()
    private val lines = ArrayDeque<String>()
    private val pending = java.io.ByteArrayOutputStream()

    init {
        require(maxLines > 0 && maxLineBytes > 0) { "Stderr tail limits must be positive" }
    }

    @Synchronized
    fun append(bytes: ByteArray, offset: Int, length: Int) {
        for (index in offset until offset + length) {
            if (bytes[index] == '\n'.code.toByte()) {
                commit()
            } else if (pending.size() < maxLineBytes) {
                pending.write(bytes[index].toInt())
            }
        }
    }

    @Synchronized
    fun suffix(): String {
        // An unterminated last line is still the Agent's last words.
        commit()
        if (lines.isEmpty()) return ""
        return "; stderr tail: " + lines.joinToString(" | ")
    }

    /** Called once the stderr stream ended or its reader stopped, whichever came first. */
    fun markDrained() {
        drained.complete(Unit)
    }

    /** Bounded wait for the stderr stream to end; never throws, so a terminal path can always continue. */
    suspend fun awaitDrained(timeoutMillis: Long = STDERR_TAIL_SETTLE_MILLIS) {
        withContext(NonCancellable) { withTimeoutOrNull(timeoutMillis) { drained.await() } }
    }

    private fun commit() {
        val text = sanitize(String(pending.toByteArray(), Charsets.UTF_8)).trim()
        pending.reset()
        if (text.isEmpty()) return
        if (lines.size >= maxLines) lines.removeFirst()
        lines.addLast(text)
    }
}

/** Appends the captured tail to a terminal failure, keeping its type so a caller's catch stays valid. */
internal fun withStderrTail(failure: IOException, tail: StderrTail): IOException {
    val suffix = tail.suffix()
    if (suffix.isEmpty()) return failure
    val message = failure.message?.takeIf { it.isNotBlank() } ?: "Agent transport failed"
    return if (failure is EOFException) EOFException(message + suffix) else IOException(message + suffix, failure.cause)
}

private fun sanitize(raw: String): String {
    val text = StringBuilder(raw.length)
    var index = 0
    while (index < raw.length) {
        val value = raw[index]
        if (value == '\u001B') {
            // Drop an escape sequence, including its parameters, up to the final byte.
            index++
            if (index < raw.length && raw[index] == '[') {
                index++
                while (index < raw.length && raw[index] !in '@'..'~') index++
            }
            index++
        } else {
            text.append(if (value.code < 32 || value.code == 127) ' ' else value)
            index++
        }
    }
    return text.toString()
}

/** Reads stderr to its end, or until the reader stops, and keeps only a bounded sanitized tail for diagnosis. */
internal fun drainStderr(input: InputStream, tail: StderrTail) {
    val buffer = ByteArray(8192)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        tail.append(buffer, 0, read)
    }
}
