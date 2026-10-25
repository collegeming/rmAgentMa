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

package org.connectbot.ui.screens.agents

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What each challenge asks the conversation surface for. A key-use confirmation arrives as `key_passphrase` with its
 * secret flag unset and is checked against the literal `accept`, so the surface has to draw it as a confirmation:
 * a passphrase field there would answer text that check never compares and would cancel the connection every time.
 */
class AgentChallengeDialogTest {
    @Test
    fun aHostKeyIsConfirmedWithTheLiteralTheConnectionCompares() {
        assertEquals(AgentChallengeInput.HOST_KEY, agentChallengeInput("host_key", secret = false))
        assertEquals("accept", AGENT_CHALLENGE_ACCEPT)
    }

    @Test
    fun aPasswordOrAnEncryptedKeyIsTyped() {
        assertEquals(AgentChallengeInput.SECRET, agentChallengeInput("password", secret = true))
        assertEquals(AgentChallengeInput.SECRET, agentChallengeInput("key_passphrase", secret = true))
    }

    @Test
    fun aKeyUseConfirmationIsAnsweredWithItsOwnButtonsInsteadOfAField() {
        // The confirmation `AgentSshPool` raises for a key whose use is not automatic: no secret, no typed answer.
        assertEquals(AgentChallengeInput.CONFIRMATION, agentChallengeInput("key_passphrase", secret = false))
        assertEquals(AGENT_CHALLENGE_ACCEPT, "accept")
    }

    @Test
    fun anUnknownChallengeIsNeverAcceptedImplicitly() {
        for (kind in listOf("", "hostkey", "keyboard_interactive", "HOST_KEY", "accept")) {
            assertEquals(kind, AgentChallengeInput.UNSUPPORTED, agentChallengeInput(kind, secret = false))
            assertEquals(kind, AgentChallengeInput.UNSUPPORTED, agentChallengeInput(kind, secret = true))
        }
    }
}
