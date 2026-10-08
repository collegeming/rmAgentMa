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
        assertThat(result.map { it.id }).containsExactly("p", "q")
        assertThat(history.resolve(result.first()).map { it.id }).containsExactly("q")
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
        assertThat(tool.raw.toString()).contains("path", "done", "-old", "+new")
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
