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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.connectbot.util.ProviderLoaderListener
import org.junit.Test
import java.io.IOException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AgentCryptoInitializerTest {
    @Test
    fun synchronousSuccessLoadsAndConfiguresOnlyOnce() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val events = mutableListOf<String>()
        val initializer = AgentCryptoInitializer(
            dispatcher,
            dispatcher,
            { listener ->
                events += "load"
                listener.onProviderLoaderSuccess()
                listener.onProviderLoaderError()
                listener.onProviderLoaderSuccess()
            },
            { events += "configure" },
        )
        initializer.ensureReady()
        initializer.ensureReady()
        assertThat(events).containsExactly("load", "configure")
    }

    @Test
    fun concurrentCallsWaitForAsynchronousSuccess() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val callbacks = mutableListOf<ProviderLoaderListener>()
        var configurations = 0
        val initializer = AgentCryptoInitializer(dispatcher, dispatcher, callbacks::add, { configurations++ })
        val callers = List(3) { async { initializer.ensureReady() } }
        runCurrent()
        assertThat(callbacks).hasSize(1)
        assertThat(configurations).isZero()
        assertThat(callers.all { !it.isCompleted }).isTrue()
        callbacks.single().onProviderLoaderSuccess()
        callers.forEach { it.await() }
        assertThat(configurations).isEqualTo(1)
        assertThat(callbacks).hasSize(1)
    }

    @Test
    fun callbackErrorIsSurfacedAndNextCallRetries() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var attempts = 0
        var configurations = 0
        val initializer = AgentCryptoInitializer(
            dispatcher,
            dispatcher,
            { listener ->
                if (++attempts == 1) listener.onProviderLoaderError() else listener.onProviderLoaderSuccess()
            },
            { configurations++ },
        )
        val failure = runCatching { initializer.ensureReady() }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IOException::class.java).hasMessage("Agent crypto provider installation failed")
        assertThat(configurations).isZero()
        initializer.ensureReady()
        assertThat(attempts).isEqualTo(2)
        assertThat(configurations).isEqualTo(1)
    }

    @Test
    fun thrownLoaderAndConfigurationFailuresCanRetry() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val failure = IOException("failure")
        var attempts = 0
        var configurations = 0
        val initializer = AgentCryptoInitializer(
            dispatcher,
            dispatcher,
            { listener ->
                if (++attempts == 1) throw failure
                listener.onProviderLoaderSuccess()
            },
            { if (++configurations == 1) throw failure },
        )
        assertThat(runCatching { initializer.ensureReady() }.exceptionOrNull()).isInstanceOf(IOException::class.java).hasMessage(failure.message)
        assertThat(runCatching { initializer.ensureReady() }.exceptionOrNull()).isInstanceOf(IOException::class.java).hasMessage(failure.message)
        initializer.ensureReady()
        initializer.ensureReady()
        assertThat(attempts).isEqualTo(3)
        assertThat(configurations).isEqualTo(2)
    }

    @Test
    fun timeoutIsSurfacedAndOldCallbackCannotCompleteRetry() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val callbacks = mutableListOf<ProviderLoaderListener>()
        var configurations = 0
        val initializer = AgentCryptoInitializer(dispatcher, dispatcher, callbacks::add, { configurations++ }, 100)
        val first = async { runCatching { initializer.ensureReady() }.exceptionOrNull() }
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        assertThat(first.await()).isInstanceOf(IOException::class.java).hasMessage("Agent crypto provider installation timed out")
        val retry = async { initializer.ensureReady() }
        runCurrent()
        callbacks.first().onProviderLoaderSuccess()
        runCurrent()
        assertThat(retry.isCompleted).isFalse()
        assertThat(configurations).isZero()
        callbacks.last().onProviderLoaderSuccess()
        retry.await()
        assertThat(configurations).isEqualTo(1)
    }

    @Test
    fun cancellingFirstCallerAllowsQueuedCallerToRetryWithItsOwnCallback() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val callbacks = mutableListOf<ProviderLoaderListener>()
        var configurations = 0
        val initializer = AgentCryptoInitializer(dispatcher, dispatcher, callbacks::add, { configurations++ })
        val first = launch { initializer.ensureReady() }
        val queued = async { initializer.ensureReady() }
        runCurrent()
        first.cancel()
        first.join()
        runCurrent()
        assertThat(callbacks).hasSize(2)
        callbacks.first().onProviderLoaderSuccess()
        callbacks.first().onProviderLoaderError()
        runCurrent()
        assertThat(queued.isCompleted).isFalse()
        assertThat(configurations).isZero()
        callbacks.last().onProviderLoaderSuccess()
        queued.await()
        assertThat(configurations).isEqualTo(1)
    }

    @Test
    fun cancellingQueuedCallerDoesNotCancelActiveInitialization() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val callbacks = mutableListOf<ProviderLoaderListener>()
        val initializer = AgentCryptoInitializer(dispatcher, dispatcher, callbacks::add, {})
        val active = async { initializer.ensureReady() }
        val queued = launch { initializer.ensureReady() }
        runCurrent()
        queued.cancel()
        queued.join()
        callbacks.single().onProviderLoaderSuccess()
        active.await()
        initializer.ensureReady()
        assertThat(callbacks).hasSize(1)
    }

    @Test
    fun callerDeadlineRemainsCancellationRatherThanProviderFailure() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val initializer = AgentCryptoInitializer(dispatcher, dispatcher, {}, {})
        val failure = runCatching { withTimeout(100) { initializer.ensureReady() } }.exceptionOrNull()
        assertThat(failure).isInstanceOf(CancellationException::class.java)
    }

    @Test
    fun usesSeparateInjectedDispatchersForLoaderAndSshConfiguration(): Unit = kotlinx.coroutines.runBlocking {
        java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "provider-loader") }
            .asCoroutineDispatcher().use { loaderDispatcher ->
                java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "ssh-configuration") }
                    .asCoroutineDispatcher().use { configurationDispatcher ->
                        val threads = mutableListOf<String>()
                        val initializer = AgentCryptoInitializer(
                            loaderDispatcher,
                            configurationDispatcher,
                            { listener ->
                                threads += Thread.currentThread().name.substringBefore(" @coroutine#")
                                listener.onProviderLoaderSuccess()
                            },
                            { threads += Thread.currentThread().name.substringBefore(" @coroutine#") },
                        )
                        initializer.ensureReady()
                        assertThat(threads).containsExactly("provider-loader", "ssh-configuration")
                    }
            }
    }
}
