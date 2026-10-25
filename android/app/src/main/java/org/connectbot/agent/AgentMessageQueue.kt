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

import android.content.Context
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.connectbot.di.IoDispatcher
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** What the sender asks of the receiving conversation: something to read, or something to act on. */
enum class AgentMessageKind { NOTIFY, REQUEST }

/**
 * A message is delivered only once the receiving conversation took it, so a hand-off is never reported early.
 * A message the sender withdrew before its conversation could take it ends as [WITHDRAWN]: it is never handed
 * over, never counted as delivered, and stays readable as its own terminal state.
 */
enum class AgentMessageState { PENDING, DELIVERED, WITHDRAWN }

/** One message waiting for the conversation named by [hostId], [agent] and [sessionId]. */
data class AgentQueuedMessage(
    val id: String,
    val hostId: Long,
    val agent: String,
    val sessionId: String,
    val kind: AgentMessageKind,
    val text: String,
    val createdAtMillis: Long,
    val state: AgentMessageState,
)

/** Retention counts, so the cap that drops the oldest message is visible instead of reading as a lost send. */
data class AgentMessageQueueStats(
    val stored: Int,
    val pending: Int,
    val delivered: Int,
    val withdrawn: Int = 0,
    val dropped: Int,
)

/**
 * Persists the messages one conversation addressed to another, under `noBackupFilesDir`, and hands them to the
 * receiving conversation the next time it is opened. Nothing here delivers on its own: there is no background
 * service, and a session picks up its own queue and confirms each message it took. A message its sender withdrew
 * before that is never handed over and stays recorded as [AgentMessageState.WITHDRAWN].
 *
 * Only the message text and the tuple that addresses it are stored; no credential, key material or other session
 * content reaches this file, which is also why it stays out of the system backup.
 */
