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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Events are historical facts only; no permission/question options or active request payloads are retained. */
data class TranscriptPage(val nextCursor: String? = null, val eventCount: Int = 0, val error: String? = null)

class RemoteTranscriptReader(private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO) {
    suspend fun readPage(
        host: HostSession,
        session: AgentSession,
        cursor: String? = null,
        limit: Int = 100,
        onEvent: suspend (AgentEvent) -> Unit,
    ): TranscriptPage {
        require(session.hostId == host.hostId) { "Transcript host must match session host" }
        require(session.agent == AgentKind.DSH || session.agent == AgentKind.ZCODE) { "Unsupported transcript agent" }
        require(session.cwd.startsWith('/') && session.sessionId.isNotBlank()) { "Invalid transcript identity" }
        requireRemoteArgument(session.cwd)
        requireRemoteArgument(session.sessionId)
        require(limit in 1..200) { "Limit must be between 1 and 200" }
        require(cursor == null || cursor.length <= 2048) { "Cursor exceeds limit" }
        val args = listOf(
            "--host-id", host.hostId.toString(),
            "--agent", session.agent.wireName,
            "--session-id", session.sessionId,
            "--cwd", session.cwd,
            "--limit", limit.toString(),
        ) + cursor?.let { listOf("--cursor", it) }.orEmpty()
        var page: TranscriptPage? = null
        var count = 0
        var failure: String? = null
        try {
            readRemoteJson(host, "remote/read_transcript.py", args, ioDispatcher, 45_000, limit + 3) { row ->
                when (row.string("record")) {
                    "event" -> {
                        check(page == null && failure == null && count < limit) { "Unexpected transcript event" }
                        check(
                            (row["hostId"] as? JsonPrimitive)?.longOrNull == host.hostId &&
                                row.string("agent") == session.agent.wireName &&
                                row.string("sessionId") == session.sessionId && row.string("cwd") == session.cwd,
                        ) { "Transcript identity mismatch" }
                        val type = row.string("type")
                        check(type in setOf("user", "content", "thinking", "tool", "tool_update")) { "Unsafe historical event" }
                        count++
                        onEvent(
                            AgentEvent(
                                type = type,
                                text = row.string("text"),
                                id = row.string("id"),
                                title = row.string("title"),
                                status = if (type.startsWith("tool")) "historical" else "",
                            ),
                        )
                    }

                    "page" -> {
                        check(page == null && failure == null) { "Duplicate transcript page" }
                        check((row["count"] as? JsonPrimitive)?.longOrNull == count.toLong()) { "Transcript count mismatch" }
                        val next = row.string("nextCursor").ifEmpty { null }
                        check(next == null || next.length <= 2048) { "Invalid transcript cursor" }
                        page = TranscriptPage(next, count)
                    }

                    "error" -> failure = failure ?: row.string("error").ifEmpty { "history_unreadable" }

                    else -> error("Invalid transcript record")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failure = "history_transport_failed"
        }
        return if (failure != null) {
            TranscriptPage(eventCount = count, error = failure)
        } else {
            page ?: TranscriptPage(eventCount = count, error = "history_incomplete")
        }
    }
}
