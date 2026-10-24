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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZcodeConfigTest {
    @Test
    fun createPublishesFullCatalogAndStateUpdateNeverShrinksIt() = runBlocking {
        withTimeout(10000) {
            val host = ZcodePipeHost()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val updated = CompletableDeferred<AgentEvent>()
            try {
                val server = async(Dispatchers.IO) {
                    host.ready()
                    val create = host.read()
                    assertEquals("session/create", create.string("method"))
                    host.reply(create, fullSnapshot())
                    host.subscribe()
                    host.write(
                        """{"method":"state.updated","params":{"scope":"session","sessionId":"s","revision":7,"reason":"model_changed","type":"state.updated","patch":{"model":{"current":{"providerId":"p","modelId":"m2"},"available":$CURRENT_CATALOG},"thoughtLevel":{"enabled":true,"current":"low","defaultLevel":"low","available":[{"value":"low","label":"Low"}]},"mode":{"current":"build"},"permission":{"mode":"build"},"futurePatchField":"kept"}}}""",
                    )
                }
                val handle = ZcodeDriver(scope).prepare(host, null, "/work")
                val collector = async {
                    handle.events.collect { if (it.raw?.string("revision") == "7") updated.complete(it) }
                }
                handle.load()
                server.await()
                assertEquals("session_info", updated.await().type)
                val config = handle.config
                assertEquals(ZcodeCatalogCompleteness.FULL, config.catalogCompleteness)
                assertEquals(2, config.catalog.size)
                assertEquals("m1", config.catalog.first().selection.modelId)
                assertEquals("Provider", config.catalog.first().providerLabel)
                assertEquals(100000L, config.catalog.first().contextWindow)
                assertEquals(4096L, config.catalog.first().maxOutputTokens)
                assertEquals(listOf("high", "low"), config.catalog.first().reasoningLevels.map { it.value })
                assertEquals("high", config.catalog.first().defaultReasoningLevel)
                assertTrue(config.catalog.first().raw.containsKey("futureCapability"))
                assertEquals("m2", config.currentModel?.modelId)
                assertEquals("low", config.thought.current)
                assertEquals("build", config.currentMode)
                assertEquals(7L, config.revision)
                assertEquals("kept", config.raw.string("futurePatchField"))
                handle.close()
                collector.await()
                Unit
            } finally {
                host.channel.dispose()
                scope.cancel()
            }
        }
    }

    @Test
    fun resumePublishesFullCatalogAndCurrentSelection() = runBlocking {
        withTimeout(10000) {
            val host = ZcodePipeHost()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val server = async(Dispatchers.IO) {
                    host.ready()
                    val resume = host.read()
                    assertEquals("session/resume", resume.string("method"))
                    host.reply(resume, fullSnapshot())
                    host.subscribe()
                }
                val handle = ZcodeDriver(scope).prepare(host, "s", "/work")
                val collector = async { handle.events.toList() }
                handle.load()
                server.await()
                assertEquals(ZcodeCatalogCompleteness.FULL, handle.config.catalogCompleteness)
                assertEquals(listOf("m1", "m2"), handle.config.catalog.map { it.selection.modelId })
                assertEquals("m1", handle.config.currentModel?.modelId)
                assertEquals("high", handle.config.currentModel?.reasoningLevel)
                handle.close()
                collector.await()
                Unit
            } finally {
                host.channel.dispose()
                scope.cancel()
            }
        }
    }

    @Test
    fun setModelReturnsAuthoritativeSnapshotWithoutReplacingCatalog() = runBlocking {
        withTimeout(10000) {
            val host = ZcodePipeHost()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val server = async(Dispatchers.IO) {
                    host.ready()
                    val create = host.read()
                    host.reply(create, fullSnapshot())
                    host.subscribe()
                    val set = host.read()
                    assertEquals("session/setModel", set.string("method"))
                    val params = set["params"]!!.jsonObject
                    val model = params["model"]!!.jsonObject
                    assertEquals("p", model.string("providerId"))
                    assertEquals("m2", model.string("modelId"))
                    assertEquals("low", model["options"]!!.jsonObject.string("reasoningLevel"))
                    assertFalse(params.containsKey("expectedRevision"))
                    host.reply(
                        set,
                        fullSnapshot(
                            currentModel = "m2",
                            thought = "low",
                            available = CURRENT_CATALOG,
                            runtime = """{"stateRevision":2}""",
                        ),
                    )
                }
                val handle = ZcodeDriver(scope).prepare(host, null, "/work")
                val collector = async { handle.events.toList() }
                handle.load()
                val change = handle.setModel("p", "m2", "low")
                server.await()
                assertEquals(ZcodeConfigStatus.CONFIRMED, change.status)
                assertEquals("p/m2@low", change.applied)
                assertEquals(2L, change.revision)
                assertFalse(change.busy)
                assertEquals("m2", change.config.currentModel?.modelId)
                assertEquals(2, change.config.catalog.size)
                assertEquals(ZcodeCatalogCompleteness.FULL, change.config.catalogCompleteness)
                handle.close()
                collector.await()
                Unit
            } finally {
                host.channel.dispose()
                scope.cancel()
            }
        }
    }

    @Test
    fun ackOnlySetterReplyStaysPendingAndKeepsCurrentValue() = runBlocking {
        withTimeout(10000) {
            val host = ZcodePipeHost()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val server = async(Dispatchers.IO) {
                    host.ready()
                    val create = host.read()
                    host.reply(create, fullSnapshot())
                    host.subscribe()
                    val set = host.read()
                    assertEquals("session/setThoughtLevel", set.string("method"))
                    assertEquals("low", set["params"]!!.jsonObject.string("thoughtLevel"))
                    host.reply(set, """{"accepted":true,"stateRevision":5}""")
                }
                val handle = ZcodeDriver(scope).prepare(host, null, "/work")
                val collector = async { handle.events.toList() }
                handle.load()
                val change = handle.setThoughtLevel("low")
                server.await()
                assertEquals(ZcodeConfigStatus.DELIVERED_PENDING, change.status)
                assertNull(change.applied)
                assertEquals(5L, change.revision)
                assertEquals("high", change.config.thought.current)
                assertEquals("high", handle.config.thought.current)
                assertEquals(1L, handle.config.revision)
                assertEquals(2, handle.config.catalog.size)
                handle.close()
                collector.await()
                Unit
            } finally {
                host.channel.dispose()
                scope.cancel()
            }
        }
    }

    @Test
    fun setterFailureIsSurfacedAndSessionRemainsUsable() = runBlocking {
        withTimeout(10000) {
            val host = ZcodePipeHost()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val server = async(Dispatchers.IO) {
                    host.ready()
                    val create = host.read()
                    host.reply(create, fullSnapshot())
                    host.subscribe()
                    val denied = host.read()
                    assertEquals("yolo", denied["params"]!!.jsonObject.string("mode"))
                    host.write("""{"id":${denied["id"]},"error":{"code":-32009,"message":"Session state revision mismatch"}}""")
                    val retry = host.read()
                    assertEquals("build", retry["params"]!!.jsonObject.string("mode"))
                    host.reply(retry, fullSnapshot(currentMode = "build", runtime = """{"stateRevision":2}"""))
                }
                val handle = ZcodeDriver(scope).prepare(host, null, "/work")
                val collector = async { handle.events.toList() }
                handle.load()
                val failure = runCatching { handle.setMode("yolo") }.exceptionOrNull()
                assertTrue(failure is ZcodeException)
                assertEquals(-32009, (failure as ZcodeException).code)
                assertTrue(failure.message.orEmpty().contains("revision mismatch"))
                val retry = handle.setMode("build")
                server.await()
                assertEquals(ZcodeConfigStatus.CONFIRMED, retry.status)
                assertEquals("build", retry.applied)
                handle.close()
                collector.await()
                Unit
            } finally {
                host.channel.dispose()
                scope.cancel()
            }
        }
    }

    @Test
    fun busyModeErrorIsTypedAndStillFails() = runBlocking {
        withTimeout(10000) {
            val host = ZcodePipeHost()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val server = async(Dispatchers.IO) {
                    host.ready()
                    val create = host.read()
                    host.reply(create, fullSnapshot())
                    host.subscribe()
                    val busy = host.read()
                    host.write(
                        """{"id":${busy["id"]},"error":{"code":-32603,"message":"Permission update is busy; retry mode change"}}""",
                    )
                }
                val handle = ZcodeDriver(scope).prepare(host, null, "/work")
                val collector = async { handle.events.toList() }
                handle.load()
                val failure = runCatching { handle.setMode("plan") }.exceptionOrNull()
                server.await()
                assertTrue(failure is ZcodeBusyException)
                assertEquals(-32603, (failure as ZcodeBusyException).code)
                assertTrue(failure.message.orEmpty().contains("busy"))
                handle.close()
                collector.await()
                Unit
            } finally {
                host.channel.dispose()
                scope.cancel()
            }
        }
    }

    @Test
    fun unknownNotificationsKeepRawFramesAndDoNotStopTheSession() = runBlocking {
        withTimeout(10000) {
            val host = ZcodePipeHost()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val server = async(Dispatchers.IO) {
                    host.ready()
                    val create = host.read()
                    host.reply(create, fullSnapshot())
                    host.subscribe()
                    host.write("""{"method":"future/notification","params":{"sessionId":"s","payload":{"x":1}}}""")
                    host.event(1, "future.event", """{"x":2}""")
                    val set = host.read()
                    assertEquals("session/setMode", set.string("method"))
                    host.reply(set, fullSnapshot(currentMode = "edit", runtime = """{"stateRevision":2}"""))
                }
                val handle = ZcodeDriver(scope).prepare(host, null, "/work")
                val unknown = Channel<AgentEvent>(4)
                val collector = async {
                    handle.events.collect { if (it.type == "unknown") unknown.send(it) }
                }
                handle.load()
                val notification = unknown.receive()
                assertEquals("future/notification", notification.title)
                assertEquals("1", notification.raw?.get("payload")?.jsonObject?.get("x").toString())
                val event = unknown.receive()
                assertEquals("future.event", event.title)
                assertEquals("future.event", event.raw?.string("type"))
                assertEquals("2", event.raw?.get("payload")?.jsonObject?.get("x").toString())
                val change = handle.setMode("edit")
                server.await()
                assertEquals(ZcodeConfigStatus.CONFIRMED, change.status)
                assertEquals("edit", change.config.currentMode)
                handle.close()
                collector.await()
                Unit
            } finally {
                host.channel.dispose()
                scope.cancel()
            }
        }
    }

    @Test
    fun readCurrentOnlySnapshotKeepsFullCatalog() = runBlocking {
        withTimeout(10000) {
            val host = ZcodePipeHost()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val server = async(Dispatchers.IO) {
                    host.ready()
                    val create = host.read()
                    host.reply(create, fullSnapshot())
                    host.subscribe()
                    val read = host.read()
                    assertEquals("session/read", read.string("method"))
                    host.reply(
                        read,
                        fullSnapshot(
                            currentModel = "m2",
                            thought = "low",
                            available = CURRENT_CATALOG,
                            runtime = """{"stateRevision":2}""",
                        ),
                    )
                }
                val handle = ZcodeDriver(scope).prepare(host, null, "/work")
                val collector = async { handle.events.toList() }
                handle.load()
                val config = handle.read()
                server.await()
                assertEquals("m2", config.currentModel?.modelId)
                assertEquals("low", config.thought.current)
                assertEquals(2, config.catalog.size)
                assertEquals(ZcodeCatalogCompleteness.FULL, config.catalogCompleteness)
                assertEquals(2L, config.revision)
                handle.close()
                collector.await()
                Unit
            } finally {
                host.channel.dispose()
                scope.cancel()
            }
        }
    }

    @Test
    fun currentOnlyBeforeAnyCatalogIsMarkedAndInventsNoEntries() = runBlocking {
        withTimeout(10000) {
            val host = ZcodePipeHost()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val server = async(Dispatchers.IO) {
                    host.ready()
                    val create = host.read()
                    host.reply(
                        create,
                        """{"session":{"sessionId":"s","mode":"build"},"messages":[],"runtime":{"stateRevision":1}}""",
                    )
                    host.subscribe()
                    val read = host.read()
                    assertEquals("session/read", read.string("method"))
                    host.reply(
                        read,
                        """{"session":{"sessionId":"s","mode":"build"},"messages":[],"runtime":{"stateRevision":2},"settings":{"mode":{"current":"build"},"model":{"current":{"providerId":"p","modelId":"m1"},"available":[{"ref":{"providerId":"p","modelId":"m1"},"label":"Model One","properties":{"inputFormat":"text","outputFormat":"text"}}]},"thoughtLevel":{"enabled":true,"current":"high","available":[{"value":"high","label":"High"}]},"permission":{"mode":"build"}}}""",
                    )
                }
                val handle = ZcodeDriver(scope).prepare(host, null, "/work")
                val collector = async { handle.events.toList() }
                handle.load()
                assertEquals(ZcodeCatalogCompleteness.UNKNOWN, handle.config.catalogCompleteness)
                assertTrue(handle.config.catalog.isEmpty())
                assertNull(handle.config.currentModel)
                assertNull(handle.config.currentMode)
                assertNull(handle.config.thought.current)
                val config = handle.read()
                server.await()
                assertEquals(ZcodeCatalogCompleteness.CURRENT_ONLY, config.catalogCompleteness)
                assertTrue(config.catalog.isEmpty())
                assertEquals("m1", config.currentModel?.modelId)
                assertEquals(2L, config.revision)
                handle.close()
                collector.await()
                Unit
            } finally {
                host.channel.dispose()
                scope.cancel()
            }
        }
    }

    @Test
    fun busySnapshotIsDistinguishableFromIdleConfirmation() = runBlocking {
        withTimeout(10000) {
            val host = ZcodePipeHost()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val server = async(Dispatchers.IO) {
                    host.ready()
                    val create = host.read()
                    host.reply(create, fullSnapshot())
                    host.subscribe()
                    val busy = host.read()
                    assertEquals("session/setModel", busy.string("method"))
                    host.reply(
                        busy,
                        fullSnapshot(
                            currentModel = "m2",
                            thought = "low",
                            available = CURRENT_CATALOG,
                            runtime = """{"stateRevision":2,"activeTurnId":"turn-9","activeTurnKind":"regular"}""",
                        ),
                    )
                    val idle = host.read()
                    assertEquals("session/setThoughtLevel", idle.string("method"))
                    host.reply(
                        idle,
                        fullSnapshot(
                            currentModel = "m2",
                            thought = "high",
                            available = CURRENT_CATALOG,
                            runtime = """{"stateRevision":3}""",
                        ),
                    )
                }
                val handle = ZcodeDriver(scope).prepare(host, null, "/work")
                val collector = async { handle.events.toList() }
                handle.load()
                val duringTurn = handle.setModel("p", "m2", "low")
                val afterTurn = handle.setThoughtLevel("high")
                server.await()
                assertEquals(ZcodeConfigStatus.CONFIRMED, duringTurn.status)
                assertTrue(duringTurn.busy)
                assertEquals(ZcodeConfigStatus.CONFIRMED, afterTurn.status)
                assertFalse(afterTurn.busy)
                handle.close()
                collector.await()
                Unit
            } finally {
                host.channel.dispose()
                scope.cancel()
            }
        }
    }
}

