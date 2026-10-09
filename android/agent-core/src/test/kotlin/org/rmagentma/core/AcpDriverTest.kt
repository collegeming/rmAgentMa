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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicInteger

class AcpDriverTest {
    @Test
    fun loadHistoryPromptPermissionQuestionUnknownAndEof() = runBlocking {
        withTimeout(10000) {
            val host = PipeHost()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val server = async(Dispatchers.IO) {
                    val initialize = host.read()
                    assertEquals("initialize", initialize.string("method"))
                    val capabilities = initialize["params"]!!.jsonObject["clientCapabilities"]!!.jsonObject
                    assertEquals(JsonPrimitive(false), capabilities["terminal"])
                    assertEquals(JsonPrimitive(false), capabilities["fs"]!!.jsonObject["readTextFile"])
                    assertTrue(capabilities["elicitation"]!!.jsonObject["form"] is JsonObject)
                    host.reply(initialize, """{"protocolVersion":1,"agentCapabilities":{"loadSession":true}}""")
                    val load = host.read()
                    assertEquals("session/load", load.string("method"))
                    assertEquals("/work dir", load["params"]!!.jsonObject.string("cwd"))
                    host.update("""{"sessionUpdate":"user_message_chunk","content":{"type":"text","text":"history"}}""")
                    host.write("not-json-private-body\n")
                    host.reply(load, "{}")
                    val prompt = host.read()
                    assertEquals("session/prompt", prompt.string("method"))
                    host.write("""{"jsonrpc":"2.0","id":91,"method":"session/request_permission","params":{"sessionId":"s","toolCall":{"toolCallId":"t","title":"Execute"},"options":[{"optionId":"allow","name":"Allow","kind":"allow_once"}]}}""" + "\n")
                    val permission = host.read()
                    assertEquals(JsonPrimitive(91), permission["id"])
                    assertEquals("allow", permission["result"]!!.jsonObject["outcome"]!!.jsonObject.string("optionId"))
                    host.write("""{"jsonrpc":"2.0","id":"q","method":"elicitation/create","params":{"sessionId":"s","mode":"form","message":"Name?","requestedSchema":{"type":"object","properties":{"name":{"type":"string"}}}}}""" + "\n")
                    assertEquals("Alex", host.read()["result"]!!.jsonObject["content"]!!.jsonObject.string("name"))
                    host.write("""{"jsonrpc":"2.0","id":"unsupported","method":"fs/read_text_file","params":{}}""" + "\n")
                    assertEquals(JsonPrimitive(-32601), host.read()["error"]!!.jsonObject["code"])
                    host.update("""{"sessionUpdate":"future_event","newField":true}""")
                    host.update("""{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"answer"}}""")
                    host.reply(prompt, """{"stopReason":"end_turn"}""")
                    host.end()
                }
                val handle = AcpDriver(AgentKind.KIMI, scope).open(host, "s", "/work dir")
                val events = mutableListOf<AgentEvent>()
                val collector = async {
                    handle.events.collect { event ->
                        events += event
                        when (event.type) {
                            "permission" -> handle.respondPermission(event.id, "allow")
                            "question" -> handle.respondQuestion(event.id, "Alex")
                        }
                    }
                }
                handle.load()
                handle.send("hello")
                collector.await()
                server.await()
                assertEquals(listOf("user", "error", "permission", "question", "unknown", "content", "complete", "error"), events.map { it.type })
                assertEquals("history", events.first().text)
                assertTrue(events.none { it.text.contains("private-body") })
                withTimeout(1000) { host.channel.closed.await() }
                assertTrue(host.channel.closes.get() > 0)
                handle.close()
            } finally {
                host.channel.close()
                scope.cancel()
            }
        }
    }

    @Test
    fun newAndCancelNotificationDoNotWaitForPromptLock() = runBlocking {
        withTimeout(30000) {
            val host = PipeHost()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val promptSeen = Channel<Unit>(1)
            try {
                val server = async(Dispatchers.IO) {
                    host.reply(host.read(), """{"protocolVersion":1,"agentCapabilities":{}}""")
                    val new = host.read()
                    assertEquals("session/new", new.string("method"))
                    host.reply(new, """{"sessionId":"new-session"}""")
                    val prompt = host.read()
                    promptSeen.send(Unit)
                    val cancel = host.read()
                    assertEquals("session/cancel", cancel.string("method"))
                    assertFalse(cancel.containsKey("id"))
                    host.reply(prompt, """{"stopReason":"cancelled"}""")
                }
                val handle = AcpDriver(AgentKind.OPENCODE, scope).prepare(host, null, "/tmp")
                val collector = async { handle.events.toList() }
                handle.load()
                assertEquals("new-session", handle.sessionId)
                val prompt = async { handle.send("hello") }
                promptSeen.receive()
                handle.cancel()
                prompt.await()
                server.await()
                handle.close()
            } finally {
                host.channel.close()
                scope.cancel()
            }
        }
    }

    @Test
    fun eofPublishesTerminalEventAndFailsPendingRequestWhileTransportCloseBlocks() = runBlocking {
        withTimeout(10000) {
            val host = PipeHost()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val closeEntered = kotlinx.coroutines.CompletableDeferred<Unit>()
            val releaseClose = java.util.concurrent.CountDownLatch(1)
            val transportClosed = kotlinx.coroutines.CompletableDeferred<Unit>()
            val channel = object : ExecChannel {
                override val stdout = host.channel.stdout
                override val stdin = host.channel.stdin
                override val stderr = host.channel.stderr

                override fun close() {
                    closeEntered.complete(Unit)
                    check(releaseClose.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "Close was not released" }
                    host.channel.close()
                    transportClosed.complete(Unit)
                }
            }
            val connection = AcpConnection(channel, scope, Dispatchers.IO)
            try {
                val collector = async { connection.events.toList() }
                val pending = async { runCatching { connection.request("session/prompt", JsonObject(emptyMap())) } }
                host.read()
                connection.emit(AgentEvent("content", text = "last content"))
                connection.emit(AgentEvent("complete", status = "end_turn"))
                host.end()
                closeEntered.await()
                val events = withTimeout(500) { collector.await() }
                assertTrue(withTimeout(500) { pending.await() }.exceptionOrNull() is EOFException)
                assertFalse(transportClosed.isCompleted)
                val closing = async { connection.close() }
                assertEquals(null, kotlinx.coroutines.withTimeoutOrNull(100) { closing.await() })
                assertEquals(listOf("content", "complete", "error"), events.map { it.type })
                assertEquals("ACP stdout reached EOF", events.last().text)
                releaseClose.countDown()
                withTimeout(1000) { transportClosed.await() }
                closing.await()
                assertEquals(1, host.channel.closes.get())
            } finally {
                releaseClose.countDown()
                connection.close()
                host.channel.close()
                scope.cancel()
            }
        }
    }

    @Test
    fun cleanupTimeoutInterruptsTransportCloseAndBoundsExplicitClose() = runBlocking {
        withTimeout(5000) {
            val host = PipeHost()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val closeEntered = kotlinx.coroutines.CompletableDeferred<Unit>()
            val cleanupExited = kotlinx.coroutines.CompletableDeferred<Unit>()
            val interrupted = java.util.concurrent.atomic.AtomicBoolean(false)
            val channel = object : ExecChannel {
                override val stdout = host.channel.stdout
                override val stdin = host.channel.stdin
                override val stderr = host.channel.stderr

                override fun close() {
                    closeEntered.complete(Unit)
                    try {
                        java.util.concurrent.CountDownLatch(1).await()
                    } catch (_: InterruptedException) {
                        interrupted.set(true)
                    } finally {
                        host.channel.close()
                        cleanupExited.complete(Unit)
                    }
                }
            }
            val connection = AcpConnection(channel, scope, Dispatchers.IO, cleanupTimeoutMillis = 100)
            try {
                val collector = async { connection.events.toList() }
                val pending = async { runCatching { connection.request("session/prompt", JsonObject(emptyMap())) } }
                host.read()
                val closing = async { connection.close() }
                closeEntered.await()
                assertTrue(withTimeout(500) { pending.await() }.exceptionOrNull() is EOFException)
                withTimeout(500) { collector.await() }
                withTimeout(1000) { closing.await() }
                withTimeout(1000) { cleanupExited.await() }
                assertTrue(interrupted.get())
                assertEquals(1, host.channel.closes.get())
                connection.close()
                assertEquals(1, host.channel.closes.get())
            } finally {
                host.channel.close()
                scope.cancel()
            }
        }
    }

    @Test
    fun cancelledLoadDoesNotWaitForBlockedTransportCleanup() = runBlocking {
        withTimeout(10000) {
            val host = PipeHost()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val closeEntered = kotlinx.coroutines.CompletableDeferred<Unit>()
            val releaseClose = java.util.concurrent.CountDownLatch(1)
            val cleanupExited = kotlinx.coroutines.CompletableDeferred<Unit>()
            val wrapped = object : HostSession {
                override val hostId = host.hostId
                override suspend fun exec(command: String): ExecChannel {
                    host.exec(command)
                    return object : ExecChannel {
                        override val stdout = host.channel.stdout
                        override val stdin = host.channel.stdin
                        override val stderr = host.channel.stderr

                        override fun close() {
                            closeEntered.complete(Unit)
                            try {
                                releaseClose.await()
                            } finally {
                                host.channel.close()
                                cleanupExited.complete(Unit)
                            }
                        }
                    }
                }
            }
            try {
                val server = async(Dispatchers.IO) {
                    host.reply(host.read(), """{"protocolVersion":1,"agentCapabilities":{"loadSession":true}}""")
                    assertEquals("session/load", host.read().string("method"))
                }
                val handle = AcpDriver(AgentKind.KIMI, scope).prepare(wrapped, "s", "/tmp")
                val collector = async { handle.events.toList() }
                val loading = async { handle.load() }
                server.await()
                loading.cancel()
                withTimeout(500) { loading.join() }
                closeEntered.await()
                withTimeout(500) { collector.await() }
                assertFalse(cleanupExited.isCompleted)
                releaseClose.countDown()
                withTimeout(1000) { cleanupExited.await() }
            } finally {
                releaseClose.countDown()
                host.channel.close()
                scope.cancel()
            }
        }
    }

    @Test
    fun eofFailsPendingPromptAndFinishesEvents() = runBlocking {
        withTimeout(10000) {
            val host = PipeHost()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val server = async(Dispatchers.IO) {
                    host.reply(host.read(), """{"protocolVersion":1,"agentCapabilities":{}}""")
                    host.reply(host.read(), """{"sessionId":"s"}""")
                    host.read()
                    host.end()
                }
                val handle = AcpDriver(AgentKind.OMP, scope).prepare(host, null, "/tmp")
                val collector = async { handle.events.toList() }
                handle.load()
                try {
                    handle.send("hello")
                    throw AssertionError("EOF should fail the prompt")
                } catch (_: EOFException) {
                    // expected
                }
                assertEquals("error", collector.await().last().type)
                server.await()
            } finally {
                host.channel.close()
                scope.cancel()
            }
        }
    }

    @Test
    fun loadRequiresCapabilityAndClosesOnlyChannel() = runBlocking {
        withTimeout(10000) {
            val host = PipeHost()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val server = async(Dispatchers.IO) {
                    host.reply(host.read(), """{"protocolVersion":1,"agentCapabilities":{}}""")
                }
                try {
                    AcpDriver(AgentKind.KIMI, scope).open(host, "s", "/tmp")
                    throw AssertionError("Missing capability should fail")
                } catch (_: UnsupportedOperationException) {
                    // expected
                }
                server.await()
                assertTrue(host.channel.closes.get() > 0)
                assertEquals(1, host.execs)
            } finally {
                host.channel.close()
                scope.cancel()
            }
        }
    }

    @Test
    fun booleanListCapabilityIsNotAnObjectCapability() = runBlocking {
        withTimeout(10000) {
            val host = PipeHost()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val server = async(Dispatchers.IO) {
                    host.reply(host.read(), """{"protocolVersion":1,"agentCapabilities":{"sessionCapabilities":{"list":true}}}""")
                }
                try {
                    AcpDriver(AgentKind.OMP, scope).listSessions(host)
                    throw AssertionError("Boolean list capability should be rejected")
                } catch (_: UnsupportedOperationException) {
                    assertTrue(host.channel.closes.get() > 0)
                }
                server.await()
            } finally {
                host.channel.close()
                scope.cancel()
            }
        }
    }

    @Test
    fun scopeCancellationClosesChannelAndCompletesEvents() = runBlocking {
        withTimeout(10000) {
            val host = PipeHost()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val server = async(Dispatchers.IO) {
                    host.reply(host.read(), """{"protocolVersion":1,"agentCapabilities":{}}""")
                    host.reply(host.read(), """{"sessionId":"s"}""")
                }
                val handle = AcpDriver(AgentKind.KIMI, scope).prepare(host, null, "/tmp")
                val collector = async { handle.events.toList() }
                handle.load()
                server.await()
                scope.cancel()
                assertEquals("error", collector.await().last().type)
                withTimeout(1000) { host.channel.closed.await() }
                assertTrue(host.channel.closes.get() > 0)
            } finally {
                host.channel.close()
                scope.cancel()
            }
        }
    }

    @Test
    fun dshStreamsAllTranscriptPagesBeforeResumeAndConsumesResumeUpdatesBeforeReady() = runBlocking {
        withTimeout(10000) {
            val pipe = PipeHost()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            var pages = 0
            val historyConsumed = kotlinx.coroutines.CompletableDeferred<Unit>()
            val releaseHistory = kotlinx.coroutines.CompletableDeferred<Unit>()
            val host = object : HostSession {
                override val hostId = pipe.hostId
                override suspend fun exec(command: String): ExecChannel {
                    if (command.contains("--profile acp")) return pipe.exec(command)
                    val number = pages++
                    val body = """{"record":"event","hostId":42,"agent":"dsh","sessionId":"s","cwd":"/tmp","type":"user","text":"history$number","id":"m$number"}
                        |{"record":"page","count":1,"nextCursor":"${if (number == 0) "next" else ""}"}
                    """.trimMargin()
                    return object : ExecChannel {
                        override val stdout = ByteArrayInputStream(body.toByteArray())
                        override val stdin = java.io.ByteArrayOutputStream()
                        override val stderr = ByteArrayInputStream(byteArrayOf())
                        override fun close() = Unit
                    }
                }
            }
            try {
                val server = async(Dispatchers.IO) {
                    pipe.reply(pipe.read(), """{"protocolVersion":1,"agentCapabilities":{"loadSession":false,"sessionCapabilities":{"resume":{}}}}""")
                    val resume = pipe.read()
                    assertTrue(releaseHistory.isCompleted)
                    assertEquals(2, pages)
                    assertEquals("session/resume", resume.string("method"))
                    assertEquals("s", resume["params"]!!.jsonObject.string("sessionId"))
                    pipe.update("""{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"resumed"}}""")
                    pipe.reply(resume, "{}")
                }
                val handle = AcpDriver(AgentKind.DSH, scope).prepare(host, "s", "/tmp")
                val collected = mutableListOf<String>()
                val consumer = async {
                    handle.events.collect {
                        collected += it.text
                        if (it.text == "history1") {
                            historyConsumed.complete(Unit)
                            releaseHistory.await()
                        }
                    }
                }
                val loading = async { handle.load() }
                historyConsumed.await()
                assertFalse(loading.isCompleted)
                releaseHistory.complete(Unit)
                loading.await()
                assertEquals(listOf("history0", "history1", "resumed"), collected)
                server.await()
                handle.close()
                consumer.await()
            } finally {
                pipe.channel.close()
                scope.cancel()
            }
        }
    }

    @Test
    fun dshNewSessionDoesNotReadTranscriptOrRequireLoadCapability() = runBlocking {
        withTimeout(10000) {
            val host = PipeHost()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val server = async(Dispatchers.IO) {
                    host.reply(host.read(), """{"protocolVersion":1,"agentCapabilities":{}}""")
                    val create = host.read()
                    assertEquals("session/new", create.string("method"))
                    host.reply(create, """{"sessionId":"new-dsh"}""")
                }
                val handle = AcpDriver(AgentKind.DSH, scope).prepare(host, null, "/tmp")
                val collector = async { handle.events.toList() }
                handle.load()
                assertEquals("new-dsh", handle.sessionId)
                assertTrue(host.command.endsWith("exec dsh --profile acp"))
                assertEquals(1, host.execs)
                server.await()
                handle.close()
                collector.await()
                Unit
            } finally {
                host.channel.close()
                scope.cancel()
            }
        }
    }

    @Test
    fun sessionListUsesObjectCapabilityAndPagination() = runBlocking {
        withTimeout(10000) {
            val host = PipeHost()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val server = async(Dispatchers.IO) {
                    host.reply(host.read(), """{"protocolVersion":1,"agentCapabilities":{"sessionCapabilities":{"list":{}}}}""")
                    val first = host.read()
                    assertEquals("session/list", first.string("method"))
                    assertFalse(first["params"]!!.jsonObject.containsKey("cursor"))
                    host.reply(first, """{"sessions":[{"sessionId":"a","cwd":"/a","title":"A","updatedAt":"2026-01-01T00:00:00Z"}],"nextCursor":"page2"}""")
                    val second = host.read()
                    assertEquals("page2", second["params"]!!.jsonObject.string("cursor"))
                    host.reply(second, """{"sessions":[{"sessionId":"b","cwd":"/b"}]}""")
                }
                val sessions = AcpDriver(AgentKind.OMP, scope).listSessions(host)
                server.await()
                assertEquals(listOf("a", "b"), sessions.map { it.sessionId })
                assertEquals(42L, sessions.first().hostId)
                assertEquals(1767225600000L, sessions.first().updatedAt)
                assertTrue(host.channel.closes.get() > 0)
            } finally {
                host.channel.close()
                scope.cancel()
            }
        }
    }
}

