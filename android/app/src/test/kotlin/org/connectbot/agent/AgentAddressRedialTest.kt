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

class AgentAddressRedialTest {
    private fun host(hostname: String = "192.168.1.5", port: Int = 22, protocol: String = "ssh", jumpHostId: Long? = null) = Host(id = 1, protocol = protocol, hostname = hostname, port = port, jumpHostId = jumpHostId)

    private fun eligible(
        host: Host = host(),
        hasTrust: Boolean = true,
        hasFailure: Boolean = true,
        blocked: Boolean = false,
        waitingMillis: Long = 0L,
        hasLiveConnection: Boolean = false,
    ): Boolean = redialEligible(host, hasTrust, hasFailure, blocked, waitingMillis, hasLiveConnection)

    @Test
    fun recognisesOnlyIpLiterals() {
        listOf("192.168.1.5", "0.0.0.0", "255.255.255.255", "::", "::1", "fe80::1", "fe80::1%wlan0", "2001:db8::1", "1:2:3:4:5:6:7:8", "[fe80::1]", "[::1]").forEach {
            assertThat(isIpLiteral(it)).describedAs(it).isTrue()
        }
        listOf("", "host.local", "192.168.1", "192.168.1.256", "1.2.3.4.5", "192.168.1.5 ", "192.168.1.5:22", "1::2::3", ":::", ":1:2:3:4:5:6:7", "1:2:3:4:5:6:7:8:9", "1:2:3:4:5:6:7:", "::ffff:1.2.3.4").forEach {
            assertThat(isIpLiteral(it)).describedAs(it).isFalse()
        }
    }

    @Test
    fun redialsOnlyAfterAUserVisibleFailureOfATrustedDirectIpSshHost() {
        assertThat(eligible()).isTrue()
        assertThat(eligible(host = host(protocol = "telnet"))).isFalse()
        assertThat(eligible(host = host(jumpHostId = 5))).isFalse()
        assertThat(eligible(host = host(hostname = "host.local"))).isFalse()
        assertThat(eligible(hasTrust = false)).isFalse()
        assertThat(eligible(hasFailure = false)).isFalse()
        assertThat(eligible(blocked = true)).isFalse()
        assertThat(eligible(waitingMillis = 1000L)).isFalse()
        assertThat(eligible(hasLiveConnection = true)).isFalse()
    }

    @Test
    fun candidatesPreferDiscoveredAddressesAndKeepOneEntryPerAddress() {
        val recorded = listOf(
            HostAddressRecord("10.0.0.7", 22, 100L, AgentAddressSource.CONNECTED),
            HostAddressRecord("10.0.0.9", 22, 50L, AgentAddressSource.CONNECTED),
        )
        val discovered = listOf(DiscoveredSshEndpoint("10.0.0.7", 22), DiscoveredSshEndpoint("10.0.0.8", 22))
        assertThat(redialCandidates(host(), recorded, discovered)).containsExactly(
            AddressCandidate("10.0.0.7", 22, AgentAddressSource.REDISCOVERED),
            AddressCandidate("10.0.0.8", 22, AgentAddressSource.REDISCOVERED),
            AddressCandidate("10.0.0.9", 22, AgentAddressSource.CONNECTED),
        )
    }

    @Test
    fun candidatesExcludeTheCurrentAddressAndEveryOtherPort() {
        val recorded = listOf(
            HostAddressRecord("192.168.1.5", 22, 100L, AgentAddressSource.CONNECTED),
            HostAddressRecord("10.0.0.9", 2222, 90L, AgentAddressSource.CONNECTED),
            HostAddressRecord("10.0.0.9", 22, 80L, AgentAddressSource.REDISCOVERED),
        )
        val discovered = listOf(DiscoveredSshEndpoint("192.168.1.5", 22), DiscoveredSshEndpoint("10.0.0.11", 2222))
        assertThat(redialCandidates(host(), recorded, discovered)).containsExactly(
            AddressCandidate("10.0.0.9", 22, AgentAddressSource.REDISCOVERED),
        )
    }

    @Test
    fun candidatesStopAtEightAddresses() {
        val discovered = (1..12).map { DiscoveredSshEndpoint("10.0.0.$it", 22) }
        val candidates = redialCandidates(host(), emptyList(), discovered)
        assertThat(candidates).hasSize(8)
        assertThat(candidates.map { it.address }).containsExactly(
            "10.0.0.1",
            "10.0.0.2",
            "10.0.0.3",
            "10.0.0.4",
            "10.0.0.5",
            "10.0.0.6",
            "10.0.0.7",
            "10.0.0.8",
        )
    }
}
