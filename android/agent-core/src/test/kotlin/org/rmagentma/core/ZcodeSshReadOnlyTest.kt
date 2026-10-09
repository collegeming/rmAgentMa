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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class ZcodeSshReadOnlyTest {
    @Test
    fun realSshReadinessAndListNeverResumeOrSend() = runBlocking {
        assumeTrue(System.getenv("ZCODE_READONLY_SSH_TEST") == "1")
        withTimeout(60000) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val commands = mutableListOf<String>()
            val host = object : HostSession {
                override val hostId = 42L
                override suspend fun exec(command: String): ExecChannel = withContext(Dispatchers.IO) {
                    commands += command
                    val home = System.getProperty("user.home")
                    val process = ProcessBuilder(
                        "ssh", "-T", "-i", "$home/.cache/rmagentma-ssh-test/client_ed25519",
                        "-oUserKnownHostsFile=$home/.cache/rmagentma-ssh-test/known_hosts",
                        "-oStrictHostKeyChecking=yes", "-p2222", "colle@127.0.0.1", command,
                    ).start()
                    object : ExecChannel {
                        override val stdout = process.inputStream
                        override val stdin = process.outputStream
                        override val stderr = process.errorStream
                        override fun close() {
                            process.destroy()
                            listOf(stdout, stdin, stderr).forEach { runCatching { it.close() } }
                        }
                    }
                }
            }
            try {
                val driver = ZcodeDriver(scope)
                val global = driver.listSessionRecords(host, limit = 2)
                assertTrue(global.isNotEmpty())
                assertTrue(global.all { it.session.agent == AgentKind.ZCODE })
                val identity = "remote:wsl:default:/mnt/e/ProgramingCode/onLineDoc"
                val workspace = driver.listSessionRecords(
                    host,
                    limit = 2,
                    cwd = "/mnt/e/ProgramingCode/onLineDoc",
                    workspaceIdentity = identity,
                )
                assertTrue(workspace.isNotEmpty())
                assertTrue(workspace.all { it.workspaceIdentity == identity })
                assertEquals(2, commands.size)
                assertTrue(commands.all { it.contains("app-server") && !it.contains("--resume") && !it.contains("--prompt") })
            } finally {
                scope.cancel()
            }
        }
    }
}
