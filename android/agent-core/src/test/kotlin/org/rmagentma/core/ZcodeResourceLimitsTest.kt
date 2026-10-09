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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ZcodeResourceLimitsTest {
    @Test
    fun slowConsumerBackpressureIsReleasedWhenConsumerCancels() = runBlocking {
        withTimeout(10000) {
            val host = ZcodePipeHost()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val connection = ZcodeConnection(host.channel, scope, Dispatchers.IO, 1000)
            val consumed = CompletableDeferred<Unit>()
            connection.sessionId = "s"
            connection.subscribe()
            try {
                val collector = async {
                    connection.events.collect {
                        consumed.complete(Unit)
                        awaitCancellation()
                    }
                }
                val producer = async {
                    try {
                        repeat(MAX_BUFFERED_EVENTS + 10) { connection.emit(AgentEvent("content", text = "delta")) }
                        false
                    } catch (_: IOException) {
                        true
                    }
                }
                consumed.await()
                assertFalse(producer.isCompleted)
                collector.cancelAndJoin()
                assertTrue(producer.await())
                host.channel.awaitClosed()
                assertTrue(host.channel.closes.get() > 0)
            } finally {
                connection.close()
                host.channel.dispose()
                scope.cancel()
            }
        }
    }

    @Test
    fun incomingPermissionBudgetFailsClosedWithExplicitError() = runBlocking {
        withTimeout(10000) {
            val host = ZcodePipeHost()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val connection = ZcodeConnection(host.channel, scope, Dispatchers.IO, 1000)
            connection.sessionId = "s"
            try {
                val collector = async { connection.events.toList() }
                val server = async(Dispatchers.IO) {
                    repeat(MAX_INCOMING_REQUESTS + 1) { id ->
                        host.write("""{"id":$id,"method":"interaction/requestPermission","params":{"requestId":"p$id","sessionId":"s","options":[{"optionId":"deny","name":"Deny","response":{"decision":"deny"}}]}}""")
                    }
                    host.channel.awaitClosed()
                    val error = host.read()
                    assertEquals(MAX_INCOMING_REQUESTS.toString(), error.string("id"))
                    assertEquals(-32000, (error["error"] as JsonObject).string("code").toInt())
                    try {
                        host.read()
                        throw AssertionError("Budget rejection must close the protocol after its error response")
                    } catch (_: java.io.EOFException) {
                        assertTrue(host.channel.closed.isCompleted)
                    }
                }
                server.await()
                val events = collector.await()
                assertEquals(MAX_INCOMING_REQUESTS, events.count { it.type == "permission" })
                assertEquals("error", events.last().type)
                host.channel.awaitClosed()
                assertTrue(host.channel.closes.get() > 0)
            } finally {
                connection.close()
                host.channel.dispose()
                scope.cancel()
            }
        }
    }

    @Test
    fun oversizedFrameClosesPendingRequestAndDoesNotRetainFrame() = runBlocking {
        withTimeout(40000) {
            val host = ZcodePipeHost()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val connection = ZcodeConnection(host.channel, scope, Dispatchers.IO, 30000)
            try {
                val collector = async { connection.events.toList() }
                val server = async(Dispatchers.IO) {
                    host.read()
                    val chunk = ByteArray(8192) { 'x'.code.toByte() }
                    try {
                        repeat(MAX_FRAME_BYTES / chunk.size + 1) { host.channel.agentOutput.write(chunk) }
                    } catch (_: IOException) {
                        host.channel.awaitClosed()
                        assertTrue(host.channel.closes.get() > 0)
                    }
                }
                try {
                    connection.request("runtime/capabilities", buildJsonObject {})
                    throw AssertionError("Oversized frame must fail")
                } catch (_: IOException) {
                    host.channel.awaitClosed()
                    assertTrue(host.channel.closes.get() > 0)
                }
                server.await()
                assertTrue(collector.await().last().text.contains("byte limit"))
            } finally {
                connection.close()
                host.channel.dispose()
                scope.cancel()
            }
        }
    }

    @Test
    fun cancellationSendsDenyBeforeNativeStopAndRejectsStaleApproval(): Unit = runBlocking {
        withTimeout(10000) {
            val host = ZcodePipeHost()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val permissionSeen = CompletableDeferred<AgentEvent>()
            try {
                val server = async(Dispatchers.IO) {
                    host.ready()
                    host.reply(host.read(), """{"session":{"sessionId":"s","mode":"build"},"messages":[]}""")
                    host.subscribe()
                    host.write("""{"id":"approval","method":"interaction/requestPermission","params":{"requestId":"permission","sessionId":"s","options":[{"optionId":"allow","name":"Allow once","response":{"decision":"allow"}}]}}""")
                    assertEquals("deny", host.read()["result"]!!.jsonObject.string("decision"))
                    val stop = host.read()
                    assertEquals("session/stop", stop.string("method"))
                    host.reply(stop, "{}")
                }
                val handle = ZcodeDriver(scope).prepare(host, "s", "/work")
                val collector = async {
                    handle.events.collect { if (it.type == "permission") permissionSeen.complete(it) }
                }
                handle.load()
                val permission = permissionSeen.await()
                handle.cancel()
                try {
                    handle.respondPermission(permission.id, "allow")
                    throw AssertionError("Cancelled permission must not be approved")
                } catch (_: IllegalArgumentException) {
                    assertTrue(true)
                }
                server.await()
                handle.close()
                collector.await()
            } finally {
                host.channel.dispose()
                scope.cancel()
            }
        }
    }

    @Test
    fun reusedWireIdCannotApproveNewInteractionWithOldEventToken(): Unit = runBlocking {
        withTimeout(10000) {
            val host = ZcodePipeHost()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val connection = ZcodeConnection(host.channel, scope, Dispatchers.IO, 1000)
            val received = kotlinx.coroutines.channels.Channel<AgentEvent>(2)
            connection.sessionId = "s"
            try {
                val collector = async { connection.events.collect { received.send(it) } }
                val frame = """{"id":91,"method":"interaction/requestPermission","params":{"sessionId":"s","requestId":"permission","options":[{"optionId":"deny","name":"Deny","response":{"decision":"deny"}}]}}"""
                host.write(frame)
                val first = received.receive()
                connection.respond(connection.incoming(first.id, "interaction/requestPermission"), buildJsonObject { put("decision", "deny") })
                host.read()
                host.write(frame)
                val second = received.receive()
                assertFalse(first.id == second.id)
                try {
                    connection.incoming(first.id, "interaction/requestPermission")
                    throw AssertionError("Stale event token must not match a reused protocol ID")
                } catch (_: IllegalArgumentException) {
                    assertTrue(second.id.isNotBlank())
                }
                connection.respond(connection.incoming(second.id, "interaction/requestPermission"), buildJsonObject { put("decision", "deny") })
                host.read()
                connection.close()
                collector.await()
            } finally {
                connection.close()
                host.channel.dispose()
                scope.cancel()
            }
        }
    }

    @Test
    fun structuredQuestionsAndForeignSessionRequestsStayFailClosed(): Unit = runBlocking {
        withTimeout(10000) {
            val host = ZcodePipeHost()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val server = async(Dispatchers.IO) {
                    host.ready()
                    host.reply(host.read(), """{"session":{"sessionId":"s","mode":"build"},"messages":[]}""")
                    host.subscribe()
                    host.write("""{"id":"foreign","method":"interaction/requestPermission","params":{"sessionId":"other","requestId":"p","options":[{"optionId":"allow","response":{"decision":"allow"}}]}}""")
                    assertEquals("-32602", host.read()["error"]!!.jsonObject.string("code"))
                    host.write("""{"id":"q","method":"interaction/requestUserInput","params":{"sessionId":"s","requestId":"q","questions":[{"question":"Which?","header":"Select","options":[{"value":"a","label":"A"}]}]}}""")
                    val result = host.read()["result"]!!.jsonObject
                    assertEquals("accept", result.string("action"))
                    assertEquals("a", result["content"]!!.jsonObject.string("answer_0"))
                }
                val handle = ZcodeDriver(scope).prepare(host, "s", "/work")
                val collector = async {
                    handle.events.collect { event ->
                        if (event.type == "question") {
                            try {
                                handle.respondQuestion(event.id, "not JSON")
                                throw AssertionError("Structured questions must not accept an ambiguous answer")
                            } catch (_: IllegalArgumentException) {
                                assertTrue(event.raw != null)
                            }
                            handle.respondQuestion(event.id, """{"answer_0":"a","answers":{"Which?":"a"}}""")
                        }
                    }
                }
                handle.load()
                server.await()
                scope.cancel()
                collector.await()
                host.channel.awaitClosed()
                assertTrue(host.channel.closes.get() > 0)
            } finally {
                host.channel.dispose()
                scope.cancel()
            }
        }
    }

    @Test
    fun blockedPhysicalCloseDoesNotDelayPendingFailureOrTerminalEvents() = runBlocking {
        withTimeout(10000) {
            for (cancelScope in listOf(false, true)) {
                val host = ZcodePipeHost()
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val started = CompletableDeferred<Unit>()
                val finished = CompletableDeferred<Unit>()
                val release = java.util.concurrent.CountDownLatch(1)
                val channel = object : ExecChannel by host.channel {
                    override fun close() {
                        started.complete(Unit)
                        while (release.count > 0) {
                            try {
                                release.await()
                            } catch (_: InterruptedException) {
                                Thread.interrupted()
                            }
                        }
                        host.channel.close()
                        finished.complete(Unit)
                    }
                }
                val connection = ZcodeConnection(channel, scope, Dispatchers.IO, 1000, cleanupTimeoutMs = 200)
                try {
                    val collector = async { connection.events.toList() }
                    val pending = async {
                        try {
                            connection.request("runtime/capabilities", buildJsonObject {})
                            throw AssertionError("Closed connection must fail its pending request")
                        } catch (_: IOException) {
                            Unit
                        }
                    }
                    host.read()
                    if (cancelScope) scope.cancel() else host.channel.agentOutput.close()
                    withTimeout(1000) {
                        started.await()
                        pending.await()
                        assertEquals("error", collector.await().last().type)
                        connection.close()
                    }
                    assertFalse(finished.isCompleted)
                    assertEquals(0, host.channel.closes.get())
                    release.countDown()
                    withTimeout(1000) { finished.await() }
                    host.channel.awaitClosed()
                    assertEquals(1, host.channel.closes.get())
                } finally {
                    release.countDown()
                    connection.close()
                    withTimeout(1000) { finished.await() }
                    host.channel.dispose()
                    scope.cancel()
                }
            }
        }
    }

    @Test
    fun unansweredRequestHasBoundedTimeout() = runBlocking {
        withTimeout(10000) {
            val host = ZcodePipeHost()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val connection = ZcodeConnection(host.channel, scope, Dispatchers.IO, 100)
            try {
                try {
                    connection.request("runtime/capabilities", buildJsonObject { put("unused", false) })
                    throw AssertionError("Request should time out")
                } catch (_: TimeoutCancellationException) {
                    assertFalse(scope.coroutineContext[kotlinx.coroutines.Job]!!.isCancelled)
                }
            } finally {
                connection.close()
                host.channel.dispose()
                scope.cancel()
            }
        }
    }
}
