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

object ShellCommands {
    fun quote(value: String): String {
        require('\u0000' !in value) { "Shell arguments cannot contain NUL" }
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }

    fun start(agent: AgentKind, cwd: String, sessionId: String? = null): String {
        require(cwd.startsWith('/')) { "Working directory must be absolute" }
        requireTerminalArgument(cwd)
        require(sessionId == null || sessionId.isNotBlank()) { "Session ID cannot be blank" }
        sessionId?.let(::requireTerminalArgument)
        val invocation = when (agent) {
            AgentKind.DSH -> "dsh tui"
            else -> agent.command
        }
        val flag = when (agent) {
            AgentKind.KIMI, AgentKind.OPENCODE -> "--session"
            else -> "--resume"
        }
        return "cd -- ${quote(cwd)} && exec $invocation" +
            (sessionId?.let { " $flag ${quote(it)}" } ?: "")
    }

    fun acp(agent: AgentKind, cwd: String): String {
        require(agent in setOf(AgentKind.KIMI, AgentKind.OPENCODE, AgentKind.OMP)) { "Agent does not support ACP" }
        require(cwd.startsWith('/')) { "Working directory must be absolute" }
        requireTerminalArgument(cwd)
        return "cd -- ${quote(cwd)} && exec ${agent.command} acp"
    }

    private fun requireTerminalArgument(value: String) {
        require(value.none { it.code < 32 || it.code == 127 }) { "Terminal arguments cannot contain ASCII control characters" }
    }

    internal fun scanner(script: String, agents: Set<AgentKind>, limit: Int): String {
        require(limit in 1..1000) { "Limit must be between 1 and 1000" }
        var delimiter = "RMAGENTMA_SCAN"
        val lines = script.lineSequence().toSet()
        while (delimiter in lines) delimiter += "_"
        val names = agents.sortedBy { it.ordinal }.joinToString(" ") { quote(it.wireName) }
        return "if ! command -v python3 >/dev/null 2>&1; then\n" +
            "printf '%s\\n' '{\"level\":\"error\",\"category\":\"python_missing\"}'\nexit 127\nfi\n" +
            "if python3 - --agent $names --limit $limit <<'$delimiter'\n" +
            script.trimEnd('\n') + "\n$delimiter\n" +
            "then\nexit 0\nelse\nrmagentma_status=\$?\n" +
            "printf '%s\\n' '{\"level\":\"error\",\"category\":\"scanner_exit_failed\"}'\n" +
            "exit \"\$rmagentma_status\"\nfi\n"
    }
}

class ShellDriver(
    override val agent: AgentKind,
    private val scanner: RemoteScanner = RemoteScanner(),
) : AgentDriver {
    init {
        require(agent == AgentKind.DSH || agent == AgentKind.ZCODE) { "Use AcpDriver for this agent" }
    }

    override suspend fun listSessions(host: HostSession, limit: Int): List<AgentSession> {
        val result = scanner.scan(host, setOf(agent), limit)
        if (result.errors.isNotEmpty()) throw ScanException(result)
        return result.sessions
    }

    fun terminalCommand(cwd: String, sessionId: String? = null): String = ShellCommands.start(agent, cwd, sessionId)
}

class ScanException(val result: ScanResult) : Exception(result.errors.joinToString("; "))
