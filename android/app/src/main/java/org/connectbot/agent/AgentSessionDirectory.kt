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

import org.rmagentma.core.AgentSession
import org.rmagentma.core.RemoteTarget
import org.rmagentma.core.isWindowsAbsolutePath
import java.io.IOException

/**
 * A conversation whose working directory is not known cannot be continued and no conversation can be started in
 * its group, because the Agent's own directory is what makes a session usable and guessing one - HOME, a parent
 * directory, the last used path - would put the conversation where the Agent never had it.
 */
class AgentDirectoryUnknownException(val reason: String) : IOException(
    "Session working directory is unknown; this conversation cannot be opened here: $reason",
)

/**
 * Why a session's working directory cannot be used, or null when it can.
 *
 * A record whose source named no directory, or named one that is not an absolute path, is kept in the list - the
 * session still exists on the host, and deleting the row would hide a real conversation. It is reported as its
 * own reason here, and the Agent's own value stays untouched on the session so a source that later reports a
 * usable directory only has to be read again.
 *
 * What counts as absolute is the target's own rule: a POSIX path on a POSIX or WSL target, a drive-absolute or
 * UNC path on a Windows one.
 */
fun AgentSession.unknownDirectoryReason(target: RemoteTarget = RemoteTarget.Posix): String? {
    if (target is RemoteTarget.WindowsNative) {
        if (isWindowsAbsolutePath(cwd)) return null
        if (cwd.isBlank()) return "the session reports no working directory"
        return "the session's working directory is not an absolute path: " + cwd.sanitizedDirectory()
    }
    if (cwd.startsWith('/')) return null
    if (cwd.isBlank()) return "the session reports no working directory"
    return "the session's working directory is not an absolute path: " + cwd.sanitizedDirectory()
}

/** The directory as a label: control characters removed, bounded, because this text reaches a notification. */
private fun String.sanitizedDirectory(): String = map { if (it.isISOControl()) ' ' else it }
    .joinToString("")
    .take(MAX_DIRECTORY_REASON)

/** True when this session's working directory can be used for continuing it or starting another one beside it. */
fun AgentSession.hasKnownDirectory(target: RemoteTarget = RemoteTarget.Posix): Boolean =
    unknownDirectoryReason(target) == null

private const val MAX_DIRECTORY_REASON = 120
