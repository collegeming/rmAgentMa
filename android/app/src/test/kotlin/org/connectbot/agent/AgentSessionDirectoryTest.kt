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

class AgentSessionDirectoryTest {
    private fun session(cwd: String) = AgentSession(hostId = 1L, agent = AgentKind.KIMI, sessionId = "s-1", cwd = cwd)

    @Test
    fun answersNoReasonForAnAbsoluteDirectory() {
        assertThat(session("/work/project").unknownDirectoryReason()).isNull()
        assertThat(session("/work/project").hasKnownDirectory()).isTrue()
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
