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

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.connectbot.R
import org.rmagentma.core.AgentSession
import org.rmagentma.ui.AgentDiff
import org.rmagentma.ui.AgentDisclosure

private enum class DetailsTab {
    PLAN,
    CHANGES,
    COMMANDS,
    TOOLS,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgentSessionDetailsSheet(
    session: AgentSession,
    details: SessionDetails,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var tab by remember { mutableStateOf(DetailsTab.PLAN) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp).heightIn(max = 640.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.agent_details_title, session.title.ifBlank { stringResource(R.string.agent_untitled) }),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(session.cwd, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.agent_details_window), style = MaterialTheme.typography.bodySmall)
            if (details.hasEarlier) {
                Text(stringResource(R.string.agent_details_earlier_notice), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (details.truncated) {
                Text(stringResource(R.string.agent_truncated), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                DetailsTab.entries.forEach { entry ->
                    TextButton(onClick = { tab = entry }) {
                        Text(
                            stringResource(
                                when (entry) {
                                    DetailsTab.PLAN -> R.string.agent_details_tab_plan
                                    DetailsTab.CHANGES -> R.string.agent_details_tab_changes
                                    DetailsTab.COMMANDS -> R.string.agent_details_tab_commands
                                    DetailsTab.TOOLS -> R.string.agent_details_tab_tools
                                },
                            ),
                            style = if (entry == tab) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (tab) {
                    DetailsTab.PLAN -> PlanTab(details)
                    DetailsTab.CHANGES -> ChangesTab(details)
                    DetailsTab.COMMANDS -> CommandsTab(details)
                    DetailsTab.TOOLS -> ToolsTab(details)
                }
            }
        }
    }
}

@Composable
private fun PlanTab(details: SessionDetails) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val plan = details.plan
        if (plan == null) {
            EmptySection()
        } else {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    plan.entries.forEach { entry ->
                        entry.content?.takeIf { it.isNotBlank() }?.let { content ->
                            Text(content.take(512), style = MaterialTheme.typography.bodyMedium)
                        }
                        entry.status?.let { Text(stringResource(R.string.agent_plan_status, it), style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
            plan.priority?.let { Text(stringResource(R.string.agent_plan_priority, it), style = MaterialTheme.typography.labelMedium) }
            plan.status?.let { Text(stringResource(R.string.agent_details_plan_status, it), style = MaterialTheme.typography.labelMedium) }
            Text(
                stringResource(R.string.agent_details_plan_progress, details.completedPlanEntries, plan.entries.size),
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Text(stringResource(R.string.agent_plan_notice), style = MaterialTheme.typography.bodySmall)
        UsageSection(details)
        ApprovalsSection(details)
    }
}

@Composable
private fun UsageSection(details: SessionDetails) {
    val usage = details.usage ?: return
    Text(stringResource(R.string.agent_details_usage), style = MaterialTheme.typography.labelLarge)
    SelectionContainer {
        Column {
            usage.used?.let { Text(stringResource(R.string.agent_usage_used, it)) }
            usage.size?.let { Text(stringResource(R.string.agent_usage_size, it)) }
            usage.costAmount?.let { amount ->
                Text(stringResource(R.string.agent_usage_cost, listOfNotNull(amount, usage.costCurrency).joinToString(" ")))
            }
        }
    }
}

@Composable
private fun ApprovalsSection(details: SessionDetails) {
    if (details.approvals.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.agent_details_approvals, details.approvals.size), style = MaterialTheme.typography.labelLarge)
        Text(stringResource(R.string.agent_details_approvals_readonly), style = MaterialTheme.typography.bodySmall)
        details.approvals.forEach { approval ->
            Column {
                Text(
                    stringResource(
                        if (approval.type == "question") R.string.agent_event_question else R.string.agent_event_permission,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(approval.title.take(256), style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(
                        if (approval.pending) R.string.agent_details_approval_pending else R.string.agent_details_approval_history,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ChangesTab(details: SessionDetails) {
    if (details.changes.isEmpty()) {
        EmptySection()
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            details.changes.forEach { change ->
                var expanded by remember(change.path) { mutableStateOf(false) }
                Column {
                    Text(
                        stringResource(R.string.agent_details_file_stats, change.path.ifBlank { stringResource(R.string.agent_untitled) }, change.added, change.removed),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        fontFamily = FontFamily.Monospace,
                    )
                    if (change.diff.isEmpty()) {
                        Text(stringResource(R.string.agent_details_file_reported), style = MaterialTheme.typography.bodySmall)
                    } else {
                        TextButton(onClick = { expanded = !expanded }) {
                            Text(stringResource(if (expanded) R.string.agent_details_hide_diff else R.string.agent_details_show_diff))
                        }
                        if (expanded) AgentDiff(change.diff.take(80))
                    }
                }
            }
        }
    }
}

@Composable
private fun CommandsTab(details: SessionDetails) {
    if (details.commands.isEmpty()) {
        EmptySection()
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val tests = details.commands.filter { it.test }
            if (tests.isNotEmpty()) {
                Text(stringResource(R.string.agent_details_tests), style = MaterialTheme.typography.labelLarge)
                tests.forEachIndexed { index, test ->
                    Column {
                        Text(test.command, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        test.status?.let { Text(stringResource(R.string.agent_details_test_status, it), style = MaterialTheme.typography.labelMedium) }
                        OutputBlock(test.output, key = "test:$index:${test.command}")
                    }
                }
            }
            Text(stringResource(R.string.agent_details_commands), style = MaterialTheme.typography.labelLarge)
            details.commands.forEachIndexed { index, command ->
                Column {
                    Text(command.command, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    command.status?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
                    OutputBlock(command.output, key = "command:$index:${command.command}")
                }
            }
        }
    }
}

@Composable
private fun OutputBlock(output: String?, key: String) {
    if (output.isNullOrBlank()) return
    var expanded by remember(key) { mutableStateOf(false) }
    AgentDisclosure(
        heading = stringResource(R.string.agent_tool_output),
        expanded = expanded,
        onToggle = { expanded = !expanded },
    ) {
        SelectionContainer {
            Text(output, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, maxLines = 24)
        }
    }
}

@Composable
private fun ToolsTab(details: SessionDetails) {
    if (details.tools.isEmpty()) {
        EmptySection()
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            details.tools.forEachIndexed { index, tool ->
                var expanded by remember(index, tool.name) { mutableStateOf(false) }
                AgentDisclosure(
                    heading = tool.name.take(256).ifBlank { stringResource(R.string.agent_event_tool) },
                    expanded = expanded,
                    onToggle = { expanded = !expanded },
                ) {
                    tool.status?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
                    val reported = listOfNotNull(tool.input?.takeIf { it.isReported() }, tool.output?.takeIf { it.isReported() }, tool.content?.takeIf { it.isReported() })
                    if (reported.isEmpty()) {
                        Text(stringResource(R.string.agent_details_no_detail), style = MaterialTheme.typography.bodySmall)
                    } else {
                        SelectionContainer {
                            Column {
                                reported.forEach { value ->
                                    Text(
                                        (value as? JsonPrimitive)?.content ?: value.toString(),
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 16,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun JsonElement.isReported(): Boolean = when (this) {
    is JsonPrimitive -> content.isNotBlank()
    else -> toString().length > 2
}

@Composable
private fun EmptySection() {
    Row(Modifier.fillMaxWidth()) { Text(stringResource(R.string.agent_details_empty), style = MaterialTheme.typography.bodySmall) }
}
