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

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AgentChallengesTest {
    @Test
    fun onlyTheCurrentChallengeIdCanRespond() = runTest {
        val challenges = AgentChallenges()
        val response = async { challenges.ask("server", "password", "Password", true) }
        runCurrent()
        val request = challenges.challenge.value!!
        challenges.respond("stale", "wrong")
        assertThat(response.isCompleted).isFalse()
        challenges.respond(request.id, "correct")
        assertThat(response.await()).isEqualTo("correct")
        assertThat(challenges.challenge.value).isNull()
    }

    @Test
    fun nullResponseCancelsWithoutReturningASecret() = runTest {
        val challenges = AgentChallenges()
        val response = async {
            try {
                challenges.ask("server", "key_passphrase", "Passphrase", true)
                false
            } catch (_: AgentConnectionNeedsUser) {
                true
            }
        }
        runCurrent()
        challenges.respond(challenges.challenge.value!!.id, null)
        assertThat(response.await()).isTrue()
        assertThat(challenges.challenge.value).isNull()
    }

    @Test
    fun cancellationClearsChallengeAndAllowsNextRequest() = runTest {
        val challenges = AgentChallenges()
        val cancelled = async { challenges.ask("one", "host_key", "Fingerprint") }
        runCurrent()
        cancelled.cancel()
        runCurrent()
        assertThat(challenges.challenge.value).isNull()
        val next = async { challenges.ask("two", "host_key", "Fingerprint") }
        runCurrent()
        challenges.respond(challenges.challenge.value!!.id, "accept")
        assertThat(next.await()).isEqualTo("accept")
    }
}
