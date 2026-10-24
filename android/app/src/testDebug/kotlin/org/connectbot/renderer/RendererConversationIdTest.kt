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

package org.connectbot.renderer

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.rmagentma.core.AgentKind
import org.rmagentma.core.AgentSession

class RendererConversationIdTest {
    private val session = AgentSession(hostId = 7, agent = AgentKind.KIMI, sessionId = "session-a", cwd = "/workspace/app")

    @Test
    fun everyIdRoundTripsToItsOwnHostAgentAndSession() {
        val id = RendererConversationId.of(session)
        val parsed = RendererConversationId.parse(id)
        assertThat(parsed).isNotNull
        assertThat(parsed!!.hostId).isEqualTo(7)
        assertThat(parsed.agent).isEqualTo(AgentKind.KIMI)
        assertThat(parsed.sessionId).isEqualTo("session-a")
    }

    @Test
    fun anIdIsStableAcrossRestartsAndIgnoresRowMetadata() {
        val first = RendererConversationId.of(session)
        val second = RendererConversationId.of(session.copy(title = "另一个标题", preview = "预览", updatedAt = 99, createdAt = 3))
        assertThat(first).isEqualTo(second)
        assertThat(RendererConversationId.of(session.copy(hostId = 8))).isNotEqualTo(first)
        assertThat(RendererConversationId.of(session.copy(agent = AgentKind.DSH))).isNotEqualTo(first)
        assertThat(RendererConversationId.of(session.copy(sessionId = "session-b"))).isNotEqualTo(first)
    }

    @Test
    fun idsStayInsideTheUrlUnreservedSetWhateverTheSessionIdHolds() {
        val awkward = listOf("a b", "a/b?c#d", "100%", "会话-1", "a.b", "a&b=c", "a%2Eb")
        val ids = awkward.map { RendererConversationId.of(session.copy(sessionId = it)) }
        assertThat(ids).doesNotHaveDuplicates()
        ids.forEachIndexed { index, id ->
            assertThat(id).matches("[A-Za-z0-9._~-]+")
            assertThat(RendererConversationId.parse(id)!!.sessionId).isEqualTo(awkward[index])
        }
    }

    @Test
    fun malformedOrForeignIdsNeverNameASession() {
        val rejected = listOf(
            "",
            RENDERER_CONVERSATION,
            "c1",
            "c1.",
            "c1.7",
            "c1.7.6b696d69",
            "c1.7.6b696d69.73657373696f6e2d61.extra",
            // Host 0 and unknown agent never came from the index.
            "c1.0.6b696d69.73657373696f6e2d61",
            "c1.7.7a7a.73657373696f6e2d61",
            // Odd length and non-hex payloads are not a derivation this host makes.
            "c1.7.6b696d6.73657373696f6e2d61",
            "c1.7.6b696d69.6g657373696f6e2d61",
            "c1.7.6B696D69.73657373696f6e2d61",
            "c2.7.6b696d69.73657373696f6e2d61",
        )
        rejected.forEach { value ->
            assertThat(RendererConversationId.parse(value)).describedAs(value).isNull()
        }
    }

    @Test
    fun anEmptySessionIdIsNotAConversation() {
        assertThat(RendererConversationId.parse(RendererConversationId.of(session.copy(sessionId = "")))).isNull()
    }
}
