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

package org.rmagentma.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

data class DiffLine(val text: String, val kind: DiffLineKind)

enum class DiffLineKind {
    CONTEXT,
    ADDED,
    REMOVED,
    HEADER,
}

fun projectDiff(diff: String): List<DiffLine> = diff.lines().map { line ->
    val kind = when {
        line.startsWith("+++ ") || line.startsWith("--- ") || line.startsWith("@@") || line.startsWith("diff --git ") -> DiffLineKind.HEADER
        line.startsWith("+") -> DiffLineKind.ADDED
        line.startsWith("-") -> DiffLineKind.REMOVED
        else -> DiffLineKind.CONTEXT
    }
    DiffLine(line, kind)
}

@Composable
fun AgentMessageCard(
    heading: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(heading, style = MaterialTheme.typography.labelLarge)
            content()
        }
    }
}

@Composable
fun AgentDisclosure(
    heading: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    AgentMessageCard(heading = heading, modifier = modifier) {
        TextButton(onClick = onToggle) {
            Text(stringResource(if (expanded) R.string.agent_ui_collapse else R.string.agent_ui_expand))
        }
        if (expanded) content()
    }
}

internal data class MarkdownLine(val text: String, val code: Boolean, val heading: Boolean)

internal fun projectMarkdown(text: String): List<MarkdownLine> = buildList {
    var inCode = false
    text.lines().forEach { line ->
        if (line.startsWith("```")) {
            inCode = !inCode
        } else {
            val heading = !inCode && line.startsWith("#")
            add(MarkdownLine(if (heading) line.trimStart('#', ' ') else line, inCode, heading))
        }
    }
}

@Composable
fun AgentMarkdown(text: String, modifier: Modifier = Modifier) {
    val lines = remember(text) { projectMarkdown(text) }
    SelectionContainer(modifier = modifier) {
        Column {
            lines.forEach { line ->
                Text(
                    text = line.text,
                    fontFamily = if (line.code) FontFamily.Monospace else FontFamily.Default,
                    fontWeight = if (line.heading) FontWeight.Bold else FontWeight.Normal,
                    style = if (line.heading) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().then(
                        if (line.code) Modifier.background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 8.dp) else Modifier,
                    ),
                )
            }
        }
    }
}

@Composable
fun AgentDiff(lines: List<DiffLine>, modifier: Modifier = Modifier) {
    SelectionContainer(modifier = modifier) {
        Column {
            lines.forEach { line ->
                val background = when (line.kind) {
                    DiffLineKind.ADDED -> MaterialTheme.colorScheme.primaryContainer
                    DiffLineKind.REMOVED -> MaterialTheme.colorScheme.errorContainer
                    DiffLineKind.HEADER -> MaterialTheme.colorScheme.secondaryContainer
                    DiffLineKind.CONTEXT -> MaterialTheme.colorScheme.surface
                }
                val foreground = when (line.kind) {
                    DiffLineKind.ADDED -> MaterialTheme.colorScheme.onPrimaryContainer
                    DiffLineKind.REMOVED -> MaterialTheme.colorScheme.onErrorContainer
                    DiffLineKind.HEADER -> MaterialTheme.colorScheme.onSecondaryContainer
                    DiffLineKind.CONTEXT -> MaterialTheme.colorScheme.onSurface
                }
                Text(
                    line.text,
                    color = foreground,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().background(background).padding(horizontal = 4.dp),
                )
            }
        }
    }
}
