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
import org.json.JSONArray
import org.json.JSONObject
import org.rmagentma.core.AgentKind
import org.rmagentma.core.AgentSession
import java.io.File
import java.io.FileNotFoundException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AgentIndexStore @Inject constructor(
    @ApplicationContext context: Context,
    @org.connectbot.di.IoDispatcher private val io: CoroutineDispatcher,
) {
    private val baseFile = File(context.noBackupFilesDir, "agent-session-index-v1.json")
    private val file = AtomicFile(baseFile)
    private val mutex = Mutex()
    private var loaded = false
    private var damaged = false
    private var index = emptyList<AgentSession>()

    suspend fun read(): List<AgentSession> = withContext(io) {
        mutex.withLock {
            load()
            index
        }
    }

    suspend fun retainHosts(ids: Set<Long>): List<AgentSession> = update(persistDamaged = false) { previous -> previous.filter { it.hostId in ids } }

    suspend fun clear(): List<AgentSession> = update(recoverLoad = true) { emptyList() }

    suspend fun replace(hostId: Long, agent: AgentKind, sessions: List<AgentSession>): List<AgentSession> = update { previous ->
        previous.filterNot { it.hostId == hostId && it.agent == agent } +
            sessions.filter { it.hostId == hostId && it.agent == agent && valid(it) }
                .sortedByDescending { it.updatedAt }
                .distinctBy { it.sessionId }
                .take(200)
    }

    private suspend fun update(
        persistDamaged: Boolean = true,
        recoverLoad: Boolean = false,
        transform: (List<AgentSession>) -> List<AgentSession>,
    ): List<AgentSession> = withContext(io) {
        mutex.withLock {
            try {
                load()
            } catch (e: java.io.IOException) {
                if (!recoverLoad || !damaged) throw e
            }
            val updated = transform(index)
            if (damaged && !persistDamaged) return@withLock index
            if (!damaged && updated == index && baseFile.exists()) return@withLock index
            val json = JSONArray()
            updated.forEach { session ->
                json.put(
                    JSONObject()
                        .put("hostId", session.hostId)
                        .put("agent", session.agent.wireName)
                        .put("sessionId", session.sessionId)
                        .put("cwd", session.cwd)
                        .put("title", session.title)
                        .put("updatedAt", session.updatedAt)
                        .put("createdAt", session.createdAt)
                        .put("preview", session.preview),
                )
            }
            val bytes = json.toString().toByteArray(Charsets.UTF_8)
            if (bytes.size > MAX_BYTES) throw java.io.IOException("Agent index exceeds 16 MiB; previous cache was retained")
            if (damaged && baseFile.exists()) {
                val backup = File(baseFile.parentFile, baseFile.name + ".corrupt-" + java.util.UUID.randomUUID())
                baseFile.copyTo(backup)
            }
            val stream = file.startWrite()
            try {
                stream.write(bytes)
                file.finishWrite(stream)
            } catch (e: Exception) {
                file.failWrite(stream)
                throw e
            }
            index = updated
            damaged = false
            index
        }
    }

    private fun load() {
        if (loaded) return
        try {
            val text = try {
                file.openRead().use { input ->
                    if (input.channel.size() > MAX_BYTES) throw java.io.IOException("Agent index exceeds 16 MiB")
                    input.bufferedReader().readText()
                }
            } catch (_: FileNotFoundException) {
                "[]"
            }
            val array = JSONArray(text)
            index = (0 until array.length()).mapNotNull { position ->
                val row = array.getJSONObject(position)
                val agent = AgentKind.fromWire(row.getString("agent")) ?: return@mapNotNull null
                AgentSession(
                    hostId = row.getLong("hostId"),
                    agent = agent,
                    sessionId = row.getString("sessionId"),
                    cwd = row.getString("cwd"),
                    title = row.optString("title"),
                    updatedAt = row.optLong("updatedAt"),
                    createdAt = row.optLong("createdAt"),
                    preview = row.optString("preview"),
                ).also { require(valid(it)) { "Invalid cached session identity or text" } }
            }.groupBy { it.hostId to it.agent }.values.flatMap { rows ->
                rows.sortedByDescending { it.updatedAt }.distinctBy { it.sessionId }.take(200)
            }
        } catch (e: Exception) {
            index = emptyList()
            damaged = true
            throw java.io.IOException("Agent cache is unreadable; refresh to rebuild it. The damaged file will be preserved.", e)
        } finally {
            loaded = true
        }
    }

    private fun valid(session: AgentSession): Boolean {
        fun clean(value: String): Boolean = value.none { it.isISOControl() }
        fun readable(value: String): Boolean = value.none { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }
        return session.hostId > 0 && session.sessionId.isNotBlank() && clean(session.sessionId) &&
            session.cwd.startsWith('/') && clean(session.cwd) && readable(session.title) && readable(session.preview)
    }

    private companion object {
        const val MAX_BYTES = 16 * 1024 * 1024
    }
}
