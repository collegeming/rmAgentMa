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

/** Probe error code of a Windows host whose Agent target has no implemented entry yet. */
const val WINDOWS_NATIVE_UNSUPPORTED = "windows_native_unsupported"

/**
 * Where on a host an Agent runs, and how one POSIX command line reaches that place.
 *
 * The app builds every remote command as a POSIX command line (probe, scanner, working-directory check, ACP and
 * ZCode launch). A target only wraps that line, so a command probed here is the command launched later: the probe
 * and the session prepare the same text for the same target.
 */
sealed interface RemoteTarget {
    /** Stable identity of the shell context; a detected installation is only valid for the target it was found on. */
    val id: String

    /** False when this target has no entry in the app, so nothing can be probed or launched on it. */
    val supported: Boolean

    /** The command line for the host's SSH exec channel, or null when [supported] is false. */
    fun shellCommand(command: String): String?

    /** A Linux or otherwise POSIX host: the command runs in the host's own shell, unchanged. */
    data object Posix : RemoteTarget {
        override val id: String get() = "posix"
        override val supported: Boolean get() = true
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

        override fun shellCommand(command: String): String {
            val encoded = Base64.getEncoder().encodeToString(command.toByteArray(Charsets.UTF_8))
            return "wsl.exe --distribution \"$distribution\" -- /bin/sh -c \"eval \$(printf %s $encoded | base64 -d)\""
        }
    }

    /** An Agent installed on Windows itself. Reading its paths needs a Windows entry this app does not have yet. */
    data object WindowsNative : RemoteTarget {
        override val id: String get() = "windows-native"
        override val supported: Boolean get() = false
        override fun shellCommand(command: String): String? = null
    }
}

/**
 * The target a host row describes. Only a Windows host departs from the POSIX default, and only for a
 * distribution name that can be quoted into the Windows shell without changing the command.
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
