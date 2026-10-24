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

/**
 * Persists the unsent text of every conversation under `noBackupFilesDir`, so text typed into a surface that
 * is leaving the screen is still there when that conversation comes back. A draft is the user's own content:
 * it stays out of the system backup and out of the main database, and no other part of the conversation is
 * kept here.
 */
@Singleton
class AgentDraftStore @Inject constructor(
    @ApplicationContext context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private val baseFile = File(context.noBackupFilesDir, "agent-drafts-v1.json")
    private val file = AtomicFile(baseFile)
    private val mutex = Mutex()
    private var loaded = false
    private var damaged = false
    private var drafts = emptyMap<String, String>()

    /** The stored text of [conversation], or null when nothing was typed for it yet. */
    suspend fun read(conversation: String): String? = withContext(io) {
        if (!validKey(conversation)) return@withContext null
        mutex.withLock {
            load()
            drafts[conversation]
        }
    }

    suspend fun write(conversation: String, text: String) {
        if (!valid(conversation, text)) return
        withContext(io) {
            mutex.withLock {
                try {
                    load()
                } catch (_: IOException) {
                    // What is typed now is newer than the unreadable snapshot that this write preserves.
                }
                val updated = drafts.filterKeys { it != conversation } + (conversation to text)
                persist(updated)
                drafts = updated
            }
        }
    }

    private fun persist(updated: Map<String, String>) {
        val json = JSONArray()
        updated.forEach { (conversation, text) ->
            json.put(JSONObject().put("conversation", conversation).put("text", text))
        }
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES) throw IOException("Agent drafts exceed 16 MiB; previous drafts were retained")
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
                    if (input.channel.size() > MAX_BYTES) throw IOException("Agent drafts exceed 16 MiB")
                    input.bufferedReader().readText()
                }
            } catch (_: FileNotFoundException) {
                "[]"
            }
            val array = JSONArray(text)
            drafts = (0 until array.length()).mapNotNull { position ->
                val row = array.getJSONObject(position)
                val conversation = row.getString("conversation")
                val draft = row.getString("text")
                if (!valid(conversation, draft)) return@mapNotNull null
                conversation to draft
            }.distinctBy { it.first }.toMap()
        } catch (e: Exception) {
            drafts = emptyMap()
            damaged = true
            throw IOException("Agent drafts are unreadable; the next saved draft rebuilds the file. The damaged file will be preserved.", e)
        } finally {
            loaded = true
        }
    }

    private fun validKey(conversation: String): Boolean = conversation.isNotBlank() && conversation.length <= MAX_KEY_LENGTH && conversation.none { it.isISOControl() }

    private fun valid(conversation: String, text: String): Boolean = validKey(conversation) && text.length <= MAX_DRAFT_UNITS && text.toByteArray(Charsets.UTF_8).size <= MAX_DRAFT_BYTES

    private companion object {
        // A draft over the cap is refused, never truncated: a shortened draft would read as the user's own text,
        // and these two numbers are the renderer's own draft limits, which no request may exceed anyway.
        const val MAX_DRAFT_UNITS = 16384
        const val MAX_DRAFT_BYTES = 48 * 1024
        const val MAX_KEY_LENGTH = 200
        const val MAX_BYTES = 16 * 1024 * 1024
    }
}
