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
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.rmagentma.core.AgentKind
import org.rmagentma.core.AgentSession
import java.io.File
import java.io.IOException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AgentIndexStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun context(): Context {
        val root = temporary.root
        return object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getNoBackupFilesDir(): File = root
        }
    }

    private fun session(id: String, updated: Long = 0, preview: String = "", title: String = "") = AgentSession(1, AgentKind.KIMI, id, "/workspace", title = title, updatedAt = updated, preview = preview)

    @Test
    fun preservesCompleteSearchableTextAcrossDiskReload() = runTest {
        val context = context()
        val store = AgentIndexStore(context, kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        val original = session("one", preview = "last message ".repeat(300) + "search-tail", title = "title".repeat(300))
        store.replace(1, AgentKind.KIMI, listOf(original))
        val reopened = AgentIndexStore(context, kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        assertThat(reopened.read()).containsExactly(original)
    }

    @Test
    fun samplesNewestTwoHundredAfterSortingAndDeduplication() = runTest {
        val store = AgentIndexStore(context(), kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        val rows = (1L..250L).map { session(it.toString(), it) } + session("250", 0)
        val result = store.replace(1, AgentKind.KIMI, rows)
        assertThat(result).hasSize(200)
        assertThat(result.first().updatedAt).isEqualTo(250L)
        assertThat(result.last().updatedAt).isEqualTo(51L)
    }

    @Test
    fun oversizedUpdateLeavesMemoryAndFileUntouched() = runTest {
        val context = context()
        val store = AgentIndexStore(context, kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        val original = session("one", preview = "saved")
        store.replace(1, AgentKind.KIMI, listOf(original))
        val file = File(context.noBackupFilesDir, "agent-session-index-v1.json")
        val before = file.readBytes()
        var failed = false
        try {
            store.replace(1, AgentKind.KIMI, listOf(session("large", preview = "x".repeat(16 * 1024 * 1024))))
        } catch (e: IOException) {
            failed = true
            assertThat(e.message).contains("16 MiB")
        }
        assertThat(failed).isTrue()
        assertThat(store.read()).containsExactly(original)
        assertThat(file.readBytes()).isEqualTo(before)
    }

    @Test
    fun diskWriteFailureDoesNotPublishTheReplacement() = runTest {
        val context = context()
        val store = AgentIndexStore(context, kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        val original = session("saved")
        store.replace(1, AgentKind.KIMI, listOf(original))
        val base = File(context.noBackupFilesDir, "agent-session-index-v1.json")
        val previous = base.readBytes()
        val obstruction = File(context.noBackupFilesDir, base.name + ".new")
        obstruction.mkdir()
        File(obstruction, "keep-directory-nonempty").writeText("test")
        var failed = false
        try {
            store.replace(1, AgentKind.KIMI, listOf(session("replacement")))
        } catch (_: IOException) {
            failed = true
        }
        assertThat(failed).isTrue()
        assertThat(store.read()).containsExactly(original)
        assertThat(base.readBytes()).isEqualTo(previous)
    }

    @Test
    fun clearCanRecoverAnUnreadCacheWithoutLosingDamagedEvidence() = runTest {
        val context = context()
        File(context.noBackupFilesDir, "agent-session-index-v1.json").writeText("broken")
        val store = AgentIndexStore(context, kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        assertThat(store.clear()).isEmpty()
        assertThat(store.read()).isEmpty()
        assertThat(context.noBackupFilesDir.listFiles()!!.count { it.name.contains(".corrupt-") }).isEqualTo(1)
    }

    @Test
    fun damagedCacheReportsOnceAndPreservesEvidenceBeforeRebuild() = runTest {
        val context = context()
        val file = File(context.noBackupFilesDir, "agent-session-index-v1.json")
        file.writeText("broken-json")
        val store = AgentIndexStore(context, kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        var failed = false
        try {
            store.read()
        } catch (_: IOException) {
            failed = true
        }
        assertThat(failed).isTrue()
        assertThat(store.read()).isEmpty()
        store.retainHosts(setOf(1))
        assertThat(file.readText()).isEqualTo("broken-json")
        val rebuilt = session("new")
        store.replace(1, AgentKind.KIMI, listOf(rebuilt))
        assertThat(store.read()).containsExactly(rebuilt)
        val backups = context.noBackupFilesDir.listFiles()!!.filter { it.name.contains(".corrupt-") }
        assertThat(backups).hasSize(1)
        assertThat(backups.single().readText()).isEqualTo("broken-json")
    }

    @Test
    fun rejectsRelativePathsAndControlCharactersWithoutTruncatingLegalText() = runTest {
        val store = AgentIndexStore(context(), kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        val valid = session("valid", preview = "line one\nline two\tvalue")
        val result = store.replace(
            1,
            AgentKind.KIMI,
            listOf(
                valid,
                session("relative").copy(cwd = "relative/path"),
                session("bad\u0000id"),
                session("bad-text", preview = "text\u001b[31m"),
            ),
        )
        assertThat(result).containsExactly(valid)
    }
}
