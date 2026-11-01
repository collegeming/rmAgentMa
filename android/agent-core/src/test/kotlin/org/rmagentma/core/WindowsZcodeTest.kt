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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64

class WindowsZcodeTest {
    private val executable = "E:\\SoftWare\\ZCode\\ZCode.exe"
    private val cwd = "C:\\work dir\\project"

    @Test
    fun zcodeKeepsTheWhereLookupAndAddsItsRegistryFallback() {
        val command = windowsLookupCommand(AgentKind.entries)
        // Every Agent keeps the lookup that was there; only zcode gains a fallback after it.
        for (kind in AgentKind.entries) assertTrue(command.contains("'${kind.command}'"))
        assertTrue(command.contains("where.exe \$name"))
        assertTrue(command.contains("FindRmzZcode"))
        assertTrue(command.contains("HKCU:\\Software\\Classes\\zcode\\shell\\open\\command"))
        assertTrue(command.contains("HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall"))
        assertTrue(command.contains("\$q.UninstallString"))
        assertTrue(command.contains("\$q.DisplayIcon"))
        assertTrue(command.contains("\$name -eq 'zcode'"))
        // The line format the probe parses is unchanged in all three states.
        assertTrue(command.contains("found zcode "))
        assertTrue(command.contains("missing zcode"))
        assertTrue(command.contains("probe_unavailable zcode"))
    }

    @Test
    fun otherAgentsKeepThePlainWhereLookup() {
        val command = windowsLookupCommand(listOf(AgentKind.KIMI, AgentKind.OPENCODE))
        assertFalse(command.contains("FindRmzZcode"))
        assertFalse(command.contains("HKCU:"))
        assertFalse(command.contains("zcode"))
        assertEquals(
            "if (-not (Get-Command where.exe -ErrorAction SilentlyContinue)) { 'probe_unavailable'; exit 0 }; " +
                "foreach (\$name in @('kimi','opencode')) { \$found = @(where.exe \$name 2>\$null)[0]; " +
                "if (\$found) { \"found \$name \$found\" } else { \"missing \$name\" } }",
            command,
        )
    }

    @Test
    fun theRegistryFallbackNeverReportsAPathItCannotAddress() {
        val command = windowsLookupCommand(listOf(AgentKind.ZCODE))
        // Only an absolute path is printed, and only when it is ZCode.exe inside an installation that exists.
        assertTrue(command.contains("RmzPath"))
        assertTrue(command.contains("^[A-Za-z]:[\\\\/]"))
        assertTrue(command.contains("'ZCode.exe'"))
        assertTrue(command.contains("-PathType Leaf"))
        // An answer nobody could read stays distinct from an installation that is really absent.
        assertTrue(command.contains("return ''"))
        assertTrue(command.contains("if(\$z -eq ''){ 'probe_unavailable zcode' }"))
    }

    /** The fallback is text, not trust: a path the relay prints is only used when it is absolute. */
    @Test
    fun theProbeTakesTheRegistryFallbackPathWhenItIsAbsolute() = runBlocking {
        val answer = "missing kimi\nmissing opencode\nmissing dsh\nmissing omp\nfound zcode E:\\SoftWare\\ZCode\\ZCode.exe\n"
        val rows = RemoteAgentProbe().probe(FixedAnswerHost(answer), target = RemoteTarget.WindowsNative)
        val zcode = rows.single { it.kind == AgentKind.ZCODE }
        assertTrue(zcode.available)
        assertEquals(executable, zcode.executable)

        val unavailable = RemoteAgentProbe().probe(
            FixedAnswerHost("missing kimi\nmissing opencode\nmissing dsh\nmissing omp\nprobe_unavailable zcode\n"),
            target = RemoteTarget.WindowsNative,
        )
        // The registry could not be read, so zcode has no verdict; the other Agents keep their own answers.
        val unresolved = unavailable.single { it.kind == AgentKind.ZCODE }
        assertFalse(unresolved.available)
        assertEquals(WINDOWS_NATIVE_UNSUPPORTED, unresolved.error)
        assertFalse(unavailable.single { it.kind == AgentKind.KIMI }.available)
        assertFalse(unavailable.single { it.kind == AgentKind.KIMI }.error == WINDOWS_NATIVE_UNSUPPORTED)

        // A relative path is not a path the app may launch, so the whole answer stays unreadable.
        val relative = RemoteAgentProbe().probe(
            FixedAnswerHost("missing kimi\nmissing opencode\nmissing dsh\nmissing omp\nfound zcode ZCode.exe\n"),
            target = RemoteTarget.WindowsNative,
        )
        assertTrue(relative.all { !it.available && it.error == WINDOWS_NATIVE_UNSUPPORTED })
    }

