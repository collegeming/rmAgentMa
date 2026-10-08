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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.connectbot.R
import org.rmagentma.core.AgentEvent
import org.rmagentma.ui.AgentDisclosure
import org.rmagentma.ui.AgentMarkdown
import java.math.BigDecimal
import java.text.NumberFormat

private fun formatReportedNumber(value: String): String = try {
    val number = BigDecimal(value)
    if (number.scale() !in -20..20 || number.precision() > 40) {
        value
    } else {
        NumberFormat.getNumberInstance().apply { maximumFractionDigits = number.scale().coerceAtLeast(0) }.format(number)
    }
} catch (_: NumberFormatException) {
    value
}

@Composable
internal fun AgentPlanContent(raw: JsonObject?, modifier: Modifier = Modifier) {
    val entries = remember(raw) { projectPlan(raw) }
    SelectionContainer(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            entries.forEach { entry ->
                Column {
                    entry.content?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    entry.priority?.let { Text(stringResource(R.string.agent_plan_priority, it), style = MaterialTheme.typography.labelMedium) }
                    entry.status?.let { Text(stringResource(R.string.agent_plan_status, it), style = MaterialTheme.typography.labelMedium) }
                }
            }
            Text(stringResource(R.string.agent_plan_notice), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun AgentUsageContent(raw: JsonObject?, modifier: Modifier = Modifier) {
    val usage = remember(raw) { projectUsage(raw) }
    SelectionContainer(modifier) {
        Column {
            usage.used?.let { Text(stringResource(R.string.agent_usage_used, formatReportedNumber(it))) }
            usage.size?.let { Text(stringResource(R.string.agent_usage_size, formatReportedNumber(it))) }
            usage.costAmount?.let { amount ->
                Text(stringResource(R.string.agent_usage_cost, listOfNotNull(formatReportedNumber(amount), usage.costCurrency).joinToString(" ")))
            }
        }
    }
}

@Composable
internal fun AgentSessionInfoContent(raw: JsonObject?, modifier: Modifier = Modifier) {
    val info = remember(raw) { projectSessionInfo(raw) }
    SelectionContainer(modifier) {
        Column {
            info.title?.let { Text(stringResource(R.string.agent_info_title, it)) }
            info.createdAt?.let { Text(stringResource(R.string.agent_info_created, it)) }
            info.updatedAt?.let { Text(stringResource(R.string.agent_info_updated, it)) }
        }
    }
}

@Composable
internal fun AgentToolContent(raw: JsonObject?, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        raw?.get("rawInput")?.let { AgentToolSection(stringResource(R.string.agent_tool_input), it) }
        raw?.get("rawOutput")?.let { AgentToolSection(stringResource(R.string.agent_tool_output), it) }
        raw?.get("content")?.let { AgentToolSection(stringResource(R.string.agent_tool_content), it) }
    }
}

@Composable
private fun AgentToolSection(heading: String, value: JsonElement, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    AgentDisclosure(heading, expanded, { expanded = !expanded }, modifier) {
        AgentMarkdown(if (value is JsonPrimitive) value.content else value.toString())
    }
}

@Composable
internal fun AgentQuestionContent(
    event: AgentEvent,
    responding: Boolean,
    onQuestion: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val form = remember(event.raw) { projectQuestion(event.raw) }
    var answer by remember(event.id, form) { mutableStateOf("") }
    val enabled = !responding && event.id.isNotBlank()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (form.schema == null || form.fields.isEmpty()) {
            Text(stringResource(R.string.agent_question_unsupported))
        } else {
            SelectionContainer {
                Column {
                    form.fields.forEach { field ->
                        Text(stringResource(R.string.agent_question_field, field.label, field.type))
                        if (!form.singleString && field.label != field.name) Text(field.name, style = MaterialTheme.typography.labelSmall)
                        if (field.required) Text(stringResource(R.string.agent_question_required), style = MaterialTheme.typography.labelSmall)
                        if (!form.singleString && field.choices.isNotEmpty()) Text(field.choices.joinToString(", "))
                    }
                    if (!form.singleString) Text(stringResource(R.string.agent_question_json_hint), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (form.singleString) {
                val choices = form.fields.single().choices
                choices.forEach { choice ->
                    TextButton(onClick = { onQuestion(event.id, choice) }, enabled = enabled && validQuestionAnswer(form, choice)) {
                        Text(choice)
                    }
                }
                if (choices.isEmpty()) {
                    event.options.forEach { option ->
                        TextButton(
                            onClick = { onQuestion(event.id, option.id) },
                            enabled = enabled && validQuestionAnswer(form, option.id),
                        ) { Text(option.label) }
                    }
                }
            }
            OutlinedTextField(
                value = answer,
                onValueChange = { answer = it },
                label = { Text(stringResource(if (form.singleString) R.string.agent_answer else R.string.agent_question_json)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                isError = answer.isNotEmpty() && !validQuestionAnswer(form, answer),
                supportingText = {
                    if (answer.isNotEmpty() && !validQuestionAnswer(form, answer)) Text(stringResource(R.string.agent_question_invalid))
                },
            )
            TextButton(onClick = { onQuestion(event.id, answer) }, enabled = enabled && validQuestionAnswer(form, answer)) {
                Text(stringResource(R.string.agent_send))
            }
        }
        if (event.id.isBlank()) Text(stringResource(R.string.agent_no_response))
    }
}
