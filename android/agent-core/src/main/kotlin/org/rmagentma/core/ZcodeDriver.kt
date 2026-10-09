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
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.util.UUID

// Paths only: provider credentials are resolved by the remote agent, never by this client.
data class ZcodeLaunchConfig(
    val executable: String = "~/.zcode/server/agents/glm/zcode-agent",
    val builtinProviderConfig: String? = "~/.zcode/v2/runtime/provider/bundled/zcode-builtin.json",
    val personalProviderConfig: String? = "~/.zcode/v2/provider_config.json",
) {
    fun command(cwd: String): String {
        require(cwd.startsWith('/')) { "Working directory must be absolute" }
        require(cwd.none { it.code < 32 || it.code == 127 }) { "Invalid working directory" }
        val environment = buildList {
            builtinProviderConfig?.let { add("ZCODE_BUILTIN_PROVIDER_CONFIG_FILE=${path(it)}") }
            personalProviderConfig?.let { add("ZCODE_PERSONAL_PROVIDER_CONFIG_FILE=${path(it)}") }
        }.joinToString(" ")
        val payload = "exec " + if (environment.isNotEmpty()) {
            "env $environment ${path(executable)} app-server --cwd ${ShellCommands.quote(cwd)} --mode build"
        } else {
            "${path(executable)} app-server --cwd ${ShellCommands.quote(cwd)} --mode build"
        }
        return "/bin/sh -c ${ShellCommands.quote(payload)}"
    }

    private fun path(value: String): String {
        require(value.isNotBlank() && value.none { it.code < 32 || it.code == 127 }) { "Invalid remote path" }
        require(value.startsWith('/') || value.startsWith("~/")) { "Remote path must be absolute or home-relative" }
        return if (value.startsWith("~/")) "\"\$HOME\"/${ShellCommands.quote(value.removePrefix("~/"))}" else ShellCommands.quote(value)
    }
}

data class ZcodeSessionRecord(val session: AgentSession, val workspaceIdentity: String?)

class ZcodeDriver(
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val launchConfig: ZcodeLaunchConfig = ZcodeLaunchConfig(),
    private val requestTimeoutMs: Long = 30000,
    private val turnTimeoutMs: Long = 30 * 60 * 1000L,
) : AgentDriver {
    override val agent = AgentKind.ZCODE

    suspend fun prepare(
        host: HostSession,
        sessionId: String?,
        cwd: String,
        workspaceIdentity: String? = null,
        replayHistory: Boolean = true,
    ): ZcodeSessionHandle {
        require(sessionId == null || sessionId.isNotBlank()) { "Session ID cannot be blank" }
        require(workspaceIdentity == null || workspaceIdentity.isNotBlank()) { "Workspace identity cannot be blank" }
        require(requestTimeoutMs > 0 && turnTimeoutMs > 0) { "Timeout must be positive" }
        val connection = connect(host, cwd)
        try {
            connection.sessionId = sessionId
            connection.ready()
            return ZcodeSessionHandle(sessionId.orEmpty(), connection, cwd, workspaceIdentity, replayHistory, turnTimeoutMs)
        } catch (e: Exception) {
            connection.close()
            throw e
        }
    }

    suspend fun open(
        host: HostSession,
        sessionId: String?,
        cwd: String,
        workspaceIdentity: String? = null,
        replayHistory: Boolean = true,
    ): ZcodeSessionHandle = prepare(host, sessionId, cwd, workspaceIdentity, replayHistory)

    override suspend fun listSessions(host: HostSession, limit: Int): List<AgentSession> = listSessionRecords(host, limit).map { it.session }

    suspend fun listSessionRecords(
        host: HostSession,
        limit: Int = 200,
        cwd: String? = null,
        workspaceIdentity: String? = null,
    ): List<ZcodeSessionRecord> {
        require(limit in 1..1000) { "Limit must be between 1 and 1000" }
        require(cwd == null || workspaceIdentity != null) { "Workspace-only listing requires the persisted workspace identity" }
        require(workspaceIdentity == null || (cwd != null && workspaceIdentity.isNotBlank())) { "Workspace identity requires a working directory" }
        val connection = connect(host, cwd ?: "/")
        try {
            connection.ready()
            val result = connection.request(
                "session/list",
                buildJsonObject {
                    put("limit", limit)
                    put("includeArchived", false)
                    if (cwd != null) put("workspace", zcodeWorkspace(cwd, workspaceIdentity))
                },
            )
            val rows = result["sessions"] as? JsonArray ?: throw IOException("Invalid ZCode session list")
            return rows.take(limit).map { item ->
                val row = item as? JsonObject ?: throw IOException("Invalid ZCode session record")
                val workspace = row["workspace"] as? JsonObject ?: throw IOException("Missing ZCode workspace")
                val path = workspace.string("workspacePath")
                val id = row.string("sessionId")
                if (id.isBlank() || !path.startsWith('/')) throw IOException("Invalid ZCode session identity")
                ZcodeSessionRecord(
                    AgentSession(
                        host.hostId,
                        agent,
                        id,
                        path,
                        row.string("title"),
                        row.timestamp("updatedAt"),
                        row.timestamp("createdAt"),
                    ),
                    workspace.string("workspaceIdentity").takeIf { it.isNotBlank() },
                )
            }
        } finally {
            connection.close()
        }
    }

    private suspend fun connect(host: HostSession, cwd: String): ZcodeConnection = withContext(ioDispatcher) {
        val channel = host.exec(launchConfig.command(cwd))
        try {
            ZcodeConnection(channel, scope, ioDispatcher, requestTimeoutMs)
        } catch (e: Exception) {
            channel.close()
            throw e
        }
    }
}

