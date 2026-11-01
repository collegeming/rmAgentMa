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

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.rmagentma.core.AgentKind
import org.rmagentma.core.RemoteTarget
import java.util.Base64

/** Which conversations this build has a Windows launch for, and the text it hands the Windows shell. */
class WindowsZcodeLaunchTest {
    private val executable = "E:\\SoftWare\\ZCode\\ZCode.exe"
    private val cwd = "C:\\work\\project"

    @Test
    fun theDesktopBuildOfZcodeOnAWindowsTargetHasALaunch() {
        val line = windowsZcodeLaunchLine(AgentKind.ZCODE, RemoteTarget.WindowsNative, executable, cwd)

        assertThat(line).isNotNull()
        assertThat(line).contains("ELECTRON_RUN_AS_NODE = '1'")
        assertThat(line).contains("\$start.FileName = '$executable'")
        assertThat(line).contains("app-server", "--cwd $cwd", "--mode build")
        // The entry script is this app's own, and it requires the CLI beside the executable it was derived from.
        assertThat(line).contains("E:\\\\SoftWare\\\\ZCode\\\\resources\\\\glm\\\\zcode.cjs")
    }

    /**
     * The line is the exact text the relay decodes, so the host session wraps it once: base64 of UTF-16LE that
     * `powershell.exe -EncodedCommand` turns back into this same text.
     */
    @Test
    fun theLineIsPowerShellTextThatTheWindowsRelayCarriesOnce() {
        val line = windowsZcodeLaunchLine(AgentKind.ZCODE, RemoteTarget.WindowsNative, executable, cwd).orEmpty()

        val wrapped = RemoteTarget.WindowsNative.shellCommand(line)
        assertThat(wrapped).startsWith("powershell.exe -NoProfile -NonInteractive -EncodedCommand ")
        // Nothing is wrapped twice: the payload decodes straight back to the PowerShell text.
        val payload = wrapped.substringAfter("-EncodedCommand ")
        assertThat(String(Base64.getDecoder().decode(payload), Charsets.UTF_16LE)).isEqualTo(line)
    }

    @Test
    fun everyOtherAgentKeepsNoWindowsLaunch() {
        for (agent in AgentKind.entries.filter { it != AgentKind.ZCODE }) {
            assertThat(windowsZcodeLaunchLine(agent, RemoteTarget.WindowsNative, executable, cwd)).isNull()
        }
    }

    @Test
    fun aPosixOrWslTargetKeepsItsOwnPosixLaunch() {
        assertThat(windowsZcodeLaunchLine(AgentKind.ZCODE, RemoteTarget.Posix, "/usr/bin/zcode", "/work")).isNull()
        assertThat(windowsZcodeLaunchLine(AgentKind.ZCODE, RemoteTarget.Wsl("Ubuntu-22.04"), "/usr/bin/zcode", "/work")).isNull()
    }

    @Test
    fun anExecutableOrDirectoryThisLaunchCannotAddressHasNoWindowsLaunch() {
        // A launcher that is not the desktop build's own executable has no desktop launch text.
        assertThat(windowsZcodeLaunchLine(AgentKind.ZCODE, RemoteTarget.WindowsNative, "C:\\tools\\zcode-agent.exe", cwd)).isNull()
        assertThat(windowsZcodeLaunchLine(AgentKind.ZCODE, RemoteTarget.WindowsNative, "C:\\tools\\zcode.cmd", cwd)).isNull()
        assertThat(windowsZcodeLaunchLine(AgentKind.ZCODE, RemoteTarget.WindowsNative, "/usr/bin/zcode", cwd)).isNull()
        assertThat(windowsZcodeLaunchLine(AgentKind.ZCODE, RemoteTarget.WindowsNative, executable, "/work")).isNull()
        assertThat(windowsZcodeLaunchLine(AgentKind.ZCODE, RemoteTarget.WindowsNative, "", cwd)).isNull()
        assertThat(windowsZcodeLaunchLine(AgentKind.ZCODE, RemoteTarget.WindowsNative, executable, "C:work")).isNull()
    }
}
