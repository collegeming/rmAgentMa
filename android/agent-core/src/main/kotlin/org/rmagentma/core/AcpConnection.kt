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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.EOFException
import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class AcpException(val code: Int) : IOException("ACP request failed (code $code)")

internal class AcpConnection(
    private val channel: ExecChannel,
    parentScope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private data class Pending(val method: String, val result: CompletableDeferred<JsonObject>)
    internal data class Incoming(val id: JsonPrimitive, val method: String, val params: JsonObject, val bytes: Long)

    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val stopped = AtomicBoolean(false)
    private val ids = AtomicLong()
    private val pending = ConcurrentHashMap<JsonPrimitive, Pending>()
    private val incomingLock = Any()
    private val incoming = mutableMapOf<String, Incoming>()
    private var incomingBytes = 0L
    private val writer = Mutex()
    private val eventBuffer = BoundedEventBuffer()
    val events = eventBuffer.events

    @Volatile var sessionId: String? = null

    init {
        scope.launch(ioDispatcher, start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                finish(IOException("ACP scope closed"))
            }
        }
        scope.launch(ioDispatcher) {
            try {
                runInterruptible { drainStderr(channel.stderr) }
            } catch (_: Exception) {
                if (!stopped.get()) diagnostic("ACP stderr stream closed unexpectedly")
            }
        }
        scope.launch(ioDispatcher) {
            var failure: IOException = EOFException("ACP stdout reached EOF")
            try {
                val reader = NdjsonReader(channel.stdout)
                while (!stopped.get()) {
                    val line = try {
                        runInterruptible { reader.readLine() }
                    } catch (_: CharacterCodingException) {
                        diagnostic("Skipped invalid UTF-8 frame")
                        continue
                    } ?: break
                    if (line.isBlank()) continue
                    val message = try {
                        Json.parseToJsonElement(line) as? JsonObject
                    } catch (_: Exception) {
                        null
                    }
                    if (message == null) {
                        diagnostic("Skipped malformed JSON frame")
                        continue
                    }
                    receive(message)
                }
            } catch (_: FrameTooLargeException) {
                failure = IOException("ACP frame exceeds 32 MiB")
            } catch (_: Exception) {
                failure = IOException("ACP transport read failed")
            } finally {
                finish(failure)
            }
        }
    }

    fun emit(event: AgentEvent) {
        if (!stopped.get() && !eventBuffer.offer(event)) finish(AcpBufferOverflowException(), discardEvents = true)
    }

    private fun diagnostic(message: String) = emit(AgentEvent("error", text = message))

    suspend fun request(method: String, params: JsonObject): JsonObject {
        val id = JsonPrimitive(ids.incrementAndGet())
        val deferred = CompletableDeferred<JsonObject>()
        val entry = Pending(method, deferred)
        pending[id] = entry
        try {
            write(
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", id)
                    put("method", method)
                    put("params", params)
                },
            )
            return deferred.await()
        } finally {
            pending.remove(id, entry)
        }
    }

    suspend fun notify(method: String, params: JsonObject) = write(
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
            put("params", params)
        },
    )

    fun incoming(requestId: String, method: String): Incoming {
        val request = synchronized(incomingLock) { incoming[requestId] }
            ?: throw IllegalArgumentException("Unknown or completed server request")
        require(request.method == method) { "Server request has a different method" }
        return request
    }

    private fun removeIncoming(request: Incoming): Boolean = synchronized(incomingLock) {
        if (incoming[request.id.content] !== request) return@synchronized false
        incoming.remove(request.id.content)
        incomingBytes -= request.bytes
        true
    }

    suspend fun respond(request: Incoming, result: JsonObject) {
        require(removeIncoming(request)) { "Server request already completed" }
        write(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", request.id)
                put("result", result)
            },
        )
    }

    suspend fun cancelInteractions() {
        synchronized(incomingLock) { incoming.values.toList() }.forEach { request ->
            val result = if (request.method == "session/request_permission") {
                buildJsonObject { put("outcome", buildJsonObject { put("outcome", "cancelled") }) }
            } else {
                buildJsonObject { put("action", "cancel") }
            }
            if (removeIncoming(request)) {
                write(
                    buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", request.id)
                        put("result", result)
                    },
                )
            }
        }
    }

    private suspend fun write(message: JsonObject) {
        val bytes = message.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_FRAME_BYTES) throw FrameTooLargeException()
        withContext(ioDispatcher) {
            writer.withLock {
                if (stopped.get()) throw EOFException("ACP channel is closed")
                try {
                    runInterruptible {
                        channel.stdin.write(bytes)
                        channel.stdin.write(10)
                        channel.stdin.flush()
                    }
                } catch (e: Exception) {
                    finish(IOException("ACP transport write failed"))
                    throw e
                }
            }
        }
    }

    private suspend fun receive(message: JsonObject) {
        if (message.string("jsonrpc") != "2.0") {
            diagnostic("Skipped frame without JSON-RPC 2.0 marker")
            return
        }
        val id = message["id"] as? JsonPrimitive
        val validId = id != null && id != JsonNull && (id.isString || id.longOrNull != null)
        val method = (message["method"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        if (method != null) {
            val params = if (message.containsKey("params")) message["params"] as? JsonObject else JsonObject(emptyMap())
            if (params == null || (message.containsKey("id") && !validId)) {
                diagnostic("Skipped invalid JSON-RPC request or notification")
                if (validId) errorResponse(id, -32602, "Invalid parameters")
                return
            }
            if (validId) {
                receiveRequest(id, method, params)
            } else if (method == "session/update") {
                val target = params.string("sessionId")
                if (target.isBlank() || (sessionId != null && target != sessionId)) {
                    diagnostic("Ignored update for a different session")
                    return
                }
                val update = params["update"] as? JsonObject
                if (update == null) diagnostic("Skipped invalid session update") else emit(normalizeUpdate(update))
            } else {
                emit(AgentEvent("unknown", title = method, raw = params))
            }
            return
        }
        if (!validId || (message.containsKey("result") == message.containsKey("error"))) {
            diagnostic("Skipped invalid JSON-RPC response")
            return
        }
        val entry = pending[id] ?: return
        if (entry.result.isCompleted) return
        val error = message["error"]
        if (error != null) {
            val code = ((error as? JsonObject)?.get("code") as? JsonPrimitive)?.longOrNull?.toInt()
            entry.result.completeExceptionally(AcpException(code ?: -32603))
            diagnostic("ACP request failed (code ${code ?: -32603})")
            return
        }
        val result = message["result"] as? JsonObject
        if (result == null) {
            entry.result.completeExceptionally(IOException("Invalid ACP result"))
            diagnostic("Invalid ACP response result")
            return
        }
        if (entry.method == "session/prompt") emit(AgentEvent("complete", status = result.string("stopReason"), raw = result))
        entry.result.complete(result)
    }

    private suspend fun receiveRequest(id: JsonPrimitive, method: String, params: JsonObject) {
        if (method != "session/request_permission" && method != "elicitation/create") {
            errorResponse(id, -32601, "Method not supported")
            return
        }
        val target = params.string("sessionId")
        if (target.isNotEmpty() && sessionId != null && target != sessionId) {
            errorResponse(id, -32602, "Unknown session")
            return
        }
        if (method == "elicitation/create" && params.string("mode").let { it.isNotEmpty() && it != "form" }) {
            errorResponse(id, -32602, "Only form elicitation is supported")
            return
        }
        val weight = retainedTextBytes(params.toString()) + retainedTextBytes(id.content) + 256
        val accepted = synchronized(incomingLock) {
            when {
                stopped.get() -> 0

                incoming.containsKey(id.content) -> -1

                incoming.size >= MAX_INCOMING_REQUESTS || weight > MAX_INCOMING_REQUEST_BYTES - incomingBytes -> 0

                else -> {
                    incoming[id.content] = Incoming(id, method, params, weight)
                    incomingBytes += weight
                    1
                }
            }
        }
        if (accepted < 0) {
            errorResponse(id, -32600, "Duplicate request ID")
            return
        }
        if (accepted == 0) {
            finish(IOException("ACP server request buffer exceeded its limit; use terminal fallback"), discardEvents = true)
            return
        }
        val tool = params["toolCall"] as? JsonObject
        emit(
            AgentEvent(
                type = if (method == "session/request_permission") "permission" else "question",
                id = id.content,
                text = params.string("message"),
                title = tool?.string("title").orEmpty(),
                options = permissionOptions(params),
                raw = params,
            ),
        )
    }

    private suspend fun errorResponse(id: JsonPrimitive, code: Int, message: String) = write(
        buildJsonObject {
            put("jsonrpc", "2.0")
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

    private fun finish(cause: IOException? = null, discardEvents: Boolean = false) {
        if (!stopped.compareAndSet(false, true)) return
        eventBuffer.close(cause?.message, discardEvents)
        runCatching { channel.close() }
        pending.values.forEach { it.result.completeExceptionally(cause ?: EOFException("ACP channel closed")) }
        pending.clear()
        synchronized(incomingLock) {
            incoming.clear()
            incomingBytes = 0
        }
        scope.cancel()
    }
}
