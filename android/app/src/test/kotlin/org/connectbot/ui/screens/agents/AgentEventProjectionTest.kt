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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.rmagentma.ui.DiffLine
import org.rmagentma.ui.DiffLineKind

class AgentEventProjectionTest {
    @Test
    fun projectsNestedToolContentOldAndNewText() {
        val raw = Json.parseToJsonElement("""{"content":[{"type":"diff","oldText":"old","newText":"new"}]}""")
        assertEquals(
            listOf(listOf(DiffLine("-old", DiffLineKind.REMOVED), DiffLine("+new", DiffLineKind.ADDED))),
            eventDiffs(raw),
        )
    }

    @Test
    fun projectsContentDiffTextWithoutTreatingOrdinaryTextAsDiff() {
        val raw = Json.parseToJsonElement("""{"content":"@@ -1 +1 @@\n-old\n+new"}""")
        assertEquals(
            listOf(DiffLineKind.HEADER, DiffLineKind.REMOVED, DiffLineKind.ADDED),
            eventDiffs(raw).single().map { it.kind },
        )
        assertTrue(eventDiffs(Json.parseToJsonElement("""{"content":"ordinary response"}""")).isEmpty())
    }

    @Test
    fun missingRawDoesNotInventFileChanges() {
        assertTrue(eventDiffs(null).isEmpty())
    }

    @Test
    fun projectsPlanPriorityContentAndStatusWithoutInventingApproval() {
        val raw = Json.parseToJsonElement("""{"entries":[{"priority":"high","content":"Fix tests","status":"in_progress"},{"content":"Ship"}]}""") as kotlinx.serialization.json.JsonObject
        assertEquals(
            listOf(AgentPlanEntry("Fix tests", "high", "in_progress"), AgentPlanEntry("Ship", null, null)),
            projectPlan(raw),
        )
        assertTrue(projectPlan(null).isEmpty())
    }

    @Test
    fun projectsOnlyActualUsageFieldsAndCurrency() {
        val raw = Json.parseToJsonElement("""{"used":123,"size":4096,"cost":{"amount":0.02,"currency":"USD"}}""") as kotlinx.serialization.json.JsonObject
        assertEquals(AgentUsage("123", "4096", "0.02", "USD"), projectUsage(raw))
        assertEquals(AgentUsage(null, null, null, null), projectUsage(null))
    }

    @Test
    fun singleStringEnumUsesPlainTextAndRejectsOtherValues() {
        val raw = Json.parseToJsonElement("""{"requestedSchema":{"type":"object","properties":{"choice":{"type":"string","enum":["yes","no"]}}}}""") as kotlinx.serialization.json.JsonObject
        val form = projectQuestion(raw)
        assertTrue(form.singleString)
        assertEquals(listOf("yes", "no"), form.fields.single().choices)
        assertTrue(validQuestionAnswer(form, "yes"))
        org.junit.Assert.assertFalse(validQuestionAnswer(form, "maybe"))
        org.junit.Assert.assertFalse(validQuestionAnswer(form, "{\"choice\":\"yes\"}"))
    }

    @Test
    fun multiFieldQuestionRequiresJsonPrimitiveTypesAndRequiredKeys() {
        val raw = Json.parseToJsonElement("""{"requestedSchema":{"type":"object","required":["allow","count"],"additionalProperties":false,"properties":{"allow":{"type":"boolean","title":"Allow execution"},"count":{"type":"integer"}}}}""") as kotlinx.serialization.json.JsonObject
        val form = projectQuestion(raw)
        org.junit.Assert.assertFalse(form.singleString)
        assertEquals("Allow execution", form.fields.first().label)
        assertTrue(form.fields.all { it.required })
        assertTrue(validQuestionAnswer(form, """{"allow":true,"count":2}"""))
        listOf("yes", "{}", """{"allow":"true","count":2}""", """{"allow":true,"count":1.5}""", """{"allow":true,"count":2,"extra":1}""").forEach {
            org.junit.Assert.assertFalse(validQuestionAnswer(form, it))
        }
    }

    @Test
    fun truncatedQuestionSchemaCannotBeSubmitted() {
        val raw = Json.parseToJsonElement("""{"requestedSchema":{"properties":{"answer":{"type":"string"}}},"_rmagentmaTruncated":true}""") as kotlinx.serialization.json.JsonObject
        assertFalse(validQuestionAnswer(projectQuestion(raw), "answer"))
    }

    @Test
    fun detectsNestedTruncationAndProjectsSessionTimestamps() {
        val raw = Json.parseToJsonElement("""{"title":"Updated title","createdAt":"2026-01-01T00:00:00Z","updatedAt":42,"_rmagentma":{"truncated":true}}""") as kotlinx.serialization.json.JsonObject
        assertTrue(hasTruncatedAgentData(raw))
        assertEquals(AgentSessionInfo("Updated title", "2026-01-01T00:00:00Z", "42"), projectSessionInfo(raw))
        org.junit.Assert.assertFalse(hasTruncatedAgentData(Json.parseToJsonElement("""{"truncated":false}""")))
    }
}
