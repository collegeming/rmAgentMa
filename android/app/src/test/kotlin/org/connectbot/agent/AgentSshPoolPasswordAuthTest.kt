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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.schmizz.sshj.SSHClient
import org.assertj.core.api.Assertions.assertThat
import org.connectbot.data.HostRepository
import org.connectbot.data.PubkeyRepository
import org.connectbot.data.entity.Host
import org.connectbot.util.ProviderLoaderListener
import org.connectbot.util.SecurePasswordStorage
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.RETURNS_DEEP_STUBS
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.Mockito.withSettings
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A host that authenticates with a password uses the saved one, without a key attempt and without a prompt. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AgentSshPoolPasswordAuthTest {
    @Test
    fun aSavedPasswordIsUsedWithoutTryingAKeyOrAskingTheUser() = runBlocking {
        withTimeout(30_000) {
            val keys = mock(PubkeyRepository::class.java)
            val passwords = mock(SecurePasswordStorage::class.java)
            `when`(passwords.getPassword(1L)).thenReturn("saved-secret")
            val challenges = AgentChallenges()
            val pool = pool(
                host = Host(id = 1L, hostname = "work.test", username = "colle", authMethod = Host.AUTH_METHOD_PASSWORD),
                keys = keys,
                passwords = passwords,
                challenges = challenges,
            )
            mockConstruction(SSHClient::class.java, withSettings().defaultAnswer(RETURNS_DEEP_STUBS)).use { clients ->
                // The link itself is left to the mock; what is asserted here is how it authenticated. The key
                // repository is left unstubbed on purpose: an attempt to read a key for this host would fail
                // before the saved password is used, which the verification below would catch.
                runCatching { pool.hostSession(1L).exec("true") }
                val client = clients.constructed().single()
                verify(client).authPassword(eq("colle"), any())
                verify(passwords).getPassword(1L)
                // No prompt is waiting for the user either: the saved password is everything this host needs.
                assertThat(challenges.challenge.value).isNull()
            }
        }
    }

    @Test
    fun aHostThatAuthenticatesWithAKeyKeepsTheKeyPathAndItsSavedPasswordFallback() = runBlocking {
        withTimeout(30_000) {
            val keys = mock(PubkeyRepository::class.java)
            `when`(keys.getAll()).thenReturn(emptyList())
            val passwords = mock(SecurePasswordStorage::class.java)
            `when`(passwords.getPassword(1L)).thenReturn("saved-secret")
            val challenges = AgentChallenges()
            val pool = pool(
                host = Host(id = 1L, hostname = "work.test", username = "colle"),
                keys = keys,
                passwords = passwords,
                challenges = challenges,
            )
            mockConstruction(SSHClient::class.java, withSettings().defaultAnswer(RETURNS_DEEP_STUBS)).use { clients ->
                runCatching { pool.hostSession(1L).exec("true") }
                val client = clients.constructed().single()
                // The key path ran first and found no key, so the saved password is what authenticated.
                verify(client).authPassword(eq("colle"), any())
                assertThat(challenges.challenge.value).isNull()
            }
        }
    }

    private fun pool(
        host: Host,
        keys: PubkeyRepository,
        passwords: SecurePasswordStorage,
        challenges: AgentChallenges,
    ): AgentSshPool {
        val hosts = mock(HostRepository::class.java)
        `when`(hosts.findHostById(host.id)).thenReturn(host)
        `when`(hosts.getKnownHostsForHost(host.id)).thenReturn(emptyList())
        return AgentSshPool(
            hosts,
            keys,
            passwords,
            challenges,
            mock(Context::class.java),
            Dispatchers.IO,
            AgentCryptoInitializer(Dispatchers.IO, Dispatchers.IO, { listener: ProviderLoaderListener -> listener.onProviderLoaderSuccess() }, {}),
            mock(AgentHostAddressStore::class.java),
            mock(AgentLanSshDiscovery::class.java),
        )
    }
}
