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

enum class AgentAddressSource { CONNECTED, REDISCOVERED }

data class HostAddressRecord(
    val address: String,
    val port: Int,
    val verifiedAtMillis: Long,
    val source: AgentAddressSource,
)

data class HostAddressChange(
    val hostId: Long,
    val fromAddress: String,
    val toAddress: String,
)

@Singleton
class AgentHostAddressStore @Inject constructor(
    @ApplicationContext context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private val baseFile = File(context.noBackupFilesDir, "agent-host-addresses-v1.json")
    private val file = AtomicFile(baseFile)
    private val mutex = Mutex()
    private var loaded = false
    private var rows = emptyList<Pair<Long, HostAddressRecord>>()

    suspend fun record(hostId: Long, address: String, port: Int, source: AgentAddressSource) = withContext(io) {
        if (!valid(hostId, address, port)) return@withContext
        mutex.withLock {
            try {
                load()
            } catch (_: IOException) {
                // The verified address below replaces the unreadable snapshot.
            }
            val previous = rows.filter { it.first == hostId }.map { it.second }
            val newest = previous.maxOfOrNull { it.verifiedAtMillis } ?: 0L
            // A fresh record must outrank same-millisecond predecessors, or the per-host cap drops it as the oldest.
            val fresh = HostAddressRecord(address, port, maxOf(System.currentTimeMillis(), newest + 1L), source)
            val kept = (previous.filterNot { it.address == address && it.port == port } + fresh)
                .sortedByDescending { it.verifiedAtMillis }
                .take(MAX_PER_HOST)
            val updated = rows.filterNot { it.first == hostId } + kept.map { hostId to it }
            persist(updated)
            rows = updated
        }
    }

    suspend fun candidates(hostId: Long, port: Int, limit: Int = 8): List<HostAddressRecord> = withContext(io) {
        mutex.withLock {
            load()
            hostRecords(hostId).filter { it.port == port }.take(limit.coerceAtLeast(0))
        }
    }

    suspend fun latest(hostId: Long): HostAddressRecord? = withContext(io) {
        mutex.withLock {
            load()
            hostRecords(hostId).maxByOrNull { it.verifiedAtMillis }
        }
    }

    private fun hostRecords(hostId: Long): List<HostAddressRecord> = rows.filter { it.first == hostId }
        .map { it.second }
        .sortedByDescending { it.verifiedAtMillis }
        .distinctBy { it.address to it.port }

    private fun persist(all: List<Pair<Long, HostAddressRecord>>) {
        val json = JSONArray()
        all.forEach { (hostId, record) ->
            json.put(
                JSONObject()
                    .put("hostId", hostId)
                    .put("address", record.address)
                    .put("port", record.port)
                    .put("verifiedAtMillis", record.verifiedAtMillis)
                    .put("source", record.source.name),
            )
        }
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES) throw IOException("Agent address history exceeds 16 MiB; previous cache was retained")
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
    }

    private fun load() {
        if (loaded) return
        try {
            val text = try {
                file.openRead().use { input ->
                    if (input.channel.size() > MAX_BYTES) throw IOException("Agent address history exceeds 16 MiB")
                    input.bufferedReader().readText()
                }
            } catch (_: FileNotFoundException) {
                "[]"
            }
            val array = JSONArray(text)
            val parsed = (0 until array.length()).mapNotNull { position ->
                val row = array.getJSONObject(position)
                val source = AgentAddressSource.entries.firstOrNull { it.name == row.getString("source") } ?: return@mapNotNull null
                val hostId = row.getLong("hostId")
                val record = HostAddressRecord(
                    address = row.getString("address"),
                    port = row.getInt("port"),
                    verifiedAtMillis = row.getLong("verifiedAtMillis"),
                    source = source,
                ).also { require(valid(hostId, it.address, it.port)) { "Invalid cached address identity" } }
                hostId to record
            }
            rows = parsed.groupBy({ it.first }, { it.second }).flatMap { (hostId, hostRows) ->
                hostRows.sortedByDescending { it.verifiedAtMillis }.distinctBy { it.address to it.port }.take(MAX_PER_HOST).map { hostId to it }
            }
        } catch (e: Exception) {
            rows = emptyList()
            throw unreadableHistory(e)
        } finally {
            loaded = true
        }
    }

    /** Keeps the unreadable snapshot for inspection; a failed backup must surface instead of claiming preservation. */
    private fun unreadableHistory(cause: Exception): IOException {
        if (!baseFile.exists()) {
            return IOException("Agent address history is unreadable; the next verified address rebuilds it", cause)
        }
        return try {
            baseFile.copyTo(File(baseFile.parentFile, baseFile.name + ".corrupt-" + UUID.randomUUID()))
            IOException("Agent address history is unreadable; the next verified address rebuilds it. The damaged file was preserved.", cause)
        } catch (backup: IOException) {
            IOException("Agent address history is unreadable and its damaged file could not be preserved", backup)
                .also { it.addSuppressed(cause) }
        }
    }

    private fun valid(hostId: Long, address: String, port: Int): Boolean = hostId > 0 && port in 1..65535 && address.length in 1..MAX_ADDRESS_LENGTH &&
        address.none { it.isISOControl() || it.isWhitespace() }

    private companion object {
        const val MAX_BYTES = 16 * 1024 * 1024
        const val MAX_PER_HOST = 16
        const val MAX_ADDRESS_LENGTH = 255
    }
}
