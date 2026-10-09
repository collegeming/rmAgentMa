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

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.rmagentma.core.AgentEvent

class AgentEventHistoryTest {
    @Test
    fun mergesOnlyConsecutiveChunksWithTheSameMessageId() {
        val history = AgentEventHistory()
        history.append(AgentEvent("content", "one", id = "a"))
        val merged = history.append(AgentEvent("content", "two", id = "a"))
        assertThat(merged.map { it.text }).containsExactly("onetwo")
        val separate = history.append(AgentEvent("content", "three", id = "b"))
        assertThat(separate.map { it.text }).containsExactly("onetwo", "three")
        history.append(AgentEvent("tool", id = "t"))
        assertThat(history.append(AgentEvent("content", "four", id = "b"))).hasSize(4)
    }

    @Test
    fun trimsHistoryButPreservesPendingApprovals() {
        val history = AgentEventHistory(limit = 2)
        history.append(AgentEvent("permission", id = "p"))
        history.append(AgentEvent("question", id = "q"))
        val result = history.append(AgentEvent("tool", id = "t"))
        assertThat(result.map { it.id }).containsExactly("p", "q", "t")
        assertThat(history.resolve(result.first()).map { it.id }).containsExactly("q", "t")
    }

    @Test
    fun preservesRawForToolsPlanAndUsage() {
        val history = AgentEventHistory()
        val raw = kotlinx.serialization.json.Json.parseToJsonElement("""{"diff":"@@ -1 +1 @@\\n-old\\n+new"}""") as kotlinx.serialization.json.JsonObject
        val tool = history.append(AgentEvent("tool", id = "t", raw = raw)).single()
        assertThat(tool.raw).isEqualTo(raw)
        history.append(AgentEvent("plan", raw = raw))
        assertThat(history.append(AgentEvent("usage", raw = raw)).map { it.raw }).containsOnly(raw)
    }

    @Test
    fun mergesToolUpdatesWithoutLosingInputOutputOrDiff() {
        val history = AgentEventHistory()
        fun raw(text: String) = kotlinx.serialization.json.Json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject
        history.append(AgentEvent("tool", id = "t", title = "Edit", raw = raw("""{"rawInput":{"path":"file"},"content":[{"diff":"-old"}]}""")))
        history.append(AgentEvent("thinking", "working"))
        val updated = history.append(AgentEvent("tool_update", id = "t", status = "completed", raw = raw("""{"rawOutput":"done","content":[{"diff":"+new"}]}""")))
        assertThat(updated).hasSize(2)
        val tool = updated.first()
        assertThat(tool.type).isEqualTo("tool")
        assertThat(tool.title).isEqualTo("Edit")
        assertThat(tool.status).isEqualTo("completed")
        assertThat(tool.raw.toString()).contains("path", "done", "+new").doesNotContain("-old")
    }

    @Test
    fun projectsOversizedRawWithExplicitTruncationMarker() {
        val history = AgentEventHistory()
        val raw = kotlinx.serialization.json.JsonObject(mapOf("diff" to kotlinx.serialization.json.JsonPrimitive("差".repeat(100_000))))
        val event = history.append(AgentEvent("tool", raw = raw)).single()
        assertThat(event.raw.toString().toByteArray(Charsets.UTF_8).size).isLessThanOrEqualTo(64 * 1024)
        assertThat(event.raw.toString()).contains("truncated", "originalRawBytes", "diff")
    }

    @Test
    fun validatesPendingEventByIdentityRatherThanReusedRequestId() {
        val history = AgentEventHistory()
        val old = history.append(AgentEvent("permission", id = "1")).single()
        assertThat(history.isPending(old.copy(), "1", "permission")).isFalse()
        assertThat(history.isPending(old, "1", "permission")).isTrue()
        history.clear()
        history.append(AgentEvent("permission", id = "1"))
        assertThat(history.isPending(old, "1", "permission")).isFalse()
    }

    @Test
    fun rawMessageIdTakesPriorityOverNormalizedId() {
        val history = AgentEventHistory()
        fun event(text: String, message: String) = AgentEvent(
            "content",
            text,
            id = "same",
            raw = kotlinx.serialization.json.JsonObject(mapOf("messageId" to kotlinx.serialization.json.JsonPrimitive(message))),
        )
        history.append(event("one", "a"))
        assertThat(history.append(event("two", "b"))).hasSize(2)
    }

