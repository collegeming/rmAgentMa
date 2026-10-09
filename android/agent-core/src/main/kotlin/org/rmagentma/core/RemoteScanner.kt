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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.nio.charset.CharacterCodingException
import java.time.Instant
import java.util.Collections

class RemoteScanner(private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO) {
    suspend fun scan(
        host: HostSession,
        agents: Set<AgentKind> = DEFAULT_AGENTS,
        limit: Int = 200,
    ): ScanResult {
        require(limit in 1..1000) { "Limit must be between 1 and 1000" }
        if (agents.isEmpty()) return ScanResult(emptyList(), emptyList())
        val supported = agents - AgentKind.OMP
        val errors = Collections.synchronizedList(
            object : ArrayList<String>() {
                override fun add(element: String): Boolean = if (size < 100) super.add(element) else false
            },
        )
        if (AgentKind.OMP in agents) errors += "omp: use ACP session/list; file format is not assumed"
        if (supported.isEmpty()) return ScanResult(emptyList(), errors)
        val script = withContext(ioDispatcher) {
            javaClass.classLoader.getResourceAsStream("remote/scan_sessions.py")?.use {
                it.readBytes().toString(Charsets.UTF_8)
            }
        } ?: return ScanResult(emptyList(), errors + "Remote scanner resource is unavailable")
        val sessions = mutableListOf<AgentSession>()
        try {
            withContext(ioDispatcher) {
                val channel = host.exec(ShellCommands.scanner(script, supported, limit))
                coroutineScope {
                    val closer = launch(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            awaitCancellation()
                        } finally {
                            runCatching { channel.close() }
                        }
                    }
                    val shuttingDown = java.util.concurrent.atomic.AtomicBoolean(false)
                    val stderr = launch {
                        try {
                            runInterruptible { drainStderr(channel.stderr) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            if (!shuttingDown.get()) errors += "Remote scanner stderr stream closed unexpectedly"
                        }
                    }
                    try {
                        val reader = NdjsonReader(channel.stdout)
                        while (true) {
                            val line = try {
                                runInterruptible { reader.readLine() }
                            } catch (_: CharacterCodingException) {
                                errors += "Remote scanner returned invalid UTF-8"
                                continue
                            } ?: break
                            if (line.isBlank()) continue
                            val row = try {
                                Json.parseToJsonElement(line) as? JsonObject
                            } catch (_: Exception) {
                                null
                            }
                            if (row == null) {
                                errors += "Remote scanner returned malformed JSON"
                                continue
                            }
                            if (row.string("level") == "error") {
                                val agent = AgentKind.fromWire(row.string("agent"))
                                errors += when (row.string("category")) {
                                    "python_missing" -> "Remote scanner requires python3 on the remote host"
                                    "base64_missing" -> "Remote scanner requires base64 on the remote host"
                                    "scanner_exit_failed" -> "Remote scanner exited unsuccessfully; cached sessions must be retained"
                                    else -> "${agent?.wireName ?: "scanner"}: remote scan failed"
                                }
                                continue
                            }
                            val kind = AgentKind.fromWire(row.string("agent"))
                            val id = row.string("sessionId")
                            val cwd = row.string("cwd")
                            if (kind !in supported || id.isBlank() || !cwd.startsWith('/')) {
                                errors += "Remote scanner returned an invalid session record"
                                continue
                            }
                            if (sessions.count { it.agent == kind } >= limit) continue
                            sessions += AgentSession(
                                host.hostId,
                                kind!!,
                                id,
                                cwd,
                                row.string("title"),
                                row.timestamp("updatedAt"),
                                row.timestamp("createdAt"),
                                row.string("preview"),
                            )
                        }
                    } finally {
                        shuttingDown.set(true)
                        stderr.cancel()
                        runCatching { channel.close() }
                        closer.cancel()
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errors += "Remote scanner transport failed: ${e.javaClass.simpleName}"
        }
        return ScanResult(sessions.distinctBy { it.agent to it.sessionId }, errors.distinct())
    }

    companion object {
        val DEFAULT_AGENTS: Set<AgentKind> = setOf(AgentKind.KIMI, AgentKind.OPENCODE, AgentKind.DSH, AgentKind.ZCODE)
    }
}

internal fun JsonObject.string(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

internal fun JsonObject.timestamp(key: String): Long {
    val value = string(key)
    return value.toLongOrNull() ?: runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(0)
}
