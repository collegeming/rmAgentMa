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

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val SSH_SERVICE_TYPE = "_ssh._tcp"

data class DiscoveredSshEndpoint(val address: String, val port: Int)

fun isPrivateLanAddress(value: String): Boolean {
    // A scope id is local to the querying device and is not part of the address value
    val literal = value.trim().substringBefore('%')
    if (literal.isEmpty()) return false
    return if (literal.contains(':')) isPrivateIpv6Literal(literal) else isPrivateIpv4Literal(literal)
}

internal fun addPrivateLanEndpoint(target: MutableSet<DiscoveredSshEndpoint>, address: String, port: Int): Boolean = isPrivateLanAddress(address) && target.add(DiscoveredSshEndpoint(address, port))

private fun isPrivateIpv4Literal(literal: String): Boolean {
    val parts = literal.split('.')
    if (parts.size != 4) return false
    val octets = parts.map { part ->
        // A leading zero makes the value ambiguous, and only decimal digits are part of an address
        if (part.length !in 1..3 || (part.length > 1 && part[0] == '0') || part.any { it !in '0'..'9' }) {
            return false
        }
        part.toInt()
    }
    if (octets.any { it > 255 }) return false
    val first = octets[0]
    val second = octets[1]
    return first == 10 ||
        (first == 172 && second in 16..31) ||
        (first == 192 && second == 168) ||
        (first == 169 && second == 254)
}

private fun isPrivateIpv6Literal(literal: String): Boolean {
    // Only literals reach this point: the JDK sends colon-free input to the name service, never colon input
    val address = try {
        InetAddress.getByName(literal)
    } catch (_: UnknownHostException) {
        return false
    }
    if (address is Inet4Address) return isPrivateIpv4Literal(address.hostAddress.orEmpty())
    val bytes = (address as? Inet6Address)?.address ?: return false
    val first = bytes[0].toInt() and 0xFF
    val second = bytes[1].toInt() and 0xFF
    return (first and 0xFE) == 0xFC || (first == 0xFE && (second and 0xC0) == 0x80)
}

@Singleton
class AgentLanSshDiscovery @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun endpoints(timeoutMillis: Long = 4_000L): List<DiscoveredSshEndpoint> {
        val manager = context.getSystemService(NsdManager::class.java) ?: return emptyList()
        val found = ConcurrentHashMap.newKeySet<DiscoveredSshEndpoint>()
        val startFailure = CompletableDeferred<Unit>()
        val listener = discoveryListener(manager, found, startFailure)
        try {
            manager.discoverServices(SSH_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: Exception) {
            return emptyList()
        }
        try {
            // Discovery never reports completion, so the time window is what ends the search
            withTimeoutOrNull(timeoutMillis.coerceAtLeast(0L)) { startFailure.await() }
        } finally {
            stopDiscoveryQuietly(manager, listener)
        }
        return found.toList()
    }

    // resolveService remains the only resolve API below the Tiramisu SDK extensions
    @Suppress("DEPRECATION")
    private fun discoveryListener(
        manager: NsdManager,
        found: MutableSet<DiscoveredSshEndpoint>,
        startFailure: CompletableDeferred<Unit>,
    ): NsdManager.DiscoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) = Unit

        override fun onDiscoveryStopped(serviceType: String) = Unit

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            startFailure.complete(Unit)
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

        override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            try {
                manager.resolveService(serviceInfo, resolveListener(found))
            } catch (_: Exception) {
                // A service that cannot be resolved simply drops out of the candidate list
            }
        }
    }

    private fun resolveListener(found: MutableSet<DiscoveredSshEndpoint>) = object : NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit

        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            for (address in addressesOf(serviceInfo)) {
                val host = address.hostAddress ?: continue
                addPrivateLanEndpoint(found, host, serviceInfo.port)
            }
        }
    }

    // getHost() is the only address accessor below API 34
    @Suppress("DEPRECATION")
    private fun addressesOf(serviceInfo: NsdServiceInfo): List<InetAddress> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        serviceInfo.hostAddresses.orEmpty()
    } else {
        listOfNotNull(serviceInfo.host)
    }

    private fun stopDiscoveryQuietly(manager: NsdManager, listener: NsdManager.DiscoveryListener) {
        try {
            manager.stopServiceDiscovery(listener)
        } catch (_: Exception) {
            // Stopping is best effort; a failed or already stopped listener must not escape here
        }
    }
}
