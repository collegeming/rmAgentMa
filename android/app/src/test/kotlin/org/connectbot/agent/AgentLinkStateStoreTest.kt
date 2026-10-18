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
import org.json.JSONArray
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AgentLinkStateStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun context(): Context {
        val root = temporary.root
        return object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getNoBackupFilesDir(): File = root
        }
    }

    private fun store(context: Context, scheduler: TestCoroutineScheduler) = AgentLinkStateStore(context, StandardTestDispatcher(scheduler))

    private fun file(context: Context) = File(context.noBackupFilesDir, "agent-link-states-v1.json")

    private fun record(hostId: Long, agent: String?, state: AgentLinkState, attempts: Int = 0, updatedAt: Long = 0) = AgentLinkRecord(hostId, agent, state, attempts, updatedAt)

    @Test
    fun roundTripsEveryStateAndPersistsOnlyStateFrequencyAndTime() = runTest {
        val context = context()
        val connected = record(1, "kimi", AgentLinkState.CONNECTED, 0, 1_700_000_000_000)
        val retrying = record(1, "zcode", AgentLinkState.RETRYING, 3, 1_700_000_000_500)
        val hostLevel = record(2, null, AgentLinkState.NEEDS_USER, 5, 1_700_000_001_000)
        val store = store(context, testScheduler)
        store.record(connected)
        store.record(retrying)
        store.record(hostLevel)

        val reopened = store(context, testScheduler)
        assertThat(reopened.all()).containsExactlyInAnyOrder(connected, retrying, hostLevel)

        val persisted = JSONArray(file(context).readText())
        val keys = persisted.getJSONObject(0).keys().asSequence().toSet()
        assertThat(keys).containsExactlyInAnyOrder("hostId", "agent", "state", "attempts", "updatedAtMillis")
    }

    @Test
    fun overwritesTheSameIdentityAndKeepsOtherAgentsAndHosts() = runTest {
        val store = store(context(), testScheduler)
        store.record(record(1, "kimi", AgentLinkState.RETRYING, 3, 100))
        store.record(record(1, "kimi", AgentLinkState.CONNECTED, 0, 200))
        store.record(record(1, null, AgentLinkState.DISCONNECTED, 1, 300))
        store.record(record(2, "kimi", AgentLinkState.DISCONNECTED, 0, 400))

        assertThat(store.all()).containsExactlyInAnyOrder(
            record(1, "kimi", AgentLinkState.CONNECTED, 0, 200),
            record(1, null, AgentLinkState.DISCONNECTED, 1, 300),
            record(2, "kimi", AgentLinkState.DISCONNECTED, 0, 400),
        )
        assertThat(store(context(), testScheduler).all()).hasSize(3)
    }

    @Test
    fun forgetDropsEveryAgentOfTheHostAndLeavesUntouchedFilesAlone() = runTest {
        val context = context()
        val store = store(context, testScheduler)
        store.record(record(1, "kimi", AgentLinkState.CONNECTED))
        store.record(record(1, "zcode", AgentLinkState.RETRYING, 2))
        store.record(record(2, "kimi", AgentLinkState.DISCONNECTED))

        store.forget(1)
        assertThat(store.all()).containsExactly(record(2, "kimi", AgentLinkState.DISCONNECTED))
        assertThat(store(context, testScheduler).all()).containsExactly(record(2, "kimi", AgentLinkState.DISCONNECTED))

        val before = file(context).readBytes()
        store.forget(7)
        store.forget(0)
        assertThat(file(context).readBytes()).isEqualTo(before)
        assertThat(store(context, testScheduler).all()).containsExactly(record(2, "kimi", AgentLinkState.DISCONNECTED))
    }

    @Test
    fun damagedFileReportsARealReasonThenRebuildsWithTheEvidencePreserved() = runTest {
        val context = context()
        file(context).writeText("broken-json")
        val store = store(context, testScheduler)
        var reason: String? = null
        try {
            store.all()
        } catch (e: IOException) {
            reason = e.message
        }
        assertThat(reason).contains("unreadable").contains("preserved")
        assertThat(store.all()).isEmpty()

        val rebuilt = record(1, "kimi", AgentLinkState.DISCONNECTED, 1, 900)
        store.record(rebuilt)
        assertThat(store.all()).containsExactly(rebuilt)
        assertThat(store(context, testScheduler).all()).containsExactly(rebuilt)
        val backups = context.noBackupFilesDir.listFiles()!!.filter { it.name.contains(".corrupt-") }
        assertThat(backups).hasSize(1)
        assertThat(backups.single().readText()).isEqualTo("broken-json")
    }

    @Test
    fun forgetKeepsTheDamagedEvidenceInsteadOfRewritingIt() = runTest {
        val context = context()
        file(context).writeText("broken-json")
        val store = store(context, testScheduler)
        store.forget(1)
        assertThat(file(context).readText()).isEqualTo("broken-json")
        store.record(record(2, "kimi", AgentLinkState.CONNECTED))
        assertThat(context.noBackupFilesDir.listFiles()!!.count { it.name.contains(".corrupt-") }).isEqualTo(1)
    }

    @Test
    fun rejectsInvalidIdentitiesAttemptsAndAgentNames() = runTest {
        val context = context()
        val store = store(context, testScheduler)
        store.record(record(0, "kimi", AgentLinkState.CONNECTED))
        store.record(record(-1, "kimi", AgentLinkState.CONNECTED))
        store.record(record(1, "a".repeat(65), AgentLinkState.CONNECTED))
        store.record(record(1, "  ", AgentLinkState.CONNECTED))
        store.record(record(1, "ki\u0000mi", AgentLinkState.CONNECTED))
        store.record(record(1, "kimi", AgentLinkState.CONNECTED, attempts = -1))

        assertThat(store.all()).isEmpty()
        assertThat(file(context)).doesNotExist()
    }

    @Test
    fun writeFailureKeepsTheLastKnownStateOnDiskAndInMemory() = runTest {
        val context = context()
        val store = store(context, testScheduler)
        val saved = record(1, "kimi", AgentLinkState.CONNECTED, 0, 500)
        store.record(saved)
        val before = file(context).readBytes()
        val obstruction = File(context.noBackupFilesDir, file(context).name + ".new")
        obstruction.mkdir()
        File(obstruction, "keep-directory-nonempty").writeText("test")

        var failed = false
        try {
            store.record(record(1, "kimi", AgentLinkState.DISCONNECTED, 1, 600))
        } catch (_: IOException) {
            failed = true
        }
        assertThat(failed).isTrue()
        assertThat(store.all()).containsExactly(saved)
        assertThat(file(context).readBytes()).isEqualTo(before)
    }

    @Test
    fun newestRowWinsWhenTheFileRepeatsAnIdentity() = runTest {
        val context = context()
        file(context).writeText(
            JSONArray()
                .put(
                    org.json.JSONObject()
                        .put("hostId", 1)
                        .put("agent", "kimi")
                        .put("state", "CONNECTED")
                        .put("attempts", 0)
                        .put("updatedAtMillis", 100),
                )
                .put(
                    org.json.JSONObject()
                        .put("hostId", 1)
                        .put("agent", "kimi")
                        .put("state", "RETRYING")
                        .put("attempts", 2)
                        .put("updatedAtMillis", 200),
                )
                .put(
                    org.json.JSONObject()
                        .put("hostId", 1)
                        .put("state", "NEEDS_USER")
                        .put("attempts", 1)
                        .put("updatedAtMillis", 300),
                )
                .toString(),
        )

        assertThat(store(context, testScheduler).all()).containsExactlyInAnyOrder(
            record(1, "kimi", AgentLinkState.RETRYING, 2, 200),
            record(1, null, AgentLinkState.NEEDS_USER, 1, 300),
        )
    }
}
