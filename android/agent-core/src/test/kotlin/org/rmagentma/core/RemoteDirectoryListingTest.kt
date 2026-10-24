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
import java.nio.file.Files
import java.util.Comparator
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RemoteDirectoryListingTest {
    @Test
    fun realListingReportsKindsSizesAndSymlinksWithoutWalkingIntoThem() = runBlocking {
        withTimeout(30_000) {
            val root = Files.createTempDirectory("listing-test")
            try {
                Files.write(root.resolve("main.kt"), "val answer = 42\n".toByteArray())
                Files.createDirectory(root.resolve("nested"))
                Files.write(root.resolve("nested/hidden-in-subdirectory.kt"), "x".toByteArray())
                Files.write(root.resolve(".hidden.kt"), "KEY=1\n".toByteArray())
                Files.createSymbolicLink(root.resolve("link.kt"), root.resolve("main.kt"))

                val host = ProcessHost()
                val listing = RemoteDirectoryListing(Dispatchers.IO).list(host, root.toString())
                val listed = listing as DirectoryListing.Listed
                assertFalse(listed.truncated)
                val byName = listed.entries.associateBy { it.name }
                assertEquals(setOf(".hidden.kt", "link.kt", "main.kt", "nested"), byName.keys)
                assertEquals(DirectoryEntryKind.FILE, byName.getValue("main.kt").kind)
                assertEquals(16L, byName.getValue("main.kt").size)
                assertEquals(DirectoryEntryKind.FILE, byName.getValue(".hidden.kt").kind)
                assertEquals(DirectoryEntryKind.DIRECTORY, byName.getValue("nested").kind)
                // A link is the link itself: reported as a symlink with its stored target, never resolved.
                assertEquals(DirectoryEntryKind.SYMLINK, byName.getValue("link.kt").kind)
                assertEquals(root.resolve("main.kt").toString(), byName.getValue("link.kt").symlinkTarget)

                val command = host.commands.single()
                assertTrue(command.startsWith("/bin/sh -c "))
                assertTrue(command.contains("--directory"))
                assertTrue(command.contains("'--limit' '200'"))
                assertTrue(command.contains("--max-bytes"))
                // The command travels into python3 the way every other remote script does, so a host without
                // python3 says so in the answer instead of failing as an empty directory.
                assertTrue(command.contains("| base64 -d | python3 -"))
                for (write in listOf(">", "rm ", "mkdir", "touch", "tee", "chmod", "mv ")) {
                    assertFalse(command, command.contains(write))
                }
            } finally {
                deleteTree(root)
            }
        }
    }

    @Test
    fun namesThatWouldBreakAnyDelimiterSurviveTheWireExactly() = runBlocking {
        withTimeout(30_000) {
            val root = Files.createTempDirectory("listing-names")
            try {
                // Quotes, JSON braces and a line break are all legal in a POSIX file name; the listing must carry
                // each of them back as the same name rather than as extra entries or as mangled text.
                val names = listOf("quote'.kt", "brace{}.kt", "line\nbreak.kt", "<script>alert(1)</script>")
                names.forEach { Files.write(root.resolve(it), "x".toByteArray()) }

                val listed = RemoteDirectoryListing(Dispatchers.IO).list(ProcessHost(), root.toString()) as DirectoryListing.Listed
                assertEquals(names.toSet(), listed.entries.map { it.name }.toSet())
            } finally {
                deleteTree(root)
            }
        }
    }

    @Test
    fun emptyDirectoryIsAnEmptyListingAndNeverAnError() = runBlocking {
        withTimeout(30_000) {
            val root = Files.createTempDirectory("listing-empty")
            try {
                val listed = RemoteDirectoryListing(Dispatchers.IO).list(ProcessHost(), root.toString()) as DirectoryListing.Listed
                assertTrue(listed.entries.isEmpty())
                assertFalse(listed.truncated)
            } finally {
                deleteTree(root)
            }
        }
    }

    @Test
    fun directoryHoldingMoreThanTheLimitIsTruncatedInsteadOfReadWhole() = runBlocking {
        withTimeout(30_000) {
            val root = Files.createTempDirectory("listing-limit")
            try {
                repeat(5) { Files.write(root.resolve("file-$it.kt"), "x".toByteArray()) }
                val listed = RemoteDirectoryListing(Dispatchers.IO).list(ProcessHost(), root.toString(), limit = 3) as DirectoryListing.Listed
                assertEquals(3, listed.entries.size)
                assertTrue(listed.truncated)
            } finally {
                deleteTree(root)
            }
        }
    }

    @Test
    fun missingPathAndFileOnThePathAreTheirOwnProblems() = runBlocking {
        withTimeout(30_000) {
            val root = Files.createTempDirectory("listing-problems")
            try {
                val missing = RemoteDirectoryListing(Dispatchers.IO).list(ProcessHost(), root.resolve("gone").toString())
                assertEquals(DirectoryListingProblem.MISSING, (missing as DirectoryListing.Unavailable).problem)

                val file = root.resolve("regular.txt")
                Files.write(file, "x".toByteArray())
                val notDirectory = RemoteDirectoryListing(Dispatchers.IO).list(ProcessHost(), file.toString())
                assertEquals(DirectoryListingProblem.NOT_A_DIRECTORY, (notDirectory as DirectoryListing.Unavailable).problem)
            } finally {
                deleteTree(root)
            }
        }
    }

    @Test
    fun directoryWithoutPythonReportsTheMissingToolInsteadOfAnEmptyListing() = runBlocking {
        withTimeout(30_000) {
            val emptyPath = Files.createTempDirectory("listing-path")
            try {
                val host = ProcessHost(path = emptyPath.toString())
                val listing = RemoteDirectoryListing(Dispatchers.IO).list(host, emptyPath.toString())
                assertEquals(DirectoryListingProblem.TOOL_MISSING, (listing as DirectoryListing.Unavailable).problem)
            } finally {
                deleteTree(emptyPath)
            }
        }
    }

    @Test
    fun relativeDirectoryIsRejectedWithoutTouchingTheHost() = runBlocking {
        withTimeout(30_000) {
            for (directory in listOf("work", "", "/work\nbad", "/work\u0000bad")) {
                val host = AnswerHost("")
                val listing = RemoteDirectoryListing(Dispatchers.IO).list(host, directory)
                assertEquals(directory, DirectoryListingProblem.NOT_ABSOLUTE, (listing as DirectoryListing.Unavailable).problem)
                assertTrue(host.commands.isEmpty())
            }
        }
    }

    @Test
    fun unboundedAndOffRangeRequestsAreRejectedBeforeAnyCommand() = runBlocking {
        withTimeout(30_000) {
            val host = AnswerHost("")
            var rejected = 0
            for (limit in listOf(0, -1, DIRECTORY_LISTING_MAX_ENTRIES + 1)) {
                try {
                    RemoteDirectoryListing(Dispatchers.IO).list(host, "/work", limit)
                } catch (_: IllegalArgumentException) {
                    rejected++
                }
            }
            assertEquals(3, rejected)
            assertTrue(host.commands.isEmpty())
        }
    }

    @Test
    fun hostWithoutAnAnswerIsATimeoutAndTheChannelIsClosed() = runBlocking {
        withTimeout(30_000) {
            val host = AnswerHost("", silent = true)
            val listing = RemoteDirectoryListing(Dispatchers.IO, timeoutMillis = 200).list(host, "/silent")
            assertEquals(DirectoryListingProblem.TIMEOUT, (listing as DirectoryListing.Unavailable).problem)
            host.channel.awaitClosed()
        }
    }

    @Test
    fun malformedOversizedAndIncompleteAnswersAreFailuresInsteadOfListings() = runBlocking {
        withTimeout(30_000) {
            val malformed = listOf(
                "",
                "not json at all",
                "{\"record\":\"entry\",\"name\":\"\"}",
                "{\"record\":\"listing\",\"count\":1,\"truncated\":false}",
                "{\"record\":\"future_record\"}",
                "{\"record\":\"entry\",\"name\":\"a.kt\",\"kind\":\"file\",\"size\":1}",
                "x".repeat(DIRECTORY_LISTING_MAX_BYTES + 1),
            )
            for (answer in malformed) {
                val listing = RemoteDirectoryListing(Dispatchers.IO).list(AnswerHost(answer), "/work")
                assertEquals(answer.take(32), DirectoryListingProblem.FAILED, (listing as DirectoryListing.Unavailable).problem)
            }
        }
    }

    @Test
    fun remoteCategoriesBecomeTheirOwnProblems() = runBlocking {
        withTimeout(30_000) {
            val expected = mapOf(
                "dir_not_readable" to DirectoryListingProblem.NOT_READABLE,
                "dir_missing" to DirectoryListingProblem.MISSING,
                "dir_not_a_directory" to DirectoryListingProblem.NOT_A_DIRECTORY,
                "dir_list_tool_missing" to DirectoryListingProblem.TOOL_MISSING,
                "dir_list_timeout" to DirectoryListingProblem.TIMEOUT,
                // The shared remote transport names a host that cannot run the script at all this way.
                "python_missing" to DirectoryListingProblem.TOOL_MISSING,
                "base64_missing" to DirectoryListingProblem.TOOL_MISSING,
                "remote_exit_failed" to DirectoryListingProblem.FAILED,
            )
            for ((category, problem) in expected) {
                val answer = "{\"record\":\"error\",\"level\":\"error\",\"error\":\"$category\"}"
                val listing = RemoteDirectoryListing(Dispatchers.IO).list(AnswerHost(answer), "/work")
                assertEquals(category, problem, (listing as DirectoryListing.Unavailable).problem)
            }
        }
    }

    @Test
    fun entriesKeepTheirTypesAndUnknownKindsStayDrawable() = runBlocking {
        withTimeout(30_000) {
            val answer = "{\"record\":\"entry\",\"name\":\"dir\",\"kind\":\"dir\",\"size\":4096}\n" +
                "{\"record\":\"entry\",\"name\":\"link\",\"kind\":\"symlink\",\"size\":7,\"symlink_target\":\"../outside\"}\n" +
                "{\"record\":\"entry\",\"name\":\"socket\",\"kind\":\"socket\",\"size\":-1}\n" +
                "{\"record\":\"listing\",\"directory\":\"/work\",\"count\":3,\"truncated\":true}"
            val listed = RemoteDirectoryListing(Dispatchers.IO).list(AnswerHost(answer), "/work") as DirectoryListing.Listed
            assertTrue(listed.truncated)
            assertEquals(listOf("dir", "link", "socket"), listed.entries.map { it.name })
            assertEquals(DirectoryEntryKind.DIRECTORY, listed.entries[0].kind)
            assertEquals(DirectoryEntryKind.SYMLINK, listed.entries[1].kind)
            assertEquals("../outside", listed.entries[1].symlinkTarget)
            assertEquals(-1L, listed.entries[2].size)
            // A kind this build has no word for is still shown as a file rather than dropped from the listing.
            assertEquals(DirectoryEntryKind.FILE, listed.entries[2].kind)
            assertEquals(null, listed.entries[0].symlinkTarget)
        }
    }

    @Test
    fun unreachableHostKeepsItsOwnConnectionError() = runBlocking {
        withTimeout(30_000) {
            val host = AnswerHost("", failure = IOException("Host 42 was deleted"))
            val failure = runCatching { RemoteDirectoryListing(Dispatchers.IO).list(host, "/work") }.exceptionOrNull()
            assertEquals("Host 42 was deleted", failure?.message)
            assertFalse(failure is DirectoryListing.Unavailable)
        }
    }

    private fun deleteTree(root: java.nio.file.Path) {
        Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }
}

