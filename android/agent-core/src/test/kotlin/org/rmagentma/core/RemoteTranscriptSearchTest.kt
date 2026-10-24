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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InterruptedIOException
import java.nio.file.Files
import java.util.Base64
import java.util.Comparator
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RemoteTranscriptSearchTest {
    @Test
    fun realSearchThroughThePackagedScriptFindsChineseTextAndTouchesNothing() = runBlocking {
        withTimeout(30_000) {
            val root = Files.createTempDirectory("transcript-search")
            try {
                val transcript = root.resolve("session.v1.jsonl")
                Files.write(
                    transcript,
                    (
                        """{"type":"user/message","seq":0,"data":{"content":"hello world"}}""" + "\n" +
                            """{"type":"assistant/message","seq":1,"data":{"content":"会议记录：中文关键词在这里"}}""" + "\n"
                        ).toByteArray(),
                )
                val before = Files.readAllBytes(transcript)
                val target = TranscriptSearchTarget(AgentKind.DSH, "same", root.toString(), listOf("session.v1.jsonl"))
                val host = SearchProcessHost()
                val result = RemoteTranscriptSearch(Dispatchers.IO).search(host, "关键词", listOf(target))
                assertEquals(1, host.commands.size)
                assertTrue(host.commands.single(), host.commands.single().startsWith("/bin/sh -c "))
                assertNull(result.problem)
                assertFalse(result.truncated)
                assertEquals(0, result.unreadableFiles)
                val hit = result.hits.single()
                assertEquals("dsh:same", hit.sessionKey)
                assertEquals(AgentKind.DSH, hit.agent)
                assertEquals(2, hit.line)
                assertEquals("session.v1.jsonl#2", hit.anchor)
                assertTrue(hit.snippet, hit.snippet.contains("关键词"))
                assertFalse(hit.snippet, hit.snippet.any { it.code < 32 })
                // The offset counts characters of the stored line, so a Chinese query points at the match itself.
                val line = Files.readAllLines(transcript).last()
                assertEquals("关键词", line.substring(hit.offset, hit.offset + 3))
                assertTrue(before.contentEquals(Files.readAllBytes(transcript)))
            } finally {
                Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
            }
        }
    }

    @Test
    fun hitsKeepTheirSessionAndAnchorAndTheCommandKeepsItsBounds() = runBlocking {
        val answer = """
            {"record":"match","sessionKey":"dsh:same","agent":"dsh","sessionId":"same","directory":"/home/user/.dsh/sessions/p/same","file":"session.v10.jsonl","line":12,"offset":40,"snippet":"会议记录 关键词 在这里","clipped":true}
            {"record":"match","sessionKey":"zcode:other","agent":"zcode","sessionId":"other","directory":"/home/user/other","file":"chat.jsonl","line":3,"offset":0,"snippet":"short line","clipped":false}
            {"record":"search","scopes":2,"files":2,"read":2,"bytes":900,"matches":2,"truncated":false,"unreadable":0,"timedOut":false}
        """.trimIndent()
        val host = SearchAnswerHost(answer)
        val scope = listOf(
            TranscriptSearchTarget(AgentKind.DSH, "same", "/home/user/.dsh/sessions/p/same", listOf("session.v10.jsonl")),
            TranscriptSearchTarget(AgentKind.ZCODE, "other", "/home/user/other", listOf("chat.jsonl")),
        )
        val result = RemoteTranscriptSearch(Dispatchers.IO).search(host, "关键词 mixed", scope, limit = 5)
        assertNull(result.problem)
        assertFalse(result.truncated)
        assertEquals(0, result.unreadableFiles)
        assertEquals(listOf("session.v10.jsonl#12", "chat.jsonl#3"), result.hits.map { it.anchor })
        assertEquals(listOf("dsh:same", "zcode:other"), result.hits.map { it.sessionKey })
        assertEquals(listOf(AgentKind.DSH, AgentKind.ZCODE), result.hits.map { it.agent })
        assertEquals(40, result.hits.first().offset)
        assertEquals("会议记录 关键词 在这里", result.hits.first().snippet)
        assertTrue(result.hits.first().clipped)
        assertFalse(result.hits.last().clipped)
        assertTrue(host.channel.isClosed)

        val command = host.commands.single()
        assertTrue(command.startsWith("/bin/sh -c "))
        assertTrue(command, command.contains("| base64 -d | python3 -"))
        // Every bound reaches python3 as an argument of the payload, so each one is asserted in the spelling the
        // host really receives rather than in the payload's own spelling.
        assertTrue(command, command.contains(payloadArgument("--max-files", "$TRANSCRIPT_SEARCH_MAX_FILES")))
        assertTrue(command, command.contains(payloadArgument("--max-bytes", "$TRANSCRIPT_SEARCH_MAX_BYTES")))
        assertTrue(command, command.contains(payloadArgument("--max-matches", "5")))
        assertTrue(command, command.contains(payloadArgument("--max-snippet", "$TRANSCRIPT_SEARCH_MAX_SNIPPET")))
        assertTrue(command, command.contains(payloadArgument("--timeout", "$TRANSCRIPT_SEARCH_SCAN_SECONDS")))
        // Query and scopes travel base64-encoded, so a host whose locale is not UTF-8 still receives these characters.
        assertTrue(command, command.contains(Base64.getEncoder().encodeToString("关键词 mixed".toByteArray())))
        val scopeJson = """{"agent":"dsh","sessionId":"same","directory":"/home/user/.dsh/sessions/p/same",""" +
            """"files":["session.v10.jsonl"]}"""
        val encodedScope = Base64.getEncoder().encodeToString(scopeJson.toByteArray())
        assertTrue(command, command.contains(encodedScope))
        // Nothing here writes on the host: the script arrives piped into python3 and reads files only.
        for (write in listOf("rm ", "mkdir", "touch", "tee", "chmod", "mv ", "install ", "sed -i")) {
            assertFalse(command, command.contains(write))
        }
    }

    @Test
    fun limitsUnreadableFilesAndTheClockAllLeaveTheAnswerIncomplete() = runBlocking {
        val target = TranscriptSearchTarget(AgentKind.DSH, "same", "/work", listOf("a.jsonl"))
        val search = RemoteTranscriptSearch(Dispatchers.IO)
        val truncated = search.search(
            SearchAnswerHost(summary(files = 9, read = 3, truncated = true)),
            "hello",
            listOf(target),
        )
        assertNull(truncated.problem)
        assertTrue(truncated.truncated)
        assertEquals(0, truncated.unreadableFiles)
        val unreadable = search.search(
            SearchAnswerHost("""{"record":"search","scopes":1,"files":3,"read":1,"bytes":90,"matches":0,"truncated":false,"unreadable":2,"timedOut":false}"""),
            "hello",
            listOf(target),
        )
        assertNull(unreadable.problem)
        assertTrue(unreadable.truncated)
        assertEquals(2, unreadable.unreadableFiles)
        val timedOut = search.search(
            SearchAnswerHost("""{"record":"search","scopes":1,"files":1,"read":1,"bytes":90,"matches":0,"truncated":false,"unreadable":0,"timedOut":true}"""),
            "hello",
            listOf(target),
        )
        assertNull(timedOut.problem)
        assertTrue(timedOut.truncated)
        val whole = search.search(
            SearchAnswerHost("""{"record":"search","scopes":1,"files":1,"read":1,"bytes":90,"matches":0,"truncated":false,"unreadable":0,"timedOut":false}"""),
            "hello",
            listOf(target),
        )
        assertNull(whole.problem)
        assertFalse(whole.truncated)
        assertEquals(0, whole.unreadableFiles)
        assertTrue(whole.hits.isEmpty())
    }

    @Test
    fun hitsFromAnotherSessionAndUnfinishedAnswersAreNeverASearch() = runBlocking {
        val target = TranscriptSearchTarget(AgentKind.DSH, "same", "/work", listOf("a.jsonl"))
        val answers = listOf(
            // A hit for a session, directory or file the caller never asked about is not a hit of this search.
            hit(sessionKey = "dsh:other"),
            hit(directory = "/elsewhere"),
            hit(file = "b.jsonl"),
            hit(sessionKey = "dsh:wrong"),
            hit(line = 0),
            hit(offset = -1),
            // A snippet that still holds a control character is not a snippet this search hands to a view.
            hit(snippet = "tab\\u0009here"),
            """{"record":"future_record"}""",
            """{"record":"match","sessionKey":"dsh:same","agent":"dsh","sessionId":"same","directory":"/work","file":"a.jsonl","line":1,"offset":0,"snippet":"x","clipped":false}""",
            "",
        )
        for (answer in answers) {
            val host = SearchAnswerHost(answer)
            val result = RemoteTranscriptSearch(Dispatchers.IO).search(host, "hello", listOf(target))
            assertTrue(answer.take(64), result.problem != null)
        }
    }

    @Test
    fun remoteCategoriesBecomeTheirOwnProblems() = runBlocking {
        val expected = mapOf(
            "search_query_empty" to TranscriptSearchProblem.QUERY_EMPTY,
            "search_query_invalid" to TranscriptSearchProblem.QUERY_INVALID,
            "search_scope_invalid" to TranscriptSearchProblem.SCOPE_INVALID,
            // The shared remote transport names a host that cannot run the script at all this way.
            "python_missing" to TranscriptSearchProblem.TOOL_MISSING,
            "base64_missing" to TranscriptSearchProblem.TOOL_MISSING,
            "search_failed" to TranscriptSearchProblem.FAILED,
            "remote_exit_failed" to TranscriptSearchProblem.FAILED,
        )
        val target = TranscriptSearchTarget(AgentKind.DSH, "same", "/work", listOf("a.jsonl"))
        for ((category, problem) in expected) {
            val answer = """{"record":"error","level":"error","error":"$category"}"""
            val result = RemoteTranscriptSearch(Dispatchers.IO).search(SearchAnswerHost(answer), "hello", listOf(target))
            assertEquals(category, problem, result.problem)
            assertTrue(result.hits.isEmpty())
            assertTrue(result.truncated)
        }
    }

    @Test
    fun aQueryOrScopeThatCannotBeSentIsRejectedWithoutACommand() = runBlocking {
        val host = SearchAnswerHost("")
        val target = TranscriptSearchTarget(AgentKind.DSH, "same", "/work", listOf("a.jsonl"))
        val search = RemoteTranscriptSearch(Dispatchers.IO)
        assertEquals(TranscriptSearchProblem.QUERY_EMPTY, search.search(host, "  ", listOf(target)).problem)
        assertEquals(TranscriptSearchProblem.QUERY_INVALID, search.search(host, "a\nb", listOf(target)).problem)
        assertEquals(
            TranscriptSearchProblem.QUERY_INVALID,
            search.search(host, "x".repeat(TRANSCRIPT_SEARCH_MAX_QUERY + 1), listOf(target)).problem,
        )
        assertEquals(TranscriptSearchProblem.SCOPE_INVALID, search.search(host, "hello", emptyList()).problem)
        for (invalid in listOf(
            target.copy(directory = "work"),
            target.copy(directory = "/work\nbad"),
            target.copy(sessionId = ""),
            target.copy(files = listOf("../escape.jsonl")),
            target.copy(files = listOf("/etc/passwd")),
            target.copy(files = listOf("")),
        )) {
            assertEquals(invalid.toString(), TranscriptSearchProblem.SCOPE_INVALID, search.search(host, "hello", listOf(invalid)).problem)
        }
        var rejected = 0
        for (limit in listOf(0, -1, TRANSCRIPT_SEARCH_MAX_MATCHES + 1)) {
            try {
                search.search(host, "hello", listOf(target), limit)
            } catch (_: IllegalArgumentException) {
                rejected++
            }
        }
        assertEquals(3, rejected)
        assertTrue(host.commands.isEmpty())
    }

    @Test
    fun aHostThatNeverAnswersIsATimeoutAndTheChannelIsClosed() = runBlocking {
        withTimeout(30_000) {
            val host = SearchAnswerHost("", silent = true)
            val target = TranscriptSearchTarget(AgentKind.DSH, "same", "/work", listOf("a.jsonl"))
            val result = RemoteTranscriptSearch(Dispatchers.IO, timeoutMillis = 200).search(host, "hello", listOf(target))
            assertEquals(TranscriptSearchProblem.TIMEOUT, result.problem)
            assertTrue(result.truncated)
            assertTrue(result.hits.isEmpty())
            host.channel.awaitClosed()
        }
    }

    private fun hit(
        sessionKey: String = "dsh:same",
        directory: String = "/work",
        file: String = "a.jsonl",
        line: Int = 1,
        offset: Int = 0,
        snippet: String = "x",
    ): String = """{"record":"match","sessionKey":"$sessionKey","agent":"dsh","sessionId":"same","directory":"$directory",""" +
        """"file":"$file","line":$line,"offset":$offset,"snippet":"$snippet","clipped":false}"""

    private fun summary(files: Int, read: Int, truncated: Boolean): String =
        """{"record":"search","scopes":1,"files":$files,"read":$read,"bytes":1024,"matches":0,"truncated":$truncated,"unreadable":0,"timedOut":false}"""
}

