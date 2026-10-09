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
import org.connectbot.R
import org.rmagentma.core.AgentAvailability

@Composable
internal fun AgentAvailabilityContent(status: AgentAvailability?, modifier: Modifier = Modifier) {
    SelectionContainer(modifier) {
        Column {
            Text(
                stringResource(
                    when (status?.available) {
                        true -> R.string.agent_available
                        false -> R.string.agent_unavailable
                        null -> R.string.agent_availability_unknown
                    },
                ),
                color = if (status?.available == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            if (status == null) {
                Text(stringResource(R.string.agent_availability_unknown_hint), style = MaterialTheme.typography.bodySmall)
            } else {
                if (status.version.isNotBlank()) {
                    Text(stringResource(R.string.agent_available_version, status.version), style = MaterialTheme.typography.bodySmall, maxLines = 2)
                }
                if (!status.available) {
                    Text(status.error.ifBlank { stringResource(R.string.agent_unavailable_no_reason) }, style = MaterialTheme.typography.bodySmall, maxLines = 4)
                }
                var expanded by remember(status.hostId, status.kind) { mutableStateOf(false) }
                TextButton(onClick = { expanded = !expanded }) { Text(stringResource(R.string.agent_connection_details)) }
                if (expanded) {
                    if (status.executable.isNotBlank()) {
                        Text(stringResource(R.string.agent_available_executable, status.executable), style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    }
                    status.capabilities?.let {
                        Text(stringResource(R.string.agent_available_capabilities, it.toString().take(2_000)), style = MaterialTheme.typography.bodySmall, maxLines = 4)
                    }
                }
            }
        }
    }
}
