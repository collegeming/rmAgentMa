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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AcpConfigTest {
    @Test
    fun newSessionResponseExposesOptionsModesAndLegacyModels() = runBlocking {
        withTimeout(10000) {
            val host = PipeHost()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val server = async(Dispatchers.IO) {
                    host.reply(host.read(), """{"protocolVersion":1,"agentCapabilities":{}}""")
                    val create = host.read()
                    assertEquals("session/new", create.string("method"))
                    host.reply(
                        create,
                        """
                        {"sessionId":"s",
                         "configOptions":[
                          {"id":"model","name":"Model","category":"model","type":"select","currentValue":"a",
                           "options":[{"value":"a","name":"A"},{"value":"b","name":"B","description":"B detail"}]},
                          {"id":"mode","name":"Mode","category":"mode","type":"select","currentValue":"default",
                           "options":[{"group":"g1","name":"Group 1","options":[{"value":"default","name":"Default"},{"value":"plan","name":"Plan"}]}]},
                          {"id":"future","name":"Future","category":"_custom","type":"slider","currentValue":7,"extra":{"kept":true}}],
                         "modes":{"currentModeId":"default","availableModes":[{"id":"default","name":"Default"},{"id":"plan","name":"Plan","description":"Plan mode"}]},
                         "models":{"currentModelId":"a","availableModels":[{"modelId":"a","name":"A","description":"A model"}]}}
                        """.trimIndent(),
                    )
                }
                val handle = AcpDriver(AgentKind.KIMI, scope).prepare(host, null, "/tmp/work")
                val collector = async { handle.events.collect { } }
                handle.load()
                server.await()
                val config = handle.config
                assertEquals(listOf("model", "mode", "future"), config.options.map { it.id })
                val model = config.options[0]
                assertEquals("Model", model.name)
                assertEquals("model", model.category)
                assertEquals("select", model.type)
                assertEquals(JsonPrimitive("a"), model.currentValue)
                assertEquals(listOf("a", "b"), model.values.map { it.value })
                assertEquals("B detail", model.values[1].description)
                val mode = config.options[1]
                assertEquals(listOf("default", "plan"), mode.values.map { it.value })
                assertEquals("g1", mode.values[0].group)
                assertEquals("Group 1", mode.values[0].groupName)
                val unknown = config.options[2]
                assertEquals("slider", unknown.type)
                assertTrue(unknown.values.isEmpty())
                assertEquals(JsonPrimitive(7), unknown.currentValue)
                assertEquals(JsonPrimitive(true), unknown.raw["extra"]!!.jsonObject["kept"])
                assertEquals(listOf("default", "plan"), config.modes.map { it.value })
                assertEquals("Plan mode", config.modes[1].description)
                assertEquals("default", config.currentModeId)
                assertEquals(listOf("a"), config.models.map { it.value })
                assertEquals("A model", config.models.single().description)
                assertEquals("a", config.currentModelId)
                handle.close()
                collector.await()
            } finally {
                host.channel.close()
                scope.cancel()
            }
        }
    }

    @Test
    fun loadResponseKeepsUnstatedValuesNull() = runBlocking {
        withTimeout(10000) {
            val host = PipeHost()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val server = async(Dispatchers.IO) {
                    host.reply(host.read(), """{"protocolVersion":1,"agentCapabilities":{"loadSession":true}}""")
                    val load = host.read()
                    assertEquals("session/load", load.string("method"))
                    host.reply(load, """{"configOptions":[{"id":"model","name":"Model","type":"select","options":[]}]}""")
                }
                val handle = AcpDriver(AgentKind.OPENCODE, scope).prepare(host, "s", "/tmp/work")
                val collector = async { handle.events.collect { } }
                handle.load()
                server.await()
                val option = handle.config.options.single()
                assertEquals("model", option.id)
                assertEquals("", option.category)
                assertEquals(null, option.currentValue)
                assertTrue(option.values.isEmpty())
                assertEquals(null, handle.config.currentModeId)
                assertTrue(handle.config.modes.isEmpty())
                assertEquals(null, handle.config.currentModelId)
                assertTrue(handle.config.models.isEmpty())
                handle.close()
                collector.await()
            } finally {
                host.channel.close()
                scope.cancel()
            }
        }
    }

    @Test
    fun configNotificationReplacesSnapshotAndModeUpdateIsADelta() = runBlocking {
        withTimeout(10000) {
            val host = PipeHost()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val first = CompletableDeferred<Unit>()
            val second = CompletableDeferred<Unit>()
            try {
                val server = async(Dispatchers.IO) {
                    host.reply(host.read(), """{"protocolVersion":1,"agentCapabilities":{}}""")
                    host.reply(
                        host.read(),
                        """
                        {"sessionId":"s",
                         "configOptions":[
                          {"id":"model","name":"Model","category":"model","type":"select","currentValue":"a","options":[{"value":"a","name":"A"}]},
                          {"id":"mode","name":"Mode","category":"mode","type":"select","currentValue":"default","options":[{"value":"default","name":"Default"}]}],
                         "modes":{"currentModeId":"default","availableModes":[{"id":"default","name":"Default"},{"id":"plan","name":"Plan"}]}}
                        """.trimIndent(),
                    )
                    host.update(
                        """{"sessionUpdate":"config_option_update","configOptions":[{"id":"model","name":"Model","category":"model","type":"select","currentValue":"b","options":[{"value":"b","name":"B"}]}]}""",
                    )
                    host.update("""{"sessionUpdate":"current_mode_update","currentModeId":"plan"}""")
                    host.update("""{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"marker"}}""")
                    host.write(
                        """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"other","update":{"sessionUpdate":"config_option_update","configOptions":[]}}}""" + "\n",
                    )
                    host.update("""{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"end"}}""")
                }
                val handle = AcpDriver(AgentKind.KIMI, scope).prepare(host, null, "/tmp/work")
                val collector = async {
                    handle.events.collect {
                        when (it.text) {
                            "marker" -> first.complete(Unit)
                            "end" -> second.complete(Unit)
                        }
                    }
                }
                handle.load()
                first.await()
                val updated = handle.config
                assertEquals(listOf("model"), updated.options.map { it.id })
                assertEquals(JsonPrimitive("b"), updated.options.single().currentValue)
                assertEquals("plan", updated.currentModeId)
                assertEquals(listOf("default", "plan"), updated.modes.map { it.value })
                second.await()
                assertEquals(listOf("model"), handle.config.options.map { it.id })
                handle.close()
                collector.await()
                server.await()
            } finally {
                host.channel.close()
                scope.cancel()
            }
        }
    }

    @Test
    fun setConfigOptionReturnsTheAgentSnapshot() = runBlocking {
        withTimeout(10000) {
            val host = PipeHost()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val server = async(Dispatchers.IO) {
                    host.reply(host.read(), """{"protocolVersion":1,"agentCapabilities":{}}""")
                    host.reply(
                        host.read(),
                        """{"sessionId":"s","configOptions":[{"id":"model","name":"Model","category":"model","type":"select","currentValue":"a","options":[{"value":"a","name":"A"},{"value":"b","name":"B"}]}]}""",
                    )
                    val set = host.read()
                    assertEquals("session/set_config_option", set.string("method"))
                    val params = set["params"]!!.jsonObject
                    assertEquals(setOf("sessionId", "configId", "value"), params.keys)
                    assertEquals("s", params.string("sessionId"))
                    assertEquals("model", params.string("configId"))
                    assertEquals("b", params.string("value"))
                    host.reply(
                        set,
                        """{"configOptions":[{"id":"model","name":"Model","category":"model","type":"select","currentValue":"b","options":[{"value":"a","name":"A"},{"value":"b","name":"B"}]},{"id":"thinking","name":"Thinking","category":"thought_level","type":"select","currentValue":"on","options":[{"value":"off","name":"Thinking Off"},{"value":"on","name":"Thinking On"}]}]}""",
                    )
                }
                val handle = AcpDriver(AgentKind.KIMI, scope).prepare(host, null, "/tmp/work")
                val collector = async { handle.events.collect { } }
                handle.load()
                val updated = handle.setConfigOption("model", "b")
                server.await()
                assertEquals(listOf("model", "thinking"), updated.options.map { it.id })
                assertEquals(JsonPrimitive("b"), updated.options[0].currentValue)
                assertEquals("thought_level", updated.options[1].category)
                assertEquals(updated, handle.config)
                handle.close()
                collector.await()
            } finally {
                host.channel.close()
                scope.cancel()
            }
        }
    }

    @Test
    fun setConfigOptionPropagatesAgentRejection() = runBlocking {
        withTimeout(10000) {
            val host = PipeHost()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val server = async(Dispatchers.IO) {
                    host.reply(host.read(), """{"protocolVersion":1,"agentCapabilities":{}}""")
                    host.reply(
                        host.read(),
                        """{"sessionId":"s","configOptions":[{"id":"model","name":"Model","category":"model","type":"select","currentValue":"a","options":[{"value":"a","name":"A"}]}]}""",
                    )
                    val set = host.read()
                    host.write("""{"jsonrpc":"2.0","id":${set["id"]!!},"error":{"code":-32602,"message":"Unknown configId: nope"}}""" + "\n")
                }
                val handle = AcpDriver(AgentKind.KIMI, scope).prepare(host, null, "/tmp/work")
                val collector = async { handle.events.collect { } }
                handle.load()
                val failure = runCatching { handle.setConfigOption("nope", "b") }.exceptionOrNull()
                server.await()
                assertTrue("Expected AcpException but got $failure", failure is AcpException)
                assertEquals(-32602, (failure as AcpException).code)
                assertEquals("Unknown configId: nope", failure.reason)
                assertEquals(JsonPrimitive("a"), handle.config.options.single().currentValue)
                handle.close()
                collector.await()
            } finally {
                host.channel.close()
                scope.cancel()
            }
        }
    }

    @Test
    fun setConfigOptionWithoutSnapshotFailsInsteadOfClaimingSuccess() = runBlocking {
        withTimeout(10000) {
            val host = PipeHost()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val server = async(Dispatchers.IO) {
                    host.reply(host.read(), """{"protocolVersion":1,"agentCapabilities":{}}""")
                    host.reply(
                        host.read(),
                        """{"sessionId":"s","configOptions":[{"id":"model","name":"Model","category":"model","type":"select","currentValue":"a","options":[{"value":"a","name":"A"},{"value":"b","name":"B"}]}]}""",
                    )
                    host.reply(host.read(), "{}")
                }
                val handle = AcpDriver(AgentKind.KIMI, scope).prepare(host, null, "/tmp/work")
                val collector = async { handle.events.collect { } }
                handle.load()
                val failure = runCatching { handle.setConfigOption("model", "b") }.exceptionOrNull()
                server.await()
                assertTrue("Expected IOException but got $failure", failure != null && failure !is AcpException)
                assertEquals(JsonPrimitive("a"), handle.config.options.single().currentValue)
                assertFalse(handle.config.options.single().values.isEmpty())
                handle.close()
                collector.await()
            } finally {
                host.channel.close()
                scope.cancel()
            }
        }
    }
}
