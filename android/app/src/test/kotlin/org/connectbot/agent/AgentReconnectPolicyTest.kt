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
import org.connectbot.data.entity.KnownHost
import org.junit.Test

class AgentReconnectPolicyTest {
    @Test
    fun retriesBackOffToSixtySecondsAndResetAfterSuccess() {
        val policy = AgentReconnectPolicy()
        listOf(1000L, 2000L, 4000L, 8000L, 16000L, 32000L, 60000L, 60000L).forEach { expected ->
            policy.failed(1, 100)
            assertThat(policy.waiting(1, 100)).isEqualTo(expected)
        }
        policy.succeeded(1)
        policy.failed(1, 200)
        assertThat(policy.waiting(1, 200)).isEqualTo(1000L)
    }

    @Test
    fun permanentFailureRequiresExplicitUserRetry() {
        val policy = AgentReconnectPolicy()
        policy.block(1)
        policy.succeeded(1)
        assertThat(policy.isBlocked(1)).isTrue()
        policy.userRetry(1)
        assertThat(policy.isBlocked(1)).isFalse()
    }

    @Test
    fun trustedKeySurvivesAddressAndPortChangeButNotKeyChange() {
        val wire = byteArrayOf(1, 2, 3)
        val trusted = KnownHost(hostId = 7, hostname = "old-address", port = 22, hostKeyAlgo = "ssh-rsa", hostKey = wire)
        assertThat(trustedAgentHostKey(listOf(trusted), "rsa-sha2-512", wire)).isTrue()
        assertThat(trustedAgentHostKey(listOf(trusted.copy(hostname = "new-address", port = 2222)), "ssh-rsa", wire)).isTrue()
        assertThat(trustedAgentHostKey(listOf(trusted), "ssh-rsa", byteArrayOf(4, 5))).isFalse()
        assertThat(trustedAgentHostKey(listOf(trusted), "ssh-ed25519", wire)).isFalse()
    }
}