@Singleton
class AgentMessageQueue @Inject constructor(
    @ApplicationContext context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private val baseFile = File(context.noBackupFilesDir, "agent-message-queue-v1.json")
    private val file = AtomicFile(baseFile)
    private val mutex = Mutex()
    private var loaded = false
    private var damaged = false
    private var dropped = 0
    private var rows = emptyList<AgentQueuedMessage>()

    /**
     * Queues one message for the conversation identified by [hostId], [agent] and [sessionId], and returns its id,
     * or null when the target or the text is refused. [createdAtMillis] defaults to now; a caller replaying text it
     * already has passes the original time, so the receiving session still reads the real order. A queue that
     * cannot be written throws [IOException] and leaves the previous queue in place rather than losing it.
     */
    suspend fun enqueue(
        hostId: Long,
        agent: String,
        sessionId: String,
        kind: AgentMessageKind,
        text: String,
        createdAtMillis: Long = System.currentTimeMillis(),
    ): String? {
        if (!validTarget(hostId, agent, sessionId) || !validText(text) || createdAtMillis < 0) return null
        val queued = AgentQueuedMessage(
            id = UUID.randomUUID().toString(),
            hostId = hostId,
            agent = agent,
            sessionId = sessionId,
            kind = kind,
            text = text,
            createdAtMillis = createdAtMillis,
            state = AgentMessageState.PENDING,
        )
        withContext(io) {
            mutex.withLock {
                try {
                    load()
                } catch (_: IOException) {
                    // What is queued now is newer than the unreadable snapshot that this persist preserves.
                }
                // The queue is a window of the newest MAX_ROWS messages, and the overflow it dropped is counted
                // instead of vanishing; equal times keep their queue order, so one session's messages stay ordered.
                val appended = (rows + queued).sortedBy { it.createdAtMillis }
                val overflow = (appended.size - MAX_ROWS).coerceAtLeast(0)
                val updated = if (overflow == 0) appended else appended.drop(overflow)
                persist(updated, dropped + overflow)
                rows = updated
                dropped += overflow
            }
        }
        return queued.id
    }

    /** Every message still waiting for its conversation, oldest first. */
    suspend fun pending(): List<AgentQueuedMessage> = withContext(io) {
        mutex.withLock {
            load()
            rows.filter { it.state == AgentMessageState.PENDING }.sortedBy { it.createdAtMillis }
        }
    }

    /**
     * Every message this queue still holds, whatever its terminal state, oldest first. Reading it applies nothing:
     * a pending message is still handed over only when its conversation is next opened, and the rows that are done
     * are the record of what happened to them.
     */
    suspend fun held(): List<AgentQueuedMessage> = withContext(io) {
        mutex.withLock {
            load()
            rows.sortedBy { it.createdAtMillis }
        }
    }

    /** What the conversation named by [hostId], [agent] and [sessionId] has waiting for it, oldest first. */
    suspend fun pendingFor(hostId: Long, agent: String, sessionId: String): List<AgentQueuedMessage> {
        if (!validTarget(hostId, agent, sessionId)) return emptyList()
        return pending().filter { it.hostId == hostId && it.agent == agent && it.sessionId == sessionId }
    }

    /**
     * Marks the message [id] as taken by its conversation, and reports false when it is unknown, already
     * delivered or withdrawn, so a retried hand-off cannot turn one message into two and a message the sender
     * took back is never reported as handed over.
     */
    suspend fun delivered(id: String): Boolean = withContext(io) {
        if (!validId(id)) return@withContext false
        mutex.withLock {
            try {
                load()
            } catch (_: IOException) {
                // An unreadable queue names no message, and rewriting it would drop the evidence.
                return@withLock false
            }
            val row = rows.firstOrNull { it.id == id } ?: return@withLock false
            if (row.state != AgentMessageState.PENDING) return@withLock false
            val updated = rows.map { if (it.id == id) it.copy(state = AgentMessageState.DELIVERED) else it }
            persist(updated, dropped)
            rows = updated
            true
        }
    }

    /**
     * Withdraws the message [id]: its state becomes [AgentMessageState.WITHDRAWN], so it is never handed to its
     * conversation and never counted as delivered. Only a message still waiting can be withdrawn — one the
     * conversation already took belongs to that conversation and [delivered] is the only entry that claims a
     * hand-off — so anything else reports false. A withdrawal is not counted as a drop, which stays the cap's own
     * count.
     */
    suspend fun withdraw(id: String): Boolean = withContext(io) {
        if (!validId(id)) return@withContext false
        mutex.withLock {
            try {
                load()
            } catch (_: IOException) {
                // An unreadable queue names no message, and rewriting it would drop the evidence.
                return@withLock false
            }
            val row = rows.firstOrNull { it.id == id } ?: return@withLock false
            if (row.state != AgentMessageState.PENDING) return@withLock false
            val updated = rows.map { if (it.id == id) it.copy(state = AgentMessageState.WITHDRAWN) else it }
            persist(updated, dropped)
            rows = updated
            true
        }
    }

    /** Drops everything queued for a host, delivered or not: a removed host has no conversation left to tell. */
    suspend fun forget(hostId: Long) {
        if (hostId <= 0) return
        withContext(io) {
            mutex.withLock {
                try {
                    load()
                } catch (_: IOException) {
                    // An unreadable snapshot remembers nothing for this host, and rewriting it would drop the evidence.
                    return@withLock
                }
                val kept = rows.filterNot { it.hostId == hostId }
                if (kept.size == rows.size) return@withLock
                persist(kept, dropped)
                rows = kept
            }
        }
    }

    /** What the file holds now, including the [AgentMessageQueueStats.dropped] count no other call could show. */
    suspend fun stats(): AgentMessageQueueStats = withContext(io) {
        mutex.withLock {
            load()
            AgentMessageQueueStats(
                stored = rows.size,
                pending = rows.count { it.state == AgentMessageState.PENDING },
                delivered = rows.count { it.state == AgentMessageState.DELIVERED },
                withdrawn = rows.count { it.state == AgentMessageState.WITHDRAWN },
                dropped = dropped,
            )
        }
    }

    private fun persist(updated: List<AgentQueuedMessage>, droppedCount: Int) {
        val messages = JSONArray()
        updated.forEach { row ->
            messages.put(
                JSONObject()
                    .put("id", row.id)
                    .put("hostId", row.hostId)
                    .put("agent", row.agent)
                    .put("sessionId", row.sessionId)
                    .put("kind", row.kind.name)
                    .put("text", row.text)
                    .put("createdAtMillis", row.createdAtMillis)
                    .put("state", row.state.name),
            )
        }
        val json = JSONObject().put("dropped", droppedCount).put("messages", messages)
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES) throw IOException("Agent message queue exceeds 16 MiB; previous queue was retained")
        if (damaged && baseFile.exists()) {
            baseFile.copyTo(File(baseFile.parentFile, baseFile.name + ".corrupt-" + UUID.randomUUID()))
        }
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
        damaged = false
    }

    private fun load() {
        if (loaded) return
        try {
            val text = try {
                file.openRead().use { input ->
                    if (input.channel.size() > MAX_BYTES) throw IOException("Agent message queue exceeds 16 MiB")
                    input.bufferedReader().readText()
                }
            } catch (_: FileNotFoundException) {
                "{}"
            }
            val root = JSONObject(text)
            val array = root.optJSONArray("messages") ?: JSONArray()
            rows = (0 until array.length()).mapNotNull { position ->
                val row = array.getJSONObject(position)
                val kind = AgentMessageKind.entries.firstOrNull { it.name == row.getString("kind") } ?: return@mapNotNull null
                val state = AgentMessageState.entries.firstOrNull { it.name == row.getString("state") } ?: return@mapNotNull null
                val hostId = row.getLong("hostId")
                val agent = row.getString("agent")
                val sessionId = row.getString("sessionId")
                val queued = row.getString("text")
                AgentQueuedMessage(
                    id = row.getString("id"),
                    hostId = hostId,
                    agent = agent,
                    sessionId = sessionId,
                    kind = kind,
                    text = queued,
                    createdAtMillis = row.getLong("createdAtMillis"),
                    state = state,
                ).also {
                    require(
                        validTarget(hostId, agent, sessionId) && validText(queued) && validId(it.id) && it.createdAtMillis >= 0,
                    ) { "Invalid cached queue row" }
                }
            }.distinctBy { it.id }
            dropped = root.optInt("dropped", 0).coerceAtLeast(0)
        } catch (e: Exception) {
            rows = emptyList()
            dropped = 0
            damaged = true
            throw IOException("Agent message queue is unreadable; the next queued message rebuilds it. The damaged file will be preserved.", e)
        } finally {
            loaded = true
        }
    }

    private fun validTarget(hostId: Long, agent: String, sessionId: String): Boolean =
        hostId > 0 && validName(agent, MAX_AGENT_LENGTH) && validName(sessionId, MAX_SESSION_ID_LENGTH)

    private fun validName(value: String, maxLength: Int): Boolean = value.isNotBlank() && value.length <= maxLength && value.none { it.isISOControl() }

    private fun validId(id: String): Boolean = id.isNotBlank() && id.length <= MAX_ID_LENGTH && id.none { it.isISOControl() }

    /** Newlines and tabs inside the text are the sender's own content; only the length and emptiness are checked. */
    private fun validText(text: String): Boolean =
        text.isNotBlank() && text.length <= MAX_TEXT_UNITS && text.toByteArray(Charsets.UTF_8).size <= MAX_TEXT_BYTES

    private companion object {
        // Text over the cap is refused, never truncated: a shortened message would read as the sender's own words.
        const val MAX_TEXT_UNITS = 16384
        const val MAX_TEXT_BYTES = 48 * 1024
        // The cap keeps the newest messages by send time and counts what it dropped, because a queue that grew
        // without bound would both hide the loss and eventually trip the whole-file guard below.
        const val MAX_ROWS = 100
        const val MAX_ID_LENGTH = 64
        const val MAX_AGENT_LENGTH = 64
        const val MAX_SESSION_ID_LENGTH = 200
        const val MAX_BYTES = 16 * 1024 * 1024
    }
}
