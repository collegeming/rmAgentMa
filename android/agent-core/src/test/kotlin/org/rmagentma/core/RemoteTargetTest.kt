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

package org.rmagentma.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64

class RemoteTargetTest {
    @Test
    fun posixHostKeepsTheCommandAndTheDefaultsSelectIt() {
        val command = "cd -- '/work' && exec kimi acp"
        assertEquals(command, RemoteTarget.Posix.shellCommand(command))
        assertTrue(remoteTargetOf(HOST_OS_LINUX, AGENT_TARGET_NATIVE) is RemoteTarget.Posix)
        // Rows written before this column existed hold no value at all and stay on their previous behavior.
        assertTrue(remoteTargetOf(null, null) is RemoteTarget.Posix)
        assertTrue(remoteTargetOf(HOST_OS_LINUX, "wsl:Ubuntu") is RemoteTarget.Posix)
    }

    @Test
    fun wslTargetRunsTheSameCommandInsideTheDistribution() {
        val command = "cd -- '/work' && exec sh -c 'echo \"quoted\" \$HOME'"
        val line = RemoteTarget.Wsl("Ubuntu-22.04").shellCommand(command)
        val encoded = Base64.getEncoder().encodeToString(command.toByteArray(Charsets.UTF_8))
        assertEquals(
            "wsl.exe --distribution \"Ubuntu-22.04\" -- /bin/sh -c \"eval \$(printf %s $encoded | base64 -d)\"",
            line,
        )
        // The command travels base64-encoded, so nothing the Windows shell would interpret survives in the line.
        assertFalse(line.contains("\$HOME"))
        assertFalse(line.contains("'"))
    }

    @Test
    fun windowsHostWithoutADistributionKeepsNoEntry() {
        val target = remoteTargetOf(HOST_OS_WINDOWS, AGENT_TARGET_NATIVE)
        assertTrue(target is RemoteTarget.WindowsNative)
        assertFalse(target.supported)
        assertNull(target.shellCommand("true"))
        assertTrue(remoteTargetOf(HOST_OS_WINDOWS, "wsl:ubuntu; rm -rf /") is RemoteTarget.WindowsNative)
        assertTrue(remoteTargetOf(HOST_OS_WINDOWS, null) is RemoteTarget.WindowsNative)
    }

    @Test
    fun wslDistributionNameIsAcceptedOnlyWhenQuotingCannotChangeIt() {
        assertTrue(validWslDistribution("Ubuntu"))
        assertTrue(validWslDistribution("Ubuntu-22.04 arm64"))
        assertFalse(validWslDistribution(""))
        assertFalse(validWslDistribution("Ubuntu \" -e calc"))
        assertFalse(validWslDistribution("Ubuntu\nexe"))
        assertFalse(validWslDistribution("a".repeat(65)))
    }

    @Test
    fun unsupportedTargetKeepsItsOwnProbeVerdictInsteadOfAConnectionFailure() = runBlocking {
        val commands = mutableListOf<String>()
        val host = object : HostSession {
            override val hostId = 7L
            override suspend fun exec(command: String): ExecChannel {
                commands += command
                return object : ExecChannel {
                    override val stdout = ByteArrayInputStream(ByteArray(0))
                    override val stdin = ByteArrayOutputStream()
                    override val stderr = ByteArrayInputStream(ByteArray(0))
                    override fun close() = Unit
                }
            }
        }
        val rows = RemoteAgentProbe().probe(host, target = RemoteTarget.WindowsNative)
        assertEquals(AgentKind.entries.size, rows.size)
        assertTrue(rows.all { !it.available && it.error == WINDOWS_NATIVE_UNSUPPORTED })
        assertTrue(commands.isEmpty())
    }
}
