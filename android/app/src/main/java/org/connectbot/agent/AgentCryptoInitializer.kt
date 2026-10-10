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
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.schmizz.sshj.common.SecurityUtils
import org.connectbot.BuildConfig
import org.connectbot.di.CoroutineDispatchers
import org.connectbot.util.ProviderLoader
import org.connectbot.util.ProviderLoaderListener
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AgentCryptoInitializer internal constructor(
    private val loaderDispatcher: CoroutineDispatcher,
    private val configurationDispatcher: CoroutineDispatcher,
    private val loadProvider: (ProviderLoaderListener) -> Unit,
    private val configureSsh: () -> Unit,
    private val timeoutMillis: Long = 20_000,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        dispatchers: CoroutineDispatchers,
    ) : this(
        loaderDispatcher = if (BuildConfig.FLAVOR == "google") dispatchers.main else dispatchers.io,
        configurationDispatcher = dispatchers.io,
        loadProvider = { listener -> ProviderLoader.load(context, listener) },
        configureSsh = {
            // setSecurityProvider resets sshj's BouncyCastle flag; opt out afterwards.
            SecurityUtils.setSecurityProvider(null)
            SecurityUtils.setRegisterBouncyCastle(false)
        },
    )

    private val mutex = Mutex()
    private var ready = false

    suspend fun ensureReady() = mutex.withLock {
        if (ready) return@withLock
        val installed = CompletableDeferred<Unit>()
        try {
            withTimeoutOrNull(timeoutMillis) {
                withContext(loaderDispatcher) {
                    loadProvider(object : ProviderLoaderListener {
                        override fun onProviderLoaderSuccess() {
                            installed.complete(Unit)
                        }

                        override fun onProviderLoaderError() {
                            installed.completeExceptionally(IOException("Agent crypto provider installation failed"))
                        }
                    })
                }
                installed.await()
                withContext(configurationDispatcher) { configureSsh() }
            } ?: throw IOException("Agent crypto provider installation timed out")
            currentCoroutineContext().ensureActive()
            ready = true
        } finally {
            installed.cancel()
        }
    }
}
