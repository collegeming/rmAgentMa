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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.IOException

class AcpDriver(
    override val agent: AgentKind,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val executable: String? = null,
) : AgentDriver {
    init {
        require(agent in setOf(AgentKind.KIMI, AgentKind.OPENCODE, AgentKind.OMP, AgentKind.DSH)) { "Agent does not support ACP" }
    }

    suspend fun prepare(host: HostSession, sessionId: String?, cwd: String): AcpSessionHandle {
        require(sessionId == null || sessionId.isNotBlank()) { "Session ID cannot be blank" }
        val connection = connect(host, cwd)
        try {
            val capabilities = initialize(connection)
            val resumeOnly = agent == AgentKind.DSH
            if (sessionId != null) {
                val supported = if (resumeOnly) {
                    val sessions = capabilities["sessionCapabilities"] as? JsonObject
                    sessions?.get("resume") is JsonObject
                } else {
                    (capabilities["loadSession"] as? JsonPrimitive)?.booleanOrNull == true
                }
                if (!supported) throw UnsupportedOperationException("Agent does not advertise ${if (resumeOnly) "sessionCapabilities.resume" else "loadSession"}")
            }
            connection.sessionId = sessionId
            val replay: (suspend (suspend (AgentEvent) -> Unit) -> Unit)? = if (resumeOnly && sessionId != null) {
                { emit -> readTranscript(host, AgentSession(host.hostId, agent, sessionId, cwd), emit) }
            } else {
                null
            }
            return AcpSessionHandle(sessionId.orEmpty(), connection, cwd, resumeOnly, replay)
        } catch (e: Exception) {
            connection.close()
            throw e
        }
    }

    suspend fun open(host: HostSession, sessionId: String?, cwd: String): AcpSessionHandle = prepare(host, sessionId, cwd)

    private suspend fun readTranscript(host: HostSession, session: AgentSession, emit: suspend (AgentEvent) -> Unit) {
        val reader = RemoteTranscriptReader(ioDispatcher)
        val seen = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val page = reader.readPage(host, session, cursor, onEvent = emit)
            page.error?.let { throw IOException("Agent history read failed: $it") }
            cursor = page.nextCursor
            if (cursor != null && !seen.add(cursor)) throw IOException("Agent repeated a history cursor")
        } while (cursor != null)
    }

    override suspend fun listSessions(host: HostSession, limit: Int): List<AgentSession> {
        require(limit in 1..1000) { "Limit must be between 1 and 1000" }
        val connection = connect(host, "/")
        try {
            val capabilities = initialize(connection)
            val sessions = capabilities["sessionCapabilities"] as? JsonObject
            if (sessions?.get("list") !is JsonObject) {
                throw UnsupportedOperationException("Agent does not advertise sessionCapabilities.list")
            }
            val output = linkedMapOf<String, AgentSession>()
            val cursors = mutableSetOf<String>()
            var cursor: String? = null
            var pages = 0
            do {
                if (++pages > MAX_LIST_PAGES) throw IOException("Agent exceeded session list page limit")
                val response = connection.request(
                    "session/list",
                    buildJsonObject {
                        if (cursor != null) put("cursor", cursor)
                    },
                )
                val rows = response["sessions"] as? JsonArray ?: throw IOException("Invalid ACP session list")
                for (item in rows) {
                    val row = item as? JsonObject ?: throw IOException("Invalid ACP session record")
                    val id = row.string("sessionId")
                    val cwd = row.string("cwd")
                    if (id.isBlank() || !cwd.startsWith('/')) throw IOException("Invalid ACP session identity")
                    output.putIfAbsent(id, AgentSession(host.hostId, agent, id, cwd, row.string("title"), row.timestamp("updatedAt")))
                    if (output.size >= limit) return output.values.toList()
                }
                cursor = response.string("nextCursor").takeIf { it.isNotEmpty() }
                if (cursor != null && !cursors.add(cursor)) throw IOException("Agent repeated a session list cursor")
            } while (cursor != null)
            return output.values.toList()
        } finally {
            connection.close()
        }
    }

    private suspend fun connect(host: HostSession, cwd: String): AcpConnection = withContext(ioDispatcher) {
        val channel = host.exec(ShellCommands.acp(agent, cwd, executable))
        try {
            AcpConnection(channel, scope, ioDispatcher)
        } catch (e: Exception) {
            channel.close()
            throw e
        }
    }

    private suspend fun initialize(connection: AcpConnection): JsonObject {
        val response = connection.request(
            "initialize",
            buildJsonObject {
                put("protocolVersion", 1)
                put(
                    "clientInfo",
                    buildJsonObject {
                        put("name", "rmagentma")
                        put("version", "0.1")
                    },
                )
                put(
                    "clientCapabilities",
                    buildJsonObject {
                        put(
                            "fs",
                            buildJsonObject {
                                put("readTextFile", false)
                                put("writeTextFile", false)
                            },
                        )
                        put("terminal", false)
                        put("elicitation", buildJsonObject { put("form", JsonObject(emptyMap())) })
                    },
                )
            },
        )
        if ((response["protocolVersion"] as? JsonPrimitive)?.longOrNull != 1L) {
            throw IOException("Unsupported ACP protocol version")
        }
        return response["agentCapabilities"] as? JsonObject ?: JsonObject(emptyMap())
    }
}

