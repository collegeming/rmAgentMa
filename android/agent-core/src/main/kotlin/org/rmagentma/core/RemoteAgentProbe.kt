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
import kotlinx.coroutines.TimeoutCancellationException
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
    suspend fun probe(
        host: HostSession,
        zcodeExecutable: String? = null,
        target: RemoteTarget = RemoteTarget.Posix,
    ): List<AgentAvailability> {
        // A Windows target is entered with the Windows lookup instead of the POSIX probe script. Every target this
        // app knows is supported, so the guard below is reached only by a target with no entry in the app at all —
        // none exists today, and a test's own target is the only way to it: that target holds no installation to
        // report, that fact is its verdict rather than a connection failure, and no session opens from this answer.
        if (target is RemoteTarget.WindowsNative) return windowsProbe(host)
        if (!target.supported) {
            return AgentKind.entries.map { AgentAvailability(host.hostId, it, "", false, WINDOWS_NATIVE_UNSUPPORTED) }
        }
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

    /**
     * The installations a Windows target holds, read with the Windows lookup itself (`where`, printed for one
     * Agent per line). Only a path Windows really resolved is reported, and it is reported as the whole detection:
     * nothing here claims a version or a protocol capability, because nothing on this path ran the Agent.
     *
     * An answer that could not be read at all — no PowerShell on the host, or a line this app cannot parse — is
     * the lookup's own verdict, reported as `windows_native_unsupported` rather than as a missing installation; an
     * Agent the lookup really did not find is reported as exactly that, and a lookup that never reached the host
     * keeps the probe's own transport code instead of claiming either.
     */
    private suspend fun windowsProbe(host: HostSession): List<AgentAvailability> = withContext(ioDispatcher) {
        val answers = try {
            withTimeout(WINDOWS_PROBE_TIMEOUT_MILLIS) { readWindowsLookup(host) }
        } catch (_: TimeoutCancellationException) {
            // Nothing answered in time, so no installation is claimed and the host keeps what it detected before.
            return@withContext transportFailure(host)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The lookup never reached the host: that is a connection failure rather than a missing installation.
            return@withContext transportFailure(host)
        }
        AgentKind.entries.map { kind ->
            val executable = answers?.get(kind)
            when {
                answers == null -> AgentAvailability(host.hostId, kind, "", false, WINDOWS_NATIVE_UNSUPPORTED)
                executable == null -> AgentAvailability(host.hostId, kind, "", false, "executable_missing")
                else -> AgentAvailability(host.hostId, kind, executable, true)
            }
        }
    }

    /** Every Agent on a host this probe never reached, reported with the probe's own transport code. */
    private fun transportFailure(host: HostSession): List<AgentAvailability> =
        AgentKind.entries.map { AgentAvailability(host.hostId, it, "", false, "probe_transport_failed") }

    /** One bounded Windows lookup run: every line is one answer, and null means no answer could be read at all. */
    private suspend fun readWindowsLookup(host: HostSession): Map<AgentKind, String?>? {
        val channel = host.exec(windowsLookupCommand(AgentKind.entries))
        val answers = mutableMapOf<AgentKind, String?>()
        var readable = true
        try {
            val reader = NdjsonReader(channel.stdout, WINDOWS_LOOKUP_MAX_BYTES)
            while (true) {
                val line = runInterruptible { reader.readLine() } ?: break
                if (line.isBlank()) continue
                if (!record(line, answers)) {
                    readable = false
                    break
                }
            }
        } finally {
            runCatching { channel.close() }
        }
        return answers.takeIf { readable && it.isNotEmpty() }
    }

    /** Reads one lookup answer into [answers], and answers false for a line this app cannot use. */
    private fun record(line: String, answers: MutableMap<AgentKind, String?>): Boolean {
        val parts = line.split(" ", limit = 3)
        if (parts[0] == WINDOWS_LOOKUP_UNAVAILABLE) return false
        val kind = parts.getOrNull(1)?.let { name -> AgentKind.entries.firstOrNull { it.command == name } } ?: return false
        return when (parts[0]) {
            WINDOWS_LOOKUP_MISSING -> {
                answers[kind] = null
                true
            }

            // A path the app cannot address as a Windows path is not a path it may launch.
            WINDOWS_LOOKUP_FOUND -> {
                val path = parts.getOrNull(2).orEmpty().trim().takeIf(::isWindowsAbsolutePath) ?: return false
                answers[kind] = path
                true
            }

            else -> false
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

/** One Windows lookup answers within this bound, so a host that never replies cannot hold a probe open. */
private const val WINDOWS_PROBE_TIMEOUT_MILLIS: Long = 30_000

/** One lookup prints one short line per Agent; anything past this is not the answer this app asked for. */
private const val WINDOWS_LOOKUP_MAX_BYTES = 16 * 1024
