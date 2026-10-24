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
class AgentDraftStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun context(): Context {
        val root = temporary.root
        return object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getNoBackupFilesDir(): File = root
        }
    }

    private fun store(context: Context, scheduler: TestCoroutineScheduler) = AgentDraftStore(context, StandardTestDispatcher(scheduler))

    private fun file(context: Context) = File(context.noBackupFilesDir, "agent-drafts-v1.json")

    @Test
    fun roundTripsEveryConversationAndPersistsOnlyTheText() = runTest {
        val context = context()
        val store = store(context, testScheduler)
        store.write("7:kimi:session-a", "第一行\n第二行 😀")
        store.write("7:zcode:session-b", "另一个会话的草稿")

        val reopened = store(context, testScheduler)
        assertThat(reopened.read("7:kimi:session-a")).isEqualTo("第一行\n第二行 😀")
        assertThat(reopened.read("7:zcode:session-b")).isEqualTo("另一个会话的草稿")
        assertThat(reopened.read("7:kimi:session-c")).isNull()

        val persisted = JSONArray(file(context).readText())
        assertThat(persisted.length()).isEqualTo(2)
        val keys = persisted.getJSONObject(0).keys().asSequence().toSet()
        assertThat(keys).containsExactlyInAnyOrder("conversation", "text")
    }

    @Test
    fun overwritesTheSameConversationAndKeepsTheOthers() = runTest {
        val store = store(context(), testScheduler)
        store.write("7:kimi:session-a", "旧草稿")
        store.write("7:kimi:session-a", "")
        store.write("7:kimi:session-b", "保留")

        assertThat(store.read("7:kimi:session-a")).isEmpty()
        assertThat(store(context(), testScheduler).read("7:kimi:session-b")).isEqualTo("保留")
    }

    @Test
    fun refusesAnOversizedDraftWithoutLosingTheStoredOne() = runTest {
        val context = context()
        val store = store(context, testScheduler)
        store.write("7:kimi:session-a", "已经保存的草稿")
        val before = file(context).readBytes()

        store.write("7:kimi:session-a", "长".repeat(16385))

        assertThat(store.read("7:kimi:session-a")).isEqualTo("已经保存的草稿")
        assertThat(file(context).readBytes()).isEqualTo(before)
        assertThat(store(context, testScheduler).read("7:kimi:session-a")).isEqualTo("已经保存的草稿")
    }

    @Test
    fun damagedFileReportsARealReasonThenRebuildsWithTheEvidencePreserved() = runTest {
        val context = context()
        file(context).writeText("broken-json")
        val store = store(context, testScheduler)
        var reason: String? = null
        try {
            store.read("7:kimi:session-a")
        } catch (e: IOException) {
            reason = e.message
        }
        assertThat(reason).contains("unreadable").contains("preserved")
        assertThat(store.read("7:kimi:session-a")).isNull()

        store.write("7:kimi:session-a", "重写后的草稿")
        assertThat(store.read("7:kimi:session-a")).isEqualTo("重写后的草稿")
        assertThat(store(context, testScheduler).read("7:kimi:session-a")).isEqualTo("重写后的草稿")
        val backups = context.noBackupFilesDir.listFiles()!!.filter { it.name.contains(".corrupt-") }
        assertThat(backups).hasSize(1)
        assertThat(backups.single().readText()).isEqualTo("broken-json")
    }

    @Test
    fun ignoresInvalidConversationKeysAndLeavesTheFileAlone() = runTest {
        val context = context()
        val store = store(context, testScheduler)
        store.write("7:kimi:session-a", "保留的草稿")
        val before = file(context).readBytes()

        store.write("", "空键")
        store.write("   ", "空白键")
        store.write("7:kimi:ses\u0000sion", "控制字符")
        store.write("k".repeat(201), "超长键")

        assertThat(file(context).readBytes()).isEqualTo(before)
        assertThat(store.read("")).isNull()
        assertThat(store.read("k".repeat(201))).isNull()
        assertThat(store.read("7:kimi:session-a")).isEqualTo("保留的草稿")
    }

    @Test
    fun writeFailureKeepsTheLastKnownDraftOnDiskAndInMemory() = runTest {
        val context = context()
        val store = store(context, testScheduler)
        store.write("7:kimi:session-a", "已经保存的草稿")
        val before = file(context).readBytes()
        val obstruction = File(context.noBackupFilesDir, file(context).name + ".new")
        obstruction.mkdir()
        File(obstruction, "keep-directory-nonempty").writeText("test")

        var failed = false
        try {
            store.write("7:kimi:session-a", "写不进去的草稿")
        } catch (_: IOException) {
            failed = true
        }
        assertThat(failed).isTrue()
        assertThat(store.read("7:kimi:session-a")).isEqualTo("已经保存的草稿")
        assertThat(file(context).readBytes()).isEqualTo(before)
    }
}