/**
 * One argument as the host really receives it: the payload single-quotes every argument, and the command hands that
 * whole payload to `sh -c` as one quoted word, so each of those quotes is escaped once more on the way out.
 */
private fun payloadArgument(name: String, value: String): String {
    val quote = "'\"'\"'"
    return "$quote$name$quote $quote$value$quote"
}

/** A host that answers the search command from script output, or never answers at all. */
private class SearchAnswerHost(answer: String, silent: Boolean = false) : HostSession {
    override val hostId = 42L
    val commands = mutableListOf<String>()
    val channel = SearchAnswerChannel(answer, silent)

    override suspend fun exec(command: String): ExecChannel {
        commands += command
        return channel
    }
}

/** A host that really runs the search command through /bin/sh, so the packaged script is exercised as sent. */
private class SearchProcessHost : HostSession {
    override val hostId = 42L
    val commands = mutableListOf<String>()

    override suspend fun exec(command: String): ExecChannel {
        commands += command
        val process = ProcessBuilder("/bin/sh", "-c", command).start()
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

private class SearchAnswerChannel(answer: String, silent: Boolean) : ExecChannel {
    override val stdout: InputStream = if (silent) SearchSilentInput() else ByteArrayInputStream(answer.toByteArray())
    override val stdin = ByteArrayOutputStream()
    override val stderr: InputStream = ByteArrayInputStream(byteArrayOf())
    private val closed = CompletableDeferred<Unit>()
    val isClosed get() = closed.isCompleted

    suspend fun awaitClosed() = withTimeout(5000) { closed.await() }

    override fun close() {
        (stdout as? SearchSilentInput)?.release()
        listOf(stdout, stdin, stderr).forEach { runCatching { it.close() } }
        closed.complete(Unit)
    }
}

/** Never answers on its own, so a search without a bound would block here forever. */
private class SearchSilentInput : InputStream() {
    private val released = CountDownLatch(1)

    fun release() = released.countDown()

    override fun read(): Int {
        try {
            released.await(30, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            throw InterruptedIOException("Search was interrupted")
        }
        return -1
    }
}
