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

package org.connectbot.util

import org.connectbot.data.entity.KnownHost
import java.security.MessageDigest

internal enum class HostKeyTrust {
    FIRST_USE,
    TRUSTED,
    CHANGED,
}

internal fun hostKeyTrust(
    hostId: Long,
    known: List<KnownHost>,
    algorithm: String,
    wire: ByteArray,
): HostKeyTrust {
    val records = known.filter { it.hostId == hostId }
    if (records.isEmpty()) return HostKeyTrust.FIRST_USE
    val keyFamily = hostKeyFamily(algorithm)
    val matches = records.any {
        hostKeyFamily(it.hostKeyAlgo) == keyFamily && MessageDigest.isEqual(it.hostKey, wire)
    }
    return if (matches) HostKeyTrust.TRUSTED else HostKeyTrust.CHANGED
}

private fun hostKeyFamily(algorithm: String): String = when (algorithm) {
    "rsa-sha2-256", "rsa-sha2-512" -> "ssh-rsa"
    else -> algorithm
}
