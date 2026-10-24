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
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

class AcpActivationTest {
    @Test
    fun activationWithoutTheAdvertisedCapabilityFailsExplicitlyAndSendsNothing() = runBlocking {
        withTimeout(10000) {
            val host = PipeHost()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val received = CopyOnWriteArrayList<String>()
            try {
                val server = async(Dispatchers.IO) {
                    while (true) {
                        val frame = runCatching { host.read() }.getOrNull() ?: break
                        received += frame.string("method")
                        if (frame.string("method") == "initialize") {
                            // Resume is offered, load is not: the product must not send session/load anyway.
                            host.reply(frame, """{"protocolVersion":1,"agentCapabilities":{"sessionCapabilities":{"resume":{}}}}""")
                        }
                    }
                }
                val failure = runCatching { AcpDriver(AgentKind.OPENCODE, scope).open(host, "s", "/tmp") }.exceptionOrNull()
                server.await()
                assertTrue("Expected an explicit capability failure, got $failure", failure is UnsupportedOperationException)
                assertTrue(failure?.message.orEmpty().contains("loadSession"))
                assertEquals(listOf("initialize"), received.toList())
                assertEquals(1, host.execs)
                assertTrue(host.channel.closes.get() > 0)
            } finally {
                host.channel.close()
                scope.cancel()
            }
        }
    }

    @Test
    fun unansweredActivationFailsAtTheBoundClosesTheAttemptAndStaysRetryable() = runBlocking {
        withTimeout(30000) {
            val silenceMillis = 1000L
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val driver = AcpDriver(AgentKind.OPENCODE, scope, activationTimeoutMillis = silenceMillis)
            val silent = PipeHost()
            val received = CopyOnWriteArrayList<String>()
            val silentServer = async(Dispatchers.IO) {
                while (true) {
                    val frame = runCatching { silent.read() }.getOrNull() ?: break
                    received += frame.string("method")
                    if (frame.string("method") == "initialize") {
                        silent.reply(frame, """{"protocolVersion":1,"agentCapabilities":{"loadSession":true}}""")
                    }
                    // The peer keeps the activation frame and never answers, as opencode 1.19.4 does.
                }
            }
            try {
                val handle = driver.open(silent, "s", "/tmp")
                val events = async { handle.events.toList() }
                val failure = runCatching { handle.load() }.exceptionOrNull()
                silentServer.await()
                assertTrue("Expected the activation bound to fail the load, got $failure", failure is IOException)
                val message = failure?.message.orEmpty()
                assertTrue(message.contains("session/load"))
                assertTrue(message.contains("within ${silenceMillis}ms"))
                assertEquals(listOf("initialize", "session/load"), received.toList())
                // The abandoned attempt leaves no half-open handle: its transport closed exactly once and
                // the closed session refuses further work instead of writing into a dead channel.
                assertEquals(1, silent.channel.closes.get())
                assertTrue(runCatching { handle.send("hello") }.exceptionOrNull() is IllegalStateException)
                handle.close()
                assertEquals(1, silent.channel.closes.get())
                events.await()

                // The same driver must still open the same session once the Agent answers again.
                val healthy = PipeHost()
                val healthyServer = async(Dispatchers.IO) {
                    healthy.reply(healthy.read(), """{"protocolVersion":1,"agentCapabilities":{"loadSession":true}}""")
                    val load = healthy.read()
                    assertEquals("session/load", load.string("method"))
                    healthy.reply(load, "{}")
                    healthy.reply(healthy.read(), """{"stopReason":"end_turn"}""")
                }
                val retry = driver.open(healthy, "s", "/tmp")
                val retryEvents = async { retry.events.toList() }
                retry.load()
                assertEquals("s", retry.sessionId)
                retry.send("hello")
                healthyServer.await()
                retry.close()
                assertEquals(1, healthy.channel.closes.get())
                retryEvents.await()
                Unit
            } finally {
                silent.channel.close()
                scope.cancel()
            }
        }
    }
}
