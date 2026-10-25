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
import org.connectbot.data.entity.Host
import org.junit.Test
import org.rmagentma.core.AgentKind
import org.rmagentma.core.AgentSession

class AgentSessionNoticeTest {
    private val session = AgentSession(hostId = 1L, agent = AgentKind.KIMI, sessionId = "s-1", cwd = "/work", title = "fix tests")

    @Test
    fun statesTheTurnInProgressAndNeverInventsARunningSession() {
        val running = agentSessionNoticeText(facts(conversation = AgentConversationState.Ready, busy = true))
        assertThat(running).contains("Running: kimi · fix tests")

        val idle = agentSessionNoticeText(facts(conversation = AgentConversationState.Ready, busy = false))
        assertThat(idle).doesNotContain("Running")

        val failed = agentSessionNoticeText(facts(conversation = AgentConversationState.Failed, busy = false))
        assertThat(failed).contains("Disconnected: kimi · fix tests").doesNotContain("Running")
    }

    @Test
    fun statesAConversationThatIsStillLoadingAsOpening() {
        assertThat(agentSessionNoticeText(facts(conversation = AgentConversationState.Loading, busy = true)))
            .contains("Opening: kimi · fix tests")
    }

    @Test
    fun statesAStateItCannotStateAsUnknownAndNeverAsConnected() {
        val unknown = agentSessionNoticeText(facts(hosts = listOf(Host(id = 3, nickname = "workstation"))))
        assertThat(unknown).contains("1 state unknown").doesNotContain("connected")

        val recorded = agentSessionNoticeText(
            facts(
                hosts = listOf(Host(id = 3, nickname = "workstation")),
                recorded = listOf(AgentLinkRecord(3L, null, AgentLinkState.CONNECTED, 0, 1_000)),
            ),
        )
        assertThat(recorded).contains("1 disconnected").doesNotContain("1 connected")

        val live = agentSessionNoticeText(
            facts(hosts = listOf(Host(id = 3, nickname = "workstation")), live = mapOf(3L to AgentLinkState.CONNECTED)),
        )
        assertThat(live).contains("1 connected")
    }

    @Test
    fun boundsTheTextARemoteNameCanPutInANotification() {
        val noisy = facts(hosts = listOf(Host(id = 3, nickname = "work\u0000station\u001b[31m " + "x".repeat(200))))
        val text = agentSessionNoticeText(noisy)!!
        assertThat(text).doesNotContain("\u0000", "\u001b")
        assertThat(text.length).isLessThanOrEqualTo(400)
    }

    @Test
    fun statesNothingWhenThisAppHoldsNothing() {
        assertThat(agentSessionNoticeText(facts(open = null))).isNull()
    }

    private fun facts(
        hosts: List<Host> = emptyList(),
        live: Map<Long, AgentLinkState> = emptyMap(),
        recorded: List<AgentLinkRecord> = emptyList(),
        conversation: AgentConversationState = AgentConversationState.Closed,
        busy: Boolean = false,
        open: AgentSession? = session,
    ) = agentSessionNoticeFacts(
        hosts = hosts,
        session = open,
        conversation = conversation,
        busy = busy,
        live = live,
        recorded = recorded,
    )
}