private const val CURRENT_CATALOG =
    """[{"ref":{"providerId":"p","modelId":"m2"},"label":"Model Two","properties":{"inputFormat":"text","outputFormat":"text"}}]"""

private const val FULL_CATALOG =
    """[{"ref":{"providerId":"p","modelId":"m1"},"label":"Model One","providerLabel":"Provider","description":"first","contextWindow":100000,"maxOutputTokens":4096,"reasoning":{"levels":[{"value":"high","label":"High"},{"value":"low","label":"Low"}],"defaultLevel":"high"},"properties":{"inputFormat":"text","outputFormat":"text"},"futureCapability":{"tier":2}},{"ref":{"providerId":"p","modelId":"m2"},"label":"Model Two","properties":{"inputFormat":"text","outputFormat":"text"}}]"""

private fun fullSnapshot(
    currentModel: String = "m1",
    thought: String? = "high",
    currentMode: String = "build",
    available: String = FULL_CATALOG,
    runtime: String = """{"stateRevision":1}""",
): String {
    val options = thought?.let { ""","options":{"reasoningLevel":"$it"}""" }.orEmpty()
    val thoughtState = if (thought == null) {
        """{"enabled":false,"available":[]}"""
    } else {
        """{"enabled":true,"current":"$thought","defaultLevel":"high","available":[{"value":"high","label":"High"},{"value":"low","label":"Low"}]}"""
    }
    return """{"session":{"sessionId":"s","mode":"build"},"messages":[],"runtime":$runtime,"settings":{"mode":{"current":"$currentMode"},"model":{"current":{"providerId":"p","modelId":"$currentModel"$options},"available":$available},"thoughtLevel":$thoughtState,"permission":{"mode":"build"},"futureSettings":{"keep":true}}}"""
}
