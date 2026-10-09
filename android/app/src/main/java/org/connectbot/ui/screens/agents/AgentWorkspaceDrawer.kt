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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.connectbot.R
import org.connectbot.data.entity.Host
import org.rmagentma.core.AgentAvailability
import org.rmagentma.core.AgentKind
import org.rmagentma.core.AgentSession

@Composable
internal fun AgentWorkspaceDrawer(
    hosts: List<Host>,
    sessions: List<AgentSession>,
    availability: List<AgentAvailability>,
    active: AgentSession?,
    selectedHostId: Long?,
    selectedAgent: AgentKind,
    drawerOpen: Boolean,
    onOpen: (AgentSession) -> Unit,
    onNew: (AgentWorkspaceKey?) -> Unit,
    onSettings: () -> Unit,
    onKeys: () -> Unit,
    onHosts: () -> Unit,
    onTerminal: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var showAll by remember { mutableStateOf(false) }
    var collapsed by remember { mutableStateOf(emptySet<AgentWorkspaceKey>()) }
    LaunchedEffect(drawerOpen, selectedHostId, selectedAgent) {
        if (drawerOpen) showAll = false
    }
    val groups = remember(sessions, query, showAll, selectedHostId, selectedAgent) {
        groupAgentWorkspaces(sessions, query, if (showAll) null else selectedHostId, if (showAll) null else selectedAgent)
    }
    Column(modifier.padding(12.dp)) {
        Text(stringResource(R.string.agent_workspaces), style = MaterialTheme.typography.titleLarge)
        TextButton(onClick = { showAll = !showAll }) {
            Text(stringResource(if (showAll) R.string.agent_current_scope else R.string.agent_all_workspaces))
        }
        if (!showAll) {
            Text(
                stringResource(
                    R.string.agent_choice_value,
                    hosts.firstOrNull { it.id == selectedHostId }?.nickname ?: stringResource(R.string.agent_all),
                    agentName(selectedAgent),
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.agent_search)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = { onNew(null) }, enabled = canOpenAgent(availability, selectedHostId, selectedAgent)) {
            Text(stringResource(R.string.agent_new))
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (groups.isEmpty()) item(key = "empty") { Text(stringResource(R.string.agent_no_sessions)) }
            groups.forEach { group ->
                val workspace = group.key
                item(key = "workspace:${workspace.hostId}:${workspace.cwd}") {
                    Row {
                        TextButton(
                            onClick = { collapsed = if (workspace in collapsed) collapsed - workspace else collapsed + workspace },
                            modifier = Modifier.weight(1f),
                        ) {
                            Column {
                                Text(
                                    stringResource(
                                        if (workspace in collapsed) R.string.agent_workspace_collapsed else R.string.agent_workspace_expanded,
                                        hosts.firstOrNull { it.id == workspace.hostId }?.nickname ?: stringResource(R.string.agent_missing_host),
                                        group.sessions.size,
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(workspace.cwd, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        TextButton(onClick = { onNew(workspace) }, enabled = canOpenAgent(availability, workspace.hostId, selectedAgent)) {
                            Text(stringResource(R.string.agent_new_in_workspace))
                        }
                    }
                }
                if (workspace !in collapsed || query.isNotBlank()) {
                    items(group.sessions, key = { "session:${it.hostId}:${it.agent.wireName}:${it.sessionId}:${it.cwd}" }) { session ->
                        NavigationDrawerItem(
                            selected = sameAgentSession(active, session),
                            onClick = { if (canOpenAgent(availability, session.hostId, session.agent)) onOpen(session) },
                            label = {
                                Column {
                                    Text(
                                        session.title.ifBlank { stringResource(R.string.agent_untitled) },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(agentName(session.agent), style = MaterialTheme.typography.labelSmall)
                                    if (session.preview.isNotBlank()) {
                                        Text(session.preview.take(240), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                    val status = agentAvailability(availability, session.hostId, session.agent)
                                    if (status?.available == false) {
                                        Text(stringResource(R.string.agent_unavailable), style = MaterialTheme.typography.labelSmall)
                                        Text(status.error.ifBlank { stringResource(R.string.agent_unavailable_no_reason) }, maxLines = 3)
                                    }
                                    TextButton(
                                        onClick = { onOpen(session) },
                                        enabled = canOpenAgent(availability, session.hostId, session.agent),
                                    ) { Text(stringResource(R.string.agent_continue)) }
                                }
                            },
                        )
                    }
                }
            }
        }
        Row {
            TextButton(onClick = onSettings) { Text(stringResource(R.string.agent_settings)) }
            TextButton(onClick = onKeys) { Text(stringResource(R.string.agent_keys)) }
            TextButton(onClick = onHosts) { Text(stringResource(R.string.agent_hosts)) }
        }
        Row {
            TextButton(onClick = onTerminal) { Text(stringResource(R.string.agent_debug_terminal)) }
            TextButton(onClick = onDisconnect) { Text(stringResource(R.string.agent_disconnect)) }
        }
    }
}
