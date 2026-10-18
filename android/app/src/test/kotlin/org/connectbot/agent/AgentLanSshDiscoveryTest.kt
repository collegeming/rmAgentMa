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

class AgentLanSshDiscoveryTest {
    @Test
    fun ipv4PrivateAndLinkLocalRangesAreAccepted() {
        assertPrivate(
            "10.0.0.1",
            "10.255.255.255",
            "172.16.0.1",
            "172.31.255.255",
            "192.168.0.0",
            "192.168.255.255",
            "169.254.0.1",
            "169.254.255.254",
        )
    }

    @Test
    fun ipv4OutsideTheLanRangesIsRejected() {
        assertNotPrivate(
            "9.255.255.255",
            "11.0.0.1",
            "172.15.255.255",
            "172.32.0.0",
            "192.167.255.255",
            "192.169.0.0",
            "169.253.255.255",
            "169.255.0.0",
            "8.8.8.8",
            "127.0.0.1",
            "0.0.0.0",
            "255.255.255.255",
            "224.0.0.1",
        )
    }

    @Test
    fun malformedIpv4ValuesAreRejected() {
        assertNotPrivate(
            "",
            "   ",
            "10",
            "10.0.0",
            "10.0.0.1.1",
            "10.0.0.256",
            "10.0.0.-1",
            "10.0.0.a",
            "10.0.0.1x",
            "10.0.00.1",
            "10.0.0.",
            ".10.0.0.1",
            "10.0.0.1:22",
        )
    }

    @Test
    fun surroundingWhitespaceIsIgnored() {
        assertPrivate(" 192.168.1.1 ", "\t10.0.0.1")
    }

    @Test
    fun ipv6UniqueLocalAndLinkLocalRangesAreAccepted() {
        assertPrivate(
            "fc00::",
            "fc00::1",
            "fd00::1",
            "FD00:0:0:0:0:0:0:1",
            "fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
            "fe80::",
            "fe80::1",
            "febf::ffff",
            "fe80::1%wlan0",
            "fd00::1%3",
        )
    }

    @Test
    fun ipv6OutsideTheLanRangesIsRejected() {
        assertNotPrivate(
            "fe7f::1",
            "fec0::1",
            "fe00::1",
            "2001:db8::1",
            "2001:4860:4860::8888",
            "64:ff9b::1",
            "::1",
            "::",
            "ff02::1",
        )
    }

    @Test
    fun malformedIpv6ValuesAreRejected() {
        assertNotPrivate(
            "fe80::zz",
            "fe80::g",
            "fe80::1::2",
            "2001:db8::1::2",
            "abc:def",
            "1:2:3",
            ":",
            ":::",
        )
    }

    @Test
    fun ipv4MappedIpv6LiteralsAreJudgedAsIpv4() {
        assertPrivate("::ffff:10.0.0.1", "::ffff:192.168.1.5")
        assertNotPrivate("::ffff:8.8.8.8", "::ffff:127.0.0.1")
    }

    @Test
    fun discoveredEndpointsExposeTheAddressAndCompareAsValues() {
        assertThat(DiscoveredSshEndpoint("192.168.1.5", 22).address).isEqualTo("192.168.1.5")
        assertThat(DiscoveredSshEndpoint("192.168.1.5", 22)).isEqualTo(DiscoveredSshEndpoint("192.168.1.5", 22))
        assertThat(DiscoveredSshEndpoint("192.168.1.5", 22)).isNotEqualTo(DiscoveredSshEndpoint("192.168.1.5", 2222))
    }

    @Test
    fun candidatesKeepOneEntryPerAddressAndPortAndDropNonLanAddresses() {
        val found = linkedSetOf<DiscoveredSshEndpoint>()
        assertThat(addPrivateLanEndpoint(found, "8.8.8.8", 22)).isFalse()
        assertThat(addPrivateLanEndpoint(found, "not-an-address", 22)).isFalse()
        assertThat(addPrivateLanEndpoint(found, "::1", 22)).isFalse()
        assertThat(addPrivateLanEndpoint(found, "192.168.1.5", 22)).isTrue()
        assertThat(addPrivateLanEndpoint(found, "192.168.1.5", 22)).isFalse()
        assertThat(addPrivateLanEndpoint(found, "192.168.1.5", 2222)).isTrue()
        assertThat(addPrivateLanEndpoint(found, "192.168.1.6", 22)).isTrue()
        assertThat(addPrivateLanEndpoint(found, "fd00::1", 22)).isTrue()
        assertThat(found).containsExactlyInAnyOrder(
            DiscoveredSshEndpoint("192.168.1.5", 22),
            DiscoveredSshEndpoint("192.168.1.5", 2222),
            DiscoveredSshEndpoint("192.168.1.6", 22),
            DiscoveredSshEndpoint("fd00::1", 22),
        )
    }

    private fun assertPrivate(vararg values: String) {
        for (value in values) {
            assertThat(isPrivateLanAddress(value)).describedAs("private: %s", value).isTrue()
        }
    }

    private fun assertNotPrivate(vararg values: String) {
        for (value in values) {
            assertThat(isPrivateLanAddress(value)).describedAs("not private: %s", value).isFalse()
        }
    }
}
