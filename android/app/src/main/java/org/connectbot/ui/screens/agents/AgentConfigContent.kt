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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.contentOrNull
import org.connectbot.R
import org.rmagentma.core.AgentConfigOption
import org.rmagentma.core.AgentConfigValue

/** Header entry of the open conversation: the current model and reasoning tier, never a value the Agent did not state. */
@Composable
internal fun AgentConfigHeader(
    state: AgentConfigUiState,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        AgentConfigUiState.Loading -> Text(
            stringResource(R.string.agent_config_header_loading),
            modifier = modifier.padding(horizontal = 12.dp),
            style = MaterialTheme.typography.bodySmall,
        )

        AgentConfigUiState.Unsupported -> Text(
            stringResource(R.string.agent_config_header_unsupported),
            modifier = modifier.padding(horizontal = 12.dp),
            style = MaterialTheme.typography.bodySmall,
        )

        AgentConfigUiState.Closed -> Text(
            stringResource(R.string.agent_config_header_closed),
            modifier = modifier.padding(horizontal = 12.dp),
            style = MaterialTheme.typography.bodySmall,
        )

        is AgentConfigUiState.Failed -> TextButton(
            onClick = onOpen,
            modifier = modifier,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
        ) {
            Text(stringResource(R.string.agent_config_header_failed), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        is AgentConfigUiState.Ready -> {
            val model = agentConfigRoleView(state.config, AgentConfigRole.MODEL)
            val reasoning = agentConfigRoleView(state.config, AgentConfigRole.REASONING)
            if (model == null && reasoning == null) {
                TextButton(
                    onClick = onOpen,
                    modifier = modifier,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                ) {
                    Text(stringResource(R.string.agent_config_header_unsupported), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else {
                TextButton(
                    onClick = onOpen,
                    modifier = modifier,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                ) {
                    Text(
                        stringResource(
                            R.string.agent_config_summary,
                            roleText(model),
                            roleText(reasoning),
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun roleText(view: AgentConfigRoleView?): String {
    val current = view?.current
    return when {
        view == null -> stringResource(R.string.agent_config_unsupported)
        current == null -> stringResource(R.string.agent_config_unset)
        else -> current
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgentConfigSheet(
    state: AgentConfigUiState,
    writing: String?,
    notice: AgentConfigNotice?,
    onSelect: (String, String) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
                .heightIn(max = 640.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.agent_config_title), style = MaterialTheme.typography.titleMedium)
            when (state) {
                AgentConfigUiState.Loading -> Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.agent_config_loading), style = MaterialTheme.typography.bodyMedium)
                }

                AgentConfigUiState.Unsupported -> Text(
                    stringResource(R.string.agent_config_unsupported_body),
                    style = MaterialTheme.typography.bodyMedium,
                )

                AgentConfigUiState.Closed -> Text(
                    stringResource(R.string.agent_config_closed_body),
                    style = MaterialTheme.typography.bodyMedium,
                )

                is AgentConfigUiState.Failed -> {
                    Text(
                        stringResource(
                            R.string.agent_config_read_failed,
                            state.reason ?: stringResource(R.string.agent_config_reason_unknown),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.agent_config_retry)) }
                }

                is AgentConfigUiState.Ready -> AgentConfigCatalog(state.config.options, writing, onSelect)
            }
            notice?.let { AgentConfigNoticeText(it) }
        }
    }
}

@Composable
private fun AgentConfigCatalog(
    options: List<AgentConfigOption>,
    writing: String?,
    onSelect: (String, String) -> Unit,
) {
    if (options.isEmpty()) {
        Text(stringResource(R.string.agent_config_empty), style = MaterialTheme.typography.bodyMedium)
    } else {
        options.forEach { option ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(option.name.ifBlank { option.id }, style = MaterialTheme.typography.titleSmall)
                if (option.description.isNotBlank()) {
                    Text(option.description, style = MaterialTheme.typography.bodySmall)
                }
                when {
                    option.values.isEmpty() -> {
                        Text(stringResource(R.string.agent_config_no_values), style = MaterialTheme.typography.bodySmall)
                        AgentConfigCurrentValue(option)
                    }

                    !isAgentConfigSwitchable(option) -> {
                        Text(stringResource(R.string.agent_config_type_readonly), style = MaterialTheme.typography.bodySmall)
                        AgentConfigCurrentValue(option)
                    }

                    else -> {
                        if (writing == option.id) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                Text(stringResource(R.string.agent_config_writing), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        option.values.forEach { value -> AgentConfigValueRow(option, value, writing, onSelect) }
                    }
                }
            }
        }
    }
}

/** The Agent's own current value, shown for an option that cannot be switched from here. */
@Composable
private fun AgentConfigCurrentValue(option: AgentConfigOption) {
    option.currentText()?.let {
        Text(stringResource(R.string.agent_config_current_value, it), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AgentConfigValueRow(
    option: AgentConfigOption,
    value: AgentConfigValue,
    writing: String?,
    onSelect: (String, String) -> Unit,
) {
    val current = option.currentValue?.contentOrNull == value.value
    val enabled = writing == null && !current
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = current, enabled = enabled, role = Role.RadioButton) { onSelect(option.id, value.value) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = current, onClick = null, enabled = writing == null)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    value.name.ifBlank { value.value },
                    style = if (current) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (current) {
                    Text(
                        stringResource(R.string.agent_config_current),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
            if (value.description.isNotBlank()) {
                Text(
                    value.description,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun AgentConfigNoticeText(notice: AgentConfigNotice) {
    val (text, color) = when (notice.outcome) {
        AgentConfigWriteOutcome.CONFIRMED -> stringResource(R.string.agent_config_write_confirmed) to MaterialTheme.colorScheme.primary

        AgentConfigWriteOutcome.NEXT_TURN -> stringResource(R.string.agent_config_write_next_turn) to MaterialTheme.colorScheme.secondary

        AgentConfigWriteOutcome.UNCONFIRMED -> stringResource(R.string.agent_config_write_unconfirmed) to MaterialTheme.colorScheme.secondary

        AgentConfigWriteOutcome.UNSUPPORTED -> stringResource(R.string.agent_config_write_unsupported) to MaterialTheme.colorScheme.error

        AgentConfigWriteOutcome.CLOSED -> stringResource(R.string.agent_config_write_closed) to MaterialTheme.colorScheme.error

        AgentConfigWriteOutcome.FAILED -> stringResource(
            R.string.agent_config_write_failed,
            notice.reason ?: stringResource(R.string.agent_config_reason_unknown),
        ) to MaterialTheme.colorScheme.error
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = color)
}
