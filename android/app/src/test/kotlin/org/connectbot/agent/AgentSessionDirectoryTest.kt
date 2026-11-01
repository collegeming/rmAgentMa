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
import org.rmagentma.core.AgentSession
import org.rmagentma.core.RemoteTarget

class AgentSessionDirectoryTest {
    private fun session(cwd: String) = AgentSession(hostId = 1L, agent = AgentKind.KIMI, sessionId = "s-1", cwd = cwd)

    @Test
    fun answersNoReasonForAnAbsoluteDirectory() {
        assertThat(session("/work/project").unknownDirectoryReason()).isNull()
        assertThat(session("/work/project").hasKnownDirectory()).isTrue()
    }

    /** What is absolute is the target's rule: a Windows conversation names a drive path, not a POSIX one. */
    @Test
    fun aWindowsTargetJudgesItsDirectoryAsAWindowsPath() {
        val windows = session("C:\\work\\project")
        assertThat(windows.unknownDirectoryReason(RemoteTarget.WindowsNative)).isNull()
        assertThat(windows.hasKnownDirectory(RemoteTarget.WindowsNative)).isTrue()
        assertThat(session("\\\\server\\share\\project").hasKnownDirectory(RemoteTarget.WindowsNative)).isTrue()

        // A POSIX path names no directory on Windows, and a Windows path names none on a POSIX or WSL target.
        assertThat(session("/work/project").unknownDirectoryReason(RemoteTarget.WindowsNative)).contains("not an absolute path")
        assertThat(windows.unknownDirectoryReason(RemoteTarget.Posix)).contains("not an absolute path")
        assertThat(windows.unknownDirectoryReason(RemoteTarget.Wsl("Ubuntu-22.04"))).contains("not an absolute path")
        assertThat(session("rel\\dir").hasKnownDirectory(RemoteTarget.WindowsNative)).isFalse()
        assertThat(session("C:rel").hasKnownDirectory(RemoteTarget.WindowsNative)).isFalse()
        assertThat(session("").unknownDirectoryReason(RemoteTarget.WindowsNative)).contains("no working directory")
        assertThat(session("C:\\work\u001b").unknownDirectoryReason(RemoteTarget.WindowsNative)).doesNotContain("\u001b")
    }

    @Test
    fun answersWhyARecordWithNoDirectoryOrARelativeOneCannotBeUsed() {
        assertThat(session("").unknownDirectoryReason()).contains("no working directory")
        assertThat(session("   ").unknownDirectoryReason()).contains("no working directory")
        assertThat(session("relative/path").unknownDirectoryReason()).contains("not an absolute path", "relative/path")
        assertThat(session("~/project").hasKnownDirectory()).isFalse()
        // The path is the Agent's own text: a control character in it never reaches a notification or a label.
        assertThat(session("rel\u001bative").unknownDirectoryReason()).doesNotContain("\u001b")
    }

    @Test
    fun statesTheRefusalAsItsOwnFailure() {
        val refusal = AgentDirectoryUnknownException("the session reports no working directory")
        assertThat(refusal.reason).contains("no working directory")
        assertThat(refusal.message).contains("cannot be opened here", "no working directory")
    }
}
