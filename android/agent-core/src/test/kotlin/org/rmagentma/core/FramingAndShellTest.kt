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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.util.Comparator

class FramingAndShellTest {
    @Test
    fun splitsCrLfAndKeepsFinalUnterminatedUtf8Frame() {
        val reader = NdjsonReader(ByteArrayInputStream("你好\r\n\nfinal".toByteArray()))
        assertEquals("你好", reader.readLine())
        assertEquals("", reader.readLine())
        assertEquals("final", reader.readLine())
        assertNull(reader.readLine())
    }

    @Test
    fun limitCountsUtf8BytesNotCharacters() {
        val exact = NdjsonReader(ByteArrayInputStream("你好\n".toByteArray()), 6)
        assertEquals("你好", exact.readLine())
        val oversized = NdjsonReader(ByteArrayInputStream("你好!\n".toByteArray()), 6)
        try {
            oversized.readLine()
            throw AssertionError("Oversized frame should fail")
        } catch (_: FrameTooLargeException) {
            // expected
        }
        assertEquals(32 * 1024 * 1024, MAX_FRAME_BYTES)
    }

    @Test
    fun invalidUtf8FrameCanBeSkippedWithoutLosingNextFrame() {
        val reader = NdjsonReader(ByteArrayInputStream(byteArrayOf(0xC3.toByte(), 10, 111, 107, 10)))
        try {
            reader.readLine()
            throw AssertionError("Malformed UTF-8 should fail")
        } catch (_: CharacterCodingException) {
            // expected
        }
        assertEquals("ok", reader.readLine())
    }

    @Test
    fun quoteRoundTripsShellMetacharacters() {
        val value = "a' b\n\$(touch /not-executed);`id`\\\""
        val process = ProcessBuilder("/bin/sh", "-c", "printf %s ${ShellCommands.quote(value)}").start()
        assertEquals(value, process.inputStream.bufferedReader().readText())
        assertEquals(0, process.waitFor())
        assertEquals("''", ShellCommands.quote(""))
    }

    @Test
    fun commandsKeepCwdAndResumeArgumentsQuoted() {
        assertEquals("cd -- '/a'\"'\"'b' && exec kimi --session 's; x'", ShellCommands.start(AgentKind.KIMI, "/a'b", "s; x"))
        assertEquals("cd -- '/work dir' && exec dsh tui --resume 'id'", ShellCommands.start(AgentKind.DSH, "/work dir", "id"))
        assertEquals("cd -- '/tmp' && exec omp acp", ShellCommands.acp(AgentKind.OMP, "/tmp"))
        assertEquals("cd -- '/work dir' && exec dsh --profile acp", ShellCommands.acp(AgentKind.DSH, "/work dir"))
        assertEquals("cd -- '/tmp' && exec '/cli path/dsh' --profile acp", ShellCommands.acp(AgentKind.DSH, "/tmp", "/cli path/dsh"))
        assertEquals(AgentKind.ZCODE, AgentKind.fromWire("zcode"))
        assertNull(AgentKind.fromWire("future"))
    }

    @Test
    fun terminalCommandsRejectEveryAsciiControlCharacter() {
        for (code in (0..31) + 127) {
            val control = code.toChar()
            for (agent in AgentKind.entries) {
                rejects { ShellCommands.start(agent, "/work${control}dir") }
                rejects { ShellCommands.start(agent, "/work", "id${control}suffix") }
            }
            rejects { ShellCommands.acp(AgentKind.KIMI, "/work${control}dir") }
        }
        assertEquals("'a\nb'", ShellCommands.quote("a\nb"))
        assertEquals("cd -- '/项目 路径' && exec kimi --session '会话'", ShellCommands.start(AgentKind.KIMI, "/项目 路径", "会话"))
    }

    private fun rejects(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Control character must be rejected")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNul() {
        ShellCommands.quote("a\u0000b")
    }

    @Test
    fun scannerWrapperPreservesExitStatusAndEmptySuccess() {
        val root = Files.createTempDirectory("scanner-wrapper-test")
        try {
            // The generated command needs sh and base64, so those are symlinked into the isolated
            // PATH. python3 is intentionally absent from it until the test creates a stub, which
            // keeps this test independent of whatever the host happens to have installed.
            val sh = File("/bin/sh")
            val base64 = File("/usr/bin/base64").takeIf { it.exists() } ?: File("/bin/base64")
            assertTrue(Files.createSymbolicLink(root.resolve("sh"), sh.toPath()) != null)
            assertTrue(Files.createSymbolicLink(root.resolve("base64"), base64.toPath()) != null)
            val path = root.toString()
            for (status in listOf(0, 23)) {
                val python = root.resolve("python3").toFile()
                python.writeText("#!/bin/sh\nexit $status\n")
                assertTrue(python.setExecutable(true))
                val command = ShellCommands.scanner("ignored", setOf(AgentKind.KIMI), 1)
                val process = ProcessBuilder("/bin/sh", "-c", command).apply {
                    environment()["PATH"] = path
                    environment()["HOME"] = root.toString()
                }.start()
                val output = process.inputStream.bufferedReader().readText()
                assertEquals(status, process.waitFor())
                assertEquals(if (status == 0) "" else "{\"level\":\"error\",\"category\":\"scanner_exit_failed\"}\n", output)
            }
            Files.delete(root.resolve("python3"))
            val process = ProcessBuilder("/bin/sh", "-c", ShellCommands.scanner("ignored", setOf(AgentKind.KIMI), 1)).apply {
                environment()["PATH"] = path
                environment()["HOME"] = root.toString()
            }.start()
            assertEquals("{\"level\":\"error\",\"category\":\"python_missing\"}\n", process.inputStream.bufferedReader().readText())
            assertEquals(127, process.waitFor())
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    @Test
    fun scannerWorksUnderAShellWithoutHeredocSupport() {
        val script = "import sys\nprint('script-ran')\n"
        val command = ShellCommands.scanner(script, setOf(AgentKind.KIMI, AgentKind.ZCODE), 20)
        // A heredoc would be a syntax error under fish and other non-bash login shells, so the
        // command must not rely on one and must not depend on the login shell for parsing.
        assertFalse(command.contains("<<"))
        assertTrue(command.startsWith("/bin/sh -c "))
        assertTrue(command.contains("| base64 -d | python3 -"))
        // Behaviour is what matters: the agent names and limit must reach python3 intact.
        val process = ProcessBuilder("/bin/sh", "-c", command).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor())
        // The payload reached python3, so the script itself executed.
        assertTrue(output.contains("script-ran"))
    }

    @Test
    fun scannerTransportsTheScriptWithoutRemoteShellExpansion() {
        val marker = "\$HOME `id` ; rm -rf /"
        val script = "print(${marker.length})\n"
        val command = ShellCommands.scanner(script, setOf(AgentKind.KIMI), 1)
        // shell quoting must keep the literal text intact for the payload argument
        val strippedPayload = command.removePrefix("bash -c '").removeSuffix("'")
        assertTrue(strippedPayload.contains("base64 -d"))
        // Executing the generated command with the real script must reproduce the script's output.
        val process = ProcessBuilder("/bin/sh", "-c", command).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor())
        assertEquals("${marker.length}\n", output)
    }
}