/** A host that answers the listing command from a script output, or never answers at all. */
private class AnswerHost(
    answer: String,
    silent: Boolean = false,
    private val failure: IOException? = null,
) : HostSession {
    override val hostId = 42L
    val commands = mutableListOf<String>()
    val channel = AnswerChannel(answer, silent)

    override suspend fun exec(command: String): ExecChannel {
        failure?.let { throw it }
        commands += command
        return channel
    }
}

/** A host that really runs the listing command through /bin/sh, so the packaged script is exercised as sent. */
private class ProcessHost(private val path: String? = null) : HostSession {
    override val hostId = 42L
    val commands = mutableListOf<String>()

    override suspend fun exec(command: String): ExecChannel {
        commands += command
        val builder = ProcessBuilder("/bin/sh", "-c", command)
        path?.let { builder.environment()["PATH"] = it }
        val process = builder.start()
        return object : ExecChannel {
            override val stdout: InputStream = process.inputStream
            override val stdin = process.outputStream
            override val stderr: InputStream = process.errorStream

            override fun close() {
                process.destroy()
                listOf(stdout, stdin, stderr).forEach { runCatching { it.close() } }
            }
        }
    }
}

private class AnswerChannel(answer: String, silent: Boolean) : ExecChannel {
    override val stdout: InputStream = if (silent) SilentInput() else ByteArrayInputStream(answer.toByteArray())
    override val stdin = ByteArrayOutputStream()
    override val stderr: InputStream = ByteArrayInputStream(byteArrayOf())
    private val closed = CompletableDeferred<Unit>()

    suspend fun awaitClosed() = withTimeout(5000) { closed.await() }

    override fun close() {
        (stdout as? SilentInput)?.release()
        listOf(stdout, stdin, stderr).forEach { runCatching { it.close() } }
        closed.complete(Unit)
    }
}

/** Never answers on its own, so a listing without a bound would block here forever. */
private class SilentInput : InputStream() {
    private val released = CountDownLatch(1)

    fun release() = released.countDown()

    override fun read(): Int {
        try {
            released.await(30, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            throw InterruptedIOException("Listing was interrupted")
        }
        return -1
    }
}
