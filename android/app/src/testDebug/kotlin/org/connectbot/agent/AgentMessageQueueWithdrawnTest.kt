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

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

/**
 * The withdrawal end of [AgentMessageQueue]: a message its sender took back ends under its own terminal state, is
 * never handed to its conversation, is never reported as delivered and cannot be handed over after a restart.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AgentMessageQueueWithdrawnTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun context(): Context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
        override fun getNoBackupFilesDir(): File = temporary.root
    }

    private fun queue(context: Context, scheduler: TestCoroutineScheduler) = AgentMessageQueue(context, StandardTestDispatcher(scheduler))

    /** Every state the queue file holds, so a test can prove what was really written. */
    private fun storedStates(): List<String> {
        val array = JSONObject(File(temporary.root, "agent-message-queue-v1.json").readText()).getJSONArray("messages")
        return (0 until array.length()).map { array.getJSONObject(it).getString("state") }
    }

    @Test
    fun aWithdrawnMessageIsNeverWaitedForAndNeverDelivered() = runTest {
        val context = context()
        val queue = queue(context, testScheduler)
        val takenBack = queue.enqueue(1, "kimi", "session-a", AgentMessageKind.REQUEST, "别发了", 10)!!
        val kept = queue.enqueue(1, "kimi", "session-a", AgentMessageKind.NOTIFY, "这条照发", 20)!!

        assertThat(queue.withdraw(takenBack)).isTrue()
        // The same message is not pending again, so a second withdrawal is refused.
        assertThat(queue.withdraw(takenBack)).isFalse()
        assertThat(queue.pending().map { it.id }).containsExactly(kept)
        assertThat(queue.pendingFor(1, "kimi", "session-a").map { it.id }).containsExactly(kept)
        // Nothing can claim a hand-off of it either: the conversation never took it.
        assertThat(queue.delivered(takenBack)).isFalse()
        assertThat(queue.stats()).isEqualTo(AgentMessageQueueStats(stored = 2, pending = 1, delivered = 0, withdrawn = 1, dropped = 0))

        // The terminal state is what was written, so a withdrawn message cannot come back as pending.
        assertThat(storedStates()).containsExactlyInAnyOrder("WITHDRAWN", "PENDING")
        assertThat(queue.held().map { it.id }).containsExactly(takenBack, kept)
        val reopened = queue(context, testScheduler)
        assertThat(reopened.pending().map { it.id }).containsExactly(kept)
        assertThat(reopened.stats().withdrawn).isEqualTo(1)
        assertThat(reopened.delivered(takenBack)).isFalse()
    }

    @Test
    fun aMessageItsConversationTookCannotBeWithdrawn() = runTest {
        val context = context()
        val queue = queue(context, testScheduler)
        val taken = queue.enqueue(1, "kimi", "session-a", AgentMessageKind.NOTIFY, "已取走", 10)!!
        assertThat(queue.delivered(taken)).isTrue()

        assertThat(queue.withdraw(taken)).isFalse()
        assertThat(queue.withdraw("")).isFalse()
        assertThat(queue.withdraw("no-such-message")).isFalse()
        assertThat(queue.stats()).isEqualTo(AgentMessageQueueStats(stored = 1, pending = 0, delivered = 1, withdrawn = 0, dropped = 0))
        assertThat(storedStates()).containsExactly("DELIVERED")
    }
}
