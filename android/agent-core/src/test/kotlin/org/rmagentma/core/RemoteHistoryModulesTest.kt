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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.Comparator

class RemoteHistoryModulesTest {
    private val session = AgentSession(7, AgentKind.DSH, "same", "/project")

    @Test
    fun transcriptStreamsFactsWithNoApprovalPayload() = runBlocking {
        val host = fakeHost(
            """
            {"record":"event","hostId":7,"agent":"dsh","sessionId":"same","cwd":"/project","type":"user","text":"hello"}
            {"record":"event","hostId":7,"agent":"dsh","sessionId":"same","cwd":"/project","type":"tool","text":"read file","id":"2","options":[{"id":"allow"}],"raw":{"method":"permission"}}
            {"record":"page","nextCursor":"next","count":2}
            """.trimIndent(),
        )
        val events = mutableListOf<AgentEvent>()
        val page = RemoteTranscriptReader().readPage(host, session, onEvent = { events += it })
        assertEquals(2, page.eventCount)
        assertEquals("next", page.nextCursor)
        assertNull(page.error)
        assertEquals("historical", events.last().status)
        assertTrue(events.all { it.options.isEmpty() && it.raw == null })
        assertTrue(host.closed)
    }

    @Test
    fun transcriptRejectsForeignIdentityUnsafeEventsAndMissingCompletion() = runBlocking {
        for (payload in listOf(
            """{"record":"event","hostId":8,"agent":"dsh","sessionId":"same","cwd":"/project","type":"content"}""",
            """{"record":"event","hostId":7,"agent":"dsh","sessionId":"same","cwd":"/other","type":"content"}""",
            """{"record":"event","hostId":7,"agent":"dsh","sessionId":"same","cwd":"/project","type":"permission"}""",
            "",
        )) {
            val host = fakeHost(payload)
            val events = mutableListOf<AgentEvent>()
            val page = RemoteTranscriptReader().readPage(host, session, onEvent = { events += it })
            assertTrue(page.error != null)
            assertTrue(events.isEmpty())
            assertTrue(host.closed)
        }
    }

    @Test
    fun validatesHostLimitAndExecutableBeforeExec() = runBlocking {
        val host = fakeHost("")
        for (invalid in listOf(session.copy(hostId = 8), session.copy(cwd = "relative"), session.copy(sessionId = "id\n"))) {
            try {
                RemoteTranscriptReader().readPage(host, invalid) {}
                throw AssertionError("Invalid identity accepted")
            } catch (_: IllegalArgumentException) {
                assertFalse(host.executed)
            }
        }
        try {
            RemoteAgentProbe().probe(host, "zcode; echo nope")
            throw AssertionError("Relative executable accepted")
        } catch (_: IllegalArgumentException) {
            assertFalse(host.executed)
        }
    }

    @Test
    fun probePreservesDshResumeAndRejectsUnverifiedZcode() = runBlocking {
        val host = fakeHost(
            """
            {"kind":"dsh","executable":"/bin/dsh","available":true,"version":"0.2.1-alpha.1","capabilities":{"acp":true,"resume":true,"load":false}}
            {"kind":"zcode","executable":"/custom/zcode","available":true,"capabilities":{},"error":"interactive_protocol_unverified"}
            """.trimIndent(),
        )
        val rows = RemoteAgentProbe().probe(host, "/custom/zcode")
        assertEquals(5, rows.size)
        assertTrue(rows.single { it.kind == AgentKind.DSH }.available)
        assertFalse(rows.single { it.kind == AgentKind.ZCODE }.available)
        assertEquals("/custom/zcode", rows.single { it.kind == AgentKind.ZCODE }.executable)
        assertTrue(host.closed)
    }

    @Test
    fun probeAcceptsZcodeOnlyWithProtocolAndStartupReadinessEvidence() = runBlocking {
        for (ready in listOf(false, true)) {
            val host = fakeHost(
                """{"kind":"zcode","executable":"/home/user/.zcode/server/agents/glm/zcode-agent","available":true,"version":"0.16.9","capabilities":{"acp":false,"zcodeProtocol":true,"startupReady":$ready}}""",
            )
            val row = RemoteAgentProbe().probe(host).single { it.kind == AgentKind.ZCODE }
            assertEquals(ready, row.available)
            assertEquals("0.16.9", row.version)
            assertTrue(host.closed)
        }
    }