internal class PipeHost : HostSession {
    val channel = PipeChannel()
    override val hostId = 42L
    var execs = 0
    var command = ""
    private val reader = NdjsonReader(channel.agentInput)

    override suspend fun exec(command: String): ExecChannel {
        this.command = command
        execs++
        return channel
    }

    suspend fun read(): JsonObject = withContext(Dispatchers.IO) {
        Json.parseToJsonElement(reader.readLine() ?: throw EOFException("Client closed")) as JsonObject
    }

    fun write(value: String) {
        channel.agentOutput.write(value.toByteArray(Charsets.UTF_8))
        channel.agentOutput.flush()
    }

    fun reply(request: JsonObject, result: String) = write(
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", request["id"]!!)
            put("result", Json.parseToJsonElement(result))
        }.toString() + "\n",
    )

    fun update(value: String) = write(
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "session/update")
            put(
                "params",
                buildJsonObject {
                    put("sessionId", "s")
                    put("update", Json.parseToJsonElement(value))
                },
            )
        }.toString() + "\n",
    )

    fun end() = channel.agentOutput.close()
}

internal class PipeChannel : ExecChannel {
    override val stdout = PipedInputStream(65536)
    val agentOutput = PipedOutputStream(stdout)
    val agentInput = PipedInputStream(65536)
    override val stdin = PipedOutputStream(agentInput)
    override val stderr = ByteArrayInputStream("private-stderr-body".toByteArray())
    val closes = AtomicInteger()
    val closed = kotlinx.coroutines.CompletableDeferred<Unit>()

    override fun close() {
        closes.incrementAndGet()
        listOf(stdout, stdin, stderr, agentOutput, agentInput).forEach { runCatching { it.close() } }
        closed.complete(Unit)
    }
}
