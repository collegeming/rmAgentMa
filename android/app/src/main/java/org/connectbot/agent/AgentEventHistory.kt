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
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.rmagentma.core.AgentEvent
import org.rmagentma.core.AgentKind
import org.rmagentma.core.AgentOption
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.CRC32

/** Every page this app set out to read for one session is stored, and the Agent's replay reached its barrier. */
internal const val HISTORY_COMPLETE = "complete"

/** Some pages are missing, unreadable or not read yet; [AgentEventHistory.integrityNote] says why. */
internal const val HISTORY_PARTIAL = "partial"

/** The stored pages cannot be read at all, so this store answers for none of them. */
internal const val HISTORY_UNAVAILABLE = "unavailable"

/**
 * One conversation's stored event pages, keyed by `(hostId, agent, sessionId)`.
 *
 * A named store keeps its pages in [directory] - `noBackupFilesDir` in the app - so they survive a process
 * restart, are never emptied by opening a session, never deleted at start-up and never reach the system backup.
 * The store only appends: every failure (full store, torn record, unreadable file, dropped link) keeps the pages
 * already written and reports the history as incomplete instead of dropping them, and only a completed history
 * read marks it complete. A store without a name writes temporary pages and stands for no session.
 *
 * Not thread safe: the conversation owner (the repository) serializes every call.
 */
