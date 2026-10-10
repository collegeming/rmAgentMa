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
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import org.assertj.core.api.Assertions.assertThat
import org.connectbot.data.HostRepository
import org.connectbot.data.PubkeyRepository
import org.connectbot.data.entity.Host
import org.connectbot.util.ProviderLoaderListener
import org.connectbot.util.SecurePasswordStorage
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.RETURNS_DEEP_STUBS
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.Mockito.withSettings
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AgentSshPoolCryptoTest {
    @Test
    fun connectWaitsForProviderAndSshConfigurationBeforeConstructingDefaultConfig() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val hosts = mock(HostRepository::class.java)
        `when`(hosts.findHostById(1)).thenReturn(Host(id = 1, hostname = "test.invalid"))
        var listener: ProviderLoaderListener? = null
        var configured = false
        val initializer = AgentCryptoInitializer(dispatcher, dispatcher, { listener = it }, { configured = true })
        val pool = AgentSshPool(
            hosts,
            mock(PubkeyRepository::class.java),
            mock(SecurePasswordStorage::class.java),
            AgentChallenges(),
            mock(Context::class.java),
            dispatcher,
            initializer,
        )
        val failure = IOException("stop before network")
        mockConstruction(DefaultConfig::class.java) { _, _ -> assertThat(configured).isTrue() }.use { configs ->
            mockConstruction(SSHClient::class.java, withSettings().defaultAnswer(RETURNS_DEEP_STUBS)) { client, _ ->
                doThrow(failure).`when`(client).connect("test.invalid", 22)
            }.use { clients ->
                val result = async { runCatching { pool.hostSession(1).exec("true") }.exceptionOrNull() }
                runCurrent()
                assertThat(configs.constructed()).isEmpty()
                assertThat(clients.constructed()).isEmpty()
                assertThat(listener).isNotNull()
                listener!!.onProviderLoaderSuccess()
                assertThat(result.await()).isInstanceOf(IOException::class.java).hasMessage(failure.message)
                assertThat(configs.constructed()).hasSize(1)
                verify(configs.constructed().single()).keepAliveProvider = KeepAliveProvider.KEEP_ALIVE
                assertThat(clients.constructed()).hasSize(1)
            }
        }
    }

    @Test
    fun providerFailurePreventsDefaultConfigAndNextConnectRetries() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val hosts = mock(HostRepository::class.java)
        `when`(hosts.findHostById(1)).thenReturn(Host(id = 1))
        var attempts = 0
        val initializer = AgentCryptoInitializer(
            dispatcher,
            dispatcher,
            { listener ->
                attempts++
                listener.onProviderLoaderError()
            },
            { error("must not configure sshj after provider failure") },
        )
        val pool = AgentSshPool(
            hosts,
            mock(PubkeyRepository::class.java),
            mock(SecurePasswordStorage::class.java),
            AgentChallenges(),
            mock(Context::class.java),
            dispatcher,
            initializer,
        )
        mockConstruction(DefaultConfig::class.java).use { configs ->
            repeat(2) {
                val failure = runCatching { pool.hostSession(1).exec("true") }.exceptionOrNull()
                assertThat(failure).isInstanceOf(IOException::class.java).hasMessage("Agent crypto provider installation failed")
            }
            assertThat(attempts).isEqualTo(2)
            assertThat(configs.constructed()).isEmpty()
            assertThat(pool.needsUserRetry(1)).isFalse()
        }
    }
}
