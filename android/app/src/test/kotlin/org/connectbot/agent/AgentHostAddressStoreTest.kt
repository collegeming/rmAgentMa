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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AgentHostAddressStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun context(): Context {
        val root = temporary.root
        return object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getNoBackupFilesDir(): File = root
        }
    }

    private fun store(context: Context, scheduler: TestCoroutineScheduler) = AgentHostAddressStore(context, StandardTestDispatcher(scheduler))

    @Test
    fun recordsAndReadsBackTheVerifiedAddressAcrossReopen() = runTest {
        val context = context()
        store(context, testScheduler).record(1, "192.168.1.5", 22, AgentAddressSource.CONNECTED)
        val latest = store(context, testScheduler).latest(1)
        assertThat(latest).isNotNull()
        assertThat(latest!!.address).isEqualTo("192.168.1.5")
        assertThat(latest.port).isEqualTo(22)
        assertThat(latest.source).isEqualTo(AgentAddressSource.CONNECTED)
        assertThat(latest.verifiedAtMillis).isGreaterThan(0L)
    }

    @Test
    fun keepsTheSixteenNewestAddressesPerHostAndDropsTheOldest() = runTest {
        val store = store(context(), testScheduler)
        (0 until 20).forEach { store.record(1, "10.0.0.$it", 22, AgentAddressSource.REDISCOVERED) }
        val kept = store.candidates(1, 22, limit = 100)
        assertThat(kept).hasSize(16)
        assertThat(kept.first().address).isEqualTo("10.0.0.19")
        assertThat(kept.last().address).isEqualTo("10.0.0.4")
        assertThat(kept.map { it.address }).doesNotContain("10.0.0.0", "10.0.0.1", "10.0.0.3")
    }

    @Test
    fun candidatesAreNewestFirstFilteredByPortAndBoundedByLimit() = runTest {
        val store = store(context(), testScheduler)
        store.record(1, "10.0.0.7", 22, AgentAddressSource.CONNECTED)
        store.record(1, "10.0.0.8", 2222, AgentAddressSource.CONNECTED)
        store.record(1, "10.0.0.9", 22, AgentAddressSource.REDISCOVERED)
        assertThat(store.candidates(1, 22).map { it.address }).containsExactly("10.0.0.9", "10.0.0.7")
        assertThat(store.candidates(1, 2222).map { it.address }).containsExactly("10.0.0.8")
        assertThat(store.candidates(1, 22, limit = 1).map { it.address }).containsExactly("10.0.0.9")
        assertThat(store.candidates(2, 22)).isEmpty()
    }

    @Test
    fun repeatingAnAddressRefreshesItInsteadOfDuplicatingIt() = runTest {
        val context = context()
        val store = store(context, testScheduler)
        store.record(1, "10.0.0.7", 22, AgentAddressSource.CONNECTED)
        store.record(1, "10.0.0.8", 22, AgentAddressSource.CONNECTED)
        store.record(1, "10.0.0.7", 22, AgentAddressSource.REDISCOVERED)
        val rows = store(context, testScheduler).candidates(1, 22, limit = 10)
        assertThat(rows.map { it.address }).containsExactly("10.0.0.7", "10.0.0.8")
        assertThat(rows.first().source).isEqualTo(AgentAddressSource.REDISCOVERED)
    }

    @Test
    fun damagedHistoryReportsOnceThenRebuildsFromTheNextVerifiedAddress() = runTest {
        val context = context()
        File(context.noBackupFilesDir, "agent-host-addresses-v1.json").writeText("broken-json")
        val store = store(context, testScheduler)
        var failed = false
        try {
            store.candidates(1, 22)
        } catch (_: IOException) {
            failed = true
        }
        assertThat(failed).isTrue()
        assertThat(store.latest(1)).isNull()
        store.record(1, "10.0.0.7", 22, AgentAddressSource.CONNECTED)
        assertThat(store.candidates(1, 22).map { it.address }).containsExactly("10.0.0.7")
        val backups = context.noBackupFilesDir.listFiles()!!.filter { it.name.contains(".corrupt-") }
        assertThat(backups).hasSize(1)
        assertThat(backups.single().readText()).isEqualTo("broken-json")
    }

    @Test
    fun rejectsUnusableIdentitiesWithoutTouchingRecordedHistory() = runTest {
        val store = store(context(), testScheduler)
        store.record(1, "10.0.0.7", 22, AgentAddressSource.CONNECTED)
        store.record(1, "", 22, AgentAddressSource.REDISCOVERED)
        store.record(1, "10.0.0.8", 0, AgentAddressSource.REDISCOVERED)
        store.record(1, "10.0.0 9", 22, AgentAddressSource.REDISCOVERED)
        store.record(0, "10.0.0.9", 22, AgentAddressSource.REDISCOVERED)
        assertThat(store.candidates(1, 22).map { it.address }).containsExactly("10.0.0.7")
        assertThat(store.latest(0)).isNull()
    }
}