internal class AgentEventHistory(
    private val limit: Int = 500,
    private val textLimit: Int = 32_768,
    private val rawLimit: Int = 64 * 1024,
    private val directory: File = File(System.getProperty("java.io.tmpdir"), "agent-history"),
    private val capacityBytes: Long = 256L * 1024 * 1024,
    private val windowBytes: Long = 8L * 1024 * 1024,
    private var name: String? = null,
    private val source: String = "",
) {
    private val history = mutableListOf<AgentEvent>()
    private val pending = mutableListOf<AgentEvent>()
    private var dataFile: File? = null
    private var indexFile: File? = null
    private var data: RandomAccessFile? = null
    private var rows: RandomAccessFile? = null
    private var rowCount = 0L
    private var lastKey = -1L

    /** The stored row the Agent's replay is expected to re-deliver next; -1 stops matching, see [replay]. */
    private var replayRow = -1L

    /** The identity of the record the replay is currently re-delivering, once a whole stored page was matched. */
    private var replayIdentity: String? = null

    /** How much of an identityless stored row the replay has repeated so far, chunk by chunk. */
    private var replayText = ""

    /** What this store reports about its own completeness, and the reason it is not [HISTORY_COMPLETE]. */
    private var state = HISTORY_PARTIAL
    private var note: String? = null

    /** Set by any read or write that failed while this store was open: complete is never claimed afterwards. */
    private var failed = false
    private val recentTools = object : LinkedHashMap<String, Long>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > 128
    }
    private var pageStart = 0L
    private var viewingEarlier = false
    val hasEarlier: Boolean get() = pageStart > 0
    val hasLatest: Boolean get() = viewingEarlier

    /** What this store reports about the pages it holds. */
    val integrityState: String get() = state

    /** Why this store is not [HISTORY_COMPLETE], or null while it is. */
    val integrityNote: String? get() = note

    init {
        require(limit > 0 && textLimit > 0 && rawLimit >= 1024 && capacityBytes > 0)
    }

    /**
     * Opens this session's store, resumes the pages it already holds and shows its newest page as the current
     * window, so a conversation that was read before is readable again without reading the Agent a second time.
     */
    fun attach(checkActive: () -> Unit = {}): List<AgentEvent> {
        openStore()
        readMeta()
        replayRow = if (rowCount > 0) 0 else -1
        replayIdentity = null
        replayText = ""
        loadPage(rowCount, checkActive)
        return window()
    }

    /** Marks the history complete. Only a caller that saw every page of this session read may call this. */
    fun markComplete() {
        if (failed) return
        state = HISTORY_COMPLETE
        note = null
        persistMeta()
    }

    /**
     * Reports a failure that left some of this session's pages unfetched or unwritten. The pages already stored
     * stay exactly as they are, and only a later completed read marks the history complete again. What this store
     * cannot state is recorded for the next run as well, so a process that ends here still reports the pages it
     * holds as incomplete instead of inheriting a state pages no longer back.
     */
    fun markIncomplete(reason: String) {
        failed = true
        state = HISTORY_PARTIAL
        note = reason.take(MAX_NOTE)
        persistMeta()
    }

    /** Stops matching the Agent's replay: after the history barrier every event is the live conversation. */
    fun endReplay() {
        replayRow = -1
        replayIdentity = null
        replayText = ""
    }

    /** Closes this store's files and keeps every page written; the next open resumes them. */
    fun close() {
        try {
            data?.close()
            rows?.close()
        } catch (_: IOException) {
            // A file that cannot be closed is still on disk: the pages are kept either way.
        }
        data = null
        rows = null
        dataFile = null
        indexFile = null
        history.clear()
        pending.clear()
        recentTools.clear()
        pageStart = 0
        viewingEarlier = false
        replayRow = -1
        replayIdentity = null
        replayText = ""
    }

    /**
     * Moves these pages onto the store the Agent's own session id names, once the Agent answered with the id it
     * created for a conversation this app opened without one. A store that already exists under that name keeps
     * every page it holds: the pages written before the Agent answered then stay in this store beside it, and the
     * fact that this session's history is split over two stores is reported rather than hidden.
     */
    fun rename(target: String) {
        val previous = name ?: return
        if (previous == target) return
        val log = dataFile
        val index = indexFile
        try {
            data?.close()
            rows?.close()
        } catch (_: IOException) { }
        data = null
        rows = null
        val targetLog = File(directory, "$target.log")
        val targetIndex = File(directory, "$target.idx")
        if (log != null && index != null && !targetLog.exists() && !targetIndex.exists() && log.renameTo(targetLog) && index.renameTo(targetIndex)) {
            name = target
            File(directory, "$previous.meta.json").takeIf { it.exists() }?.renameTo(File(directory, "$target.meta.json"))
            openStore()
            return
        }
        name = previous
        openStore()
        record("this open's pages stay in their own store: the session already has pages under the Agent's own id")
    }

    private fun openStore() {
        if (data != null) return
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create private agent history store")
        try {
            dataFile = if (name == null) File.createTempFile("history-", ".log", directory) else File(directory, "$name.log")
            indexFile = if (name == null) File.createTempFile("history-", ".idx", directory) else File(directory, "$name.idx")
            data = RandomAccessFile(dataFile!!, "rw")
            rows = RandomAccessFile(indexFile!!, "rw")
            if (name != null) resumeStoredPages()
        } catch (e: Exception) {
            // A named store holds pages a user may still want: it is closed, never cleared, when it cannot open.
            try {
                if (name == null) clear() else close()
            } catch (_: IOException) { }
            throw IOException("Cannot open private agent history store", e)
        }
    }

    /**
     * Continues the pages already stored: the index length is where this store writes next and its last row the
     * order the rows are appended in. A torn or unreadable tail is left out of the index and reported - never
     * repaired by rewriting the pages before it, and never by deleting them.
     */
    private fun resumeStoredPages() {
        val size = indexFile?.length() ?: 0L
        rowCount = size / 8
        if (size % 8 != 0L) {
            rows?.setLength(rowCount * 8)
            record("the last stored page was cut off by an interrupted write")
        }
        var dropped = 0
        while (rowCount > 0 && dropped < MAX_DROPPED_TAIL && !rowReadable(rowCount - 1)) {
            rowCount--
            rows?.setLength(rowCount * 8)
            dropped++
        }
        if (dropped >= MAX_DROPPED_TAIL) {
            state = HISTORY_UNAVAILABLE
            note = "the stored pages of this session are damaged and cannot be read"
        } else if (dropped > 0) {
            record("the newest stored page is unreadable and was left out")
        }
        lastKey = rowCount - 1
    }

    /** True when the row at [key] reads back from the stored pages. */
    private fun rowReadable(key: Long): Boolean = try {
        readItem(key, preview = true)
        true
    } catch (_: IOException) {
        false
    }

    /** Keeps the first reason this store is not complete; a later [markComplete] clears it again. */
    private fun record(reason: String) {
        if (state != HISTORY_UNAVAILABLE) state = HISTORY_PARTIAL
        if (note == null) note = reason.take(MAX_NOTE)
    }

    private fun metaFile(): File? = name?.let { File(directory, "$it.meta.json") }

    /** Restores the integrity the last run of this store reported for these pages. */
    private fun readMeta() {
        val file = metaFile() ?: return
        if (!file.exists()) return
        try {
            val value = Json.parseToJsonElement(file.readText()) as? JsonObject ?: throw IOException("Invalid integrity record")
            val version = value["version"]?.jsonPrimitive?.longOrNull
            val writtenSource = value["source"]?.jsonPrimitive?.contentOrNull
            if (version != FORMAT_VERSION.toLong() || (source.isNotEmpty() && !writtenSource.isNullOrEmpty() && writtenSource != source)) {
                // A record this store did not write describes pages it cannot answer for.
                state = HISTORY_PARTIAL
                note = "the stored integrity record was written for another history format or source"
                return
            }
            state = value["state"]?.jsonPrimitive?.contentOrNull ?: HISTORY_PARTIAL
            note = value["note"]?.jsonPrimitive?.contentOrNull
            val cursor = value["cursor"]?.jsonPrimitive?.longOrNull
            if (state == HISTORY_COMPLETE && cursor != rowCount) {
                // A complete record that names a different number of pages describes pages that are not here.
                record("the stored integrity record does not match the stored pages")
            }
        } catch (e: Exception) {
            state = HISTORY_PARTIAL
            note = "the stored integrity record is unreadable".take(MAX_NOTE)
        }
    }

    /** Records what this store can honestly say about its pages; a record that cannot be written is not claimed. */
    private fun persistMeta() {
        val file = metaFile() ?: return
        try {
            val temp = File(directory, file.name + ".tmp")
            temp.writeText(
                buildJsonObject {
                    put("state", state)
                    note?.let { put("note", it) }
                    put("cursor", rowCount)
                    put("source", source)
                    put("version", FORMAT_VERSION)
                    put("writtenAt", System.currentTimeMillis())
                }.toString(),
            )
            if (!temp.renameTo(file)) throw IOException("Cannot replace the stored integrity record")
        } catch (e: Exception) {
            failed = true
            state = HISTORY_PARTIAL
            note = "the stored integrity record could not be written".take(MAX_NOTE)
        }
    }

    fun append(event: AgentEvent, checkActive: () -> Unit = {}): List<AgentEvent> {
        checkActive()
        openStore()
        if (replay(event, checkActive)) return window()
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
        } else if (last != null && continuesMessage(last, event)) {
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

    /**
     * True when the Agent's own history replay delivered a record this store already holds, so no page is written
     * twice and the conversation shows one copy of it. Records are matched by the identity their source gave them
     * - the message or tool call the Agent names - and never by their text, because two records may read the same.
     * A prompt this app wrote itself stands for the Agent's copy of it at the same position, and records that carry
     * no identity at all are matched by the order they were read in, with their content confirming the match. Rows
     * the replay does not re-deliver (this app's own diagnostics, an interaction already resolved) are stepped
     * over by the bounded seek below, so a replay that starts after the oldest stored row still lands on its pages.
     */
    private fun replay(event: AgentEvent, checkActive: () -> Unit): Boolean {
        val identity = sourceIdentity(event)
        if (identity != null && identity == replayIdentity) {
            // The rest of a record the Agent replays in parts, already stored as one page.
            checkActive()
            return true
        }
        while (replayRow >= 0 && replayRow < rowCount) {
            checkActive()
            val stored = readStoredRow(replayRow, checkActive)
            // A prompt this app wrote down itself is the same record as the Agent's own copy of it here: the row
            // holds the user's own words, so the Agent's copy is what repeats and must not be stored a second
            // time, whatever identity the Agent gives that copy.
            if (event.type == "user" && stored.id.startsWith(LOCAL_ID_PREFIX)) {
                replayRow++
                return true
            }
            if (identity != null) {
                if (identity == sourceIdentity(stored)) {
                    replayIdentity = identity
                    replayRow++
                    return true
                }
                val found = seekReplayRow(identity, checkActive)
                if (found < 0) {
                    // Not one of the stored pages: this event is the live conversation, and the row the replay is
                    // expected to re-deliver next is still the one matched against from here on.
                    return false
                }
                replayIdentity = identity
                replayRow = found + 1
                return true
            }
            // Records the Agent named nothing: the order they were read in identifies them, and the content the
            // replay repeats confirms the match.
            if (sourceIdentity(stored) != null || stored.type != event.type) return false
            val progress = replayText + event.text
            when {
                stored.text == progress -> {
                    replayRow++
                    replayText = ""
                    return true
                }

                stored.text.startsWith(progress) -> {
                    // The stored page holds more of this message than the replay has repeated so far.
                    replayText = progress
                    return true
                }

                else -> {
                    replayText = ""
                    return false
                }
            }
        }
        return false
    }

    /** The row at [key] as the replay sees it: its identity and its text, bounded to the display window's size. */
    private fun readStoredRow(key: Long, checkActive: () -> Unit): AgentEvent = try {
        readItem(key, preview = true, checkActive)
    } catch (e: IOException) {
        markIncomplete(e.message ?: "a stored history page could not be read")
        throw e
    }

    /** The first row from the current match onward that carries [identity], or -1 within a bounded scan. */
    private fun seekReplayRow(identity: String, checkActive: () -> Unit): Long {
        var candidate = replayRow + 1
        val bound = minOf(rowCount, candidate + REPLAY_SEEK_LIMIT)
        while (candidate < bound) {
            checkActive()
            if (sourceIdentity(readStoredRow(candidate, checkActive)) == identity) return candidate
            candidate++
        }
        return -1
    }

    /**
     * The stable identity the source gave this record: the tool call it belongs to, or the message it is a part
     * of. Null means the Agent named nothing, and only the order its records arrived in identifies them.
     */
    private fun sourceIdentity(event: AgentEvent): String? = when {
        event.type in TOOL_TYPES -> event.id.takeIf { it.isNotEmpty() }?.let { "tool:$it" }
        event.type in STREAM_TYPES -> messageId(event).takeIf { it.isNotEmpty() }?.let { "${event.type}:$it" }
        else -> event.id.takeIf { it.isNotEmpty() }?.let { "${event.type}:$it" }
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
            // A full store keeps every page it already holds. Pages are never dropped to make room for newer
            // ones: this session's history is reported incomplete instead, and the next read continues from the
            // page that failed here.
            markIncomplete("the stored history reached its storage budget ($capacityBytes bytes)")
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
            markIncomplete("a history page could not be written (disk full or unavailable)")
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

    /**
     * Deletes this store's pages and starts over. This is what an explicit user cleanup does to one session's
     * pages; nothing else in this class ever removes a page.
     */
    fun clear() {
        history.clear()
        pending.clear()
        data?.close()
        rows?.close()
        data = null
        rows = null
        dataFile?.let { if (it.exists() && !it.delete()) throw IOException("Cannot clean agent history store") }
        indexFile?.let { if (it.exists() && !it.delete()) throw IOException("Cannot clean agent history index") }
        metaFile()?.let { if (it.exists() && !it.delete()) throw IOException("Cannot clean the stored integrity record") }
        dataFile = null
        indexFile = null
        rowCount = 0
        lastKey = -1
        recentTools.clear()
        pageStart = 0
        viewingEarlier = false
        replayRow = -1
        replayIdentity = null
        replayText = ""
        state = HISTORY_PARTIAL
        note = null
        failed = false
    }

    private fun messageId(event: AgentEvent): String = (event.raw?.get("messageId") as? JsonPrimitive)?.contentOrNull
        ?.takeIf { it.isNotEmpty() } ?: event.id

    /**
     * Assistant and thinking text arrives as unspecified delta chunks of one message, so consecutive
     * chunks of those types continue the previous row. User updates are separate messages: an agent
     * that replays them without an identity still sends one update per stored message, and merging
     * those would append injected system reminders to the user's own text.
     */
    private fun continuesMessage(last: AgentEvent, event: AgentEvent): Boolean {
        if (event.type !in STREAM_TYPES || last.type != event.type) return false
        val identity = messageId(event)
        if (identity != messageId(last)) return false
        return identity.isNotEmpty() || event.type != "user"
    }

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

    companion object {
        private val TOOL_TYPES = setOf("tool", "tool_update")
        private val STREAM_TYPES = setOf("content", "thinking", "user")
        private val INTERACTIONS = setOf("permission", "question")

        /** Page files this store writes, and the order they are recognized in. */
        private val STORE_SUFFIXES = listOf(".meta.json", ".idx", ".log")

        /**
         * Stable name of one session's store: the host and the Agent stay readable and the Agent's own session
         * id is hashed, because that id is the Agent's text and may hold any character a file name cannot.
         */
        fun storeName(hostId: Long, agent: AgentKind, sessionId: String): String =
            "history-$hostId-${agent.wireName}-" + MessageDigest.getInstance("SHA-256")
                .digest(sessionId.toByteArray(Charsets.UTF_8))
                .take(8)
                .joinToString("") { "%02x".format(it) }

        /**
         * Removes every stored page under [directory] and answers how many sessions' stores were removed. This is
         * the explicit cleanup a user asks for and the only path that removes stored pages; a file that cannot be
         * removed throws, so no caller can report a cleanup that did not happen.
         */
        fun clearAll(directory: File): Int {
            val files = directory.listFiles().orEmpty()
            val stores = files.mapNotNull { storeOf(it.name) }.toSet()
            files.forEach { file ->
                if (storeOf(file.name) == null) return@forEach
                if (file.isDirectory || !file.delete()) throw IOException("Cannot clean the stored history of a session")
            }
            if (directory.listFiles().orEmpty().any { storeOf(it.name) != null }) {
                throw IOException("Cannot clean the stored history of every session")
            }
            return stores.size
        }

        /** The store a page file belongs to, or null for a file this store did not write. */
        private fun storeOf(fileName: String): String? {
            if (!fileName.startsWith("history-")) return null
            val withoutTemp = fileName.removeSuffix(TEMP_SUFFIX)
            val suffix = STORE_SUFFIXES.firstOrNull { withoutTemp.endsWith(it) } ?: return null
            return withoutTemp.removeSuffix(suffix).takeIf { it.isNotEmpty() }
        }
    }
}

/** The suffix of an integrity record that is being replaced; a stored page file never carries it. */
private const val TEMP_SUFFIX = ".tmp"

/** How many rows a tail-only replay may skip before this store stops matching the replay row by row. */
private const val REPLAY_SEEK_LIMIT = 512

/** How many unreadable rows at the end of the store are left out before its pages are called damaged. */
private const val MAX_DROPPED_TAIL = 8

/** Id prefix of a prompt this app wrote into its own history; the Agent's own copy of it has no such row. */
private const val LOCAL_ID_PREFIX = "local-"

/** Bounded length of the human-readable reason a store reports for being incomplete. */
private const val MAX_NOTE = 512

/** Version of the integrity record this store writes; a record of another version is not read as this one. */
private const val FORMAT_VERSION = 1
