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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.EOFException
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class ZcodeException(val code: Int) : IOException("ZCode request failed (code $code)")

internal class ZcodeConnection(
    private val channel: ExecChannel,
    parentScope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val requestTimeoutMs: Long,
) {
    internal data class Incoming(val token: String, val id: JsonPrimitive, val method: String, val params: JsonObject, val bytes: Long)
    private data class Turn(val inputId: String, val result: CompletableDeferred<Unit>)
    private data class Pending(val method: String, val result: CompletableDeferred<JsonObject>)

    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val stopped = AtomicBoolean(false)
    private val ids = AtomicLong()
    private val pending = ConcurrentHashMap<JsonPrimitive, Pending>()
    private val lock = Any()
    private val incoming = mutableMapOf<String, Incoming>()
    private var incomingBytes = 0L
    private var turn: Turn? = null
    private val writer = Mutex()
    private val buffer = BoundedEventBuffer()
    private val storageReady = CompletableDeferred<Unit>()
    val events = flow {
        try {
            emitAll(buffer.events)
        } finally {
            withContext(NonCancellable + ioDispatcher) { finish(IOException("ZCode event consumer closed")) }
        }
    }

    @Volatile var sessionId: String? = null

    @Volatile private var subscribed = false
    private var lastSeq = -1L

    init {
        require(requestTimeoutMs > 0)
        scope.launch(ioDispatcher, start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                finish(IOException("ZCode scope closed"))
            }
        }
        scope.launch(ioDispatcher) {
            runCatching { runInterruptible { drainStderr(channel.stderr) } }
        }
        scope.launch(ioDispatcher) {
            var failure: IOException = EOFException("ZCode stdout reached EOF")
            try {
                val reader = NdjsonReader(channel.stdout)
                while (!stopped.get()) {
                    val line = runInterruptible { reader.readLine() } ?: break
                    if (line.isBlank()) continue
                    val message = Json.parseToJsonElement(line) as? JsonObject ?: throw IOException("Invalid ZCode frame")
                    receive(message)
                }
            } catch (e: Exception) {
                failure = if (e is IOException) e else IOException("ZCode transport read failed")
            } finally {
                finish(failure)
            }
        }
    }

    suspend fun ready() = withTimeout(requestTimeoutMs) {
        storageReady.await()
        request("runtime/capabilities", JsonObject(emptyMap()))
    }

    suspend fun awaitConsumer() = withTimeout(requestTimeoutMs) { buffer.awaitConsumer() }

    suspend fun request(method: String, params: JsonObject): JsonObject = withTimeout(requestTimeoutMs) {
        if (pending.size >= MAX_INCOMING_REQUESTS) throw IOException("Too many pending ZCode requests")
        val id = JsonPrimitive(ids.incrementAndGet())
        val result = CompletableDeferred<JsonObject>()
        val entry = Pending(method, result)
        pending[id] = entry
        try {
            write(
                buildJsonObject {
                    put("id", id)
                    put("method", method)
                    put("params", params)
                },
            )
            result.await()
        } finally {
            pending.remove(id, entry)
        }
    }

    suspend fun emit(event: AgentEvent) {
        if (!buffer.offer(event)) throw IOException("ZCode event consumer closed")
    }

    fun expectTurn(inputId: String): CompletableDeferred<Unit> = synchronized(lock) {
        check(!stopped.get() && turn == null) { "ZCode turn is already running or closed" }
        CompletableDeferred<Unit>().also { turn = Turn(inputId, it) }
    }

    fun forgetTurn(result: CompletableDeferred<Unit>) = synchronized(lock) {
        if (turn?.result === result) turn = null
    }

    fun subscribe() {
        subscribed = true
    }

    fun incoming(requestId: String, method: String): Incoming = synchronized(lock) {
        val request = incoming[requestId] ?: throw IllegalArgumentException("Unknown or completed ZCode interaction")
        require(request.method == method) { "ZCode interaction has a different method" }
        request
    }

    private fun removeIncoming(request: Incoming): Boolean = synchronized(lock) {
        if (incoming[request.token] !== request) return@synchronized false
        incoming.remove(request.token)
        incomingBytes -= request.bytes
        true
    }

    suspend fun respond(request: Incoming, result: JsonObject) {
        require(removeIncoming(request)) { "ZCode interaction already completed" }
        writeResponse(request.id, result)
    }

    suspend fun cancelInteractions() {
        synchronized(lock) { incoming.values.toList() }.forEach { request ->
            if (removeIncoming(request)) {
                val result = if (request.method == "interaction/requestPermission") {
                    buildJsonObject {
                        put("decision", "deny")
                        put("reason", "Cancelled by user")
                    }
                } else {
                    buildJsonObject { put("action", "cancel") }
                }
                writeResponse(request.id, result)
            }
        }
    }

    private suspend fun writeResponse(id: JsonPrimitive, result: JsonObject) = write(
        buildJsonObject {
            put("id", id)
            put("result", result)
        },
    )

    private suspend fun write(message: JsonObject) {
        val bytes = message.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_FRAME_BYTES) throw FrameTooLargeException()
        withTimeout(requestTimeoutMs) {
            withContext(ioDispatcher) {
                writer.withLock {
                    writeBytes(bytes)
                }
            }
        }
    }

    private suspend fun writeBytes(bytes: ByteArray) {
        if (stopped.get()) throw EOFException("ZCode channel closed")
        try {
            runInterruptible {
                channel.stdin.write(bytes)
                channel.stdin.write(10)
                channel.stdin.flush()
            }
        } catch (e: Exception) {
            finish(IOException("ZCode transport write failed"))
            throw e
        }
    }

    private suspend fun receive(message: JsonObject) {
        if (message.containsKey("jsonrpc")) throw IOException("ZCode does not use JSON-RPC")
        val id = message["id"] as? JsonPrimitive
        val validId = id != null && id != JsonNull && (id.isString || id.longOrNull != null)
        val method = message.string("method")
        if (method.isNotBlank()) {
            if (message.keys.any { it !in setOf("id", "method", "params", "trace") }) throw IOException("Invalid ZCode request fields")
            val params = message["params"] as? JsonObject ?: if (!message.containsKey("params")) JsonObject(emptyMap()) else null
            if (params == null || (message.containsKey("id") && !validId)) {
                if (validId) error(id, -32602, "Invalid parameters") else throw IOException("Invalid ZCode request")
            } else if (validId) {
                receiveRequest(id, method, params)
            } else if (method == "startup/storageState") {
                when (params.string("phase")) {
                    "ready" -> if (params.string("databaseKind") == "session") storageReady.complete(Unit)
                    "failed" -> throw IOException("ZCode storage startup failed")
                }
            } else if (method == "session/event" && subscribed) {
                receiveEvent(params)
            } else if (method == "state.updated" && subscribed && params.string("sessionId") == sessionId) {
                emit(AgentEvent("session_info", raw = params))
            }
            return
        }
        if (!validId || message.keys.any { it !in setOf("id", "result", "error") } || (message.containsKey("result") == message.containsKey("error"))) {
            throw IOException("Invalid ZCode response")
        }
        val entry = pending[id] ?: return
        val error = message["error"] as? JsonObject
        if (error != null) {
            entry.result.completeExceptionally(ZcodeException((error["code"] as? JsonPrimitive)?.longOrNull?.toInt() ?: -32603))
        } else {
            val value = message["result"] as? JsonObject ?: throw IOException("Invalid ZCode result")
            if (entry.method == "session/subscribe") {
                (value["events"] as? JsonArray ?: throw IOException("Invalid ZCode subscription events")).forEach {
                    receiveEvent(it as? JsonObject ?: throw IOException("Invalid ZCode replay event"))
                }
                buffer.barrier()
            }
            entry.result.complete(value)
        }
    }

    private suspend fun receiveRequest(id: JsonPrimitive, method: String, params: JsonObject) {
        val target = params.string("sessionId")
        if (target.isBlank() || (sessionId != null && target != sessionId)) {
            error(id, -32602, "Unknown session")
            return
        }
        if (method == "session/requestRuntimePreferences") {
            if (params.string("scope") !in setOf("runtime-materialization", "user-execution")) {
                error(id, -32602, "Invalid runtime preferences scope")
                return
            }
            writeResponse(
                id,
                buildJsonObject {
                    put("nativeSearchEnhancementsEnabled", false)
                    put("memoryEnabled", false)
                    put("askUserQuestionAutoResolutionEnabled", false)
                    put("modelContextBudgetStrategy", "preflight-v1")
                },
            )
            return
        }
        if (method !in setOf("interaction/requestPermission", "interaction/requestUserInput")) {
            error(id, -32601, "Method not supported")
            return
        }
        if (sessionId == null || params.string("requestId").isBlank() ||
            (method == "interaction/requestPermission" && zcodePermissionOptions(params).isEmpty())
        ) {
            error(id, -32602, "Invalid interaction")
            return
        }
        val key = UUID.randomUUID().toString()
        val weight = retainedTextBytes(params.toString()) + retainedTextBytes(key) + 256
        val accepted = synchronized(lock) {
            if (incoming.values.any { it.id == id }) {
                -1
            } else if (incoming.size >= MAX_INCOMING_REQUESTS || weight > MAX_INCOMING_REQUEST_BYTES - incomingBytes) {
                0
            } else {
                incoming[key] = Incoming(key, id, method, params, weight)
                incomingBytes += weight
                1
            }
        }
        if (accepted != 1) {
            error(id, if (accepted == -1) -32600 else -32000, "Interaction buffer unavailable")
            if (accepted == 0) throw IOException("ZCode interaction buffer exceeded limit")
            return
        }
        emit(
            AgentEvent(
                type = if (method == "interaction/requestPermission") "permission" else "question",
                id = key,
                text = params.string("reason").ifEmpty { params.string("prompt") },
                title = params.string("toolName"),
                options = zcodePermissionOptions(params),
                raw = params,
            ),
        )
    }

    suspend fun receiveEvent(event: JsonObject) {
        if (event.string("sessionId") != sessionId) return
        val seq = (event["seq"] as? JsonPrimitive)?.longOrNull ?: throw IOException("Missing ZCode event sequence")
        if (seq < 0 || event.string("eventId").isBlank()) throw IOException("Invalid ZCode event identity")
        if (seq <= lastSeq) return
        lastSeq = seq
        val payload = event["payload"] as? JsonObject ?: JsonObject(emptyMap())
        val type = event.string("type")
        if (type == "permission.resolved" || type == "userInput.resolved") {
            val method = if (type == "permission.resolved") "interaction/requestPermission" else "interaction/requestUserInput"
            val requestId = payload.string("requestId")
            synchronized(lock) {
                incoming.values.filter { it.method == method && it.params.string("requestId") == requestId }.toList()
            }.forEach { removeIncoming(it) }
        }
        val normalized = normalizeZcodeEvent(event, payload)
        emit(normalized)
        if (type == "turn.completed" && payload["usage"] != null) emit(AgentEvent("usage", raw = payload))
        if (type == "turn.completed" || type == "turn.failed") {
            val current = synchronized(lock) {
                turn?.takeIf { payload.string("inputId").let { input -> input.isEmpty() || input == it.inputId } }
            }
            current?.result?.complete(Unit)
        }
    }

    private suspend fun error(id: JsonPrimitive, code: Int, message: String) = write(
        buildJsonObject {
            put("id", id)
            put(
                "error",
                buildJsonObject {
                    put("code", code)
                    put("message", message)
                },
            )
        },
    )

    suspend fun close() = withContext(NonCancellable + ioDispatcher) { finish() }

    private fun finish(cause: IOException? = null) {
        if (!stopped.compareAndSet(false, true)) return
        val failure = cause ?: EOFException("ZCode channel closed")
        buffer.close(cause?.message)
        storageReady.completeExceptionally(failure)
        pending.values.forEach { it.result.completeExceptionally(failure) }
        pending.clear()
        synchronized(lock) {
            turn?.result?.completeExceptionally(failure)
            turn = null
            incoming.clear()
            incomingBytes = 0
        }
        runCatching { channel.close() }
        scope.cancel()
    }
}