class ZcodeSessionHandle internal constructor(
    initialSessionId: String,
    private val connection: ZcodeConnection,
    private val cwd: String,
    private val workspaceIdentity: String?,
    private val replayHistory: Boolean,
    private val turnTimeoutMs: Long,
) : AgentSessionHandle {
    override var sessionId: String = initialSessionId
        private set
    override val events: Flow<AgentEvent> = connection.events
    private val loading = Mutex()
    private val prompt = Mutex()

    @Volatile private var ready = false

    @Volatile private var closed = false

    suspend fun load() = loading.withLock {
        check(!ready && !closed) { "ZCode session is already loaded or closed" }
        try {
            connection.awaitConsumer()
            val existing = sessionId.takeIf { it.isNotEmpty() }
            val snapshot = connection.request(
                if (existing == null) "session/create" else "session/resume",
                buildJsonObject {
                    if (existing == null) {
                        put("workspace", zcodeWorkspace(cwd, workspaceIdentity))
                        put("mode", "build")
                    } else {
                        put("sessionId", existing)
                        if (workspaceIdentity != null) put("workspace", zcodeWorkspace(cwd, workspaceIdentity))
                    }
                },
            )
            val session = snapshot["session"] as? JsonObject ?: throw IOException("ZCode did not return session state")
            val returned = session.string("sessionId")
            if (returned.isBlank() || (existing != null && returned != existing)) throw IOException("Invalid ZCode resumed session identity")
            sessionId = returned
            connection.sessionId = returned
            if (session.string("mode") != "build") {
                connection.request(
                    "session/setMode",
                    buildJsonObject {
                        put("sessionId", returned)
                        put("mode", "build")
                    },
                )
            }
            if (replayHistory) replayMessages(snapshot["messages"] as? JsonArray ?: throw IOException("Missing ZCode messages"))
            connection.subscribe()
            val subscription = connection.request(
                "session/subscribe",
                buildJsonObject {
                    put("sessionId", returned)
                    put("deliveryKind", "desktop-continuous")
                    put("includeSnapshot", false)
                },
            )
            if (subscription.string("sessionId") != returned) throw IOException("Invalid ZCode subscription identity")
            check(!closed) { "ZCode session closed while loading" }
            ready = true
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    private suspend fun replayMessages(messages: JsonArray) {
        messages.forEach { item ->
            val message = item as? JsonObject ?: throw IOException("Invalid ZCode message")
            val info = message["info"] as? JsonObject ?: throw IOException("Missing ZCode message info")
            if (info.string("sessionId") != sessionId) throw IOException("Foreign ZCode history message")
            val semantics = info["semantics"] as? JsonObject
            if (info.string("visibility") == "model-only" || semantics?.string("uiVisibility") == "hidden") return@forEach
            val role = info.string("role")
            require(role == "user" || role == "assistant") { "Invalid ZCode history role" }
            (message["parts"] as? JsonArray ?: throw IOException("Missing ZCode message parts")).forEach { partItem ->
                val part = partItem as? JsonObject ?: throw IOException("Invalid ZCode message part")
                if (part.string("sessionId") != sessionId || part.string("messageId") != info.string("messageId")) {
                    throw IOException("Foreign ZCode history part")
                }
                if ((part["ignored"] as? JsonPrimitive)?.booleanOrNull == true) return@forEach
                val kind = part.string("type")
                val state = part["state"] as? JsonObject
                connection.emit(
                    AgentEvent(
                        type = when (kind) {
                            "text" -> if (role == "user") "user" else "content"
                            "reasoning" -> "thinking"
                            "tool" -> "tool"
                            "timeline" -> "session_info"
                            else -> "unknown"
                        },
                        text = if (kind == "tool") state?.string("output").orEmpty() else part.string("text"),
                        id = part.string("callId").ifEmpty { part.string("partId") },
                        title = part.string("tool"),
                        status = state?.string("status").orEmpty(),
                        raw = part,
                    ),
                )
            }
        }
    }

    override suspend fun send(text: String) = prompt.withLock {
        check(ready && !closed) { "ZCode session is not ready" }
        val inputId = UUID.randomUUID().toString()
        val completion = connection.expectTurn(inputId)
        try {
            withTimeout(turnTimeoutMs) {
                val ack = connection.request(
                    "session/send",
                    buildJsonObject {
                        put("sessionId", sessionId)
                        put("content", text)
                        put("inputId", inputId)
                    },
                )
                if (ack.string("sessionId") != sessionId || (ack["accepted"] as? JsonPrimitive)?.booleanOrNull != true) {
                    throw IOException("ZCode did not accept the prompt")
                }
                completion.await()
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                if (runCatching { cancel() }.isFailure) close()
            }
            throw e
        } finally {
            connection.forgetTurn(completion)
        }
    }

    override suspend fun cancel() {
        check(!closed) { "ZCode session is closed" }
        connection.cancelInteractions()
        connection.request("session/stop", buildJsonObject { put("sessionId", sessionId) })
    }

    override suspend fun respondPermission(requestId: String, optionId: String) {
        val request = connection.incoming(requestId, "interaction/requestPermission")
        require(zcodePermissionOptions(request.params).any { it.id == optionId }) { "Unknown ZCode permission option" }
        val option = (request.params["options"] as JsonArray).mapNotNull { it as? JsonObject }.single { it.string("optionId") == optionId }
        connection.respond(request, option["response"] as JsonObject)
    }

    /** Structured question sets take a JSON content object; a single prompt takes plain text. */
    override suspend fun respondQuestion(requestId: String, answer: String) {
        val request = connection.incoming(requestId, "interaction/requestUserInput")
        val content = if (request.params["questions"] != null || request.params["schema"] != null) {
            val value = runCatching { Json.parseToJsonElement(answer) as? JsonObject }.getOrNull()
            requireNotNull(value) { "Answer must be a JSON content object for this question set" }
        } else {
            buildJsonObject { put("answer", answer) }
        }
        connection.respond(
            request,
            buildJsonObject {
                put("action", "accept")
                put("content", content)
            },
        )
    }

    suspend fun cancelQuestion(requestId: String) {
        val request = connection.incoming(requestId, "interaction/requestUserInput")
        connection.respond(request, buildJsonObject { put("action", "cancel") })
    }

    override suspend fun close() {
        closed = true
        ready = false
        connection.close()
    }
}

internal fun zcodeWorkspace(cwd: String, identity: String?): JsonObject = buildJsonObject {
    put("workspacePath", cwd)
    put("workspaceKey", identity ?: cwd)
    if (identity != null) put("workspaceIdentity", identity)
}
