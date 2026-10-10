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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.connectbot.R

/** Views available inside one open conversation; the ACP channel stays open while either is shown. */
internal enum class AgentSessionView {
    CHAT,
    TUI,
}

/**
 * The TUI is sent to a separate SSH terminal instead of being embedded here. Whether an ACP process
 * and an interactive TUI can share one session ID is unverified, so the pane states the command and
 * keeps the user's explicit confirmation step in the terminal route.
 */
@Composable
internal fun AgentTuiPane(
    command: String?,
    conversationActive: Boolean,
    hostAvailable: Boolean,
    cwdValid: Boolean,
    onOpenTerminal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.agent_tui_notice), style = MaterialTheme.typography.bodyMedium)
        if (conversationActive) {
            Text(stringResource(R.string.agent_tui_share_warning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        when {
            !hostAvailable -> Text(stringResource(R.string.agent_tui_no_host), style = MaterialTheme.typography.bodySmall)

            !cwdValid -> Text(stringResource(R.string.agent_tui_bad_cwd), style = MaterialTheme.typography.bodySmall)

            command == null -> Text(stringResource(R.string.agent_tui_no_host), style = MaterialTheme.typography.bodySmall)

            else -> {
                Text(stringResource(R.string.agent_tui_command), style = MaterialTheme.typography.labelLarge)
                SelectionContainer {
                    Text(command, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Button(onClick = onOpenTerminal, enabled = command != null && hostAvailable && cwdValid) {
                Text(stringResource(R.string.agent_tui_open))
            }
        }
    }
}
