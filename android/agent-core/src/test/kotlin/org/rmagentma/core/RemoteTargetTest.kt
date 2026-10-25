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
    fun windowsHostWithoutADistributionRunsTheWindowsRelay() {
        val target = remoteTargetOf(HOST_OS_WINDOWS, AGENT_TARGET_WINDOWS)
        assertTrue(target is RemoteTarget.WindowsNative)
        assertTrue(target.supported)
        // The target is entered, but it is not a POSIX shell: callers must not hand it a POSIX command line.
        assertFalse(target.posixCommands)
        assertEquals(WINDOWS_NATIVE_ID, target.id)
        // A row written before the `windows` literal existed names the same shell context.
        assertTrue(remoteTargetOf(HOST_OS_WINDOWS, AGENT_TARGET_NATIVE) is RemoteTarget.WindowsNative)
        assertTrue(remoteTargetOf(HOST_OS_WINDOWS, "wsl:ubuntu; rm -rf /") is RemoteTarget.WindowsNative)
        assertTrue(remoteTargetOf(HOST_OS_WINDOWS, null) is RemoteTarget.WindowsNative)
    }

    @Test
    fun windowsRelayCarriesTheExactCommandIntoTheWindowsDecoder() {
        val command = "where.exe kimi 2>\$null | Select-Object -First 1"
        val line = RemoteTarget.WindowsNative.shellCommand(command)
        assertTrue(line.startsWith("powershell.exe -NoProfile -NonInteractive -EncodedCommand "))
        val payload = line.substringAfter("-EncodedCommand ")
        // PowerShell's own decoder reads base64 of the text as UTF-16LE, so the text comes back byte for byte.
        assertEquals(command, String(Base64.getDecoder().decode(payload), Charsets.UTF_16LE))
        // cmd.exe parses the line first, and base64 carries nothing it would interpret.
        assertFalse(payload.any { it in " \"'&|<>^%" })
    }

    @Test
    fun theWindowsLookupNamesEveryAgentAndReportsItsOwnRelay() {
        val command = windowsLookupCommand(AgentKind.entries)
        // `where` is spelled out, because PowerShell resolves the bare word to its own Where-Object alias.
        assertTrue(command.contains("where.exe"))
        for (kind in AgentKind.entries) assertTrue(command.contains("'${kind.command}'"))
        assertTrue(command.startsWith("if (-not (Get-Command where.exe -ErrorAction SilentlyContinue)) { '$WINDOWS_LOOKUP_UNAVAILABLE'; exit 0 }"))
    }

    @Test
    fun onlyAPathWindowsCanAddressIsAWindowsAbsolutePath() {
        assertTrue(isWindowsAbsolutePath("C:\\Users\\colle\\kimi.exe"))
        assertTrue(isWindowsAbsolutePath("c:/work"))
        assertTrue(isWindowsAbsolutePath("\\\\server\\share\\kimi.exe"))
        assertFalse(isWindowsAbsolutePath("/usr/bin/kimi"))
        assertFalse(isWindowsAbsolutePath("kimi.exe"))
        assertFalse(isWindowsAbsolutePath("1:\\work"))
        assertFalse(isWindowsAbsolutePath("\\\\server"))
        assertFalse(isWindowsAbsolutePath("C:\\work\nbad"))
        assertTrue(isAbsoluteAgentPath("/usr/bin/kimi"))
        assertTrue(isAbsoluteAgentPath("C:\\work\\kimi.exe"))
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

    /**
     * A Windows target is probed on the Windows side, and only a path Windows really resolved is reported.
     */
    @Test
    fun windowsTargetReportsThePathsTheLookupFound() = runBlocking {
        val commands = mutableListOf<String>()
        val answer = "missing kimi\nfound opencode C:\\Users\\colle\\opencode.exe\nmissing dsh\nmissing omp\nmissing zcode\n"
        val host = object : HostSession {
            override val hostId = 9L
            override suspend fun exec(command: String): ExecChannel {
                commands += command
                return object : ExecChannel {
                    override val stdout = ByteArrayInputStream(answer.toByteArray())
                    override val stdin = ByteArrayOutputStream()
                    override val stderr = ByteArrayInputStream(ByteArray(0))
                    override fun close() = Unit
                }
            }
        }
        val rows = RemoteAgentProbe().probe(host, target = RemoteTarget.WindowsNative)
        assertEquals(AgentKind.entries.size, rows.size)
        val found = rows.single { it.kind == AgentKind.OPENCODE }
        assertTrue(found.available)
        assertEquals("C:\\Users\\colle\\opencode.exe", found.executable)
        // Nothing ran on that path, so no version and no protocol capability is claimed for it.
        assertEquals("", found.version)
        assertNull(found.capabilities)
        val missing = rows.single { it.kind == AgentKind.KIMI }
        assertFalse(missing.available)
        assertEquals("executable_missing", missing.error)

        // The lookup is the Windows text itself, which the target relays for the host.
        val command = commands.single()
        assertTrue(command.contains("where.exe"))
        assertTrue(command.contains("foreach"))
    }

    /**
     * A relay that never answered is the probe's own verdict having no answer to read, not a host that holds no
     * installation and not a target without an entry in this app.
     */
    @Test
    fun aWindowsRelayThatNeverAnsweredIsReportedAsAVerdictThisAppCannotRead() = runBlocking {
        val host = object : HostSession {
            override val hostId = 9L
            override suspend fun exec(command: String): ExecChannel = object : ExecChannel {
                override val stdout = ByteArrayInputStream("'powershell.exe' is not recognized as an internal or external command".toByteArray())
                override val stdin = ByteArrayOutputStream()
                override val stderr = ByteArrayInputStream(ByteArray(0))
                override fun close() = Unit
            }
        }
        val rows = RemoteAgentProbe().probe(host, target = RemoteTarget.WindowsNative)
        assertTrue(rows.all { !it.available && it.error == WINDOWS_NATIVE_UNSUPPORTED })

        // A path the app cannot address as a Windows path is not an installation it may launch either.
        val unusable = "found kimi kimi.exe\nmissing opencode\nmissing dsh\nmissing omp\nmissing zcode\n"
        val unreadable = object : HostSession {
            override val hostId = 9L
            override suspend fun exec(command: String): ExecChannel = object : ExecChannel {
                override val stdout = ByteArrayInputStream(unusable.toByteArray())
                override val stdin = ByteArrayOutputStream()
                override val stderr = ByteArrayInputStream(ByteArray(0))
                override fun close() = Unit
            }
        }
        assertTrue(RemoteAgentProbe().probe(unreadable, target = RemoteTarget.WindowsNative).all { !it.available })
    }

    /** A Windows lookup that never reached the host is a connection failure, not a missing installation. */
    @Test
    fun aWindowsLookupThatNeverReachedTheHostReportsItsOwnTransportFailure() = runBlocking {
        val host = object : HostSession {
            override val hostId = 9L
            override suspend fun exec(command: String): ExecChannel = throw java.io.IOException("Host 9 was deleted")
        }
        val rows = RemoteAgentProbe().probe(host, target = RemoteTarget.WindowsNative)
        assertEquals(AgentKind.entries.size, rows.size)
        assertTrue(rows.all { !it.available && it.error == "probe_transport_failed" })
    }
}
