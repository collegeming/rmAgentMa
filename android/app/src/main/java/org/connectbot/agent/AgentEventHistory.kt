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

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.rmagentma.core.AgentEvent
import org.rmagentma.core.AgentOption
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.zip.CRC32

internal class AgentEventHistory(
    private val limit: Int = 500,
    private val textLimit: Int = 32_768,
    private val rawLimit: Int = 64 * 1024,
    private val directory: File = File(System.getProperty("java.io.tmpdir"), "agent-history"),
    private val capacityBytes: Long = 256L * 1024 * 1024,
    private val cleanupExisting: Boolean = false,
    private val windowBytes: Long = 8L * 1024 * 1024,
) {
    private val history = mutableListOf<AgentEvent>()
    private val pending = mutableListOf<AgentEvent>()
    private var dataFile: File? = null
    private var indexFile: File? = null
    private var data: RandomAccessFile? = null
    private var rows: RandomAccessFile? = null
    private var rowCount = 0L
    private var lastKey = -1L
    private val recentTools = object : LinkedHashMap<String, Long>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > 128
    }
    private var pageStart = 0L
    private var viewingEarlier = false
    private var cleaned = false
    val hasEarlier: Boolean get() = pageStart > 0
    val hasLatest: Boolean get() = viewingEarlier

    init {
        require(limit > 0 && textLimit > 0 && rawLimit >= 1024 && capacityBytes > 0)
    }

    private fun openStore() {
        if (data != null) return
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create private agent history store")
        try {
            if (cleanupExisting && !cleaned) {
                val files = directory.listFiles() ?: throw IOException("Cannot list stale agent history stores")
                files.filter { it.name.startsWith("history-") && it.extension in setOf("log", "idx") }.forEach {
                    if (!it.delete()) throw IOException("Cannot clean stale agent history store")
                }
                cleaned = true
            }
            dataFile = File.createTempFile("history-", ".log", directory)
            indexFile = File.createTempFile("history-", ".idx", directory)
            data = RandomAccessFile(dataFile!!, "rw")
            rows = RandomAccessFile(indexFile!!, "rw")
        } catch (e: IOException) {
            clear()
            throw IOException("Cannot open private agent history store", e)
        }
    }

    fun append(event: AgentEvent, checkActive: () -> Unit = {}): List<AgentEvent> {
        checkActive()
        openStore()
        val last = if (lastKey >= 0) readRecord(offset(lastKey)).second else null
        var key = -1L
        if (event.type in TOOL_TYPES && event.id.isNotEmpty()) {
            key = recentTools[event.id] ?: -1
            if (key < 0 && event.type == "tool_update") {
                var candidate = rowCount - 1
                while (candidate >= 0) {
                    checkActive()
                    val old = readRecord(offset(candidate)).second
                    if (old.type in TOOL_TYPES && old.id == event.id) {
                        key = candidate
                        break
                    }
                    candidate--
                }
            }
        } else if (last != null && event.type in STREAM_TYPES && last.type == event.type && messageId(last) == messageId(event)) {
            key = lastKey
        }
        val previous = if (key >= 0) offset(key) else -1L
        if (key < 0) key = rowCount
        val stored = event.copy(historyKey = key)
        persist(stored, previous)
        if (key == rowCount) rowCount++
        lastKey = key
        if (event.type in TOOL_TYPES && event.id.isNotEmpty() && event.id.length <= 1024) recentTools[event.id] = key
        val visible = history.indexOfFirst { it.historyKey == key }
        if (!viewingEarlier || visible >= 0 || event.type in INTERACTIONS) {
            val preview = if (visible >= 0) bounded(combine(history[visible], stored)) else readItem(key, preview = true, checkActive)
            if (visible >= 0) {
                history[visible] = preview
            } else if (!viewingEarlier) {
                history += preview
            }
            if (event.type in INTERACTIONS) pending += preview
            trim()
        }
        return window()
    }

    fun loadEarlier(checkActive: () -> Unit = {}): List<AgentEvent> {
        if (!hasEarlier) return window()
        val result = loadPage(pageStart, checkActive)
        viewingEarlier = true
        return result
    }

    fun loadLatest(checkActive: () -> Unit = {}): List<AgentEvent> {
        val result = loadPage(rowCount, checkActive)
        viewingEarlier = false
        return result
    }

    private fun loadPage(end: Long, checkActive: () -> Unit): List<AgentEvent> {
        checkActive()
        val page = mutableListOf<AgentEvent>()
        var budget = pending.sumOf(::weight)
        var key = end - 1
        while (key >= 0 && page.size < limit) {
            checkActive()
            val event = readItem(key, preview = true, checkActive)
            val bytes = weight(event)
            if (page.isNotEmpty() && budget + bytes > windowBytes) break
            page += event
            budget += bytes
            key--
        }
        history.clear()
        history += page.asReversed()
        pageStart = key + 1
        return window()
    }

    fun fullText(event: AgentEvent, checkActive: () -> Unit = {}): AgentEvent {
        require(event.historyKey in 0 until rowCount) { "History item is no longer available" }
        return readItem(event.historyKey, preview = false, checkActive)
    }

    private fun persist(event: AgentEvent, previous: Long) {
        val payload = encode(event).toByteArray(Charsets.UTF_8)
        val log = data!!
        val index = rows!!
        if (payload.size.toLong() + 16 + log.length() + index.length() + 8 > capacityBytes) {
            throw IOException("Agent history store capacity exceeded ($capacityBytes bytes)")
        }
        try {
            val position = log.length()
            log.seek(position)
            val record = java.nio.ByteBuffer.allocate(16 + payload.size)
                .putLong(previous)
                .putInt(payload.size)
                .putInt(CRC32().apply { update(payload) }.value.toInt())
                .put(payload)
                .array()
            log.write(record)
            index.seek(event.historyKey * 8)
            index.write(java.nio.ByteBuffer.allocate(8).putLong(position).array())
        } catch (e: IOException) {
            throw IOException("Cannot write agent history (disk full or unavailable)", e)
        }
    }

    private fun readItem(key: Long, preview: Boolean, checkActive: () -> Unit = {}): AgentEvent {
        var position = offset(key)
        var result: AgentEvent? = null
        val text = StringBuilder()
        var originalTextLength = 0L
        var streaming = false
        while (position >= 0) {
            checkActive()
            val (previous, event) = readRecord(position)
            if (previous >= position) throw IOException("Corrupt agent history chain")
            streaming = event.type in STREAM_TYPES
            val item = if (streaming) {
                originalTextLength += event.text.length
                val available = if (preview) (textLimit - text.length).coerceAtLeast(0) else event.text.length
                text.append(event.text.takeLast(available).reversed())
                event.copy(text = "")
            } else {
                event
            }
            result = result?.let { combine(item, it) } ?: item
            if (preview) result = bounded(result)
            position = previous
        }
        val item = result ?: throw IOException("Missing agent history item")
        if (!streaming) return item
        val raw = if (preview && originalTextLength > textLimit) {
            JsonObject(item.raw.orEmpty() + mapOf("truncated" to JsonPrimitive(true), "originalTextLength" to JsonPrimitive(originalTextLength)))
        } else {
            item.raw
        }
        return item.copy(text = text.reverse().toString(), raw = raw)
    }

    private fun offset(key: Long): Long {
        try {
            rows!!.seek(key * 8)
            val bytes = ByteArray(8)
            rows!!.readFully(bytes)
            return java.nio.ByteBuffer.wrap(bytes).long
        } catch (e: IOException) {
            throw IOException("Corrupt agent history index", e)
        }
    }

    private fun readRecord(position: Long): Pair<Long, AgentEvent> {
        try {
            val log = data!!
            if (position < 0 || position > log.length() - 16) throw IOException("Invalid record offset")
            log.seek(position)
            val header = ByteArray(16)
            log.readFully(header)
            val fields = java.nio.ByteBuffer.wrap(header)
            val previous = fields.long
            val length = fields.int
            val checksum = fields.int
            if (length < 0 || length > log.length() - log.filePointer || length > capacityBytes) throw IOException("Invalid record length")
            val bytes = ByteArray(length)
            log.readFully(bytes)
            if (CRC32().apply { update(bytes) }.value.toInt() != checksum) throw IOException("Record checksum mismatch")
            return previous to decode(bytes.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            throw IOException("Corrupt agent history store", e)
        }
    }

    private fun encode(event: AgentEvent): String = buildJsonObject {
        put("type", event.type)
        put("text", event.text)
        put("id", event.id)
        put("title", event.title)
        put("status", event.status)
        put("key", event.historyKey)
        put(
            "options",
            JsonArray(
                event.options.map { option ->
                    buildJsonObject {
                        put("id", option.id)
                        put("label", option.label)
                    }
                },
            ),
        )
        event.raw?.let { put("raw", it) }
    }.toString()

    private fun decode(text: String): AgentEvent {
        val value = Json.parseToJsonElement(text) as JsonObject
        fun field(key: String) = value[key]?.jsonPrimitive?.content.orEmpty()
        return AgentEvent(
            type = field("type"),
            text = field("text"),
            id = field("id"),
            title = field("title"),
            status = field("status"),
            options = (value["options"] as JsonArray).map { item ->
                val option = item as JsonObject
                AgentOption(option["id"]!!.jsonPrimitive.content, option["label"]!!.jsonPrimitive.content)
            },
            raw = value["raw"] as? JsonObject,
            historyKey = value["key"]!!.jsonPrimitive.long,
        )
    }

    private fun combine(old: AgentEvent, event: AgentEvent): AgentEvent = event.copy(
        type = if (event.type in TOOL_TYPES) "tool" else event.type,
        text = if (event.type in STREAM_TYPES) old.text + event.text else event.text.ifEmpty { old.text },
        title = event.title.ifEmpty { old.title },
        status = event.status.ifEmpty { old.status },
        raw = merge(old.raw, event.raw),
    )

    private fun window(): List<AgentEvent> = (history.filter { it.type !in INTERACTIONS } + pending).distinctBy { it.historyKey }.sortedBy { it.historyKey }

    fun isPending(event: AgentEvent, id: String, type: String): Boolean = event.id == id && event.type == type && pending.any { it === event }

    fun resolve(event: AgentEvent): List<AgentEvent> {
        if (!pending.any { it === event }) return window()
        val resolved = event.copy(type = "${event.type}_resolved", status = "resolved", text = "", raw = null)
        persist(resolved, offset(event.historyKey))
        pending.removeAll { it === event }
        history.removeAll { it.historyKey == event.historyKey }
        return window()
    }

    fun resolveAll(): List<AgentEvent> {
        pending.toList().forEach { resolve(it) }
        return window()
    }

    fun clear() {
        history.clear()
        pending.clear()
        data?.close()
        rows?.close()
        data = null
        rows = null
        dataFile?.let { if (it.exists() && !it.delete()) throw IOException("Cannot clean agent history store") }
        indexFile?.let { if (it.exists() && !it.delete()) throw IOException("Cannot clean agent history index") }
        dataFile = null
        indexFile = null
        rowCount = 0
        lastKey = -1
        recentTools.clear()
        pageStart = 0
        viewingEarlier = false
    }

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
                value is JsonArray -> value
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

    private fun weight(event: AgentEvent): Long = 256L +
        (event.text.length + event.id.length + event.title.length + event.status.length).toLong() * 2 +
        (event.raw?.toString()?.length?.toLong() ?: 0) * 2 + event.options.sumOf { 64L + (it.id.length + it.label.length) * 2 }

    private fun trim() {
        history.sortBy { it.historyKey }
        var bytes = history.sumOf(::weight) + pending.sumOf(::weight)
        while (history.isNotEmpty() && (history.size > limit || bytes > windowBytes)) {
            bytes -= weight(history.removeAt(0))
        }
        if (pending.sumOf(::weight) > windowBytes) throw IOException("Pending agent interactions exceed the history memory budget")
        pageStart = history.firstOrNull()?.historyKey ?: rowCount
    }

    private companion object {
        val TOOL_TYPES = setOf("tool", "tool_update")
        val STREAM_TYPES = setOf("content", "thinking", "user")
        val INTERACTIONS = setOf("permission", "question")
    }
}
