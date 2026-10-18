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

enum class AgentLinkState { CONNECTED, DISCONNECTED, RETRYING, NEEDS_USER }

data class AgentLinkRecord(
    val hostId: Long,
    val agent: String?,
    val state: AgentLinkState,
    val attempts: Int,
    val updatedAtMillis: Long,
)

/** Persists link state so a process killed mid-connection reports a real state instead of a stale CONNECTED. */
@Singleton
class AgentLinkStateStore @Inject constructor(
    @ApplicationContext context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private val baseFile = File(context.noBackupFilesDir, "agent-link-states-v1.json")
    private val file = AtomicFile(baseFile)
    private val mutex = Mutex()
    private var loaded = false
    private var damaged = false
    private var rows = emptyList<AgentLinkRecord>()

    suspend fun record(record: AgentLinkRecord) {
        if (!valid(record.hostId, record.agent, record.attempts)) return
        withContext(io) {
            mutex.withLock {
                try {
                    load()
                } catch (_: IOException) {
                    // What is recorded now is newer than the unreadable snapshot that this persist preserves.
                }
                val updated = rows.filterNot { it.hostId == record.hostId && it.agent == record.agent } + record
                persist(updated)
                rows = updated
            }
        }
    }

    suspend fun all(): List<AgentLinkRecord> = withContext(io) {
        mutex.withLock {
            load()
            rows
        }
    }

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
                persist(kept)
                rows = kept
            }
        }
    }

    private fun persist(updated: List<AgentLinkRecord>) {
        val json = JSONArray()
        updated.forEach { row ->
            json.put(
                JSONObject()
                    .put("hostId", row.hostId)
                    .put("agent", row.agent ?: JSONObject.NULL)
                    .put("state", row.state.name)
                    .put("attempts", row.attempts)
                    .put("updatedAtMillis", row.updatedAtMillis),
            )
        }
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES) throw IOException("Agent link state exceeds 16 MiB; previous cache was retained")
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
                    if (input.channel.size() > MAX_BYTES) throw IOException("Agent link state exceeds 16 MiB")
                    input.bufferedReader().readText()
                }
            } catch (_: FileNotFoundException) {
                "[]"
            }
            val array = JSONArray(text)
            rows = (0 until array.length()).mapNotNull { position ->
                val row = array.getJSONObject(position)
                val state = AgentLinkState.entries.firstOrNull { it.name == row.getString("state") } ?: return@mapNotNull null
                val hostId = row.getLong("hostId")
                val agent = if (row.has("agent") && !row.isNull("agent")) row.getString("agent") else null
                AgentLinkRecord(
                    hostId = hostId,
                    agent = agent,
                    state = state,
                    attempts = row.getInt("attempts"),
                    updatedAtMillis = row.getLong("updatedAtMillis"),
                ).also { require(valid(hostId, agent, it.attempts)) { "Invalid cached link identity" } }
            }.sortedByDescending { it.updatedAtMillis }.distinctBy { it.hostId to it.agent }
        } catch (e: Exception) {
            rows = emptyList()
            damaged = true
            throw IOException("Agent link state is unreadable; the next recorded state rebuilds it. The damaged file will be preserved.", e)
        } finally {
            loaded = true
        }
    }

    private fun valid(hostId: Long, agent: String?, attempts: Int): Boolean {
        if (hostId <= 0 || attempts < 0) return false
        if (agent == null) return true
        return agent.isNotBlank() && agent.length <= MAX_AGENT_LENGTH && agent.none { it.isISOControl() }
    }

    private companion object {
        const val MAX_BYTES = 16 * 1024 * 1024
        const val MAX_AGENT_LENGTH = 64
    }
}
