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

import org.connectbot.data.entity.Host

data class AddressCandidate(
    val address: String,
    val port: Int,
    val source: AgentAddressSource,
)

fun isIpLiteral(value: String): Boolean = if (value.length > 2 && value.startsWith("[") && value.endsWith("]")) {
    isIpv6(value.substring(1, value.length - 1))
} else {
    isIpv4(value) || isIpv6(value)
}

fun redialEligible(
    host: Host,
    hasTrust: Boolean,
    hasFailure: Boolean,
    blocked: Boolean,
    waitingMillis: Long,
    hasLiveConnection: Boolean,
): Boolean = host.protocol == "ssh" &&
    host.jumpHostId == null &&
    isIpLiteral(host.hostname) &&
    hasTrust &&
    hasFailure &&
    !blocked &&
    waitingMillis == 0L &&
    !hasLiveConnection

fun redialCandidates(
    host: Host,
    recorded: List<HostAddressRecord>,
    discovered: List<DiscoveredSshEndpoint>,
): List<AddressCandidate> = // A freshly discovered address outranks a remembered one, which outranks waiting for the backoff.
    (
        discovered.map { AddressCandidate(it.address, it.port, AgentAddressSource.REDISCOVERED) } +
            recorded.map { AddressCandidate(it.address, it.port, it.source) }
        )
        .filter { it.port == host.port && it.address != host.hostname }
        .distinctBy { it.address to it.port }
        .take(MAX_REDIAL_CANDIDATES)

private fun isIpv4(value: String): Boolean {
    val parts = value.split('.')
    return parts.size == 4 &&
        parts.all { part -> part.length in 1..3 && part.all { it in '0'..'9' } && part.toInt() <= 255 }
}

private fun isIpv6(value: String): Boolean {
    val body = value.substringBefore('%')
    if (!body.contains(':')) return false
    val parts = body.split("::")
    if (parts.size > 2) return false
    if (parts.any { part -> part.isNotEmpty() && part.split(':').any { it.isEmpty() } }) return false
    val groups = parts.flatMap { it.split(':') }.filter { it.isNotEmpty() }
    if (groups.any { group -> group.length > 4 || group.any { it !in '0'..'9' && it.lowercaseChar() !in 'a'..'f' } }) return false
    return if (parts.size == 2) groups.size <= 7 else groups.size == 8
}

private const val MAX_REDIAL_CANDIDATES = 8
