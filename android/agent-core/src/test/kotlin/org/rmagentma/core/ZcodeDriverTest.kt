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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
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
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicInteger

class ZcodeDriverTest {
    @Test
    fun resumeHistoryPreferencesInteractionsAndStreamingUseNativeProtocol() = runBlocking {
        withTimeout(10000) {
            val host = ZcodePipeHost()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val server = async(Dispatchers.IO) {
                    host.ready()
                    val resume = host.read()
                    assertEquals("session/resume", resume.string("method"))
                    assertFalse(resume["params"]!!.jsonObject.containsKey("workspace"))
                    host.write("""{"id":"prefs","method":"session/requestRuntimePreferences","params":{"sessionId":"s","scope":"runtime-materialization"}}""")
                    val preferences = host.read()
                    assertEquals(JsonPrimitive(false), preferences["result"]!!.jsonObject["askUserQuestionAutoResolutionEnabled"])
                    host.reply(resume, """{"session":{"sessionId":"s","mode":"yolo"},"messages":[{"info":{"sessionId":"s","messageId":"m","role":"user"},"parts":[{"sessionId":"s","messageId":"m","partId":"p","type":"text","text":"history"}]}]}""")
                    val mode = host.read()
                    assertEquals("session/setMode", mode.string("method"))
                    assertEquals("build", mode["params"]!!.jsonObject.string("mode"))
                    host.reply(mode, "{}")
                    host.subscribe()
                    val send = host.read()
                    assertEquals("session/send", send.string("method"))
                    assertEquals("hello", send["params"]!!.jsonObject.string("content"))
                    host.reply(send, """{"sessionId":"s","accepted":true,"stateRevision":2}""")
                    host.write("""{"id":91,"method":"interaction/requestPermission","params":{"requestId":"permission","sessionId":"s","toolName":"Bash","reason":"Execute?","options":[{"optionId":"deny","name":"Deny","kind":"deny_once","response":{"decision":"deny"}},{"optionId":"allow","name":"Allow once","kind":"allow_once","response":{"decision":"allow","reason":"User selected once"}}]}}""")
                    val permission = host.read()
                    assertEquals(JsonPrimitive(91), permission["id"])
                    assertEquals("allow", permission["result"]!!.jsonObject.string("decision"))
                    assertFalse(permission["result"]!!.jsonObject.containsKey("outcome"))
                    host.write("""{"id":"q","method":"interaction/requestUserInput","params":{"requestId":"question","sessionId":"s","prompt":"Name?"}}""")
                    assertEquals("Alex", host.read()["result"]!!.jsonObject["content"]!!.jsonObject.string("answer"))
                    host.write("""{"id":"unsupported","method":"interaction/providerRuntimeHeaders","params":{"sessionId":"s"}}""")
                    assertEquals(JsonPrimitive(-32601), host.read()["error"]!!.jsonObject["code"])
                    host.event(1, "model.streaming", """{"kind":"text_delta","delta":"answer"}""")
                    host.event(1, "model.streaming", """{"kind":"text_delta","delta":"duplicate"}""")
                    host.event(2, "model.streaming", """{"kind":"reasoning_delta","delta":"thinking"}""")
                    host.event(3, "tool.updated", """{"kind":"scheduled","toolCallId":"t","toolName":"Bash"}""")
                    host.event(4, "tool.updated", """{"kind":"result","toolCallId":"t","result":{"output":"ok"}}""")
                    host.event(
                        5,
                        "turn.completed",
                        buildJsonObject {
                            put("inputId", send["params"]!!.jsonObject.string("inputId"))
                            put("resultType", "success")
                            put("usage", buildJsonObject { put("input", 12) })
                        }.toString(),
                    )
                }
                val handle = ZcodeDriver(scope).prepare(host, "s", "/work dir")
                val events = mutableListOf<AgentEvent>()
                val collector = async {
                    handle.events.collect { event ->
                        events += event
                        when (event.type) {
                            "permission" -> {
                                try {
                                    handle.respondPermission(event.id, "invented")
                                    throw AssertionError("Invalid option should fail closed")
                                } catch (_: IllegalArgumentException) {
                                    assertTrue(event.options.isNotEmpty())
                                }
                                handle.respondPermission(event.id, "allow")
                                try {
                                    handle.respondPermission(event.id, "allow")
                                    throw AssertionError("Stale permission should fail")
                                } catch (_: IllegalArgumentException) {
                                    assertTrue(event.options.isNotEmpty())
                                }
                            }

                            "question" -> handle.respondQuestion(event.id, "Alex")
                        }
                    }
                }
                handle.load()
                assertEquals("history", events.first().text)
                handle.send("hello")
                server.await()
                handle.close()
                collector.await()
                assertEquals(listOf("user", "permission", "question", "content", "thinking", "tool", "tool_update", "complete", "usage"), events.map { it.type })
                assertTrue(host.command.contains("app-server"))
                assertTrue(host.command.contains("ZCODE_BUILTIN_PROVIDER_CONFIG_FILE"))
                assertFalse(host.command.contains("--stdio"))
            } finally {
                host.channel.dispose()
                scope.cancel()
            }
        }
    }

    @Test
    fun explicitWorkspaceIdentityIsPreservedInListAndResume(): Unit = runBlocking {
        withTimeout(10000) {
            val host = ZcodePipeHost()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val identity = "remote:wsl:default:/work"
            try {
                val server = async(Dispatchers.IO) {
                    host.ready()
                    val list = host.read()
                    val workspace = list["params"]!!.jsonObject["workspace"]!!.jsonObject
                    assertEquals(identity, workspace.string("workspaceIdentity"))
                    assertEquals(identity, workspace.string("workspaceKey"))
                    host.reply(list, """{"sessions":[{"sessionId":"s","workspace":{"workspaceKey":"$identity","workspaceIdentity":"$identity","workspacePath":"/work"},"title":"Title","updatedAt":42,"createdAt":1}]}""")
                }
                val records = ZcodeDriver(scope).listSessionRecords(host, cwd = "/work", workspaceIdentity = identity)
                server.await()
                assertEquals(identity, records.single().workspaceIdentity)
                assertEquals(42L, records.single().session.updatedAt)
                host.channel.awaitClosed()
                assertTrue(host.channel.closes.get() > 0)
            } finally {
                host.channel.dispose()
                scope.cancel()
            }
            val resumeHost = ZcodePipeHost()
            val resumeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val server = async(Dispatchers.IO) {
                    resumeHost.ready()
                    val resume = resumeHost.read()
                    assertEquals(identity, resume["params"]!!.jsonObject["workspace"]!!.jsonObject.string("workspaceIdentity"))
                    resumeHost.reply(resume, """{"session":{"sessionId":"s","mode":"build"},"messages":[]}""")
                    resumeHost.subscribe()
                }
                val handle = ZcodeDriver(resumeScope).prepare(resumeHost, "s", "/work", identity)
                val collector = async { handle.events.toList() }
                handle.load()
                server.await()
                handle.close()
                collector.await()
            } finally {
                resumeHost.channel.dispose()
                resumeScope.cancel()
            }
        }
    }

    @Test
    fun globalListDoesNotConstructWorkspaceFromCwd() = runBlocking {
        withTimeout(10000) {
            val host = ZcodePipeHost()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val server = async(Dispatchers.IO) {
                    host.ready()
                    val list = host.read()
                    assertFalse(list["params"]!!.jsonObject.containsKey("workspace"))
                    host.reply(list, """{"sessions":[]}""")
                }
                assertTrue(ZcodeDriver(scope).listSessions(host).isEmpty())
                server.await()
                try {
                    ZcodeDriver(scope).listSessionRecords(host, cwd = "/work")
                    throw AssertionError("Cwd-only list must be rejected")
                } catch (_: IllegalArgumentException) {
                    Unit
                }
            } finally {
                host.channel.dispose()
                scope.cancel()
            }
        }
    }

    @Test
    fun createStopAndEofFailPendingTurnWithoutHoldingPromptMutex() = runBlocking {
        withTimeout(10000) {
            val host = ZcodePipeHost()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val promptSeen = CompletableDeferred<Unit>()
            try {
                val server = async(Dispatchers.IO) {
                    host.ready()
                    val create = host.read()
                    assertEquals("session/create", create.string("method"))
                    assertEquals("build", create["params"]!!.jsonObject.string("mode"))
                    host.reply(create, """{"session":{"sessionId":"s","mode":"build"},"messages":[]}""")
                    host.subscribe()
                    val send = host.read()
                    host.reply(send, """{"sessionId":"s","accepted":true,"stateRevision":1}""")
                    promptSeen.complete(Unit)
                    val stop = host.read()
                    assertEquals("session/stop", stop.string("method"))
                    assertTrue(stop.containsKey("id"))
                    host.reply(stop, "{}")
                }
                val handle = ZcodeDriver(scope).prepare(host, null, "/tmp")
                val collector = async { handle.events.toList() }
                handle.load()
                val prompt = async {
                    try {
                        handle.send("hello")
                        false
                    } catch (_: EOFException) {
                        true
                    }
                }
                promptSeen.await()
                handle.cancel()
                server.await()
                host.channel.agentOutput.close()
                assertTrue(prompt.await())
                assertEquals("error", collector.await().last().type)
            } finally {
                host.channel.dispose()
                scope.cancel()
            }
        }
    }

    @Test
    fun missingConsumerTimesOutBeforeResumeAndClosesChannel() = runBlocking {
        withTimeout(10000) {
            val host = ZcodePipeHost()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val server = async(Dispatchers.IO) { host.ready() }
                val handle = ZcodeDriver(scope, requestTimeoutMs = 500).prepare(host, "s", "/work")
                server.await()
                try {
                    handle.load()
                    throw AssertionError("Missing consumer should time out")
                } catch (_: TimeoutCancellationException) {
                    host.channel.awaitClosed()
                    assertTrue(host.channel.closes.get() > 0)
                }
            } finally {
                host.channel.dispose()
                scope.cancel()
            }
        }
    }

    @Test
    fun malformedFramesAndStorageFailureTerminateWithoutLeakingContent() = runBlocking {
        withTimeout(10000) {
            for (frame in listOf("private-invalid-json", """{"method":"startup/storageState","params":{"phase":"failed","databaseKind":"session"}}""")) {
                val host = ZcodePipeHost()
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                try {
                    val server = async(Dispatchers.IO) { host.write(frame) }
                    try {
                        ZcodeDriver(scope).prepare(host, null, "/work")
                        throw AssertionError("Invalid startup should fail")
                    } catch (e: IOException) {
                        assertFalse(e.message.orEmpty().contains("private-invalid-json"))
                        host.channel.awaitClosed()
                        assertTrue(host.channel.closes.get() > 0)
                    }
                    server.await()
                } finally {
                    host.channel.dispose()
                    scope.cancel()
                }
            }
        }
    }

    @Test
    fun launchQuotingUsesPosixShellAndNeverAcceptsControlCharacters() {
        val command = ZcodeLaunchConfig(executable = "/a path/z'code").command("/work dir/it's safe")
        assertTrue(command.startsWith("/bin/sh -c "))
        assertFalse(command.contains("--prompt"))
        for (path in listOf("relative", "/work\ninvalid", "/work\u0000bad")) {
            try {
                ZcodeLaunchConfig().command(path)
                throw AssertionError("Unsafe cwd accepted")
            } catch (_: IllegalArgumentException) {
                assertTrue(path.isNotEmpty())
            }
        }
    }
}