    @Test
    fun pagesRetainEveryItemAndFullTextBeyondPreview() {
        val directory = java.nio.file.Files.createTempDirectory("agent-history-test").toFile()
        val history = AgentEventHistory(limit = 3, textLimit = 5, directory = directory)
        try {
            var page = emptyList<AgentEvent>()
            repeat(10) { index -> page = history.append(AgentEvent("user", "message-$index", id = "$index")) }
            assertThat(page.map { it.id }).containsExactly("7", "8", "9")
            assertThat(history.fullText(page.last()).text).isEqualTo("message-9")
            val earlier = mutableListOf<String>()
            while (history.hasEarlier) earlier += history.loadEarlier().map { it.id }
            assertThat(earlier).containsExactly("4", "5", "6", "1", "2", "3", "0")
        } finally {
            history.clear()
            assertThat(directory.listFiles()).isEmpty()
            directory.delete()
        }
    }

    @Test
    fun mergedUserChunksRecoverFullTextAndSnapshotArraysReplace() {
        val history = AgentEventHistory(textLimit = 3)
        try {
            history.append(AgentEvent("user", "hello", id = "u"))
            val user = history.append(AgentEvent("user", " world", id = "u")).single()
            assertThat(user.text).isEqualTo("rld")
            assertThat(history.fullText(user).text).isEqualTo("hello world")
            fun raw(text: String) = kotlinx.serialization.json.Json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject
            history.append(AgentEvent("tool", id = "t", raw = raw("""{"content":[{"args":"first"}],"rawInput":{"a":1}}""")))
            val tool = history.append(AgentEvent("tool_update", id = "t", raw = raw("""{"content":[{"args":"latest"}]}"""))).last()
            assertThat(history.fullText(tool).raw.toString()).contains("latest", "rawInput").doesNotContain("first")
        } finally {
            history.clear()
        }
    }

    @Test
    fun capacityAndCorruptionAreExplicitAndKeepPreviouslyWrittenItems() {
        val directory = java.nio.file.Files.createTempDirectory("agent-history-test").toFile()
        val history = AgentEventHistory(directory = directory, capacityBytes = 1024)
        try {
            val first = history.append(AgentEvent("user", "first", id = "1")).single()
            try {
                history.append(AgentEvent("user", "x".repeat(2000), id = "2"))
                throw AssertionError("Capacity must fail")
            } catch (e: java.io.IOException) {
                assertThat(e.message).contains("capacity")
            }
            assertThat(history.fullText(first).text).isEqualTo("first")
            java.io.RandomAccessFile(directory.listFiles()!!.single { it.extension == "log" }, "rw").use { file ->
                file.seek(16)
                file.writeByte(0)
            }
            try {
                history.fullText(first)
                throw AssertionError("Corruption must fail")
            } catch (e: java.io.IOException) {
                assertThat(e.message).contains("Corrupt")
            }
        } finally {
            history.clear()
            directory.delete()
        }
    }

    @Test
    fun archivesTwentyFiveMegabytesWithBoundedWindowAndReadsEveryEarlierPage() {
        val directory = java.nio.file.Files.createTempDirectory("agent-large-history-test").toFile()
        val history = AgentEventHistory(limit = 37, directory = directory)
        val count = 4200
        val text = "x".repeat(6300)
        try {
            var page = emptyList<AgentEvent>()
            repeat(count) { index -> page = history.append(AgentEvent("content", text, id = "$index")) }
            assertThat(page).hasSize(37)
            assertThat(page.last().id).isEqualTo((count - 1).toString())
            var consumed = page.size
            var earliest = count - page.size
            while (history.hasEarlier) {
                page = history.loadEarlier()
                assertThat(page.last().id.toInt()).isEqualTo(earliest - 1)
                page.zipWithNext().forEach { (first, second) -> assertThat(second.id.toInt()).isEqualTo(first.id.toInt() + 1) }
                earliest = page.first().id.toInt()
                consumed += page.size
            }
            assertThat(consumed).isEqualTo(count)
            assertThat(page.first().id).isEqualTo("0")
            assertThat(history.fullText(page.first()).text).isEqualTo(text)
            assertThat(count.toLong() * text.length).isGreaterThan(25L * 1024 * 1024)
        } finally {
            history.clear()
            directory.delete()
        }
    }

    @Test
    fun pendingApprovalsSurvivePagingAndResolvedAuditIsReadable() {
        val history = AgentEventHistory(limit = 2)
        try {
            val permission = history.append(AgentEvent("permission", "approve?", id = "p")).single()
            repeat(7) { history.append(AgentEvent("tool", id = "$it")) }
            val page = history.loadEarlier()
            assertThat(page).anyMatch { it === permission }
            history.resolve(permission)
            while (history.hasEarlier) history.loadEarlier()
            assertThat(history.fullText(permission).type).isEqualTo("permission_resolved")
            assertThat(history.fullText(permission).text).isEqualTo("approve?")
        } finally {
            history.clear()
        }
    }