class AcpSessionHandle internal constructor(
    initialSessionId: String,
    private val connection: AcpConnection,
    private val cwd: String,
    private val resumeOnly: Boolean = false,
    private val replay: (suspend (suspend (AgentEvent) -> Unit) -> Unit)? = null,
) : AgentSessionHandle {
    override var sessionId: String = initialSessionId
        private set
    override val events: Flow<AgentEvent> = connection.events
    private val prompt = Mutex()
    private val loading = Mutex()

    @Volatile
    private var ready = false

    @Volatile
    private var closed = false

    suspend fun load() = loading.withLock {
        check(!closed && !ready) { "Session is already loaded or closed" }
        try {
            connection.awaitConsumer()
            val existing = sessionId.takeIf { it.isNotEmpty() }
            replay?.invoke { connection.emit(it) }
            if (replay != null) connection.consumeBarrier()
            val response = connection.request(
                if (existing == null) {
                    "session/new"
                } else if (resumeOnly) {
                    "session/resume"
                } else {
                    "session/load"
                },
                buildJsonObject {
                    if (existing != null) put("sessionId", existing)
                    put("cwd", cwd)
                    put("mcpServers", JsonArray(emptyList()))
                },
            )
            sessionId = existing ?: response.string("sessionId").takeIf { it.isNotBlank() }
                ?: throw IOException("Agent did not return a session ID")
            connection.sessionId = sessionId
            check(!closed) { "Session closed while loading" }
            ready = true
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    override suspend fun send(text: String) {
        check(ready && !closed) { "Session is not ready" }
        prompt.withLock {
            try {
                connection.request(
                    "session/prompt",
                    buildJsonObject {
                        put("sessionId", sessionId)
                        put(
                            "prompt",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("type", "text")
                                        put("text", text)
                                    },
                                )
                            },
                        )
                    },
                )
            } catch (e: CancellationException) {
                withContext(NonCancellable) { kotlinx.coroutines.withTimeoutOrNull(1000) { runCatching { cancel() } } }
                throw e
            }
        }
    }

    override suspend fun cancel() {
        if (!ready) {
            close()
            return
        }
        connection.notify("session/cancel", buildJsonObject { put("sessionId", sessionId) })
        connection.cancelInteractions()
    }

    override suspend fun respondPermission(requestId: String, optionId: String) {
        val request = connection.incoming(requestId, "session/request_permission")
        require(permissionOptions(request.params).any { it.id == optionId }) { "Unknown permission option" }
        connection.respond(
            request,
            buildJsonObject {
                put(
                    "outcome",
                    buildJsonObject {
                        put("outcome", "selected")
                        put("optionId", optionId)
                    },
                )
            },
        )
    }

    /** For a one-string-field form, answer is plain text; otherwise it is a JSON object matching the form. */
    override suspend fun respondQuestion(requestId: String, answer: String) {
        val request = connection.incoming(requestId, "elicitation/create")
        val schema = request.params["requestedSchema"] as? JsonObject
        val properties = schema?.get("properties") as? JsonObject
        val single = properties?.entries?.singleOrNull()
        val content = if (single != null && (single.value as? JsonObject)?.string("type") == "string") {
            buildJsonObject { put(single.key, answer) }
        } else {
            try {
                Json.parseToJsonElement(answer) as? JsonObject
            } catch (_: Exception) {
                null
            } ?: throw IllegalArgumentException("Answer must be a JSON object matching requestedSchema")
        }
        require(content.values.all { it is JsonPrimitive || it is JsonArray }) { "Answer values must be primitive or arrays" }
        connection.respond(
            request,
            buildJsonObject {
                put("action", "accept")
                put("content", content)
            },
        )
    }

    override suspend fun close() {
        closed = true
        ready = false
        connection.close()
    }
}
