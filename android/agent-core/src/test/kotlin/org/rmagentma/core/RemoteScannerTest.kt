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
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.Comparator

class RemoteScannerTest {
    @Test
    fun scannerSeparatesSessionsAndSanitizedErrors() = runBlocking {
        var closed = false
        val host = object : HostSession {
            override val hostId = 19L
            override suspend fun exec(command: String): ExecChannel {
                val output = """
                    {"agent":"kimi","sessionId":"one","cwd":"/work","title":"Title","updatedAt":1234}
                    {"level":"error","agent":"dsh","message":"private-content"}
                    private-not-json
                    {"agent":"future","sessionId":"ignored","cwd":"/work"}
                    {"agent":"kimi","sessionId":"two","cwd":"relative"}
                """.trimIndent()
                return object : ExecChannel {
                    override val stdout = ByteArrayInputStream(output.toByteArray())
                    override val stdin = ByteArrayOutputStream()
                    override val stderr = ByteArrayInputStream("private-stderr-content".toByteArray())
                    override fun close() {
                        closed = true
                    }
                }.also { this@RemoteScannerTest.lastCommand = command }
            }
        }
        val result = withTimeout(5000) { RemoteScanner().scan(host) }
        val command = lastCommand
        assertEquals(1, result.sessions.size)
        assertEquals(19L, result.sessions.single().hostId)
        assertEquals(1234L, result.sessions.single().updatedAt)
        assertTrue(result.errors.size >= 3)
        assertFalse(result.errors.any { "private" in it })
        assertTrue(closed)
        assertTrue(command.contains("if python3 - --agent 'kimi' 'opencode' 'dsh' 'zcode' --limit 200 <<'"))
    }

    @Test
    fun ompNeverInvokesFileScanner() = runBlocking {
        val host = object : HostSession {
            override val hostId = 1L
            override suspend fun exec(command: String): ExecChannel = throw AssertionError("OMP file scanning is prohibited")
        }
        val result = RemoteScanner().scan(host, setOf(AgentKind.OMP))
        assertTrue(result.sessions.isEmpty())
        assertEquals(1, result.errors.size)
        assertTrue(result.errors.single().contains("ACP"))
    }

    @Test
    fun packagedScannerRunsThroughRealShellAgainstIsolatedEmptyHome() = runBlocking {
        val root = Files.createTempDirectory("scanner-test")
        try {
            val host = object : HostSession {
                override val hostId = 5L
                override suspend fun exec(command: String): ExecChannel {
                    val process = ProcessBuilder("/bin/sh", "-c", command)
                        .directory(root.toFile())
                        .apply {
                            environment()["HOME"] = root.toString()
                            environment()["KIMI_CODE_HOME"] = root.resolve("kimi").toString()
                            environment()["DSH_HOME"] = root.resolve("dsh").toString()
                            environment()["XDG_DATA_HOME"] = root.resolve("data").toString()
                            environment()["PATH"] = "/usr/bin:/bin"
                        }.start()
                    return object : ExecChannel {
                        override val stdout = process.inputStream
                        override val stdin = process.outputStream
                        override val stderr = process.errorStream
                        override fun close() {
                            process.destroy()
                            stdout.close()
                            stdin.close()
                            stderr.close()
                        }
                    }
                }
            }
            val result = withTimeout(10000) { RemoteScanner().scan(host) }
            assertTrue(result.sessions.isEmpty())
            assertTrue(result.errors.isEmpty())
        } finally {
            Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    @Test
    fun missingPythonAndSilentNonzeroExitAreScanErrors() = runBlocking {
        val root = Files.createTempDirectory("scanner-failure-test")
        try {
            for (installed in listOf(false, true)) {
                if (installed) {
                    val python = root.resolve("python3").toFile()
                    python.writeText("#!/bin/sh\nexit 23\n")
                    assertTrue(python.setExecutable(true))
                }
                val host = object : HostSession {
                    override val hostId = 7L
                    override suspend fun exec(command: String): ExecChannel {
                        val process = ProcessBuilder("/bin/sh", "-c", command).apply {
                            environment()["PATH"] = root.toString()
                            environment()["HOME"] = root.toString()
                        }.start()
                        return object : ExecChannel {
                            override val stdout = process.inputStream
                            override val stdin = process.outputStream
                            override val stderr = process.errorStream
                            override fun close() {
                                process.destroy()
                                stdout.close()
                                stdin.close()
                                stderr.close()
                            }
                        }
                    }
                }
                val result = withTimeout(5000) { RemoteScanner().scan(host, setOf(AgentKind.KIMI)) }
                assertTrue(result.sessions.isEmpty())
                assertEquals(1, result.errors.size)
                assertTrue(result.errors.single().contains(if (installed) "exited unsuccessfully" else "requires python3"))
            }
        } finally {
            Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    @Test
    fun malformedOutputDiagnosticsAreBounded() = runBlocking {
        val host = object : HostSession {
            override val hostId = 1L
            override suspend fun exec(command: String): ExecChannel = object : ExecChannel {
                override val stdout = ByteArrayInputStream("malformed\n".repeat(10000).toByteArray())
                override val stdin = ByteArrayOutputStream()
                override val stderr = ByteArrayInputStream(byteArrayOf())
                override fun close() = Unit
            }
        }
        val result = withTimeout(5000) { RemoteScanner().scan(host) }
        assertTrue(result.errors.isNotEmpty())
        assertTrue(result.errors.size <= 100)
        assertTrue(result.sessions.isEmpty())
    }

    private var lastCommand = ""
}
