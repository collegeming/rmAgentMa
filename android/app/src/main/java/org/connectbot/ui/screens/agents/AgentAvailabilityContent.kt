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
import org.connectbot.agent.AgentLinkState
import org.connectbot.agent.AgentProbeState
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * One Agent's availability on the selected host: the detected installation, its version and the probe verdict.
 * [onRefresh] is the entry that probes this host again, for the screen that can ask the repository to.
 */
@Composable
internal fun AgentAvailabilityContent(status: AgentProbeStatus?, onRefresh: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    SelectionContainer(modifier) {
        Column {
            val row = status?.availability
            if (status == null) {
                Text(stringResource(R.string.agent_availability_unknown), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.agent_availability_unknown_hint), style = MaterialTheme.typography.bodySmall)
            } else {
                val failed = status.state != AgentProbeState.AVAILABLE && status.state != AgentProbeState.REFRESHING
                Text(
                    stringResource(probeStateText(status.state)),
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                // The previous session list is kept on failure; the top bar refresh is the retry entry for it.
                if (failed) Text(stringResource(R.string.agent_probe_retry_hint), style = MaterialTheme.typography.bodySmall)
                if (row != null) {
                    if (row.version.isNotBlank()) {
                        Text(stringResource(R.string.agent_available_version, row.version), style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    }
                    // The detected path is the connection's own configuration, so it reads without opening details.
                    if (row.executable.isNotBlank()) {
                        Text(stringResource(R.string.agent_available_executable, row.executable), style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    }
                    if (failed) {
                        Text(row.error.ifBlank { stringResource(R.string.agent_unavailable_no_reason) }, style = MaterialTheme.typography.bodySmall, maxLines = 4)
                    }
                    var expanded by remember(row.hostId, row.kind) { mutableStateOf(false) }
                    TextButton(onClick = { expanded = !expanded }) { Text(stringResource(R.string.agent_connection_details)) }
                    if (expanded) {
                        row.capabilities?.let {
                            Text(stringResource(R.string.agent_available_capabilities, it.toString().take(2_000)), style = MaterialTheme.typography.bodySmall, maxLines = 4)
                        }
                    }
                }
                onRefresh?.let { refresh ->
                    TextButton(onClick = refresh, enabled = status.state != AgentProbeState.REFRESHING) {
                        Text(stringResource(R.string.agent_probe_refresh))
                    }
                }
            }
        }
    }
}

/** Every distinct probe outcome keeps its own text, so a failure never reads like a missing installation. */
internal fun probeStateText(state: AgentProbeState): Int = when (state) {
    AgentProbeState.REFRESHING -> R.string.agent_probe_refreshing
    AgentProbeState.AVAILABLE -> R.string.agent_available
    AgentProbeState.NOT_INSTALLED -> R.string.agent_probe_not_installed
    AgentProbeState.START_FAILED -> R.string.agent_probe_start_failed
    AgentProbeState.NO_PROTOCOL_CAPABILITY -> R.string.agent_probe_no_protocol_capability
    AgentProbeState.CONNECT_FAILED -> R.string.agent_probe_connect_failed
    AgentProbeState.UNKNOWN_FAILURE -> R.string.agent_probe_unknown_failure
    AgentProbeState.TARGET_UNSUPPORTED -> R.string.agent_probe_target_unsupported
}

/** Link state of the selected host: a live fact while it lasts, otherwise the last recorded outcome as history. */
@Composable
internal fun AgentLinkStatusContent(link: AgentLinkPresentation?, modifier: Modifier = Modifier) {
    if (link == null) return
    val since = formatAgentLinkTime(link.sinceMillis)
    Text(
        when (link.state) {
            AgentLinkState.CONNECTED -> if (link.live) stringResource(R.string.agent_link_connected) else stringResource(R.string.agent_link_unknown_since, since)

            AgentLinkState.DISCONNECTED -> stringResource(R.string.agent_link_disconnected_since, since)

            AgentLinkState.RETRYING ->
                if (link.live) stringResource(R.string.agent_link_retrying) else stringResource(R.string.agent_link_retrying_last, link.attempts, since)

            AgentLinkState.NEEDS_USER ->
                if (link.live) stringResource(R.string.agent_link_needs_user) else stringResource(R.string.agent_link_needs_user_last, since)
        },
        color = if (link.state == AgentLinkState.NEEDS_USER) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
        modifier = modifier,
    )
}

/** Short locale-aware timestamp of a persisted link row, so "last seen" is a real time and not a guess. */
internal fun formatAgentLinkTime(millis: Long, locale: Locale = Locale.getDefault()): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale).format(Date(millis))
