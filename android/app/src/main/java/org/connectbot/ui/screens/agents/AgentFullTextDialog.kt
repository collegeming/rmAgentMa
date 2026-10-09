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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable
internal fun AgentFullTextDialog(text: String, onDismiss: () -> Unit) {
    var page by remember(text) { mutableIntStateOf(0) }
    val pageSize = 8_000
    val pageCount = ((text.length + pageSize - 1) / pageSize).coerceAtLeast(1)
    val offset = page * pageSize
    val scroll = rememberScrollState()
    androidx.compose.runtime.LaunchedEffect(page) { scroll.scrollTo(0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(org.connectbot.R.string.agent_full_text)) },
        text = {
            Column {
                SelectionContainer {
                    Text(
                        text.substring(offset.coerceAtMost(text.length), (offset + pageSize).coerceAtMost(text.length)),
                        modifier = Modifier.heightIn(max = 420.dp).verticalScroll(scroll),
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Row {
                    TextButton(onClick = { page-- }, enabled = page > 0) { Text(stringResource(org.connectbot.R.string.agent_text_previous)) }
                    Text(stringResource(org.connectbot.R.string.agent_text_page, page + 1, pageCount))
                    TextButton(onClick = { page++ }, enabled = page < pageCount - 1) { Text(stringResource(org.connectbot.R.string.agent_text_next)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(org.connectbot.R.string.agent_close_text)) } },
    )
}
