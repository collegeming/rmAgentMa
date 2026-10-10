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

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.rmagentma.core.AgentEvent
import org.rmagentma.ui.DiffLine
import org.rmagentma.ui.DiffLineKind
import org.rmagentma.ui.projectDiff

internal data class SessionPlanEntry(
    val entries: List<AgentPlanEntry>,
    val priority: String?,
    val status: String?,
)

internal data class SessionUsage(
    val used: String?,
    val size: String?,
    val costAmount: String?,
    val costCurrency: String?,
)

internal data class SessionCommand(
    val command: String,
    val status: String?,
    val output: String?,
    val test: Boolean,
)

internal data class SessionToolResult(
    val name: String,
    val status: String?,
    val input: JsonElement?,
    val output: JsonElement?,
    val content: JsonElement?,
)

internal data class SessionApproval(
    val type: String,
    val title: String,
    val historyKey: Long,
    val pending: Boolean,
)

internal data class FileChange(
    val path: String,
    val diff: List<DiffLine>,
    val added: Int,
    val removed: Int,
)

internal data class SessionDetails(
    val plan: SessionPlanEntry?,
    val completedPlanEntries: Int,
    val changes: List<FileChange>,
    val commands: List<SessionCommand>,
    val tools: List<SessionToolResult>,
    val usage: SessionUsage?,
    val approvals: List<SessionApproval>,
    val truncated: Boolean,
    val hasEarlier: Boolean,
)

internal fun projectSessionDetails(events: List<AgentEvent>, hasEarlier: Boolean): SessionDetails {
    var plan: SessionPlanEntry? = null
    var completed = 0
    var usage: SessionUsage? = null
    var truncated = false
    val changes = linkedMapOf<String, FileChange>()
    val commands = mutableListOf<SessionCommand>()
    val tools = mutableListOf<SessionToolResult>()
    val approvals = mutableListOf<SessionApproval>()
    events.forEach { event ->
        val raw = event.raw
        truncated = truncated || hasTruncatedAgentData(raw)
        when (event.type) {
            "plan" -> {
                val entries = projectPlan(raw)
                if (entries.isNotEmpty()) {
                    plan = SessionPlanEntry(
                        entries,
                        entries.mapNotNull { it.priority }.distinct().joinToString(", ").take(256).ifEmpty { null },
                        entries.mapNotNull { it.status }.distinct().joinToString(", ").take(256).ifEmpty { null },
                    )
                    completed = entries.count { it.status in COMPLETED_PLAN_STATUSES }
                }
            }

            "usage" -> {
                val reported = projectUsage(raw)
                if (reported != AgentUsage(null, null, null, null)) {
                    usage = SessionUsage(reported.used, reported.size, reported.costAmount, reported.costCurrency)
                }
            }

            "tool", "tool_update" -> {
                val locations = reportedLocations(raw)
                val blocks = if (event.type == "tool_update") emptyList() else eventDiffChanges(raw)
                if (blocks.isEmpty()) {
                    locations.forEach { location -> mergeChange(changes, location, emptyList()) }
                } else {
                    blocks.forEachIndexed { index, block ->
                        mergeChange(changes, block.path.ifBlank { locations.getOrNull(index) ?: "" }, block.lines)
                    }
                }
                eventCommand(event)?.let { command ->
                    commands += SessionCommand(command, event.status.ifBlank { null }, commandOutput(events, event.id), runsTests(event, command))
                }
                tools += SessionToolResult(
                    name = event.title.ifBlank { event.status }.ifBlank { event.id },
                    status = event.status.ifBlank { null },
                    input = raw?.get("rawInput"),
                    output = raw?.get("rawOutput"),
                    content = raw?.get("content"),
                )
            }

            "permission", "question" -> approvals += SessionApproval(event.type, event.title.ifBlank { event.id }, event.historyKey, false)
        }
    }
    return SessionDetails(
        plan = plan,
        completedPlanEntries = completed,
        changes = changes.values.toList(),
        commands = commands,
        tools = tools,
        usage = usage,
        approvals = approvals,
        truncated = truncated,
        hasEarlier = hasEarlier,
    )
}

/**
 * An approval record is actionable only while the conversation still holds a live handle and the
 * record is inside the loaded window; history replay must never turn a recorded request back into
 * a live one, and this projection never exposes a response action by itself.
 */
internal fun applyPendingApprovals(details: SessionDetails, conversationActive: Boolean): SessionDetails = details.copy(
    approvals = details.approvals.map { approval ->
        approval.copy(pending = conversationActive && approval.historyKey >= 0)
    },
)

internal fun looksLikeTest(event: AgentEvent): Boolean = eventCommand(event)?.let { runsTests(event, it) } ?: false

private val COMPLETED_PLAN_STATUSES = setOf("completed", "complete", "done")

