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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64

class RemoteFileWriteTest {
    @Test
    fun aWriteLandsUnderAFreeNameAndSendsEveryByte() = runBlocking {
        withTimeout(10000) {
            val content = ByteArray(30_000) { (it % 251).toByte() }
            val host = WriteHost()
            val path = RemoteFileWriter(Dispatchers.IO, chunkBytes = 12_288).write(host, "/work", "shot.png", content)

            assertEquals("/work/shot.png", path)
            assertTrue(host.written.contentEquals(content))
            // The directory is tested, a free name is chosen, the buffer is created, the payload travels in
            // chunks, and one last command moves the finished file into place.
            assertEquals(7, host.commands.size)
            assertTrue(host.commands.first().startsWith("if [ -d '/work' ]"))
            assertTrue(host.commands[1].contains("[ -e '/work/shot.png' ]"))
            assertTrue(host.commands.last().startsWith("if [ -e '/work/shot.png' ]; then printf %s write_exists; elif mv "))
            // The file is written under a temporary name, so a reader of the directory never sees half an upload.
            assertTrue(host.commands[2].contains(".rmagentma-"))
            assertTrue(host.commands[2].contains(".part"))
            // Nothing in the sequence replaces a file: only the move names the target, and it tests it first.
            val replacing = host.commands.dropLast(1).filter { it.contains(" > '/work/shot.png'") }
            assertTrue(replacing.toString(), replacing.isEmpty())
        }
    }

    @Test
    fun aNameTheDirectoryHoldsMovesTheWriteToASuffixedName() = runBlocking {
        withTimeout(10000) {
            val host = WriteHost(taken = setOf("/work/shot.png", "/work/shot-1.png"))
            val path = RemoteFileWriter(Dispatchers.IO).write(host, "/work/", "shot.png", "hello".toByteArray())
            assertEquals("/work/shot-2.png", path)
            // The trailing separator of the directory is not doubled into the path the write answers with.
            assertFalse(path.contains("//"))
        }
    }

    @Test
    fun aDirectoryThatIsGoneFailsAsItselfAndLeavesNoTemporaryFile() = runBlocking {
        withTimeout(10000) {
            val host = WriteHost(missingDirectory = true)
            val failure = runCatching {
                RemoteFileWriter(Dispatchers.IO).write(host, "/gone", "shot.png", "hello".toByteArray())
            }.exceptionOrNull()
            assertEquals(RemoteWriteProblem.DIRECTORY_MISSING, (failure as RemoteWriteFailedException).problem)
            assertTrue(failure.message.orEmpty().contains("write_directory_missing"))
            assertFalse(host.commands.any { it.contains("mv ") })
            assertTrue(host.written.isEmpty())
        }
    }

    @Test
    fun aChunkThatDidNotLandRemovesTheHalfWrittenFile() = runBlocking {
        withTimeout(10000) {
            val host = WriteHost(wrongSizeAfterFirstChunk = true)
            val content = ByteArray(30_000) { it.toByte() }
            val failure = runCatching {
                RemoteFileWriter(Dispatchers.IO, chunkBytes = 12_288).write(host, "/work", "shot.png", content)
            }.exceptionOrNull()
            assertEquals(RemoteWriteProblem.INCOMPLETE, (failure as RemoteWriteFailedException).problem)
            // The cleanup names the temporary file this write created, so nothing half-written is left behind.
            val cleanup = host.commands.last()
            assertTrue(cleanup.startsWith("rm -f '/work/.rmagentma-"))
            assertFalse(host.commands.any { it.contains("mv ") })
        }
    }

    @Test
    fun aNameThatAppearedBeforeTheMoveIsNotReplaced() = runBlocking {
        withTimeout(10000) {
            val host = WriteHost(moveAnswer = "write_exists")
            val failure = runCatching {
                RemoteFileWriter(Dispatchers.IO).write(host, "/work", "shot.png", "hello".toByteArray())
            }.exceptionOrNull()
            assertEquals(RemoteWriteProblem.NAME_TAKEN, (failure as RemoteWriteFailedException).problem)
            assertTrue(host.commands.last().startsWith("rm -f '/work/.rmagentma-"))
        }
    }

    @Test
    fun aDirectoryOrNameNoShellCouldCarryIsRefusedBeforeTheHostIsTouched() = runBlocking {
        withTimeout(10000) {
            val host = WriteHost()
            for (directory in listOf("work", "", "/work\nbad")) {
                val failure = runCatching {
                    RemoteFileWriter(Dispatchers.IO).write(host, directory, "shot.png", "hello".toByteArray())
                }.exceptionOrNull()
                assertTrue(directory, failure is IllegalArgumentException)
            }
            for (name in listOf("", " ", "a/b.png", "a\\b.png", "bad\u0001.png", ".", "..")) {
                val failure = runCatching {
                    RemoteFileWriter(Dispatchers.IO).write(host, "/work", name, "hello".toByteArray())
                }.exceptionOrNull()
                assertTrue(name, failure is IllegalArgumentException)
            }
            assertTrue(host.commands.isEmpty())
        }
    }
}

/**
 * A host that answers the write sequence the way a POSIX shell would: it tracks the temporary file's length,
 * appends every chunk it is really sent, and answers each step with the token the writer expects.
 */
private class WriteHost(
    private val missingDirectory: Boolean = false,
    private val taken: Set<String> = emptySet(),
    private val moveAnswer: String = "write_ready",
    private val wrongSizeAfterFirstChunk: Boolean = false,
) : HostSession {
    override val hostId = 5L
    val commands = mutableListOf<String>()
    var written = ByteArray(0)
    private var chunks = 0

    override suspend fun exec(command: String): ExecChannel {
        commands += command
        return Answer(respond(command))
    }

    private fun respond(command: String): String = when {
        command.contains(">>") -> append(command)
        command.startsWith("if : > ") -> "write_ready"
        command.contains("[ -d ") -> if (missingDirectory) "write_directory_missing" else "write_ready"
        command.contains("mv ") -> moveAnswer
        command.contains("rm -f ") -> {
            written = ByteArray(0)
            ""
        }

        command.contains("[ -e ") -> if (taken.any { command.contains(it) }) "write_exists" else "write_ready"
        else -> "write_failed"
    }

    private fun append(command: String): String {
        chunks += 1
        val encoded = command.substringAfter("printf %s '").substringBefore("' | base64")
        val chunk = Base64.getDecoder().decode(encoded)
        // A size the shell would not report for what it just wrote: the app has to treat this as a failed append.
        if (wrongSizeAfterFirstChunk && chunks > 1) return chunk.size.toString()
        written += chunk
        return written.size.toString()
    }
}

private class Answer(private val text: String) : ExecChannel {
    override val stdout = ByteArrayInputStream(text.toByteArray())
    override val stdin = ByteArrayOutputStream()
    override val stderr = ByteArrayInputStream(ByteArray(0))
    override fun close() = Unit
}
