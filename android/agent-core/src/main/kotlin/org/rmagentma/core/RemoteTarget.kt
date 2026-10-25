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

import java.util.Base64

/** Operating system values a host row can carry. */
const val HOST_OS_LINUX = "linux"
const val HOST_OS_WINDOWS = "windows"

/** Agent target of a host row: the host itself, or one distribution of a Windows host. */
const val AGENT_TARGET_NATIVE = "native"
const val AGENT_TARGET_WSL_PREFIX = "wsl:"

/**
 * Agent target of a Windows host that holds the Agent on Windows itself — the literal the renderer sends.
 * [AGENT_TARGET_NATIVE] keeps meaning the same thing on a Windows row, because rows written before this value
 * existed hold it and must keep addressing the shell context they were detected in.
 */
const val AGENT_TARGET_WINDOWS = "windows"

/** Probe error code of an Agent whose verdict this app could not read: a Windows target whose own lookup answered nothing it can read, or a target with no entry in the app at all. Either way no installation is claimed and no session opens from that answer. */
const val WINDOWS_NATIVE_UNSUPPORTED = "windows_native_unsupported"

/**
 * Where on a host an Agent runs, and how one command line the app built reaches that place.
 *
 * The app builds its command lines as POSIX shell text (probe, scanner, working-directory check, ACP and ZCode
 * launch). A target only wraps that line, so a command probed here is the command launched later: the probe and
 * the session prepare the same text for the same target.
 */
sealed interface RemoteTarget {
    /** Stable identity of the shell context; a detected installation is only valid for the target it was found on. */
    val id: String

    /**
     * True for every target this app can probe and launch, which is every target it knows today — a Windows host's
     * own target included, because that one is entered with the Windows `where` and PowerShell. False is reserved
     * for a target with no entry in the app at all, which nothing can probe or launch; no known target answers it.
     */
    val supported: Boolean

    /**
     * True when this target runs the app's POSIX command lines as they are written. A target entered through a
     * Windows shell carries [posixCommands] false until a Windows text exists for each of them, so such a line is
     * refused as itself instead of being handed to a shell that cannot read it.
     */
    val posixCommands: Boolean

    /**
     * The command line for the host's SSH exec channel. Every target this app knows answers one; null is reserved
     * for a target with no entry in the app at all, and a caller that gets it refuses that target instead of
     * handing a line to a shell it cannot enter.
     */
    fun shellCommand(command: String): String?

    /** A Linux or otherwise POSIX host: the command runs in the host's own shell, unchanged. */
    data object Posix : RemoteTarget {
        override val id: String get() = "posix"
        override val supported: Boolean get() = true
        override val posixCommands: Boolean get() = true
        override fun shellCommand(command: String): String = command
    }

    /**
     * One WSL distribution of a Windows host: the command runs in that distribution's own POSIX shell.
     *
     * Windows OpenSSH runs the command line in the Windows shell first — cmd.exe, its default — so the POSIX
     * command travels base64-encoded: base64 holds no character either shell interprets, and the distribution
     * decodes the exact text back into a shell. `eval` (rather than a pipe into a new shell) keeps the channel's
     * stdin attached to the command that is launched, which an Agent process reads.
     */
    data class Wsl(val distribution: String) : RemoteTarget {
        init {
            require(validWslDistribution(distribution)) { "Invalid WSL distribution name" }
        }

        override val id: String get() = AGENT_TARGET_WSL_PREFIX + distribution
        override val supported: Boolean get() = true
        override val posixCommands: Boolean get() = true

        override fun shellCommand(command: String): String {
            val encoded = Base64.getEncoder().encodeToString(command.toByteArray(Charsets.UTF_8))
            return "wsl.exe --distribution \"$distribution\" -- /bin/sh -c \"eval \$(printf %s $encoded | base64 -d)\""
        }
    }

    /**
     * An Agent installed on Windows itself. This target is entered — its installations are looked up with the
     * Windows `where` command and its directories are tested with PowerShell — but it is not a POSIX shell, so
     * the app's POSIX command lines are not handed to it: [posixCommands] is false and its callers refuse them.
     */
    data object WindowsNative : RemoteTarget {
        override val id: String get() = WINDOWS_NATIVE_ID
        override val supported: Boolean get() = true
        override val posixCommands: Boolean get() = false

        override fun shellCommand(command: String): String = windowsShellCommand(command)
    }
}

/** Identity of the shell context a Windows host's own Agent runs in, as a host row reports it. */
const val WINDOWS_NATIVE_ID = "windows-native"

