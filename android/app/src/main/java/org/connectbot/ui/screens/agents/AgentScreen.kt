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

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import kotlinx.coroutines.launch
import org.connectbot.R
import org.connectbot.agent.AgentChallenge
import org.connectbot.agent.AgentConversationState
import org.connectbot.data.entity.Host
import org.rmagentma.core.AgentAvailability
import org.rmagentma.core.AgentEvent
import org.rmagentma.core.AgentKind
import org.rmagentma.core.AgentSession
import org.rmagentma.core.ShellCommands

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen(
    onNavigateBack: () -> Unit,
    onOpenTerminal: (Host, String?) -> Unit,
    modifier: Modifier = Modifier,
    onSettings: () -> Unit = {},
    onKeys: () -> Unit = {},
    onHosts: () -> Unit = {},
    viewModel: AgentViewModel = hiltViewModel(),
) {
    val hosts by viewModel.hosts.collectAsState()
    val sessions by viewModel.sessions.collectAsState()
    val availability by viewModel.availability.collectAsState()
    val events by viewModel.events.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val working by viewModel.working.collectAsState()
    val errors by viewModel.errors.collectAsState()
    val operationError by viewModel.operationError.collectAsState()
    val responding by viewModel.responding.collectAsState()
    val challenge by viewModel.challenge.collectAsState()
    val active by viewModel.activeSession.collectAsState()
    val selectedSession by viewModel.visibleSession.collectAsState()
    val conversationState by viewModel.conversationState.collectAsState()
    val hasEarlier by viewModel.hasEarlier.collectAsState()
    val hasLatest by viewModel.hasLatest.collectAsState()
    val fullText by viewModel.fullText.collectAsState()
    val reading by viewModel.reading.collectAsState()
    val hostId by viewModel.selectedHost.collectAsState()
    val agent by viewModel.selectedAgent.collectAsState()
    val pendingSelection by viewModel.pendingSelection.collectAsState()
    var showNew by remember { mutableStateOf(false) }
    var newWorkspace by remember { mutableStateOf<AgentWorkspaceKey?>(null) }
    var showDisconnect by remember { mutableStateOf(false) }
    var showDebugTerminal by remember { mutableStateOf(false) }
    var denied by remember { mutableStateOf(false) }
    var prompt by rememberSaveable { mutableStateOf("") }
    var pendingNetworkAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pendingNetworkAction
        pendingNetworkAction = null
        denied = !granted
        if (granted) action?.invoke()
    }
    val withNetworkPermission: (() -> Unit) -> Unit = { action ->
        if (Build.VERSION.SDK_INT >= 36 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingNetworkAction = action
            permissionLauncher.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            denied = false
            action()
        }
    }
    val closeDrawer: () -> Unit = { scope.launch { drawerState.close() } }
    val newConversation: (AgentWorkspaceKey?) -> Unit = { workspace ->
        newWorkspace = workspace
        showNew = true
        closeDrawer()
    }
    BackHandler(enabled = drawerState.isOpen || selectedSession != null || busy || working) {
        when {
            drawerState.isOpen -> closeDrawer()
            conversationState == AgentConversationState.Closed && !working -> onNavigateBack()
            else -> viewModel.closeConversation()
        }
    }
    LaunchedEffect(Unit) {
        if (viewModel.selectedSession.value == null) {
            withNetworkPermission { viewModel.refresh(viewModel.selectedHost.value) }
        }
    }
    val terminal: (Host, AgentKind, String, String?) -> Unit = { host, kind, cwd, id ->
        if (validAgentCwd(cwd)) {
            withNetworkPermission {
                onOpenTerminal(host, ShellCommands.start(kind, cwd, id?.takeIf { it.isNotBlank() }))
            }
        }
    }
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                AgentWorkspaceDrawer(
                    hosts = hosts,
                    sessions = sessions,
                    availability = availability,
                    active = selectedSession,
                    selectedHostId = hostId,
                    selectedAgent = agent,
                    drawerOpen = drawerState.isOpen,
                    onOpen = { session ->
                        closeDrawer()
                        withNetworkPermission { viewModel.open(session) }
                    },
                    onNew = newConversation,
                    onSettings = {
                        closeDrawer()
                        onSettings()
                    },
                    onKeys = {
                        closeDrawer()
                        onKeys()
                    },
                    onHosts = {
                        closeDrawer()
                        onHosts()
                    },
                    onTerminal = {
                        closeDrawer()
                        showDebugTerminal = true
                    },
                    onDisconnect = { showDisconnect = true },
                )
            }
        },
        modifier = modifier,
    ) {
        Scaffold(
            modifier = Modifier.imePadding(),
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            selectedSession?.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.agent_title),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, stringResource(R.string.agent_workspaces))
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { withNetworkPermission { viewModel.refresh(hostId) } },
                            enabled = !busy && !working,
                        ) { Icon(Icons.Default.Refresh, stringResource(R.string.agent_refresh)) }
                        IconButton(onClick = { newConversation(null) }, enabled = canOpenAgent(availability, hostId, agent)) {
                            Icon(Icons.Default.Add, stringResource(R.string.agent_new))
                        }
                    },
                )
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AgentChoice(
                        label = stringResource(R.string.agent_device),
                        value = hosts.firstOrNull { it.id == hostId }?.nickname ?: stringResource(R.string.agent_select_host),
                        options = listOf(null to stringResource(R.string.agent_all)) + hosts.map { it.id to it.nickname },
                        onSelect = { withNetworkPermission { viewModel.selectHost(it) } },
                        modifier = Modifier.weight(1f),
                    )
                    AgentChoice(
                        label = stringResource(R.string.agent_kind),
                        value = agentName(agent),
                        options = AgentKind.entries.map { it to agentName(it) },
                        onSelect = viewModel::selectAgent,
                        modifier = Modifier.weight(1f),
                    )
                }
                AgentAvailabilityContent(agentAvailability(availability, hostId, agent), Modifier.padding(horizontal = 12.dp))
                if (busy || working) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (denied) {
                    Text(stringResource(R.string.agent_network_denied), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
                    TextButton(onClick = { withNetworkPermission { viewModel.refresh(hostId) } }) {
                        Text(stringResource(R.string.agent_network_retry))
                    }
                }
                if (errors.isNotEmpty() || operationError != null) {
                    SelectionContainer {
                        Text(
                            (errors + listOfNotNull(operationError)).joinToString("\n").take(8_000),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(12.dp).heightIn(max = 120.dp).verticalScroll(rememberScrollState()),
                        )
                    }
                }
                val session = selectedSession
                if (session == null) {
                    val recent = remember(sessions, hostId, agent) { filterAgentSessions(sessions, hostId, agent, "").take(5) }
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item(key = "welcome") {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(stringResource(R.string.agent_chat_empty_title), style = MaterialTheme.typography.headlineSmall)
                                Text(stringResource(R.string.agent_chat_empty_hint))
                                if (hosts.isEmpty()) TextButton(onClick = onHosts) { Text(stringResource(R.string.agent_hosts)) }
                                Button(onClick = { newConversation(null) }, enabled = !working && canOpenAgent(availability, hostId, agent)) { Text(stringResource(R.string.agent_new)) }
                                TextButton(onClick = { scope.launch { drawerState.open() } }) { Text(stringResource(R.string.agent_workspaces)) }
                                if (recent.isNotEmpty()) Text(stringResource(R.string.agent_recent_conversations), style = MaterialTheme.typography.titleSmall)
                            }
                        }
                        itemsIndexed(recent, key = { _, item -> "recent:${item.hostId}:${item.agent.wireName}:${item.sessionId}:${item.cwd}" }) { _, item ->
                            Column {
                                Text(item.title.ifBlank { stringResource(R.string.agent_untitled) }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(item.cwd, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (item.preview.isNotBlank()) Text(item.preview.take(240), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                val status = agentAvailability(availability, item.hostId, item.agent)
                                if (status?.available == false) AgentAvailabilityContent(status)
                                TextButton(
                                    onClick = { withNetworkPermission { viewModel.open(item) } },
                                    enabled = canOpenAgent(availability, item.hostId, item.agent),
                                ) { Text(stringResource(R.string.agent_continue)) }
                            }
                        }
                    }
                } else {
                    Row(Modifier.padding(horizontal = 12.dp)) {
                        Text(
                            stringResource(
                                when (conversationState) {
                                    AgentConversationState.Loading -> R.string.agent_state_loading
                                    AgentConversationState.Ready -> R.string.agent_state_ready
                                    AgentConversationState.Failed -> R.string.agent_state_failed
                                    AgentConversationState.Closed -> R.string.agent_state_closed
                                },
                            ),
                            modifier = Modifier.weight(1f),
                        )
                        if (conversationState == AgentConversationState.Failed || conversationState == AgentConversationState.Closed) {
                            TextButton(onClick = { withNetworkPermission { viewModel.retryConversation() } }, enabled = !working && canOpenAgent(availability, session.hostId, session.agent)) {
                                Text(stringResource(R.string.agent_retry_conversation))
                            }
                        }
                        TextButton(
                            onClick = { hosts.firstOrNull { it.id == session.hostId }?.let { terminal(it, session.agent, session.cwd, session.sessionId) } },
                            enabled = hosts.any { it.id == session.hostId } && validAgentCwd(session.cwd),
                        ) {
                            Text(stringResource(R.string.agent_terminal))
                        }
                        TextButton(onClick = viewModel::closeConversation) { Text(stringResource(R.string.agent_close_text)) }
                    }
                    androidx.compose.runtime.key(session.hostId, session.agent, session.sessionId, session.cwd) {
                        AgentConversation(
                            session = active ?: session,
                            events = events,
                            responding = responding,
                            interactionsEnabled = conversationState == AgentConversationState.Loading || conversationState == AgentConversationState.Ready,
                            hasEarlier = hasEarlier,
                            hasLatest = hasLatest,
                            reading = reading,
                            onEarlier = viewModel::loadEarlier,
                            onLatest = viewModel::loadLatest,
                            onFullText = viewModel::readFullText,
                            onPermission = viewModel::respondPermission,
                            onQuestion = viewModel::respondQuestion,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                AgentComposer(
                    prompt = prompt,
                    onPrompt = { prompt = it },
                    busy = busy || working || (session == null && !canOpenAgent(availability, hostId, agent)),
                    hasSession = session != null,
                    ready = conversationState == AgentConversationState.Ready,
                    onSend = {
                        if (session == null) {
                            newConversation(null)
                        } else {
                            withNetworkPermission {
                                if (viewModel.send(prompt)) prompt = ""
                            }
                        }
                    },
                    onCancel = viewModel::cancel,
                )
            }
        }
    }
    if (showNew) {
        val initialHost = newWorkspace?.hostId ?: hostId
        NewAgentSessionDialog(
            hosts = hosts,
            sessions = sessions,
            availability = availability,
            active = active,
            initialHostId = initialHost,
            initialAgent = agent,
            initialCwd = newWorkspace?.cwd ?: defaultAgentCwd(initialHost, active, sessions),
            onDismiss = { showNew = false },
            onCreate = { host, kind, cwd ->
                showNew = false
                withNetworkPermission { viewModel.newSession(host.id, kind, cwd) }
            },
        )
    }
    if (pendingSelection != null) {
        AlertDialog(
            onDismissRequest = viewModel::keepConversation,
            title = { Text(stringResource(R.string.agent_switch_title)) },
            text = { Text(stringResource(R.string.agent_switch_warning)) },
            confirmButton = {
                TextButton(onClick = { withNetworkPermission { viewModel.confirmSelection() } }) {
                    Text(stringResource(R.string.agent_close_and_switch))
                }
            },
            dismissButton = { TextButton(onClick = viewModel::keepConversation) { Text(stringResource(R.string.agent_keep_conversation)) } },
        )
    }
    if (showDebugTerminal) {
        var terminalHostId by remember { mutableStateOf(active?.hostId ?: hostId) }
        AlertDialog(
            onDismissRequest = { showDebugTerminal = false },
            title = { Text(stringResource(R.string.agent_debug_terminal)) },
            text = {
                Column {
                    Text(stringResource(R.string.agent_debug_terminal_hint))
                    AgentChoice(
                        label = stringResource(R.string.agent_device),
                        value = hosts.firstOrNull { it.id == terminalHostId }?.nickname ?: stringResource(R.string.agent_select_host),
                        options = hosts.map { it.id to it.nickname },
                        onSelect = { terminalHostId = it },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = hosts.any { it.id == terminalHostId },
                    onClick = {
                        showDebugTerminal = false
                        hosts.firstOrNull { it.id == terminalHostId }?.let { host -> withNetworkPermission { onOpenTerminal(host, null) } }
                    },
                ) { Text(stringResource(R.string.agent_terminal)) }
            },
            dismissButton = { TextButton(onClick = { showDebugTerminal = false }) { Text(stringResource(R.string.agent_cancel)) } },
        )
    }
    if (showDisconnect) {
        AlertDialog(
            onDismissRequest = { showDisconnect = false },
            title = { Text(stringResource(R.string.agent_disconnect)) },
            text = { Text(stringResource(R.string.agent_disconnect_warning)) },
            confirmButton = {
                TextButton(onClick = {
                    showDisconnect = false
                    viewModel.disconnectAll()
                }) { Text(stringResource(R.string.agent_confirm)) }
            },
            dismissButton = { TextButton(onClick = { showDisconnect = false }) { Text(stringResource(R.string.agent_cancel)) } },
        )
    }
    fullText?.let { AgentFullTextDialog(it, viewModel::closeFullText) }
    challenge?.let { AgentChallengeDialog(it, viewModel::respondChallenge) }
}

@Composable
private fun AgentConversation(
    session: AgentSession,
    events: List<AgentEvent>,
    responding: Set<AgentInteractionKey>,
    interactionsEnabled: Boolean,
    hasEarlier: Boolean,
    hasLatest: Boolean,
    reading: Boolean,
    onEarlier: () -> Unit,
    onLatest: () -> Unit,
    onFullText: (AgentEvent) -> Unit,
    onPermission: (AgentInteractionKey, String) -> Unit,
    onQuestion: (AgentInteractionKey, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val followLatest = !listState.canScrollForward
    val pageStart = events.firstOrNull { it.type != "permission" && it.type != "question" }?.historyKey
    var previousPageStart by remember { mutableStateOf(pageStart) }
    var wasEarlier by remember { mutableStateOf(hasLatest) }
    LaunchedEffect(events.size, events.lastOrNull()?.text, pageStart, hasLatest, reading, followLatest) {
        if (!reading) {
            if (hasLatest) {
                if (!wasEarlier || previousPageStart != pageStart) listState.scrollToItem(0)
            } else if ((wasEarlier || followLatest) && events.isNotEmpty()) {
                listState.animateScrollToItem(events.lastIndex + if (hasEarlier) 1 else 0)
            }
            previousPageStart = pageStart
            wasEarlier = hasLatest
        }
    }
    Column(modifier.fillMaxSize()) {
        Text(session.cwd, modifier = Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall, maxLines = 2)
        if (hasLatest) {
            Text(stringResource(R.string.agent_viewing_earlier), modifier = Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onLatest, enabled = !reading) { Text(stringResource(R.string.agent_latest_messages)) }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (hasEarlier) {
                item(key = "earlier") {
                    TextButton(onClick = onEarlier, enabled = !reading) { Text(stringResource(R.string.agent_load_earlier)) }
                }
            }
            if (events.isEmpty()) item(key = "empty") { Text(stringResource(R.string.agent_conversation_empty)) }
            itemsIndexed(events, key = { index, event -> if (event.historyKey >= 0) "history:${event.historyKey}" else "event:$index:${event.type}:${event.id}" }) { _, event ->
                val interaction = AgentInteractionKey(session, event)
                AgentEventContent(
                    event = event,
                    responding = interaction in responding,
                    interactionsEnabled = interactionsEnabled,
                    onFullText = onFullText,
                    onPermission = { _, option -> onPermission(interaction, option) },
                    onQuestion = { _, answer -> onQuestion(interaction, answer) },
                )
            }
        }
    }
}

@Composable
private fun AgentComposer(
    prompt: String,
    onPrompt: (String) -> Unit,
    busy: Boolean,
    hasSession: Boolean,
    ready: Boolean,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = prompt,
            onValueChange = onPrompt,
            label = { Text(stringResource(R.string.agent_prompt)) },
            modifier = Modifier.weight(1f),
            maxLines = 6,
        )
        Column {
            Button(onClick = onSend, enabled = !busy && (!hasSession || (ready && prompt.isNotBlank()))) {
                Text(stringResource(if (hasSession) R.string.agent_send else R.string.agent_start))
            }
            if (busy && hasSession) TextButton(onClick = onCancel) { Text(stringResource(R.string.agent_stop)) }
        }
    }
}

@Composable
private fun NewAgentSessionDialog(
    hosts: List<Host>,
    sessions: List<AgentSession>,
    availability: List<AgentAvailability>,
    active: AgentSession?,
    initialHostId: Long?,
    initialAgent: AgentKind,
    initialCwd: String,
    onDismiss: () -> Unit,
    onCreate: (Host, AgentKind, String) -> Unit,
) {
    var selectedHostId by remember { mutableStateOf(initialHostId) }
    var kind by remember { mutableStateOf(initialAgent) }
    var cwd by remember { mutableStateOf(initialCwd) }
    val host = hosts.firstOrNull { it.id == selectedHostId }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.agent_new)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (hosts.isEmpty()) Text(stringResource(R.string.agent_no_devices))
                AgentChoice(
                    label = stringResource(R.string.agent_device),
                    value = host?.nickname ?: stringResource(R.string.agent_select_host),
                    options = hosts.map { it.id to it.nickname },
                    onSelect = {
                        selectedHostId = it
                        cwd = defaultAgentCwd(it, active, sessions)
                    },
                )
                AgentChoice(
                    label = stringResource(R.string.agent_kind),
                    value = agentName(kind),
                    options = AgentKind.entries.map { it to agentName(it) },
                    onSelect = { kind = it },
                )
                AgentAvailabilityContent(agentAvailability(availability, selectedHostId, kind))
                OutlinedTextField(
                    value = cwd,
                    onValueChange = { cwd = it },
                    label = { Text(stringResource(R.string.agent_cwd)) },
                    singleLine = true,
                    isError = cwd.isNotEmpty() && !validAgentCwd(cwd),
                    supportingText = { Text(stringResource(R.string.agent_cwd_error)) },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { host?.let { onCreate(it, kind, cwd) } },
                enabled = host != null && validAgentCwd(cwd) && canOpenAgent(availability, selectedHostId, kind),
            ) {
                Text(stringResource(R.string.agent_start))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.agent_cancel)) } },
    )
}

