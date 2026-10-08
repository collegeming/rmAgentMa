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
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AcpResourceLimitsTest {
    @Test
    fun eventCountLimitNeverBlocksLoadResponse() = runBlocking {
        withTimeout(15000) {
            val host = PipeHost()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val server = async(Dispatchers.IO) {
                    host.reply(host.read(), """{"protocolVersion":1,"agentCapabilities":{"loadSession":true}}""")
                    host.read()
                    try {
                        repeat(MAX_BUFFERED_EVENTS + 1) {
                            host.update("""{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"x"}}""")
                        }
                    } catch (_: IOException) {
                        // The client closes the channel on overflow before the load response.
                    }
                }
                try {
                    AcpDriver(AgentKind.KIMI, scope).open(host, "s", "/tmp")
                    throw AssertionError("History overflow should fail open")
                } catch (e: AcpBufferOverflowException) {
                    assertTrue(e.message!!.contains("terminal fallback"))
                }
                server.await()
                assertTrue(host.channel.closes.get() > 0)
            } finally {
                host.channel.close()
                scope.cancel()
            }
        }
    }

    @Test
    fun eventByteOverflowClosesPendingAndProvidesOneTerminalDiagnostic() = runBlocking {
        withTimeout(15000) {
            val host = PipeHost()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val connection = AcpConnection(host.channel, scope, Dispatchers.IO)
            try {
                val request = async { runCatching { connection.request("session/load", JsonObject(emptyMap())) } }
                host.read()
                connection.emit(AgentEvent("content", text = "x".repeat(MAX_BUFFERED_EVENT_BYTES)))
                assertTrue(request.await().exceptionOrNull() is AcpBufferOverflowException)
                val events = connection.events.toList()
                assertEquals(1, events.size)
                assertEquals("error", events.single().type)
                assertTrue(events.single().text.contains("terminal fallback"))
                assertTrue(host.channel.closes.get() > 0)
            } finally {
                connection.close()
                scope.cancel()
            }
        }
    }

    @Test
    fun activeSlowConsumerStillCountsItsInFlightEvent() = runBlocking {
        withTimeout(10000) {
            val buffer = BoundedEventBuffer(maxBytes = 4096, maxEvents = 2)
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val collected = mutableListOf<AgentEvent>()
            assertTrue(buffer.offer(AgentEvent("content", text = "first")))
            val consumer = async {
                buffer.events.collect {
                    collected += it
                    if (it.text == "first") {
                        started.complete(Unit)
                        release.await()
                    }
                }
            }
            started.await()
            assertTrue(buffer.offer(AgentEvent("content", text = "second")))
            assertFalse(buffer.offer(AgentEvent("content", text = "third")))
            buffer.close("buffer full", discard = true)
            release.complete(Unit)
            consumer.await()
            assertEquals(listOf("first", "buffer full"), collected.map { it.text })
        }
    }

    @Test
    fun consumedEventsReleaseByteBudget() = runBlocking {
        withTimeout(10000) {
            val buffer = BoundedEventBuffer(maxBytes = 1200, maxEvents = 1)
            val first = AgentEvent("content", text = "é".repeat(50))
            assertTrue(buffer.offer(first))
            assertFalse(buffer.offer(AgentEvent("content", text = "x")))
            val consumer = async { buffer.events.toList() }
            while (!buffer.offer(first)) kotlinx.coroutines.yield()
            buffer.close()
            assertEquals(listOf(first, first), consumer.await())
        }
    }

    @Test
    fun serverRequestCountAndByteLimitsClosePendingPrompts() = runBlocking {
        withTimeout(15000) {
            for (oversized in listOf(false, true)) {
                val host = PipeHost()
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val connection = AcpConnection(host.channel, scope, Dispatchers.IO)
                try {
                    val pending = async { runCatching { connection.request("session/prompt", JsonObject(emptyMap())) } }
                    host.read()
                    val server = async(Dispatchers.IO) {
                        try {
                            repeat(if (oversized) 1 else MAX_INCOMING_REQUESTS + 1) { id ->
                                host.write(
                                    buildJsonObject {
                                        put("jsonrpc", "2.0")
                                        put("id", id)
                                        put("method", "session/request_permission")
                                        put(
                                            "params",
                                            buildJsonObject {
                                                put("sessionId", "s")
                                                put("message", if (oversized) "x".repeat(MAX_INCOMING_REQUEST_BYTES) else "")
                                            },
                                        )
                                    }.toString() + "\n",
                                )
                            }
                        } catch (_: IOException) {
                            // expected channel shutdown
                        }
                    }
                    val failure = pending.await().exceptionOrNull()
                    assertTrue(failure is IOException)
                    assertTrue(failure!!.message!!.contains("server request buffer"))
                    server.await()
                    val events = connection.events.toList()
                    assertEquals(1, events.size)
                    assertEquals("error", events.single().type)
                    try {
                        connection.incoming("0", "session/request_permission")
                        throw AssertionError("Incoming requests must be cleared")
                    } catch (_: IllegalArgumentException) {
                        // expected
                    }
                } finally {
                    connection.close()
                    scope.cancel()
                }
            }
        }
    }

    @Test
    fun listingStopsAtRequestedLimitAndKeepsDefaultCallCompatible() = runBlocking {
        withTimeout(10000) {
            for (limit in listOf(2, 200, 1000)) {
                val host = PipeHost()
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                try {
                    val server = async(Dispatchers.IO) {
                        host.reply(host.read(), """{"protocolVersion":1,"agentCapabilities":{"sessionCapabilities":{"list":{}}}}""")
                        val page = host.read()
                        host.reply(
                            page,
                            buildJsonObject {
                                put(
                                    "sessions",
                                    buildJsonArray {
                                        repeat(limit + 1) { id ->
                                            add(
                                                buildJsonObject {
                                                    put("sessionId", "s$id")
                                                    put("cwd", "/tmp")
                                                },
                                            )
                                        }
                                    },
                                )
                                put("nextCursor", "must-not-fetch")
                            }.toString(),
                        )
                    }
                    val driver: AgentDriver = AcpDriver(AgentKind.OMP, scope)
                    val result = if (limit == 200) driver.listSessions(host) else driver.listSessions(host, limit)
                    assertEquals(limit, result.size)
                    server.await()
                    assertTrue(host.channel.closes.get() > 0)
                } finally {
                    host.channel.close()
                    scope.cancel()
                }
            }
        }
    }

    @Test
    fun paginationRejectsRepeatedAndEndlessFreshCursors() = runBlocking {
        withTimeout(15000) {
            for (repeatCursor in listOf(false, true)) {
                val host = PipeHost()
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                try {
                    val server = async(Dispatchers.IO) {
                        host.reply(host.read(), """{"protocolVersion":1,"agentCapabilities":{"sessionCapabilities":{"list":{}}}}""")
                        repeat(if (repeatCursor) 2 else MAX_LIST_PAGES) { index ->
                            val page = host.read()
                            host.reply(page, """{"sessions":[],"nextCursor":"${if (repeatCursor) "same" else "page$index"}"}""")
                        }
                    }
                    try {
                        AcpDriver(AgentKind.OMP, scope).listSessions(host)
                        throw AssertionError("Endless pagination must fail")
                    } catch (e: IOException) {
                        assertTrue(e.message!!.contains(if (repeatCursor) "repeated" else "page limit"))
                    }
                    server.await()
                    assertTrue(host.channel.closes.get() > 0)
                } finally {
                    host.channel.close()
                    scope.cancel()
                }
            }
        }
    }

    @Test
    fun listRejectsInvalidLimitsBeforeExecutingRemoteCommands() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val host = PipeHost()
            for (limit in listOf(-1, 0, 1001)) {
                try {
                    AcpDriver(AgentKind.OMP, scope).listSessions(host, limit)
                    throw AssertionError("Invalid limit should fail")
                } catch (_: IllegalArgumentException) {
                    assertEquals(0, host.execs)
                }
            }
            host.channel.close()
        } finally {
            scope.cancel()
        }
    }
}
