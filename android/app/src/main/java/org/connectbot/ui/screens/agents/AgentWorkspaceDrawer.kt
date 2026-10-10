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
import androidx.compose.runtime.saveable.rememberSaveable
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

/** Sessions rendered per agent group; the rest stay reachable through the drawer search. */
private const val DRAWER_SESSION_LIMIT = 20

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
    // Collapse state survives rotation and process recreation; encoded as strings so Compose can save it directly.
    var collapsedWorkspaces by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var collapsedGroups by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val collapsedWorkspaceKeys = collapsedWorkspaces.mapNotNull(::decodeWorkspaceKey).toSet()
    val collapsedGroupKeys = collapsedGroups.mapNotNull(::decodeGroupKey).toSet()
    val toggleWorkspace = { key: AgentWorkspaceKey ->
        collapsedWorkspaces = toggleKey(collapsedWorkspaces, encodeWorkspaceKey(key))
    }
    val toggleGroup = { key: AgentGroupKey ->
        collapsedGroups = toggleKey(collapsedGroups, encodeGroupKey(key))
    }
    LaunchedEffect(drawerOpen, selectedHostId, selectedAgent) {
        if (drawerOpen) showAll = false
    }
    val workspaces = remember(sessions, query, showAll, selectedHostId, selectedAgent) {
        agentWorkspaceTree(sessions, query, if (showAll) null else selectedHostId, if (showAll) null else selectedAgent)
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
            if (workspaces.isEmpty()) item(key = "empty") { Text(stringResource(R.string.agent_no_sessions)) }
            workspaces.forEach { workspace ->
                val workspaceKey = workspace.key
                // A search must reveal its matches even inside a workspace or agent group collapsed earlier.
                val workspaceExpanded = query.isNotBlank() || workspaceKey !in collapsedWorkspaceKeys
                item(key = "workspace:${workspaceKey.hostId}:${workspaceKey.cwd}") {
                    Column {
                        TextButton(
                            onClick = { toggleWorkspace(workspaceKey) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column {
                                Text(
                                    stringResource(
                                        if (workspaceExpanded) R.string.agent_workspace_expanded else R.string.agent_workspace_collapsed,
                                        hosts.firstOrNull { it.id == workspaceKey.hostId }?.nickname ?: stringResource(R.string.agent_missing_host),
                                        workspace.sessions.size,
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(workspaceKey.cwd, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        TextButton(onClick = { onNew(workspaceKey) }, enabled = canOpenAgent(availability, workspaceKey.hostId, selectedAgent)) {
                            Text(stringResource(R.string.agent_new_in_workspace))
                        }
                    }
                }
                if (!workspaceExpanded) return@forEach
                workspace.groups.forEach { group ->
                    val groupKey = AgentGroupKey(workspaceKey, group.agent)
                    val groupExpanded = query.isNotBlank() || groupKey !in collapsedGroupKeys
                    item(key = "group:${workspaceKey.hostId}:${workspaceKey.cwd}:${group.agent.wireName}") {
                        TextButton(
                            onClick = { toggleGroup(groupKey) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                stringResource(
                                    if (groupExpanded) R.string.agent_workspace_expanded else R.string.agent_workspace_collapsed,
                                    agentName(group.agent),
                                    group.sessions.size,
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (!groupExpanded) return@forEach
                    items(
                        group.sessions.take(DRAWER_SESSION_LIMIT),
                        key = { "session:${it.hostId}:${it.agent.wireName}:${it.sessionId}:${it.cwd}" },
                    ) { session ->
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
                    if (group.sessions.size > DRAWER_SESSION_LIMIT) {
                        item(key = "group-limit:${workspaceKey.hostId}:${workspaceKey.cwd}:${group.agent.wireName}") {
                            Text(
                                stringResource(R.string.agent_group_truncated, DRAWER_SESSION_LIMIT),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 12.dp),
                            )
                        }
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

private data class AgentGroupKey(val workspace: AgentWorkspaceKey, val agent: AgentKind)

private fun encodeWorkspaceKey(key: AgentWorkspaceKey): String = "${key.hostId}\u0000${key.cwd}"

private fun decodeWorkspaceKey(value: String): AgentWorkspaceKey? {
    val hostId = value.substringBefore('\u0000').toLongOrNull() ?: return null
    return AgentWorkspaceKey(hostId, value.substringAfter('\u0000', ""))
}

private fun encodeGroupKey(key: AgentGroupKey): String = "${encodeWorkspaceKey(key.workspace)}\u0000${key.agent.wireName}"

private fun decodeGroupKey(value: String): AgentGroupKey? {
    val workspace = decodeWorkspaceKey(value.substringBeforeLast('\u0000', "")) ?: return null
    val agent = AgentKind.fromWire(value.substringAfterLast('\u0000', "")) ?: return null
    return AgentGroupKey(workspace, agent)
}

private fun toggleKey(keys: List<String>, key: String): List<String> = if (key in keys) keys - key else keys + key
