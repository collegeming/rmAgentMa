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

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

enum class AgentKind(val wireName: String, val command: String) {
    KIMI("kimi", "kimi"),
    OPENCODE("opencode", "opencode"),
    OMP("omp", "omp"),
    DSH("dsh", "dsh"),
    ZCODE("zcode", "zcode"),
    ;

    companion object {
        fun fromWire(value: String): AgentKind? = entries.firstOrNull { it.wireName == value }
    }
}

data class AgentSession(
    val hostId: Long,
    val agent: AgentKind,
    val sessionId: String,
    val cwd: String,
    val title: String = "",
    val updatedAt: Long = 0,
    val createdAt: Long = 0,
    val preview: String = "",
)

interface ExecChannel : Closeable {
    val stdout: InputStream
    val stdin: OutputStream
    val stderr: InputStream
}

/** Exec must use separate streams without a PTY. Closing a channel must unblock its reads. */
interface HostSession {
    val hostId: Long
    suspend fun exec(command: String): ExecChannel
}

data class AgentOption(val id: String, val label: String)

data class AgentEvent(
    val type: String,
    val text: String = "",
    val id: String = "",
    val title: String = "",
    val status: String = "",
    val options: List<AgentOption> = emptyList(),
    val raw: JsonObject? = null,
    val historyKey: Long = -1,
)

data class ScanResult(val sessions: List<AgentSession>, val errors: List<String>)

interface AgentDriver {
    val agent: AgentKind
    suspend fun listSessions(host: HostSession, limit: Int = 200): List<AgentSession>
}

interface AgentSessionHandle {
    /** Lossless bounded single-consumer stream with cancellable backpressure. */
    val events: Flow<AgentEvent>
    val sessionId: String
    suspend fun send(text: String)
    suspend fun cancel()
    suspend fun respondPermission(requestId: String, optionId: String)
    suspend fun respondQuestion(requestId: String, answer: String)
    suspend fun close()
}
