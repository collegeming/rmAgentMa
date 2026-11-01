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

import org.rmagentma.core.AgentKind
import org.rmagentma.core.RemoteTarget
import org.rmagentma.core.isWindowsAbsolutePath
import org.rmagentma.core.windowsZcodeCommand

/**
 * The PowerShell text that starts a Windows desktop ZCode's protocol server for one conversation, or null when
 * this Agent, target, installation and directory do not make one.
 *
 * Only ZCode has a Windows launch, and only the desktop build's own `ZCode.exe` inside an installation has one:
 * the CLI entry that is started sits beside that executable, so a `zcode` launcher the Windows `where` lookup
 * found is not turned into a desktop launch - the caller keeps its own refusal for it.
 *
 * The text is returned unwrapped. It is PowerShell, and every command a host session is given is wrapped once by
 * that target's own relay: [RemoteTarget.WindowsNative.shellCommand] sends it base64-encoded through
 * `powershell.exe -EncodedCommand`, which decodes exactly this text back for the Windows shell to run.
 */
internal fun windowsZcodeLaunchLine(agent: AgentKind, target: RemoteTarget, executable: String, cwd: String): String? {
    if (agent != AgentKind.ZCODE) return null
    if (target !is RemoteTarget.WindowsNative) return null
    if (!isWindowsAbsolutePath(executable) || !isWindowsAbsolutePath(cwd)) return null
    return try {
        windowsZcodeCommand(executable, cwd)
    } catch (_: IllegalArgumentException) {
        // A path this launch cannot address, or an executable that is not the desktop build's own.
        null
    }
}