internal class ZcodePipeHost : HostSession {
    val channel = ZcodePipeChannel()
    override val hostId = 42L
    var command = ""
    private val reader = NdjsonReader(channel.agentInput)

    override suspend fun exec(command: String): ExecChannel {
        this.command = command
        return channel
    }

    suspend fun read(): JsonObject = withContext(Dispatchers.IO) {
        (Json.parseToJsonElement(reader.readLine() ?: throw EOFException("Client closed")) as JsonObject).also {
            assertFalse(it.containsKey("jsonrpc"))
        }
    }

    fun write(frame: String) {
        channel.agentOutput.write((frame + "\n").toByteArray(Charsets.UTF_8))
        channel.agentOutput.flush()
    }

    fun reply(request: JsonObject, result: String) = write(
        buildJsonObject {
            put("id", request["id"]!!)
            put("result", Json.parseToJsonElement(result))
        }.toString(),
    )

    suspend fun ready() {
        write("""{"method":"startup/storageState","params":{"phase":"checking","databaseKind":"session"}}""")
        write("""{"method":"startup/storageState","params":{"phase":"ready","databaseKind":"session"}}""")
        val capabilities = read()
        assertEquals("runtime/capabilities", capabilities.string("method"))
        reply(capabilities, """{"independentPlanState":true}""")
    }

