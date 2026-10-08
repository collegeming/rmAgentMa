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

class AcpBufferOverflowException : IOException("ACP history or event buffer exceeded its limit; use terminal fallback")

internal class BoundedEventBuffer(
    private val maxBytes: Long = MAX_BUFFERED_EVENT_BYTES.toLong(),
    private val maxEvents: Int = MAX_BUFFERED_EVENTS,
) {
    private data class Entry(val event: AgentEvent, val bytes: Long)

    private val lock = Any()
    private val queue = ArrayDeque<Entry>()
    private val changed = Channel<Unit>(Channel.CONFLATED)
    private val collected = AtomicBoolean(false)
    private var bytes = 0L
    private var count = 0
    private var closed = false
    private var terminal: AgentEvent? = null

    val events = flow {
        check(collected.compareAndSet(false, true)) { "ACP events support a single consumer" }
        while (true) {
            val (entry, ended, finalEvent) = synchronized(lock) {
                val next = queue.pollFirst()
                Triple(next, closed, if (next == null && closed) terminal.also { terminal = null } else null)
            }
            if (entry != null) {
                try {
                    emit(entry.event)
                } finally {
                    synchronized(lock) {
                        bytes -= entry.bytes
                        count--
                    }
                }
                continue
            }
            if (ended) {
                finalEvent?.let { emit(it) }
                break
            }
            changed.receiveCatching()
        }
    }

    fun offer(event: AgentEvent): Boolean {
        val weight = eventWeight(event)
        synchronized(lock) {
            if (closed || count >= maxEvents || weight > maxBytes - bytes) return false
            queue.addLast(Entry(event, weight))
            count++
            bytes += weight
        }
        changed.trySend(Unit)
        return true
    }

    fun close(error: String? = null, discard: Boolean = false) {
        synchronized(lock) {
            if (closed) return
            closed = true
            if (discard) {
                queue.forEach {
                    bytes -= it.bytes
                    count--
                }
                queue.clear()
            }
            terminal = error?.let { AgentEvent("error", text = it) }
        }
        changed.close()
    }

    private fun eventWeight(event: AgentEvent): Long = 256L + retainedTextBytes(event.type) + retainedTextBytes(event.text) + retainedTextBytes(event.id) +
        retainedTextBytes(event.title) + retainedTextBytes(event.status) +
        event.options.sumOf { 64L + retainedTextBytes(it.id) + retainedTextBytes(it.label) } +
        (event.raw?.toString()?.let(::retainedTextBytes) ?: 0L)
}

internal fun retainedTextBytes(value: String): Long = 64L + value.length.toLong() * 2 + value.toByteArray(Charsets.UTF_8).size
