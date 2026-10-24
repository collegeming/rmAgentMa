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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import org.assertj.core.api.Assertions.assertThat
import org.connectbot.data.HostRepository
import org.connectbot.data.PubkeyRepository
import org.connectbot.data.entity.Host
import org.connectbot.data.entity.KnownHost
import org.connectbot.util.SecurePasswordStorage
import org.connectbot.util.encodeSshHostKey
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.RETURNS_DEEP_STUBS
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import org.mockito.Mockito.withSettings
import org.rmagentma.core.HOST_OS_WINDOWS
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.io.IOException
import java.math.BigInteger
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AgentSshPoolRedialTest {
    // A Windows host whose Agent runs inside one WSL distribution: discovery may rewrite its address, never this.
    private val host = Host(
        id = 1,
        nickname = "lan-host",
        protocol = "ssh",
        hostname = "192.0.2.1",
        port = 22,
        osType = HOST_OS_WINDOWS,
        agentTarget = "wsl:Ubuntu-22.04",
    )

    @Test
    fun probeWithoutPinnedKeyNeverDials() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val hosts = mock(HostRepository::class.java)
        val challenges = mock(AgentChallenges::class.java)
        `when`(hosts.getKnownHostsForHost(host.id)).thenReturn(emptyList())
        val pool = pool(dispatcher, hosts, challenges)
        mockConstruction(DefaultConfig::class.java).use { configs ->
            mockConstruction(SSHClient::class.java, withSettings().defaultAnswer(RETURNS_DEEP_STUBS)).use { clients ->
                assertThat(pool.probeAddress(host, "192.0.2.9", 22)).isFalse()
                assertThat(clients.constructed()).isEmpty()
            }
            assertThat(configs.constructed()).isEmpty()
        }
        assertThat(pool.needsUserRetry(host.id)).isFalse()
        verify(hosts).getKnownHostsForHost(host.id)
        verifyNoMoreInteractions(hosts)
        verifyNoInteractions(challenges)
    }

    @Test
    fun failedProbeLeavesHostUnblockedAndUnchanged() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val hosts = mock(HostRepository::class.java)
        val challenges = mock(AgentChallenges::class.java)
        `when`(hosts.getKnownHostsForHost(host.id)).thenReturn(listOf(trustedHost(rsaKey(643))))
        val pool = pool(dispatcher, hosts, challenges)
        mockConstruction(DefaultConfig::class.java).use {
            mockConstruction(SSHClient::class.java, withSettings().defaultAnswer(RETURNS_DEEP_STUBS)) { client, _ ->
                doThrow(IOException("no ssh server")).`when`(client).connect("192.0.2.9", 22)
            }.use { clients ->
                assertThat(pool.probeAddress(host, "192.0.2.9", 22)).isFalse()
                assertThat(clients.constructed()).hasSize(1)
            }
        }
        assertThat(pool.needsUserRetry(host.id)).isFalse()
        verify(hosts).getKnownHostsForHost(host.id)
        verifyNoMoreInteractions(hosts)
        verifyNoInteractions(challenges)
    }

    @Test
    fun verifierAcceptsOnlyThePinnedFingerprint() {
        val trusted = rsaKey(643)
        val verifier = pinnedHostKeyVerifier(listOf(trustedHost(trusted)))
        assertThat(verifier.verify("192.0.2.9", 22, trusted)).isTrue()
        assertThat(verifier.verify("192.0.2.9", 22, rsaKey(2049))).isFalse()
        assertThat(pinnedHostKeyVerifier(emptyList()).verify("192.0.2.9", 22, trusted)).isFalse()
    }

    @Test
    fun redialAdoptsTheVerifiedCandidateAndAnnouncesIt() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val hosts = mock(HostRepository::class.java)
        val keys = mock(PubkeyRepository::class.java)
        val passwords = mock(SecurePasswordStorage::class.java)
        val challenges = mock(AgentChallenges::class.java)
        val addressStore = mock(AgentHostAddressStore::class.java)
        val discovery = mock(AgentLanSshDiscovery::class.java)
        `when`(hosts.findHostById(host.id)).thenReturn(host)
        `when`(hosts.getKnownHostsForHost(host.id)).thenReturn(listOf(trustedHost(rsaKey(643))))
        `when`(keys.getAll()).thenReturn(emptyList())
        `when`(passwords.getPassword(host.id)).thenReturn("secret")
        `when`(addressStore.candidates(host.id, host.port)).thenReturn(listOf(HostAddressRecord("192.0.2.9", host.port, 1L, AgentAddressSource.CONNECTED)))
        `when`(discovery.endpoints()).thenReturn(emptyList())
        val pool = pool(dispatcher, hosts, challenges, keys, passwords, addressStore, discovery)
        val changes = mutableListOf<HostAddressChange>()
        backgroundScope.launch(dispatcher) { pool.addressChanges.collect { changes += it } }
        runCurrent()
        mockConstruction(DefaultConfig::class.java).use {
            mockConstruction(SSHClient::class.java, withSettings().defaultAnswer(RETURNS_DEEP_STUBS)).use {
                pool.hostSession(host.id).exec("true")
                pool.reconnectDisconnected()
                ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
                pool.redialFailedHosts()
            }
        }
        runCurrent()
        // The adopted row carries the address alone: the host identity and the Agent target it holds are untouched.
        verify(hosts).saveHost(host.copy(hostname = "192.0.2.9"))
        verify(addressStore).record(host.id, "192.0.2.9", host.port, AgentAddressSource.REDISCOVERED)
        assertThat(changes).containsExactly(HostAddressChange(host.id, "192.0.2.1", "192.0.2.9"))
    }

    @Test
    fun emptyCandidatesNeverBlockOrProbe() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val hosts = mock(HostRepository::class.java)
        val keys = mock(PubkeyRepository::class.java)
        val passwords = mock(SecurePasswordStorage::class.java)
        val challenges = mock(AgentChallenges::class.java)
        val addressStore = mock(AgentHostAddressStore::class.java)
        val discovery = mock(AgentLanSshDiscovery::class.java)
        `when`(hosts.findHostById(host.id)).thenReturn(host)
        `when`(hosts.getKnownHostsForHost(host.id)).thenReturn(listOf(trustedHost(rsaKey(643))))
        `when`(keys.getAll()).thenReturn(emptyList())
        `when`(passwords.getPassword(host.id)).thenReturn("secret")
        `when`(addressStore.candidates(host.id, host.port)).thenReturn(emptyList())
        `when`(discovery.endpoints()).thenReturn(emptyList())
        val pool = pool(dispatcher, hosts, challenges, keys, passwords, addressStore, discovery)
        mockConstruction(DefaultConfig::class.java).use {
            mockConstruction(SSHClient::class.java, withSettings().defaultAnswer(RETURNS_DEEP_STUBS)).use { clients ->
                pool.hostSession(host.id).exec("true")
                pool.reconnectDisconnected()
                ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
                pool.redialFailedHosts()
                // Only the session connection is built; an empty candidate list must not dial anything.
                assertThat(clients.constructed()).hasSize(1)
            }
        }
        verify(addressStore).candidates(host.id, host.port)
        verify(discovery).endpoints()
        // Matchers would NPE on this non-null suspend parameter, so assert on the recorded invocations instead.
        assertThat(mockingDetails(hosts).invocations.count { it.method.name == "saveHost" }).isZero()
        verifyNoInteractions(challenges)
        assertThat(pool.needsUserRetry(host.id)).isFalse()
    }

    @Test
    fun addressEditedWhileTheProbeRunsIsNotOverwritten() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val hosts = mock(HostRepository::class.java)
        val keys = mock(PubkeyRepository::class.java)
        val passwords = mock(SecurePasswordStorage::class.java)
        val challenges = mock(AgentChallenges::class.java)
        val addressStore = mock(AgentHostAddressStore::class.java)
        val discovery = mock(AgentLanSshDiscovery::class.java)
        val edited = host.copy(hostname = "192.0.2.77")
        var userEdited = false
        `when`(hosts.findHostById(host.id)).thenAnswer { if (userEdited) edited else host }
        `when`(hosts.getKnownHostsForHost(host.id)).thenReturn(listOf(trustedHost(rsaKey(643))))
        `when`(keys.getAll()).thenReturn(emptyList())
        `when`(passwords.getPassword(host.id)).thenReturn("secret")
        `when`(addressStore.candidates(host.id, host.port)).thenAnswer {
            // The user saves a different address while the redial probe still runs.
            userEdited = true
            listOf(HostAddressRecord("192.0.2.9", host.port, 1L, AgentAddressSource.CONNECTED))
        }
        `when`(discovery.endpoints()).thenReturn(emptyList())
        val pool = pool(dispatcher, hosts, challenges, keys, passwords, addressStore, discovery)
        val changes = mutableListOf<HostAddressChange>()
        backgroundScope.launch(dispatcher) { pool.addressChanges.collect { changes += it } }
        runCurrent()
        mockConstruction(DefaultConfig::class.java).use {
            mockConstruction(SSHClient::class.java, withSettings().defaultAnswer(RETURNS_DEEP_STUBS)).use {
                pool.hostSession(host.id).exec("true")
                pool.reconnectDisconnected()
                ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
                pool.redialFailedHosts()
            }
        }
        runCurrent()
        // Matchers would NPE on this non-null suspend parameter, so assert on the recorded invocations instead.
        assertThat(mockingDetails(hosts).invocations.count { it.method.name == "saveHost" }).isZero()
        assertThat(changes).isEmpty()
        assertThat(pool.needsUserRetry(host.id)).isFalse()
    }

    private fun pool(
        dispatcher: CoroutineDispatcher,
        hosts: HostRepository,
        challenges: AgentChallenges,
        keys: PubkeyRepository = mock(PubkeyRepository::class.java),
        passwords: SecurePasswordStorage = mock(SecurePasswordStorage::class.java),
        addressStore: AgentHostAddressStore = mock(AgentHostAddressStore::class.java),
        discovery: AgentLanSshDiscovery = mock(AgentLanSshDiscovery::class.java),
    ): AgentSshPool = AgentSshPool(
        hosts,
        keys,
        passwords,
        challenges,
        mock(Context::class.java),
        dispatcher,
        AgentCryptoInitializer(dispatcher, dispatcher, { listener -> listener.onProviderLoaderSuccess() }, { }),
        addressStore,
        discovery,
    )

    private fun trustedHost(key: RSAPublicKey): KnownHost = KnownHost(
        hostId = host.id,
        hostname = host.hostname,
        port = host.port,
        hostKeyAlgo = "ssh-rsa",
        hostKey = encodeSshHostKey(key)!!.wire,
    )

    private fun rsaKey(modulusOffset: Long): RSAPublicKey {
        val modulus = BigInteger.ONE.shiftLeft(1023).add(BigInteger.valueOf(modulusOffset))
        return KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, BigInteger.valueOf(65537))) as RSAPublicKey
    }
}