    suspend fun subscribe() {
        val subscribe = read()
        assertEquals("session/subscribe", subscribe.string("method"))
        assertEquals("desktop-continuous", subscribe["params"]!!.jsonObject.string("deliveryKind"))
        reply(subscribe, """{"sessionId":"s","eventSeq":0,"events":[]}""")
    }

    fun event(seq: Long, type: String, payload: String) = write(
        buildJsonObject {
            put("method", "session/event")
            put(
                "params",
                buildJsonObject {
                    put("sessionId", "s")
                    put("eventId", "e$seq")
                    put("seq", seq)
                    put("type", type)
                    put("payload", Json.parseToJsonElement(payload))
                },
            )
        }.toString(),
    )
}

internal class ZcodePipeChannel : ExecChannel {
    override val stdout = PipedInputStream(65536)
    val agentOutput = PipedOutputStream(stdout)
    val agentInput = PipedInputStream(65536)
    override val stdin = PipedOutputStream(agentInput)
    override val stderr = ByteArrayInputStream("private-stderr-body".toByteArray())
    val closes = AtomicInteger()
    val closed = CompletableDeferred<Unit>()

    suspend fun awaitClosed() = withTimeout(5000) { closed.await() }

    override fun close() {
        closes.incrementAndGet()
        listOf(stdout, stdin, stderr).forEach { runCatching { it.close() } }
        closed.complete(Unit)
    }

    fun dispose() {
        close()
        listOf(agentInput, agentOutput).forEach { runCatching { it.close() } }
    }
}