    @Test
    fun theLaunchCommandKeepsItsEntryAndProviderBesideItself() {
        val command = windowsZcodeCommand(executable, cwd)
        assertTrue(command.contains("Join-Path \$env:LOCALAPPDATA 'rmagentma\\zcode'"))
        assertTrue(command.contains("\$entry = Join-Path \$base 'entry.cjs'"))
        assertTrue(command.contains("[IO.File]::WriteAllText(\$entry"))
        // The entry references the installation's own CLI, so the installation is never written to.
        assertTrue(command.contains("'require(''E:\\\\SoftWare\\\\ZCode\\\\resources\\\\glm\\\\zcode.cjs'')'"))
        assertTrue(command.contains("\$provider = Join-Path \$base 'provider'"))
        assertTrue(command.contains("Join-Path \$env:USERPROFILE '.zcode\\v2\\runtime\\provider\\windows-x86_64'"))
        assertTrue(command.contains("'zcode-builtin.json'"))
        // Newest version wins, and an endpoint is only used when it really carries the provider config.
        assertTrue(command.contains("Sort-Object { [version]\$_.Name } -Descending"))
        assertTrue(command.contains("Filter 'endpoint-*'"))
        assertTrue(command.contains("-PathType Leaf"))
        // The junction is reused when it already points there and rebuilt when it points at an older version.
        assertTrue(command.contains("ReparsePoint"))
        assertTrue(command.contains("[IO.Directory]::Delete(\$provider, \$false)"))
        assertTrue(command.contains("New-Item -ItemType Junction -Path \$provider -Target \$target"))
        // Nothing below the installation directory is created, copied or removed.
        for (write in listOf("Copy-Item", "Set-Content -LiteralPath \$provider", "Remove-Item", "New-Item -ItemType Directory -Path \$cli")) {
            assertFalse(command, command.contains(write))
        }
    }

    @Test
    fun theLaunchCommandRunsTheCliThroughTheElectronBinaryWithAPipeFreeStart() {
        val command = windowsZcodeCommand(executable, cwd)
        assertTrue(command.contains("\$env:ELECTRON_RUN_AS_NODE = '1'"))
        assertTrue(command.contains("\$start.FileName = 'E:\\SoftWare\\ZCode\\ZCode.exe'"))
        assertTrue(command.contains("app-server"))
        assertTrue(command.contains("--mode build"))
        // The protocol is the channel's own stdin and stdout, so nothing may capture either of them.
        assertTrue(command.contains("[System.Diagnostics.Process]::Start(\$start)"))
        for (redirect in listOf("RedirectStandardInput", "RedirectStandardOutput", "RedirectStandardError", "| Out-String", "| ForEach-Object", "-WindowStyle")) {
            assertFalse(command, command.contains(redirect))
        }
        // The `&` operator drops input written to a piped stdin after the process started, so it is not used.
        assertFalse(command, Regex("(?m)^\\s*&\\s").containsMatchIn(command))
    }

    @Test
    fun theWorkingDirectoryTravelsAsAnArgumentAndAsTheProcessDirectory() {
        val command = windowsZcodeCommand(executable, cwd)
        assertEquals(1, countOf(command, "\$start.WorkingDirectory = 'C:\\work dir\\project'"))
        assertTrue(command.contains("--cwd \"C:\\work dir\\project\""))
        // A path with no space travels unquoted and still lands as one argument.
        assertTrue(windowsZcodeCommand(executable, "C:\\work").contains("--cwd C:\\work --mode build"))
    }

