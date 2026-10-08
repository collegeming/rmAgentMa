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

import org.connectbot.data.entity.KnownHost
import java.io.IOException
import java.security.MessageDigest

internal class AgentConnectionNeedsUser(message: String) : IOException(message)

internal class AgentReconnectPolicy {
    private data class Failure(val attempts: Int, val retryAt: Long)
    private val failures = mutableMapOf<Long, Failure>()
    private val blocked = mutableSetOf<Long>()

    @Synchronized
    fun failed(id: Long, now: Long) {
        val attempts = (failures[id]?.attempts ?: 0) + 1
        val seconds = (1L shl (attempts - 1).coerceAtMost(6)).coerceAtMost(60)
        failures[id] = Failure(attempts, now + seconds * 1000)
    }

    @Synchronized
    fun block(id: Long) {
        blocked.add(id)
    }

    @Synchronized
    fun isBlocked(id: Long): Boolean = id in blocked

    @Synchronized
    fun waiting(id: Long, now: Long): Long = ((failures[id]?.retryAt ?: now) - now).coerceAtLeast(0)

    @Synchronized
    fun hasFailure(id: Long): Boolean = id in failures

    @Synchronized
    fun userRetry(id: Long) {
        blocked.remove(id)
        failures.remove(id)
    }

    @Synchronized
    fun succeeded(id: Long) {
        failures.remove(id)
    }

    @Synchronized
    fun clear() {
        failures.clear()
        blocked.clear()
    }
}

internal fun trustedAgentHostKey(known: List<KnownHost>, algorithm: String, wire: ByteArray): Boolean {
    fun canonical(value: String): String = when (value) {
        "rsa-sha2-256", "rsa-sha2-512" -> "ssh-rsa"
        else -> value
    }
    return known.any { canonical(it.hostKeyAlgo) == canonical(algorithm) && MessageDigest.isEqual(it.hostKey, wire) }
}
