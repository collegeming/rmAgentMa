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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.connectbot.R
import org.rmagentma.core.AgentEvent
import org.rmagentma.ui.AgentDiff
import org.rmagentma.ui.AgentDisclosure
import org.rmagentma.ui.AgentMarkdown
import org.rmagentma.ui.AgentMessageCard
import org.rmagentma.ui.DiffLine
import org.rmagentma.ui.DiffLineKind
import org.rmagentma.ui.projectDiff

fun eventDiffs(raw: JsonElement?): List<List<DiffLine>> {
    val diffs = mutableListOf<List<DiffLine>>()
    fun visit(value: JsonElement?, depth: Int) {
        if (depth > 12) return
        when (value) {
            is JsonObject -> {
                val diff = (value["diff"] as? JsonPrimitive)?.content
                val oldText = (value["oldText"] as? JsonPrimitive)?.content
                    ?: (value["old_text"] as? JsonPrimitive)?.content
                val newText = (value["newText"] as? JsonPrimitive)?.content
                    ?: (value["new_text"] as? JsonPrimitive)?.content
                when {
                    !diff.isNullOrBlank() -> diffs += projectDiff(diff)

                    oldText != null || newText != null -> {
                        diffs.add(
                            buildList<DiffLine> {
                                oldText?.lines()?.forEach { add(DiffLine("-$it", DiffLineKind.REMOVED)) }
                                newText?.lines()?.forEach { add(DiffLine("+$it", DiffLineKind.ADDED)) }
                            },
                        )
                    }
                }
                value.forEach { (key, nested) ->
                    if (key == "content" && nested is JsonPrimitive &&
                        nested.content.lineSequence().any { it.startsWith("@@") || it.startsWith("diff --git") }
                    ) {
                        diffs += projectDiff(nested.content)
                    } else if (nested is JsonObject || nested is JsonArray) {
                        visit(nested, depth + 1)
                    }
                }
            }

            is JsonArray -> value.forEach { visit(it, depth + 1) }

            else -> Unit
        }
    }
    visit(raw, 0)
    return diffs.distinct()
}

@Composable
internal fun AgentEventContent(
    event: AgentEvent,
    responding: Boolean,
    onPermission: (String, String) -> Unit,
    onQuestion: (String, String) -> Unit,
    modifier: Modifier = Modifier,
    onFullText: (AgentEvent) -> Unit = {},
    interactionsEnabled: Boolean = true,
) {
    var expanded by remember(event.historyKey, event.id, event.type) { mutableStateOf(false) }
    var rawExpanded by remember(event.historyKey, event.id, event.type) { mutableStateOf(false) }
    val headingId = when (event.type) {
        "user" -> R.string.agent_event_user
        "content" -> R.string.agent_event_content
        "thinking" -> R.string.agent_event_thinking
        "tool", "tool_update" -> R.string.agent_event_tool
        "plan" -> R.string.agent_event_plan
        "permission" -> R.string.agent_event_permission
        "question" -> R.string.agent_event_question
        "usage" -> R.string.agent_event_usage
        "session_info" -> R.string.agent_event_session
        "complete" -> R.string.agent_event_complete
        "error" -> R.string.agent_event_error
        else -> R.string.agent_event_unknown
    }
    val heading = stringResource(headingId)
    val body: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectionContainer {
                Column {
                    if (event.title.isNotBlank()) Text(event.title.take(512), style = MaterialTheme.typography.titleSmall, maxLines = 3)
                    if (event.status.isNotBlank()) Text(event.status.take(256), style = MaterialTheme.typography.labelMedium, maxLines = 2)
                }
            }
            if (event.text.isNotEmpty()) AgentMarkdown(event.text.take(4_000))
            val longCard = event.text.length > 4_000 || (event.raw?.toString()?.length ?: 0) > 8_000 || hasTruncatedAgentData(event.raw)
            if (!longCard) {
                when (event.type) {
                    "plan" -> AgentPlanContent(event.raw)
                    "usage" -> AgentUsageContent(event.raw)
                    "session_info" -> AgentSessionInfoContent(event.raw)
                    "tool", "tool_update" -> AgentToolContent(event.raw)
                }
                val diffs = remember(event.raw) { eventDiffs(event.raw) }
                if (diffs.isNotEmpty()) {
                    Text(stringResource(R.string.agent_diff), style = MaterialTheme.typography.labelLarge)
                    diffs.forEach { AgentDiff(it.take(80)) }
                }
            }
            TextButton(onClick = { onFullText(event) }) { Text(stringResource(R.string.agent_full_text)) }
            when (event.type) {
                "permission" -> {
                    event.options.forEach { option ->
                        TextButton(
                            onClick = { onPermission(event.id, option.id) },
                            enabled = interactionsEnabled && !responding && event.id.isNotBlank(),
                        ) { Text(option.label) }
                    }
                    if (event.id.isBlank() || event.options.isEmpty()) Text(stringResource(R.string.agent_no_response))
                }

                "question" -> if (interactionsEnabled) AgentQuestionContent(event, responding, onQuestion) else Text(stringResource(R.string.agent_state_closed))
            }
            if (responding) Text(stringResource(R.string.agent_response_pending))
            if (event.raw != null) {
                AgentDisclosure(
                    heading = stringResource(R.string.agent_raw),
                    expanded = rawExpanded,
                    onToggle = { rawExpanded = !rawExpanded },
                ) {
                    SelectionContainer {
                        Text(event.raw.toString().take(4_000), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, maxLines = 24)
                    }
                }
            }
        }
    }
    if (event.type == "thinking" || event.type == "unknown" || headingId == R.string.agent_event_unknown) {
        AgentDisclosure(heading, expanded, { expanded = !expanded }, modifier) { body() }
    } else {
        AgentMessageCard(heading, modifier) { body() }
    }
}
