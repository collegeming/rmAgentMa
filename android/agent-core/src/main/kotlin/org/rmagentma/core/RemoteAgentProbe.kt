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
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.util.Base64

data class AgentAvailability(
    val hostId: Long,
    val kind: AgentKind,
    val executable: String,
    val available: Boolean,
    val error: String = "",
    val version: String = "",
    val capabilities: JsonObject? = null,
)

class RemoteAgentProbe(private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO) {
    suspend fun probe(host: HostSession, zcodeExecutable: String? = null): List<AgentAvailability> {
        zcodeExecutable?.let {
            require(it.startsWith('/')) { "Executable must be an absolute path" }
            requireRemoteArgument(it)
        }
        val rows = mutableMapOf<AgentKind, AgentAvailability>()
        var failure = "probe_missing_result"
        try {
            readRemoteJson(
                host,
                "remote/probe_agents.py",
                zcodeExecutable?.let { listOf("--zcode-executable", it) }.orEmpty(),
                ioDispatcher,
                timeoutMillis = 100_000,
                maxRows = 6,
            ) { row ->
                if (row.string("level") == "error") {
                    failure = row.string("error").ifEmpty { row.string("category") }
                } else {
                    val kind = AgentKind.fromWire(row.string("kind")) ?: error("Invalid probe kind")
                    check(kind !in rows) { "Duplicate probe result" }
                    val executable = row.string("executable")
                    check(executable.isEmpty() || executable.startsWith('/')) { "Invalid executable" }
                    val capabilities = row["capabilities"] as? JsonObject
                    val verified = if (kind == AgentKind.ZCODE) {
                        (capabilities?.get("zcodeProtocol") as? JsonPrimitive)?.booleanOrNull == true &&
                            (capabilities["startupReady"] as? JsonPrimitive)?.booleanOrNull == true
                    } else {
                        (capabilities?.get("acp") as? JsonPrimitive)?.booleanOrNull == true
                    }
                    val available = (row["available"] as? JsonPrimitive)?.booleanOrNull == true && verified
                    rows[kind] = AgentAvailability(
                        host.hostId,
                        kind,
                        executable,
                        available,
                        row.string("error"),
                        row.string("version"),
                        capabilities,
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failure = "probe_transport_failed"
        }
        return AgentKind.entries.map { kind ->
            rows[kind] ?: AgentAvailability(host.hostId, kind, "", false, failure)
        }
    }
}

internal fun requireRemoteArgument(value: String) {
    require(value.none { it.code < 32 || it.code == 127 }) { "Remote arguments cannot contain control characters" }
}

internal fun remotePythonCommand(script: String, arguments: List<String>): String {
    arguments.forEach(::requireRemoteArgument)
    val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_8))
    val args = arguments.joinToString(" ", transform = ShellCommands::quote)
    val body = "if ! command -v python3 >/dev/null 2>&1; then " +
        "printf '%s\\n' '{\"record\":\"error\",\"level\":\"error\",\"error\":\"python_missing\"}'; exit 127; fi; " +
        "if ! command -v base64 >/dev/null 2>&1; then " +
        "printf '%s\\n' '{\"record\":\"error\",\"level\":\"error\",\"error\":\"base64_missing\"}'; exit 127; fi; " +
        "printf '%s' '$encoded' | base64 -d | python3 - $args; " +
        "status=\$?; if [ \$status -ne 0 ]; then " +
        "printf '%s\\n' '{\"record\":\"error\",\"level\":\"error\",\"error\":\"remote_exit_failed\"}'; fi; exit \$status"
    return "/bin/sh -c ${ShellCommands.quote(body)}"
}

internal suspend fun readRemoteJson(
    host: HostSession,
    resource: String,
    arguments: List<String>,
    ioDispatcher: CoroutineDispatcher,
    timeoutMillis: Long,
    maxRows: Int,
    receive: suspend (JsonObject) -> Unit,
) = withContext(ioDispatcher) {
    val script = RemoteAgentProbe::class.java.classLoader.getResourceAsStream(resource)?.use {
        it.readBytes().toString(Charsets.UTF_8)
    } ?: error("Remote resource unavailable")
    withTimeout(timeoutMillis) {
        val channel = host.exec(remotePythonCommand(script, arguments))
        coroutineScope {
            val closer = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    runCatching { channel.close() }
                }
            }
            val stderr = launch {
                runInterruptible { drainStderr(channel.stderr) }
            }
            try {
                val reader = NdjsonReader(channel.stdout, 128 * 1024)
                var count = 0
                while (true) {
                    val line = runInterruptible { reader.readLine() } ?: break
                    if (line.isBlank()) continue
                    check(++count <= maxRows) { "Remote response row limit" }
                    val row = Json.parseToJsonElement(line) as? JsonObject ?: error("Invalid remote record")
                    receive(row)
                }
            } finally {
                runCatching { channel.close() }
                closer.cancel()
                stderr.cancel()
            }
        }
    }
}
