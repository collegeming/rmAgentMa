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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

internal const val MAX_BUFFERED_EVENT_BYTES = 8 * 1024 * 1024
internal const val MAX_BUFFERED_EVENTS = 4096
internal const val MAX_INCOMING_REQUESTS = 64
internal const val MAX_INCOMING_REQUEST_BYTES = 1024 * 1024
internal const val MAX_LIST_PAGES = 100

internal class BoundedEventBuffer(
    private val maxBytes: Long = MAX_BUFFERED_EVENT_BYTES.toLong(),
    private val maxEvents: Int = MAX_BUFFERED_EVENTS,
) {
    private data class Entry(val event: AgentEvent?, val bytes: Long, val consumed: CompletableDeferred<Unit>? = null)

    private val lock = Any()
    private val queue = ArrayDeque<Entry>()
    private val available = Channel<Unit>(Channel.CONFLATED)
    private val space = Channel<Unit>(Channel.CONFLATED)
    private val collected = AtomicBoolean(false)
    private val ready = CompletableDeferred<Unit>()
    private var bytes = 0L
    private var count = 0
    private var closed = false
    private var terminal: AgentEvent? = null

    val events = flow {
        check(collected.compareAndSet(false, true)) { "ACP events support a single consumer" }
        ready.complete(Unit)
        try {
            while (true) {
                val (entry, ended, finalEvent) = synchronized(lock) {
                    val next = queue.pollFirst()
                    Triple(next, closed, if (next == null && closed) terminal.also { terminal = null } else null)
                }
                if (entry != null) {
                    try {
                        entry.event?.let { emit(it) }
                        entry.consumed?.complete(Unit)
                    } finally {
                        synchronized(lock) {
                            bytes -= entry.bytes
                            count--
                        }
                        space.trySend(Unit)
                    }
                    continue
                }
                if (ended) {
                    finalEvent?.let { emit(it) }
                    break
                }
                available.receiveCatching()
            }
        } finally {
            close("ACP event consumer closed")
        }
    }

    suspend fun awaitConsumer() = ready.await()

    suspend fun offer(event: AgentEvent): Boolean = enqueue(Entry(event, eventWeight(event)))

    suspend fun barrier() {
        val consumed = CompletableDeferred<Unit>()
        if (!enqueue(Entry(null, 0, consumed))) throw IOException("ACP event stream closed before load completed")
        consumed.await()
    }

    private suspend fun enqueue(entry: Entry): Boolean {
        if (entry.bytes > maxBytes) throw IOException("ACP event exceeds the bounded event budget")
        while (true) {
            val accepted = synchronized(lock) {
                if (closed) return false
                if (count < maxEvents && entry.bytes <= maxBytes - bytes) {
                    queue.addLast(entry)
                    count++
                    bytes += entry.bytes
                    true
                } else {
                    false
                }
            }
            if (accepted) {
                available.trySend(Unit)
                return true
            }
            space.receiveCatching()
        }
    }

    fun close(error: String? = null) {
        synchronized(lock) {
            if (closed) return
            closed = true
            queue.forEach { it.consumed?.completeExceptionally(IOException(error ?: "ACP event stream closed")) }
            terminal = error?.let { AgentEvent("error", text = it) }
        }
        ready.completeExceptionally(IOException(error ?: "ACP event stream closed"))
        available.close()
        space.close()
    }

    private fun eventWeight(event: AgentEvent): Long = 256L + retainedTextBytes(event.type) + retainedTextBytes(event.text) + retainedTextBytes(event.id) +
        retainedTextBytes(event.title) + retainedTextBytes(event.status) +
        event.options.sumOf { 64L + retainedTextBytes(it.id) + retainedTextBytes(it.label) } +
        (event.raw?.toString()?.let(::retainedTextBytes) ?: 0L)
}

internal fun retainedTextBytes(value: String): Long = 64L + value.length.toLong() * 2 + value.toByteArray(Charsets.UTF_8).size
