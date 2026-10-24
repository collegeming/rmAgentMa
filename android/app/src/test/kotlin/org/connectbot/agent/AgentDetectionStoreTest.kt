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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.rmagentma.core.AgentKind
import java.io.File
import java.io.IOException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AgentDetectionStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun context(): Context {
        val root = temporary.root
        return object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getNoBackupFilesDir(): File = root
        }
    }

    private fun store(context: Context, scheduler: TestCoroutineScheduler) = AgentDetectionStore(context, StandardTestDispatcher(scheduler))

    private fun file(context: Context) = File(context.noBackupFilesDir, "agent-detections-v1.json")

    private fun detection(
        hostId: Long = 1,
        target: String = "posix",
        kind: AgentKind = AgentKind.KIMI,
        executable: String = "/usr/local/bin/kimi",
        version: String = "1.2.3",
        updatedAt: Long = 1_700_000_000_000,
    ) = AgentDetection(hostId, target, kind, executable, version, JsonObject(mapOf("acp" to JsonPrimitive(true))), updatedAt)

    @Test
    fun roundTripsThePathVersionAndCapabilitiesOfEveryAgent() = runTest {
        val context = context()
        val kimi = detection()
        val zcode = detection(kind = AgentKind.ZCODE, target = "wsl:Ubuntu-22.04", executable = "/home/u/.zcode/server/agents/glm/zcode-agent", version = "2")
        val store = store(context, testScheduler)
        store.replace(1, listOf(kimi, zcode))

        assertThat(store(context, testScheduler).all()).containsExactlyInAnyOrder(kimi, zcode)
    }

    @Test
    fun aNewDetectionDropsTheTargetsAndPathsOfTheOldOne() = runTest {
        val store = store(context(), testScheduler)
        store.replace(1, listOf(detection(), detection(kind = AgentKind.DSH, executable = "/usr/bin/dsh")))
        store.replace(1, listOf(detection(target = "wsl:Ubuntu", executable = "/usr/lib/kimi")))
        store.replace(2, listOf(detection(hostId = 2, executable = "/opt/kimi")))

        assertThat(store.all()).containsExactlyInAnyOrder(
            detection(target = "wsl:Ubuntu", executable = "/usr/lib/kimi"),
            detection(hostId = 2, executable = "/opt/kimi"),
        )
    }

    @Test
    fun onlyAnAbsoluteExecutableWithoutControlCharactersIsStored() = runTest {
        val store = store(context(), testScheduler)
        store.replace(
            1,
            listOf(
                detection(executable = "kimi"),
                detection(executable = "/usr/bin/ki\u0000mi"),
                detection(hostId = 2),
                detection(hostId = 0),
                detection(target = " "),
                detection(executable = "/usr/bin/kimi"),
            ),
        )

        assertThat(store.all()).containsExactly(detection(executable = "/usr/bin/kimi"))
    }

    @Test
    fun forgetDropsEveryDetectionOfTheHostAndLeavesTheFileAloneWhenTheHostIsUnknown() = runTest {
        val context = context()
        val store = store(context, testScheduler)
        store.replace(1, listOf(detection()))
        store.replace(2, listOf(detection(hostId = 2, executable = "/opt/kimi")))

        store.forget(1)
        assertThat(store(context, testScheduler).all()).containsExactly(detection(hostId = 2, executable = "/opt/kimi"))

        val before = file(context).readBytes()
        store.forget(7)
        store.forget(0)
        assertThat(file(context).readBytes()).isEqualTo(before)
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

        val rebuilt = detection()
        store.replace(1, listOf(rebuilt))
        assertThat(store(context, testScheduler).all()).containsExactly(rebuilt)
        val backups = context.noBackupFilesDir.listFiles()!!.filter { it.name.contains(".corrupt-") }
        assertThat(backups).hasSize(1)
        assertThat(backups.single().readText()).isEqualTo("broken-json")
    }

    @Test
    fun writeFailureKeepsTheLastKnownDetectionOnDiskAndInMemory() = runTest {
        val context = context()
        val store = store(context, testScheduler)
        val saved = detection()
        store.replace(1, listOf(saved))
        val before = file(context).readBytes()
        val obstruction = File(context.noBackupFilesDir, file(context).name + ".new")
        obstruction.mkdir()
        File(obstruction, "keep-directory-nonempty").writeText("test")

        var failed = false
        try {
            store.replace(1, listOf(detection(executable = "/usr/bin/other")))
        } catch (_: IOException) {
            failed = true
        }
        assertThat(failed).isTrue()
        assertThat(store.all()).containsExactly(saved)
        assertThat(file(context).readBytes()).isEqualTo(before)
    }
}
