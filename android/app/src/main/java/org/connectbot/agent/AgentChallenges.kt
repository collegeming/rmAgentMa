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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class AgentChallenge(
    val id: String,
    val hostName: String,
    val kind: String,
    val message: String,
    val secret: Boolean = false,
)

@Singleton
class AgentChallenges @Inject constructor() {
    private val mutex = Mutex()
    private val state = MutableStateFlow<AgentChallenge?>(null)
    val challenge = state.asStateFlow()
    private val pending = mutableMapOf<String, CompletableDeferred<String?>>()

    suspend fun ask(hostName: String, kind: String, message: String, secret: Boolean = false): String = mutex.withLock {
        val request = AgentChallenge(UUID.randomUUID().toString(), hostName, kind, message, secret)
        val answer = CompletableDeferred<String?>()
        synchronized(pending) { pending[request.id] = answer }
        state.value = request
        try {
            answer.await() ?: throw AgentConnectionNeedsUser("Authentication cancelled")
        } finally {
            synchronized(pending) { pending.remove(request.id) }
            if (state.value?.id == request.id) state.value = null
        }
    }

    fun respond(id: String, response: String?) {
        synchronized(pending) { pending[id] }?.complete(response)
    }

    fun cancelAll() {
        synchronized(pending) { pending.values.toList() }.forEach { it.complete(null) }
    }
}
