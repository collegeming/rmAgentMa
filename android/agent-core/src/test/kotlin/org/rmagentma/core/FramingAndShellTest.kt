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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
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
            for (status in listOf(0, 23)) {
                val python = root.resolve("python3").toFile()
                python.writeText("#!/bin/sh\nexit $status\n")
                assertTrue(python.setExecutable(true))
                val command = ShellCommands.scanner("ignored", setOf(AgentKind.KIMI), 1)
                val process = ProcessBuilder("/bin/sh", "-c", command).apply {
                    environment()["PATH"] = root.toString()
                    environment()["HOME"] = root.toString()
                }.start()
                val output = process.inputStream.bufferedReader().readText()
                assertEquals(status, process.waitFor())
                assertEquals(if (status == 0) "" else "{\"level\":\"error\",\"category\":\"scanner_exit_failed\"}\n", output)
            }
            Files.delete(root.resolve("python3"))
            val process = ProcessBuilder("/bin/sh", "-c", ShellCommands.scanner("ignored", setOf(AgentKind.KIMI), 1)).apply {
                environment()["PATH"] = root.toString()
                environment()["HOME"] = root.toString()
            }.start()
            assertEquals("{\"level\":\"error\",\"category\":\"python_missing\"}\n", process.inputStream.bufferedReader().readText())
            assertEquals(127, process.waitFor())
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    @Test
    fun scannerUsesQuotedHeredocWithNonCollidingDelimiter() {
        val script = "print('\$HOME')\nRMAGENTMA_SCAN\n"
        val command = ShellCommands.scanner(script, setOf(AgentKind.KIMI, AgentKind.ZCODE), 20)
        assertTrue(command.contains("if python3 - --agent 'kimi' 'zcode' --limit 20 <<'RMAGENTMA_SCAN_'\n"))
        assertTrue(command.contains("\nRMAGENTMA_SCAN_\nthen\n"))
        val safe = ShellCommands.scanner("print('safe')", setOf(AgentKind.KIMI), 1)
        val process = ProcessBuilder("/bin/sh", "-c", safe).start()
        assertEquals("safe\n", process.inputStream.bufferedReader().readText())
        assertEquals(0, process.waitFor())
    }
}
