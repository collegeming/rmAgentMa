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
    /**
     * The session list of a host, read by the reader its own target can run. [RemoteTarget.WindowsNative] takes
     * [windowsScanCommand] because the POSIX line is a `/bin/sh` invocation that target has no `/bin/sh` for;
     * every other target keeps the POSIX line unchanged.
     */
    suspend fun scan(
        host: HostSession,
        agents: Set<AgentKind> = DEFAULT_AGENTS,
        limit: Int = 200,
        target: RemoteTarget = RemoteTarget.Posix,
    ): ScanResult {
        require(limit in 1..1000) { "Limit must be between 1 and 1000" }
        if (agents.isEmpty()) return ScanResult(emptyList(), emptyList())
        val supported = agents - AgentKind.OMP
        val errors = errorSink()
        if (AgentKind.OMP in agents) errors += OMP_ERROR
        if (supported.isEmpty()) return ScanResult(emptyList(), errors)
        val script = withContext(ioDispatcher) {
            javaClass.classLoader.getResourceAsStream("remote/scan_sessions.py")?.use {
                it.readBytes().toString(Charsets.UTF_8)
            }
        } ?: return ScanResult(emptyList(), errors + "Remote scanner resource is unavailable")
        val windows = target is RemoteTarget.WindowsNative
        val sessions = mutableListOf<AgentSession>()
        var relayComplete = !windows
        try {
            withContext(ioDispatcher) {
                val channel = host.exec(
                    if (windows) windowsScanCommand(supported, limit) else ShellCommands.scanner(script, supported, limit),
                )
                coroutineScope {
                    if (windows) {
                        // The script travels over stdin: as one command-line argument its base64 would exceed the
                        // length a Windows command line accepts. It is written beside the read rather than before
                        // it, so a relay that stopped reading cannot hold the answer it already printed. A payload
                        // that could not be written closes stdin, which is what ends the relay's own read: it then
                        // answers with its own category instead of waiting for a script that will never arrive.
                        launch {
                            try {
                                channel.stdin.write(windowsScanPayload(script))
                                channel.stdin.flush()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                runCatching { channel.stdin.close() }
                            }
                        }
                    }
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
                        relayComplete = readScan(host.hostId, channel, errors, supported, limit, sessions, windows)
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
        // A Windows relay that never reached its own completion row parsed nothing: an empty list there would be
        // read as a host without sessions, which this scan did not establish.
        if (!relayComplete && errors.isEmpty()) {
            errors += "Remote scanner relay did not complete on the Windows host"
        }
        return ScanResult(sessions.distinctBy { it.agent to it.sessionId }, errors.distinct())
    }

    /**
     * One NDJSON answer. Answers whether the Windows relay reported its own completion; the POSIX path has no such
     * row and answers true, because the command it ran is the one whose exit status the channel carries.
     */
    private suspend fun readScan(
        hostId: Long,
        channel: ExecChannel,
        errors: MutableList<String>,
        supported: Set<AgentKind>,
        limit: Int,
        sessions: MutableList<AgentSession>,
        windows: Boolean,
    ): Boolean {
        var complete = !windows
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

                    "py_missing" -> "Remote scanner requires the Python launcher (py -3) on the Windows host"

                    "base64_missing" -> "Remote scanner requires base64 on the remote host"

                    "scanner_exit_failed" -> "Remote scanner exited unsuccessfully; cached sessions must be retained"

                    "scanner_payload_missing", "scanner_payload_failed" ->
                        "Remote scanner could not carry its script to the Windows host"

                    else -> "${agent?.wireName ?: "scanner"}: remote scan failed"
                }
                continue
            }
            // The Windows relay's own completion row; a session record never carries a level.
            if (row.string("level") == WINDOWS_SCAN_COMPLETE) {
                complete = true
                continue
            }
            val kind = AgentKind.fromWire(row.string("agent"))
            val id = row.string("sessionId")
            val cwd = row.string("cwd")
            // A session whose source named no directory, or named a relative one, is kept as it was read: it
            // exists on the host, and the app reports it as a directory-unknown record instead of deleting a
            // session it cannot place.
            if (kind !in supported || id.isBlank()) {
                errors += "Remote scanner returned an invalid session record"
                continue
            }
            if (sessions.count { it.agent == kind } >= limit) continue
            sessions += AgentSession(
                hostId,
                kind!!,
                id,
                cwd,
                row.string("title"),
                row.timestamp("updatedAt"),
                row.timestamp("createdAt"),
                row.string("preview"),
            )
        }
        return complete
    }

    private fun errorSink(): MutableList<String> = Collections.synchronizedList(
        object : ArrayList<String>() {
            override fun add(element: String): Boolean = if (size < MAX_ERRORS) super.add(element) else false
        },
    )

    companion object {
        val DEFAULT_AGENTS: Set<AgentKind> = setOf(AgentKind.KIMI, AgentKind.OPENCODE, AgentKind.DSH, AgentKind.ZCODE)
    }
}

internal fun JsonObject.string(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

internal fun JsonObject.timestamp(key: String): Long {
    val value = string(key)
    return value.toLongOrNull() ?: runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(0)
}

private const val MAX_ERRORS = 100
private const val OMP_ERROR = "omp: use ACP session/list; file format is not assumed"
