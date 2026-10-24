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

import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.keepalive.KeepAliveRunner
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class AgentKeepAliveTest {
    private fun client() = SSHClient(DefaultConfig().apply { keepAliveProvider = KeepAliveProvider.KEEP_ALIVE })

    @Test
    fun armsReplyCountedKeepAliveForTheNextConnect() {
        val client = client()
        assertThat(client.connection.keepAlive.isEnabled).isFalse()

        armKeepAlive(client)

        val keepAlive = client.connection.keepAlive
        assertThat(keepAlive.isEnabled).isTrue()
        assertThat(keepAlive.keepAliveInterval).isEqualTo(KEEP_ALIVE_INTERVAL_SECONDS)
        assertThat(keepAlive).isInstanceOf(KeepAliveRunner::class.java)
        assertThat((keepAlive as KeepAliveRunner).maxAliveCount).isEqualTo(KEEP_ALIVE_MAX_UNANSWERED)
    }

    /** KeepAliveRunner gives up on the (maxAliveCount + 1)-th check, one interval apart, so this is the detection time. */
    @Test
    fun armedClientDeclaresADeadPeerInsideTwoMinutes() {
        val client = client()
        armKeepAlive(client)

        val keepAlive = client.connection.keepAlive
        val detectionSeconds = (keepAlive as KeepAliveRunner).maxAliveCount.inc() * keepAlive.keepAliveInterval

        assertThat(detectionSeconds).isLessThanOrEqualTo(120)
    }
}