@Composable
private fun AgentChallengeDialog(challenge: AgentChallenge, onRespond: (String, String?) -> Unit) {
    var secret by remember(challenge.id) { mutableStateOf("") }
    val hostKey = challenge.kind == "host_key"
    val supported = hostKey || challenge.kind == "password" || challenge.kind == "key_passphrase"
    AlertDialog(
        onDismissRequest = { onRespond(challenge.id, null) },
        title = {
            Text(
                stringResource(
                    when (challenge.kind) {
                        "host_key" -> R.string.agent_host_key
                        "password" -> R.string.agent_password
                        "key_passphrase" -> R.string.agent_passphrase
                        else -> R.string.agent_auth
                    },
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                SelectionContainer {
                    Column {
                        Text(challenge.hostName, style = MaterialTheme.typography.titleSmall)
                        Text(challenge.message)
                    }
                }
                if (!supported) Text(stringResource(R.string.agent_unknown_challenge))
                if (supported && !hostKey) {
                    OutlinedTextField(
                        value = secret,
                        onValueChange = { secret = it },
                        label = { Text(stringResource(R.string.agent_secret)) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = {
            if (supported) {
                TextButton(onClick = {
                    onRespond(challenge.id, if (hostKey) "accept" else secret)
                    secret = ""
                }) {
                    Text(stringResource(if (hostKey) R.string.agent_accept else R.string.agent_confirm))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = {
                secret = ""
                onRespond(challenge.id, null)
            }) {
                Text(stringResource(if (hostKey) R.string.agent_reject else R.string.agent_cancel))
            }
        },
    )
}

@Composable
private fun <T> AgentChoice(
    label: String,
    value: String,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth(), enabled = options.isNotEmpty()) {
            Text(stringResource(R.string.agent_choice_value, label, value), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (id, title) ->
                DropdownMenuItem(text = { Text(title) }, onClick = {
                    expanded = false
                    onSelect(id)
                })
            }
        }
    }
}

@Composable
internal fun agentName(agent: AgentKind): String = stringResource(
    when (agent) {
        AgentKind.KIMI -> R.string.agent_name_kimi
        AgentKind.OPENCODE -> R.string.agent_name_opencode
        AgentKind.OMP -> R.string.agent_name_omp
        AgentKind.DSH -> R.string.agent_name_dsh
        AgentKind.ZCODE -> R.string.agent_name_zcode
    },
)