    @Test
    fun shellPayloadIsPosixQuotedAndPackagedProbeRunsWithoutInstalledAgents() = runBlocking {
        val root = Files.createTempDirectory("remote-probe-test")
        try {
            for (tool in listOf("python3", "base64")) {
                Files.createSymbolicLink(root.resolve(tool), java.nio.file.Path.of("/usr/bin/$tool"))
            }
            val host = object : HostSession {
                override val hostId = 7L
                override suspend fun exec(command: String): ExecChannel {
                    val process = ProcessBuilder("/bin/sh", "-c", command).apply {
                        directory(root.toFile())
                        environment()["HOME"] = root.toString()
                        environment()["PATH"] = root.toString()
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
            val rows = withTimeout(10_000) { RemoteAgentProbe().probe(host, root.resolve("x' \$(touch nope)").toString()) }
            assertEquals(5, rows.size)
            assertTrue(rows.all { !it.available && it.error == "executable_missing" })
            assertFalse(Files.exists(root.resolve("nope")))
            val events = mutableListOf<AgentEvent>()
            val page = RemoteTranscriptReader().readPage(host, session.copy(cwd = "/project' \$(touch nope)")) { events += it }
            assertEquals("history_unreadable", page.error)
            assertTrue(events.isEmpty())
            assertFalse(Files.exists(root.resolve("nope")))
            val database = root.resolve(".zcode/cli/db/db.sqlite")
            Files.createDirectories(database.parent)
            val fixture = """
                import sqlite3,sys
                db=sqlite3.connect(sys.argv[1])
                db.executescript('''
                CREATE TABLE session(id TEXT, directory TEXT, time_updated INTEGER);
                CREATE TABLE message(id TEXT, session_id TEXT, data TEXT, sequence INTEGER, time_created INTEGER);
                CREATE TABLE part(id TEXT, session_id TEXT, message_id TEXT, data TEXT, sequence INTEGER, time_created INTEGER);
                INSERT INTO session VALUES ('same','/project',1);
                INSERT INTO message VALUES ('m','same','{"role":"assistant"}',1,1);
                INSERT INTO part VALUES ('p1','same','m','{"type":"text","text":"first"}',1,1);
                INSERT INTO part VALUES ('p2','same','m','{"type":"text","text":"second"}',2,1);
                ''')
                db.commit()
                db.close()
            """.trimIndent()
            assertEquals(0, ProcessBuilder("/usr/bin/python3", "-c", fixture, database.toString()).start().waitFor())
            val before = Files.readAllBytes(database)
            val zcodeSession = session.copy(agent = AgentKind.ZCODE)
            val first = RemoteTranscriptReader().readPage(host, zcodeSession, limit = 1) { events += it }
            assertNull(first.error)
            assertEquals("first", events.single().text)
            assertTrue(first.nextCursor != null)
            val second = RemoteTranscriptReader().readPage(host, zcodeSession, cursor = first.nextCursor, limit = 1) { events += it }
            assertNull(second.error)
            assertNull(second.nextCursor)
            assertEquals("second", events.last().text)
            assertTrue(before.contentEquals(Files.readAllBytes(database)))
        } finally {
            Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    private fun fakeHost(payload: String) = FakeHost(payload)

    private class FakeHost(private val payload: String) : HostSession {
        override val hostId = 7L
        var executed = false
        var closed = false
        override suspend fun exec(command: String): ExecChannel {
            executed = true
            assertTrue(command.startsWith("/bin/sh -c "))
            return object : ExecChannel {
                override val stdout = ByteArrayInputStream(payload.toByteArray())
                override val stdin = ByteArrayOutputStream()
                override val stderr = ByteArrayInputStream("private diagnostics".toByteArray())
                override fun close() {
                    closed = true
                }
            }
        }
    }
}
