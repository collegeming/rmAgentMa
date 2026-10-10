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

package org.connectbot.ui.screens.agents

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.rmagentma.core.AgentEvent
import org.rmagentma.ui.DiffLineKind

class AgentSessionDetailsTest {
    private fun json(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject

    @Test
    fun projectsLatestPlanAndCountsOnlyItsCompletedEntries() {
        val events = listOf(
            AgentEvent("plan", raw = json("""{"entries":[{"content":"first","status":"completed"}]}""")),
            AgentEvent("plan", raw = json("""{"entries":[{"content":"a","priority":"high","status":"completed"},{"content":"b","status":"in_progress"}]}""")),
        )
        val details = projectSessionDetails(events, hasEarlier = false)
        val plan = requireNotNull(details.plan)
        assertEquals(listOf("a", "b"), plan.entries.map { it.content })
        assertEquals("high", plan.priority)
        assertEquals("completed, in_progress", plan.status)
        assertEquals(1, details.completedPlanEntries)
    }

    @Test
    fun groupsFileChangesByPathAndCountsOnlyRealDiffLines() {
        val events = listOf(
            AgentEvent("tool", id = "1", raw = json("""{"content":[{"type":"diff","path":"/work/a.kt","oldText":"old","newText":"new"}]}""")),
        )
        val change = projectSessionDetails(events, hasEarlier = false).changes.single()
        assertEquals("/work/a.kt", change.path)
        assertEquals(1, change.added)
        assertEquals(1, change.removed)
        assertEquals(listOf(DiffLineKind.REMOVED, DiffLineKind.ADDED), change.diff.map { it.kind })
    }

    @Test
    fun mergesChangesReportedForTheSamePathAndReadsNestedDiffBlocks() {
        val events = listOf(
            AgentEvent("tool", id = "1", raw = json("""{"content":[{"type":"diff","path":"/work/a.kt","diff":"@@ -1 +1 @@\n-a\n+b"},{"type":"diff","path":"/work/a.kt","diff":"@@ -2 +2 @@\n-c\n+d"}]}""")),
            AgentEvent("tool", id = "2", raw = json("""{"locations":[{"path":"/work/b.kt"}],"content":[{"type":"diff","diff":"@@ -1 +1 @@\n-old\n+new"}]}""")),
        )
        val details = projectSessionDetails(events, hasEarlier = false)
        assertEquals(listOf("/work/a.kt", "/work/b.kt"), details.changes.map { it.path })
        val merged = details.changes.first()
        assertEquals(2, merged.added)
        assertEquals(2, merged.removed)
        assertEquals(1, details.changes[1].added)
    }

    @Test
    fun usesReportedLocationPathWhenTheDiffBlockOmitsIt() {
        val events = listOf(
            AgentEvent("tool", id = "1", raw = json("""{"locations":[{"path":"/work/a.kt"}],"content":[{"type":"diff","oldText":"old","newText":"new"}]}""")),
        )
        assertEquals("/work/a.kt", projectSessionDetails(events, hasEarlier = false).changes.single().path)
    }

    @Test
    fun projectsCommandsFromRawInputAndMarksTestRuns() {
        val events = listOf(
            AgentEvent("tool", id = "1", title = "Run tests", raw = json("""{"rawInput":{"command":"pytest -q"},"rawOutput":"passed"}""")),
            AgentEvent("tool", id = "2", raw = json("""{"rawInput":{"command":"ls -la"}}""")),
        )
        val commands = projectSessionDetails(events, hasEarlier = false).commands
        assertEquals(listOf("pytest -q", "ls -la"), commands.map { it.command })
        assertTrue(commands.first().test)
        assertFalse(commands[1].test)
        assertTrue(looksLikeTest(events.first()))
        assertFalse(looksLikeTest(events[1]))
    }

    @Test
    fun fallsBackToACountableTitleOnlyWhenItLooksLikeTestOrTaskCommand() {
        val events = listOf(
            AgentEvent("tool", id = "1", title = "npm test"),
            AgentEvent("tool", id = "2", title = "Read file"),
        )
        val commands = projectSessionDetails(events, hasEarlier = false).commands
        assertEquals(listOf("npm test"), commands.map { it.command })
    }

    @Test
    fun attachesTheLatestReportedOutputAndKeepsToolSections() {
        val events = listOf(
            AgentEvent("tool", id = "7", raw = json("""{"rawInput":{"command":"./gradlew test"},"rawOutput":"1 test failed"}""")),
            AgentEvent("tool_update", id = "7", raw = json("""{"rawOutput":"2 tests failed"}""")),
        )
        val details = projectSessionDetails(events, hasEarlier = false)
        assertEquals("2 tests failed", details.commands.single().output)
        assertEquals("./gradlew test", details.commands.single().command)
        assertTrue(details.commands.single().test)
        assertEquals(2, details.tools.size)
        assertEquals(events.first().raw?.get("rawInput"), details.tools.first().input)
    }

    @Test
    fun reportsLatestUsageAndNoUsageWhenNothingWasReported() {
        val events = listOf(
            AgentEvent("usage", raw = json("""{"used":10,"size":100}""")),
            AgentEvent("usage", raw = json("""{"used":20,"size":100,"cost":{"amount":0.5,"currency":"USD"}}""")),
        )
        val usage = requireNotNull(projectSessionDetails(events, hasEarlier = false).usage)
        assertEquals("20", usage.used)
        assertEquals("100", usage.size)
        assertEquals("0.5", usage.costAmount)
        assertEquals("USD", usage.costCurrency)
        assertNull(projectSessionDetails(listOf(AgentEvent("content", text = "hello")), hasEarlier = false).usage)
    }

    @Test
    fun keepsApprovalRecordsReadOnlyOutsideAnActiveConversation() {
        val permission = AgentEvent("permission", id = "1", title = "Allow edit", historyKey = 3)
        val historyTemplate = projectSessionDetails(listOf(permission), hasEarlier = false)
        assertFalse(historyTemplate.approvals.single().pending)

        val active = applyPendingApprovals(historyTemplate, conversationActive = true)
        assertEquals("permission", active.approvals.single().type)
        assertEquals(3L, active.approvals.single().historyKey)
        assertTrue(active.approvals.single().pending)
    }

    @Test
    fun recordsQuestionsAndNeverInventsADecision() {
        val question = AgentEvent("question", id = "2", title = "Pick target", status = "pending", historyKey = 4)
        val details = applyPendingApprovals(projectSessionDetails(listOf(question), hasEarlier = false), conversationActive = true)
        val approval = details.approvals.single()
        assertEquals("question", approval.type)
        assertEquals("Pick target", approval.title)
        assertTrue(approval.pending)
    }

    @Test
    fun flagsTruncatedEventsAndCarriesTheWindowRange() {
        val truncatedEvent = AgentEvent("tool", id = "1", raw = json("""{"rawOutput":"partial","truncated":true}"""))
        val truncated = projectSessionDetails(listOf(truncatedEvent), hasEarlier = true)
        assertTrue(truncated.truncated)
        assertTrue(truncated.hasEarlier)
        val intact = projectSessionDetails(listOf(AgentEvent("content", text = "ok")), hasEarlier = false)
        assertFalse(intact.truncated)
        assertFalse(intact.hasEarlier)
    }

    @Test
    fun leavesConversationEventsOutOfTheAggregates() {
        val events = listOf(
            AgentEvent("content", text = "answer"),
            AgentEvent("thinking", text = "reasoning"),
            AgentEvent("user", text = "question"),
            AgentEvent("complete", status = "done"),
            AgentEvent("error", text = "boom"),
        )
        val details = projectSessionDetails(events, hasEarlier = false)
        assertNull(details.plan)
        assertTrue(details.changes.isEmpty())
        assertTrue(details.commands.isEmpty())
        assertTrue(details.tools.isEmpty())
        assertTrue(details.approvals.isEmpty())
    }
}