internal fun zcodePermissionOptions(params: JsonObject): List<AgentOption> = (params["options"] as? JsonArray).orEmpty().mapNotNull {
    val option = it as? JsonObject ?: return@mapNotNull null
    val response = option["response"] as? JsonObject ?: return@mapNotNull null
    val id = option.string("optionId")
    if (id.isBlank() || response.string("decision") !in setOf("allow", "deny", "escalate", "modify")) null else AgentOption(id, option.string("name"))
}

internal fun normalizeZcodeEvent(event: JsonObject, payload: JsonObject): AgentEvent {
    val kind = event.string("type")
    val stream = payload.string("kind")
    val type = when (kind) {
        "model.streaming" -> when (stream) {
            "text_delta" -> "content"
            "reasoning_delta" -> "thinking"
            "tool_input_start", "tool_input_delta", "tool_input_end", "tool_call" -> "tool_update"
            "error" -> "error"
            else -> "unknown"
        }

        "part.delta" -> when (payload.string("field")) {
            "reasoning" -> "thinking"
            "input", "output" -> "tool_update"
            else -> "content"
        }

        "tool.updated" -> if (stream == "scheduled") "tool" else "tool_update"

        "turn.started" -> "user"

        "turn.completed" -> "complete"

        "turn.failed" -> "error"

        "session.titleUpdated", "session.updated", "session.created", "session.resumed" -> "session_info"

        "permission.requested", "permission.resolved", "userInput.requested", "userInput.resolved" -> "interaction_update"

        else -> "unknown"
    }
    return AgentEvent(
        type = type,
        text = when (type) {
            "content", "thinking" -> payload.string("delta")

            "user" -> payload.string("input")

            "error" -> (payload["error"] as? JsonObject)?.string("message").orEmpty()

            "tool", "tool_update" -> payload.string("delta").ifEmpty {
                payload.string("stdoutTail").ifEmpty {
                    (payload["result"] as? JsonObject)?.let { result ->
                        result.string("output").ifEmpty { result.string("content").ifEmpty { result.toString() } }
                    } ?: (payload["error"] as? JsonObject)?.string("message").orEmpty()
                }
            }

            else -> ""
        },
        id = payload.string("toolCallId").ifEmpty { payload.string("partId").ifEmpty { payload.string("messageId") } },
        title = payload.string("toolName").ifEmpty { payload.string("title").ifEmpty { if (type == "unknown") kind else "" } },
        status = payload.string("resultType").ifEmpty { stream },
        raw = event,
    )
}