    @Test
    fun returnsToLatestAfterEveryEarlierPageWithoutNeedingLiveEvents() {
        val history = AgentEventHistory(textLimit = 5)
        try {
            val permission = history.append(AgentEvent("permission", "approve?", id = "p")).single()
            val question = history.append(AgentEvent("question", "answer?", id = "q")).last()
            repeat(1205) { history.append(AgentEvent("user", "message-$it", id = "$it")) }
            var page = history.loadEarlier()
            assertThat(history.hasLatest).isTrue()
            assertThat(page.filter { it.type == "user" }.map { it.id }).containsExactlyElementsOf((205..704).map { "$it" })
            while (history.hasEarlier) page = history.loadEarlier()
            assertThat(page.first()).isSameAs(permission)
            assertThat(page[1]).isSameAs(question)
            assertThat(page.filter { it.type == "user" }.first().id).isEqualTo("0")
            assertThat(page.filter { it.type == "user" }.last().id).isEqualTo("204")
            assertThat(history.fullText(page.last()).text).isEqualTo("message-204")
            val latest = history.loadLatest()
            assertThat(history.hasLatest).isFalse()
            assertThat(history.hasEarlier).isTrue()
            assertThat(latest.filter { it.type == "user" }.map { it.id }).containsExactlyElementsOf((705..1204).map { "$it" })
            assertThat(latest.first()).isSameAs(permission)
            assertThat(latest[1]).isSameAs(question)
            assertThat(history.isPending(permission, "p", "permission")).isTrue()
            assertThat(history.isPending(permission.copy(), "p", "permission")).isFalse()
            assertThat(history.isPending(question, "q", "question")).isTrue()
            assertThat(history.fullText(latest.last()).text).isEqualTo("message-1204")
        } finally {
            history.clear()
        }
    }

    @Test
    fun liveEventsKeepTheOlderPageAndPendingInstancesUntilReturningToLatest() {
        val history = AgentEventHistory(limit = 4, textLimit = 5)
        try {
            repeat(10) { history.append(AgentEvent("user", "message-$it", id = "$it")) }
            val earlier = history.loadEarlier()
            assertThat(earlier.map { it.id }).containsExactly("2", "3", "4", "5")
            val live = history.append(AgentEvent("user", "new input", id = "10"))
            assertThat(live).containsExactlyElementsOf(earlier)
            history.append(AgentEvent("content", "new output", id = "reply"))
            val withPermission = history.append(AgentEvent("permission", "approve?", id = "p"))
            val permission = withPermission.last()
            val withQuestion = history.append(AgentEvent("question", "answer?", id = "q"))
            val question = withQuestion.last()
            assertThat(withQuestion.take(4)).containsExactlyElementsOf(earlier)
            assertThat(history.hasLatest).isTrue()
            assertThat(history.isPending(permission, "p", "permission")).isTrue()
            history.loadEarlier()
            val latest = history.loadLatest()
            assertThat(latest.map { it.id }).containsExactly("10", "reply", "p", "q")
            assertThat(latest[2]).isSameAs(permission)
            assertThat(latest[3]).isSameAs(question)
            assertThat(history.fullText(latest.first()).text).isEqualTo("new input")
            assertThat(history.fullText(latest[1]).text).isEqualTo("new output")
            assertThat(history.isPending(question, "q", "question")).isTrue()
            val resolved = history.resolve(permission)
            assertThat(resolved).doesNotContain(permission)
            assertThat(history.isPending(permission, "p", "permission")).isFalse()
            assertThat(history.fullText(permission).type).isEqualTo("permission_resolved")
        } finally {
            history.clear()
        }
    }

    @Test
    fun brokenStorageAndCancelledReadsFailExplicitly() {
        val blocked = java.nio.file.Files.createTempFile("agent-history-blocked", ".file").toFile()
        try {
            try {
                AgentEventHistory(directory = blocked).append(AgentEvent("user", "text"))
                throw AssertionError("Storage failure must be reported")
            } catch (e: java.io.IOException) {
                assertThat(e.message).contains("private agent history")
            }
        } finally {
            blocked.delete()
        }
        val history = AgentEventHistory()
        try {
            val event = history.append(AgentEvent("content", "one", id = "m")).single()
            history.append(AgentEvent("content", "two", id = "m"))
            try {
                history.fullText(event) { throw kotlinx.coroutines.CancellationException("cancel read") }
                throw AssertionError("Read must be cancellable")
            } catch (_: kotlinx.coroutines.CancellationException) {
                assertThat(history.fullText(event).text).isEqualTo("onetwo")
            }
        } finally {
            history.clear()
        }
    }

    @Test
    fun capsTextAndKeepsDistinctTurns() {
        val history = AgentEventHistory(textLimit = 5)
        history.append(AgentEvent("thinking", "1234"))
        assertThat(history.append(AgentEvent("thinking", "5678")).single().text).isEqualTo("45678")
        history.append(AgentEvent("user", "next"))
        assertThat(history.append(AgentEvent("thinking", "other"))).hasSize(3)
        history.clear()
        assertThat(history.append(AgentEvent("content", "new"))).hasSize(1)
    }
}