/**
 * One command line for a Windows host's SSH exec channel.
 *
 * Windows OpenSSH runs the line in cmd.exe first, and cmd.exe cannot run the app's command lines itself, so the
 * text travels base64-encoded — base64 holds no character cmd interprets — into the Windows side's own decoder:
 * `powershell.exe -EncodedCommand` takes base64 of the command text as UTF-16LE and runs the exact text back.
 * PowerShell ships with every Windows that can hold OpenSSH, and the encoded text is the same text in both places
 * it is used, so a command a probe replicated is the command a session runs.
 */
fun windowsShellCommand(command: String): String {
    val encoded = Base64.getEncoder().encodeToString(command.toByteArray(Charsets.UTF_16LE))
    return "powershell.exe -NoProfile -NonInteractive -EncodedCommand $encoded"
}

/**
 * A Windows target's own lookup for one executable: `where` (spelled `where.exe`, because PowerShell resolves the
 * bare word to its own `Where-Object` alias) prints the absolute paths Windows itself would start. One line per
 * Agent is printed, so the answer stays parseable without trusting the order the remote happened to answer in.
 */
fun windowsLookupCommand(agents: List<AgentKind>): String {
    val names = agents.joinToString(",") { "'${it.command}'" }
    return "if (-not (Get-Command where.exe -ErrorAction SilentlyContinue)) { 'probe_unavailable'; exit 0 }; " +
        "foreach (\$name in @($names)) { \$found = @(where.exe \$name 2>\$null)[0]; " +
        "if (\$found) { \"found \$name \$found\" } else { \"missing \$name\" } }"
}

/** The answers one [windowsLookupCommand] run prints; anything else is an answer this app cannot read. */
const val WINDOWS_LOOKUP_FOUND = "found"
const val WINDOWS_LOOKUP_MISSING = "missing"
const val WINDOWS_LOOKUP_UNAVAILABLE = "probe_unavailable"

/**
 * True when [value] is an absolute path Windows can address: a drive-absolute path (`C:\work`) or a UNC path
 * (`\\server\share`). A path with no drive or share is relative, and a value carrying a control character is
 * refused because no such path can be typed or launched.
 */
fun isWindowsAbsolutePath(value: String): Boolean {
    if (value.isEmpty() || value.length > MAX_PATH_LENGTH || value.any { it.code < 32 || it.code == 127 }) return false
    if (value.startsWith("\\\\")) return value.drop(2).substringBefore('\\').isNotEmpty() && value.drop(2).contains('\\')
    return value.length >= 3 && value[0].isLetter() && value[0].code < 128 && value[1] == ':' && (value[2] == '\\' || value[2] == '/')
}

/** An absolute executable path on either kind of target: POSIX (`/usr/bin/kimi`) or Windows (`C:\...\kimi.exe`). */
fun isAbsoluteAgentPath(value: String): Boolean = value.startsWith('/') || isWindowsAbsolutePath(value)

/**
 * [value] as a PowerShell text: a single-quoted string holds everything literally — no expansion, no escape
 * sequence — and the only character it cannot carry as itself is the single quote, which is written twice.
 */
fun powershellLiteral(value: String): String = "'" + value.replace("'", "''") + "'"

/**
 * The target a host row describes. Only a Windows host departs from the POSIX default: one distribution it names
 * runs the Agent in that distribution's POSIX shell, and every other value — including [AGENT_TARGET_WINDOWS],
 * the legacy [AGENT_TARGET_NATIVE] and a value that names no usable distribution — addresses Windows itself.
 */
fun remoteTargetOf(osType: String?, agentTarget: String?): RemoteTarget {
    if (osType != HOST_OS_WINDOWS) return RemoteTarget.Posix
    val distribution = agentTarget
        ?.takeIf { it.startsWith(AGENT_TARGET_WSL_PREFIX) }
        ?.removePrefix(AGENT_TARGET_WSL_PREFIX)
        ?.takeIf(::validWslDistribution)
    return distribution?.let { RemoteTarget.Wsl(it) } ?: RemoteTarget.WindowsNative
}

/**
 * A distribution name must be safe to place inside a double-quoted Windows shell argument: WSL names are
 * alphanumeric with spaces, dots, underscores and dashes, and everything else is refused instead of escaped.
 */
fun validWslDistribution(distribution: String): Boolean = distribution.isNotEmpty() &&
    distribution.length <= MAX_WSL_DISTRIBUTION_LENGTH &&
    isAsciiAlphanumeric(distribution.first()) &&
    distribution.all { isAsciiAlphanumeric(it) || it in "._- " }

private fun isAsciiAlphanumeric(value: Char): Boolean = value.isLetterOrDigit() && value.code < 128

private const val MAX_WSL_DISTRIBUTION_LENGTH = 64
private const val MAX_PATH_LENGTH = 4096
