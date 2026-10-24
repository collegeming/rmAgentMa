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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.connectbot.di.IoDispatcher
import org.json.JSONArray
import org.json.JSONObject
import org.rmagentma.core.AgentKind
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** One installation an earlier probe found and verified on a host, valid only for the target it was found on. */
data class AgentDetection(
    val hostId: Long,
    val target: String,
    val kind: AgentKind,
    val executable: String,
    val version: String,
    val capabilities: JsonObject?,
    val updatedAtMillis: Long,
)

/**
 * Persists the absolute executable, version and capabilities each host target was last found to hold, so the
 * path a probe detected configures the connection without the user entering one and without a hardcoded default.
 *
 * Only a completed detection is stored: the record is written from probe rows that named an executable, and a
 * host whose detection changed (a new target, an installation that is gone) is rewritten as a whole.
 */
@Singleton
class AgentDetectionStore @Inject constructor(
    @ApplicationContext context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private val baseFile = File(context.noBackupFilesDir, "agent-detections-v1.json")
    private val file = AtomicFile(baseFile)
    private val mutex = Mutex()
    private var loaded = false
    private var damaged = false
    private var rows = emptyList<AgentDetection>()

    suspend fun all(): List<AgentDetection> = withContext(io) {
        mutex.withLock {
            load()
            rows
        }
    }

    /** Replaces every record of one host; detections not passed here are no longer known to hold a path. */
    suspend fun replace(hostId: Long, detections: List<AgentDetection>) {
        if (hostId <= 0) return
        val accepted = detections.filter { it.hostId == hostId && valid(it) }
        withContext(io) {
            mutex.withLock {
                try {
                    load()
                } catch (_: IOException) {
                    // What is detected now is newer than the unreadable snapshot this write replaces.
                }
                val updated = rows.filterNot { it.hostId == hostId } + accepted
                persist(updated)
                rows = updated
            }
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

    private fun persist(updated: List<AgentDetection>) {
        val json = JSONArray()
        updated.forEach { row ->
            json.put(
                JSONObject()
                    .put("hostId", row.hostId)
                    .put("target", row.target)
                    .put("kind", row.kind.wireName)
                    .put("executable", row.executable)
                    .put("version", row.version)
                    .put("capabilities", row.capabilities?.toString() ?: JSONObject.NULL)
                    .put("updatedAtMillis", row.updatedAtMillis),
            )
        }
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES) throw IOException("Agent detections exceed 1 MiB; the previous cache was retained")
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
                    if (input.channel.size() > MAX_BYTES) throw IOException("Agent detections exceed 1 MiB")
                    input.bufferedReader().readText()
                }
            } catch (_: FileNotFoundException) {
                "[]"
            }
            val array = JSONArray(text)
            rows = (0 until array.length()).mapNotNull { position ->
                val row = array.getJSONObject(position)
                val kind = AgentKind.fromWire(row.getString("kind")) ?: return@mapNotNull null
                AgentDetection(
                    hostId = row.getLong("hostId"),
                    target = row.getString("target"),
                    kind = kind,
                    executable = row.getString("executable"),
                    version = row.optString("version", "").take(MAX_VERSION_LENGTH),
                    capabilities = row.optString("capabilities", "").takeIf { it.isNotEmpty() }
                        ?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() },
                    updatedAtMillis = row.optLong("updatedAtMillis", 0L),
                ).also { require(valid(it)) { "Invalid cached Agent detection" } }
            }.sortedByDescending { it.updatedAtMillis }.distinctBy { Triple(it.hostId, it.target, it.kind) }
        } catch (e: Exception) {
            rows = emptyList()
            damaged = true
            throw IOException("Agent detections are unreadable; the next detection rebuilds them. The damaged file will be preserved.", e)
        } finally {
            loaded = true
        }
    }

    private fun valid(detection: AgentDetection): Boolean = detection.hostId > 0 &&
        detection.target.isNotBlank() &&
        detection.target.length <= MAX_TARGET_LENGTH &&
        detection.target.none { it.isISOControl() } &&
        detection.executable.startsWith('/') &&
        detection.executable.length <= MAX_EXECUTABLE_LENGTH &&
        detection.executable.none { it.code < 32 || it.code == 127 } &&
        detection.updatedAtMillis >= 0

    private companion object {
        const val MAX_BYTES = 1024 * 1024
        const val MAX_TARGET_LENGTH = 128
        const val MAX_EXECUTABLE_LENGTH = 4096
        const val MAX_VERSION_LENGTH = 1024
    }
}