private val TEST_COMMANDS = setOf("pytest", "py.test", "jest", "vitest", "mocha", "rspec", "ctest", "phpunit", "gradlew", "test", "t")
private val TEST_HOOKS = setOf("test", "spec", "check")
private val PASSED = Regex("""(?i)(\d+)\s+(?:tests?\s+)?(?:passed|passing)""")
private val FAILED = Regex("""(?i)(\d+)\s+(?:tests?\s+)?(?:failed|failing)""")

private fun reportedLocations(raw: JsonObject?): List<String> = (raw?.get("locations") as? JsonArray).orEmpty().mapNotNull { item ->
    val path = (item as? JsonObject)?.get("path") as? JsonPrimitive
    path?.content?.take(512)?.ifBlank { null }
}

private fun mergeChange(changes: MutableMap<String, FileChange>, path: String, lines: List<DiffLine>) {
    val merged = changes[path]?.diff.orEmpty() + lines
    changes[path] = FileChange(path, merged, merged.count { it.kind == DiffLineKind.ADDED }, merged.count { it.kind == DiffLineKind.REMOVED })
}

private fun eventCommand(event: AgentEvent): String? {
    val input = event.raw?.get("rawInput")
    val reported = when (input) {
        is JsonPrimitive -> input.contentOrNull

        is JsonObject -> (input["command"] as? JsonPrimitive)?.contentOrNull
            ?: (input["cmd"] as? JsonPrimitive)?.contentOrNull

        else -> null
    }
    return reported?.takeIf { it.isNotBlank() }?.take(4_000)
        ?: event.title.takeIf { it.isNotBlank() && runsTests(event, it) }?.take(4_000)
}

/** Test detection is a local heuristic over the command and the reported output; it never asserts a pass or failure. */
private fun runsTests(event: AgentEvent, command: String): Boolean {
    val raw = event.raw
    val output = (raw?.get("rawOutput") as? JsonPrimitive)?.contentOrNull.orEmpty() +
        (raw?.get("content") as? JsonPrimitive)?.contentOrNull.orEmpty()
    if (PASSED.containsMatchIn(output) || FAILED.containsMatchIn(output)) return true
    val words = command.trim().split(' ', '\t')
    if (words.any { it.substringAfterLast('/').lowercase() in TEST_COMMANDS }) return true
    return words.size >= 2 && words.first() in TEST_HOOKS
}

private fun commandOutput(events: List<AgentEvent>, id: String): String? {
    if (id.isBlank()) return null
    return events.lastOrNull { it.type in TOOL_TYPES && it.id == id }
        ?.let { (it.raw?.get("rawOutput") as? JsonPrimitive)?.contentOrNull }
        ?.takeIf { it.isNotBlank() }
        ?.take(4_000)
}

private val TOOL_TYPES = setOf("tool", "tool_update")

/**
 * One captured diff together with the path reported next to it. The ACP `diff` content block carries
 * a `path` that a plain diff projection loses, so the session detail sheet keeps the pair together.
 */
internal data class EventDiffChange(val path: String, val lines: List<DiffLine>)

internal fun eventDiffChanges(raw: JsonElement?): List<EventDiffChange> {
    val changes = mutableListOf<EventDiffChange>()
    fun visit(value: JsonElement?, path: String, depth: Int) {
        if (depth > 12) return
        when (value) {
            is JsonObject -> {
                val reported = (value["path"] as? JsonPrimitive)?.content?.take(512).orEmpty()
                val diff = (value["diff"] as? JsonPrimitive)?.content
                val oldText = (value["oldText"] as? JsonPrimitive)?.content
                    ?: (value["old_text"] as? JsonPrimitive)?.content
                val newText = (value["newText"] as? JsonPrimitive)?.content
                    ?: (value["new_text"] as? JsonPrimitive)?.content
                when {
                    !diff.isNullOrBlank() -> changes += EventDiffChange(reported, projectDiff(diff))

                    oldText != null || newText != null -> {
                        changes += EventDiffChange(
                            reported,
                            buildList {
                                oldText?.lines()?.forEach { add(DiffLine("-$it", DiffLineKind.REMOVED)) }
                                newText?.lines()?.forEach { add(DiffLine("+$it", DiffLineKind.ADDED)) }
                            },
                        )
                    }
                }
                value.forEach { (key, nested) ->
                    when {
                        key == "content" && nested is JsonPrimitive &&
                            nested.content.lineSequence().any { it.startsWith("@@") || it.startsWith("diff --git") } ->
                            changes += EventDiffChange(reported, projectDiff(nested.content))

                        nested is JsonObject || nested is JsonArray -> visit(nested, reported, depth + 1)
                    }
                }
            }

            is JsonArray -> value.forEach { visit(it, path, depth + 1) }

            else -> Unit
        }
    }
    visit(raw, "", 0)
    return changes.distinct()
}