    @Test
    fun aCommandLineWithAnApostropheOrAQuoteIsQuotedForWindows() {
        // A value the Windows command line must quote keeps its apostrophe as itself; PowerShell never sees it.
        val quoted = windowsArgument("C:\\it's here")
        assertEquals("\"C:\\it's here\"", quoted)
        assertEquals("C:\\plain", windowsArgument("C:\\plain"))
        assertEquals("C:\\dir\\", windowsArgument("C:\\dir\\"))
        assertEquals("\"C:\\dir with space\\\\\"", windowsArgument("C:\\dir with space\\"))
        assertEquals("\"C:\\a\\\\\\\"b\"", windowsArgument("C:\\a\\\"b"))
        val command = windowsZcodeCommand(executable, "C:\\it's a \"dir\"\\")
        assertTrue(command, command.contains("\$start.WorkingDirectory = 'C:\\it''s a \"dir\"\\'"))
    }

    @Test
    fun aPathTheAppCannotAddressIsRefusedBeforeAnyScriptExists() {
        for (path in listOf("ZCode.exe", "\\ZCode.exe", "C:ZCode.exe", "C:\\SoftWare\\zcode.cjs", "C:\\work\nZCode.exe")) {
            assertRefused { windowsZcodeCommand(path, cwd) }
        }
        for (path in listOf("/work", "work", "", "C:work", "\\\\server", "C:\\work\u0000bad", "C:\\work\nbad")) {
            assertRefused { windowsZcodeCommand(executable, path) }
        }
    }

    @Test
    fun aHostileValueStaysInsideItsLiteral() {
        val command = windowsZcodeCommand(executable, "C:\\dir'; Write-Output 'pwned'; '")
        // The apostrophes are doubled, so the injected command stays text inside the literal.
        assertTrue(command.contains("\$start.WorkingDirectory = 'C:\\dir''; Write-Output ''pwned''; '''"))
        assertFalse(command.contains("\nWrite-Output 'pwned'"))
        // The installation directory is derived from the executable alone; the script cannot name another one.
        assertTrue(command.contains("'require(''E:\\\\SoftWare\\\\ZCode\\\\resources\\\\glm\\\\zcode.cjs'')'"))
    }

    /** A host with no runtime, a foreign `provider`, and a missing CLI entry each stop with their own code. */
    @Test
    fun theLaunchCommandStopsOnItsOwnCodesInsteadOfGuessing() {
        val command = windowsZcodeCommand(executable, cwd)
        for (code in listOf("rmagentma_zcode_cli_missing", "rmagentma_zcode_provider_missing", "rmagentma_zcode_provider_conflict", "rmagentma_zcode_provider_unlinked")) {
            assertTrue(code, command.contains("[Console]::Error.WriteLine('$code')"))
        }
        // A provider that is a plain directory is refused rather than deleted: only a junction is replaced.
        assertTrue(command.contains("ReparsePoint"))
        assertTrue(command.contains("exit \$process.ExitCode"))
    }

    /**
     * The text the relay decodes is the text above, and the whole encoded line stays inside the Windows command
     * line limit that `powershell.exe -EncodedCommand` travels on.
     */
    @Test
    fun theRelayedTextIsTheScriptAndFitsTheWindowsCommandLine() {
        val line = RemoteTarget.WindowsNative.shellCommand(windowsZcodeCommand(executable, cwd))
        assertTrue(line.startsWith("powershell.exe -NoProfile -NonInteractive -EncodedCommand "))
        val payload = line.substringAfter("-EncodedCommand ")
        assertTrue(String(Base64.getDecoder().decode(payload), Charsets.UTF_16LE).startsWith("\$ErrorActionPreference = 'Stop'"))
        assertTrue(payload.length + "powershell.exe -NoProfile -NonInteractive -EncodedCommand ".length < 8191)
        val probe = RemoteTarget.WindowsNative.shellCommand(windowsLookupCommand(AgentKind.entries))
        assertTrue(probe.length < 8191)
    }

    private fun countOf(text: String, part: String): Int = text.windowed(part.length).count { it == part }

    private fun assertRefused(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Unsafe input accepted")
        } catch (_: IllegalArgumentException) {
            // The only accepted failure: the value never reached a script.
        }
    }

    private class FixedAnswerHost(private val answer: String) : HostSession {
        override val hostId = 9L

        override suspend fun exec(command: String): ExecChannel = object : ExecChannel {
            override val stdout = ByteArrayInputStream(answer.toByteArray())
            override val stdin = ByteArrayOutputStream()
            override val stderr = ByteArrayInputStream(ByteArray(0))
            override fun close() = Unit
        }
    }
}
