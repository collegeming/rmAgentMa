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

package org.connectbot.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.rmagentma.core.AgentEvent

internal class AgentEventHistory(
    private val limit: Int = 500,
    private val textLimit: Int = 32_768,
    private val rawLimit: Int = 64 * 1024,
) {
    private val history = mutableListOf<AgentEvent>()

    init {
        require(rawLimit >= 1024)
    }

    fun append(event: AgentEvent): List<AgentEvent> {
        val last = history.lastOrNull()
        val toolIndex = if (event.type in TOOL_TYPES && event.id.isNotEmpty()) {
            history.indexOfLast { it.type in TOOL_TYPES && it.id == event.id }
        } else {
            -1
        }
        if (toolIndex >= 0) {
            val old = history[toolIndex]
            history[toolIndex] = bounded(
                event.copy(
                    type = "tool",
                    text = event.text.ifEmpty { old.text },
                    title = event.title.ifEmpty { old.title },
                    status = event.status.ifEmpty { old.status },
                    raw = merge(old.raw, event.raw),
                ),
            )
        } else if (last != null && event.type in STREAM_TYPES && last.type == event.type && messageId(last) == messageId(event)) {
            history[history.lastIndex] = bounded(event.copy(text = last.text + event.text, raw = merge(last.raw, event.raw)))
        } else {
            history += bounded(event)
        }
        trim()
        return history.toList()
    }

    fun isPending(event: AgentEvent, id: String, type: String): Boolean = event.id == id && event.type == type &&
        history.any { it === event }

    fun resolve(event: AgentEvent): List<AgentEvent> {
        history.removeAll { it === event }
        trim()
        return history.toList()
    }

    fun resolveAll(): List<AgentEvent> {
        history.removeAll { it.type in INTERACTIONS }
        trim()
        return history.toList()
    }

    fun clear() = history.clear()

    private fun messageId(event: AgentEvent): String = (event.raw?.get("messageId") as? JsonPrimitive)?.contentOrNull
        ?.takeIf { it.isNotEmpty() } ?: event.id

    private fun merge(old: JsonObject?, patch: JsonObject?): JsonObject? {
        if (old == null) return patch
        if (patch == null) return old
        val fields = old.toMutableMap()
        patch.forEach { (key, value) ->
            val previous = fields[key]
            fields[key] = when {
                value == JsonNull -> previous ?: value
                value is JsonObject && previous is JsonObject -> merge(previous, value)!!
                key == "content" && value is JsonArray && previous is JsonArray -> JsonArray((previous + value).distinct())
                else -> value
            }
        }
        return JsonObject(fields)
    }

    private fun bounded(event: AgentEvent): AgentEvent {
        val raw = event.raw
        val bytes = raw?.toString()?.toByteArray(Charsets.UTF_8)?.size ?: 0
        val textTruncated = event.text.length > textLimit
        val rawTruncated = bytes > rawLimit
        var projected = raw
        if (rawTruncated && raw != null) {
            var budget = (rawLimit / 12).coerceAtLeast(64)
            fun project(value: JsonElement, depth: Int): JsonElement {
                if (depth > 10 || budget <= 0) return JsonPrimitive("[truncated]")
                return when (value) {
                    is JsonObject -> JsonObject(
                        value.entries.take(32).associate { (key, nested) ->
                            budget -= key.length.coerceAtMost(128) + 8
                            key.take(128) to project(nested, depth + 1)
                        },
                    )

                    is JsonArray -> JsonArray(value.take(32).map { project(it, depth + 1) })

                    is JsonPrimitive -> {
                        val text = value.content.take(budget.coerceAtLeast(0).coerceAtMost(4096))
                        budget -= text.length + 8
                        if (text.length == value.content.length) value else JsonPrimitive(text + "[truncated]")
                    }
                }
            }
            projected = project(raw, 0) as JsonObject
            if (projected.toString().toByteArray(Charsets.UTF_8).size > rawLimit - 256) {
                projected = JsonObject(mapOf("rawPreview" to JsonPrimitive(raw.toString().take((rawLimit / 12).coerceAtLeast(64)))))
            }
        }
        if (rawTruncated || textTruncated) {
            projected = JsonObject(
                projected.orEmpty() + mapOf(
                    "truncated" to JsonPrimitive(true),
                    "originalRawBytes" to JsonPrimitive(bytes),
                ),
            )
            if (projected.toString().toByteArray(Charsets.UTF_8).size > rawLimit) {
                projected = JsonObject(
                    mapOf(
                        "rawPreview" to JsonPrimitive(raw.toString().take(rawLimit / 12)),
                        "truncated" to JsonPrimitive(true),
                        "originalRawBytes" to JsonPrimitive(bytes),
                    ),
                )
            }
        }
        return event.copy(
            text = event.text.takeLast(textLimit),
            title = event.title.take(512),
            status = event.status.take(128),
            raw = projected,
        )
    }

    private fun trim() {
        while (history.size > limit) {
            val removable = history.indexOfFirst { it.type !in INTERACTIONS }
            if (removable < 0) break
            history.removeAt(removable)
        }
    }

    private companion object {
        val TOOL_TYPES = setOf("tool", "tool_update")
        val STREAM_TYPES = setOf("content", "thinking")
        val INTERACTIONS = setOf("permission", "question")
    }
}
