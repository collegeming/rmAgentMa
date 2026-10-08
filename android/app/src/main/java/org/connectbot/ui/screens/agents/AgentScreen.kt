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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import org.connectbot.R
import org.connectbot.agent.AgentChallenge
import org.connectbot.data.entity.Host
import org.rmagentma.core.AgentEvent
import org.rmagentma.core.AgentKind
import org.rmagentma.core.AgentSession
import org.rmagentma.core.ShellCommands
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen(
    onNavigateBack: () -> Unit,
    onOpenTerminal: (Host, String?) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AgentViewModel = hiltViewModel(),
) {
    val hosts by viewModel.hosts.collectAsState()
    val sessions by viewModel.sessions.collectAsState()
    val events by viewModel.events.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val working by viewModel.working.collectAsState()
    val errors by viewModel.errors.collectAsState()
    val operationError by viewModel.operationError.collectAsState()
    val responding by viewModel.responding.collectAsState()
    val challenge by viewModel.challenge.collectAsState()
    val active by viewModel.activeSession.collectAsState()
    var hostId by remember { mutableStateOf(viewModel.initialHostId) }
    var agent by remember { mutableStateOf<AgentKind?>(null) }
    var query by remember { mutableStateOf("") }
    var showNew by remember { mutableStateOf(false) }
    var showDisconnect by remember { mutableStateOf(false) }
    var denied by remember { mutableStateOf(false) }
    var pendingNetworkAction by remember { mutableStateOf<(() -> Unit)?>(null) }
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
    val back: () -> Unit = {
        if (active != null) viewModel.closeConversation() else onNavigateBack()
    }
    BackHandler(onBack = back)
    LaunchedEffect(Unit) {
        if (viewModel.activeSession.value == null) {
            withNetworkPermission { viewModel.refresh(viewModel.initialHostId) }
        }
    }
    val terminal: (Host, AgentKind, String, String?) -> Unit = { host, kind, cwd, id ->
        if (validAgentCwd(cwd)) {
            withNetworkPermission {
                onOpenTerminal(host, ShellCommands.start(kind, cwd, id?.takeIf { it.isNotBlank() }))
            }
        }
    }
    Scaffold(
        modifier = modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = {
                    val liveTitle = if (active != null) events.lastOrNull { it.type == "session_info" && it.raw.valueText("title") != null }?.raw.valueText("title") else null
                    Text(liveTitle ?: active?.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.agent_title))
                },
                navigationIcon = {
                    IconButton(onClick = back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.agent_back))
                    }
                },
                actions = {
                    if (active == null) {
                        IconButton(
                            onClick = { withNetworkPermission { viewModel.refresh(hostId) } },
                            enabled = !busy && !working,
                        ) { Icon(Icons.Default.Refresh, stringResource(R.string.agent_refresh)) }
                        IconButton(onClick = { showNew = true }, enabled = !busy && !working) {
                            Icon(Icons.Default.Add, stringResource(R.string.agent_new))
                        }
                    } else {
                        TextButton(
                            onClick = {
                                val session = active
                                val host = hosts.firstOrNull { it.id == session?.hostId }
                                if (session != null && host != null) terminal(host, session.agent, session.cwd, session.sessionId)
                            },
                            enabled = hosts.any { it.id == active?.hostId } && active?.cwd?.let(::validAgentCwd) == true,
                        ) { Text(stringResource(R.string.agent_terminal)) }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (busy || working) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (denied) {
                SelectionContainer {
                    Text(
                        stringResource(R.string.agent_network_denied),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(12.dp),
                    )
                }
                TextButton(onClick = { withNetworkPermission { viewModel.refresh(hostId) } }) {
                    Text(stringResource(R.string.agent_network_retry))
                }
            }
            if (errors.isNotEmpty() || operationError != null) {
                SelectionContainer {
                    Text(
                        (errors + listOfNotNull(operationError)).joinToString("\n"),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(12.dp).heightIn(max = 120.dp).verticalScroll(rememberScrollState()),
                    )
                }
            }
            val session = active
            if (session == null) {
                AgentSessionList(
                    hosts = hosts,
                    sessions = remember(sessions, hostId, agent, query) { filterAgentSessions(sessions, hostId, agent, query) },
                    hostId = hostId,
                    agent = agent,
                    query = query,
                    onHost = { hostId = it },
                    onAgent = { agent = it },
                    onQuery = { query = it },
                    onContinue = { selected ->
                        val host = hosts.firstOrNull { it.id == selected.hostId }
                        if (host != null) {
                            if (supportsStructuredConversation(selected.agent)) {
                                withNetworkPermission { viewModel.open(selected) }
                            } else {
                                terminal(host, selected.agent, selected.cwd, selected.sessionId)
                            }
                        }
                    },
                    onTerminal = { selected ->
                        hosts.firstOrNull { it.id == selected.hostId }?.let { terminal(it, selected.agent, selected.cwd, selected.sessionId) }
                    },
                    onDisconnect = { showDisconnect = true },
                    enabled = !working && !busy,
                    modifier = Modifier.weight(1f),
                )
            } else {
                AgentConversation(
                    session = session,
                    events = events,
                    responding = responding,
                    busy = busy || working,
                    onSend = { text -> withNetworkPermission { viewModel.send(text) } },
                    onCancel = viewModel::cancel,
                    onPermission = viewModel::respondPermission,
                    onQuestion = viewModel::respondQuestion,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
    if (showNew) {
        NewAgentSessionDialog(
            hosts = hosts.filter { it.protocol == "ssh" },
            initialHostId = hostId,
            onDismiss = { showNew = false },
            onCreate = { host, kind, cwd ->
                showNew = false
                if (supportsStructuredConversation(kind)) {
                    withNetworkPermission { viewModel.newSession(host.id, kind, cwd) }
                } else {
                    terminal(host, kind, cwd, null)
                }
            },
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
                }) {
                    Text(stringResource(R.string.agent_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnect = false }) { Text(stringResource(R.string.agent_cancel)) }
            },
        )
    }
    challenge?.let { AgentChallengeDialog(it, viewModel::respondChallenge) }
}

@Composable
private fun AgentSessionList(
    hosts: List<Host>,
    sessions: List<AgentSession>,
    hostId: Long?,
    agent: AgentKind?,
    query: String,
    onHost: (Long?) -> Unit,
    onAgent: (AgentKind?) -> Unit,
    onQuery: (String) -> Unit,
    onContinue: (AgentSession) -> Unit,
    onTerminal: (AgentSession) -> Unit,
    onDisconnect: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectionContainer {
                    Column {
                        Text(stringResource(R.string.agent_support_hint), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.agent_cache_hint), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AgentChoice(
                        label = stringResource(R.string.agent_device),
                        value = hosts.firstOrNull { it.id == hostId }?.nickname ?: stringResource(R.string.agent_all),
                        options = listOf(null to stringResource(R.string.agent_all)) + hosts.map { it.id to it.nickname },
                        onSelect = onHost,
                        modifier = Modifier.weight(1f),
                    )
                    AgentChoice(
                        label = stringResource(R.string.agent_kind),
                        value = agent?.let { agentName(it) } ?: stringResource(R.string.agent_all),
                        options = listOf(null to stringResource(R.string.agent_all)) + AgentKind.entries.map { it to agentName(it) },
                        onSelect = onAgent,
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = onQuery,
                    label = { Text(stringResource(R.string.agent_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = onDisconnect) { Text(stringResource(R.string.agent_disconnect)) }
            }
        }
        if (hosts.isEmpty() || sessions.isEmpty()) {
            item {
                Text(stringResource(if (hosts.isEmpty()) R.string.agent_no_devices else R.string.agent_no_sessions))
            }
        }
        items(sessions) { session ->
            val host = hosts.firstOrNull { it.id == session.hostId }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                session.title.ifBlank { session.sessionId.ifBlank { stringResource(R.string.agent_untitled) } },
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text("${host?.nickname ?: stringResource(R.string.agent_missing_host)} · ${agentName(session.agent)}")
                            if (!supportsStructuredConversation(session.agent)) Text(stringResource(R.string.agent_terminal_only))
                            Text(session.cwd, style = MaterialTheme.typography.bodySmall)
                            Text(
                                stringResource(
                                    R.string.agent_updated,
                                    remember(session.updatedAt) {
                                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(session.updatedAt))
                                    },
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (session.preview.isNotEmpty()) Text(session.preview, maxLines = 3)
                        }
                    }
                    Row {
                        TextButton(
                            onClick = { onContinue(session) },
                            enabled = enabled && host != null && validAgentCwd(session.cwd),
                        ) { Text(stringResource(R.string.agent_continue)) }
                        TextButton(
                            onClick = { onTerminal(session) },
                            enabled = host != null && validAgentCwd(session.cwd),
                        ) { Text(stringResource(R.string.agent_terminal)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentConversation(
    session: AgentSession,
    events: List<AgentEvent>,
    responding: Set<AgentInteractionKey>,
    busy: Boolean,
    onSend: (String) -> Unit,
    onCancel: () -> Unit,
    onPermission: (AgentInteractionKey, String) -> Unit,
    onQuestion: (AgentInteractionKey, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var prompt by remember(session.hostId, session.agent, session.sessionId) { mutableStateOf("") }
    val listState = rememberLazyListState()
    val followLatest = !listState.canScrollForward
    LaunchedEffect(events.size, events.lastOrNull()?.text, followLatest) {
        if (followLatest && events.isNotEmpty()) listState.animateScrollToItem(events.lastIndex)
    }
    Column(modifier.fillMaxSize()) {
        SelectionContainer { Text(session.cwd, modifier = Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall) }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (events.isEmpty()) item { Text(stringResource(R.string.agent_conversation_empty)) }
            items(events) { event ->
                val interaction = AgentInteractionKey(session, event)
                androidx.compose.runtime.key(interaction) {
                    AgentEventContent(
                        event = event,
                        responding = interaction in responding,
                        onPermission = { _, option -> onPermission(interaction, option) },
                        onQuestion = { _, answer -> onQuestion(interaction, answer) },
                    )
                }
            }
        }
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text(stringResource(R.string.agent_prompt)) },
                modifier = Modifier.weight(1f),
                maxLines = 6,
            )
            Column {
                Button(
                    onClick = {
                        onSend(prompt)
                        prompt = ""
                    },
                    enabled = !busy && prompt.isNotBlank(),
                ) { Text(stringResource(R.string.agent_send)) }
                if (busy) TextButton(onClick = onCancel) { Text(stringResource(R.string.agent_stop)) }
            }
        }
    }
}

@Composable
private fun NewAgentSessionDialog(
    hosts: List<Host>,
    initialHostId: Long?,
    onDismiss: () -> Unit,
    onCreate: (Host, AgentKind, String) -> Unit,
) {
    var selectedHostId by remember { mutableStateOf(initialHostId ?: hosts.firstOrNull()?.id) }
    var kind by remember { mutableStateOf(AgentKind.KIMI) }
    var cwd by remember { mutableStateOf("") }
    val host = hosts.firstOrNull { it.id == selectedHostId }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.agent_new)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (hosts.isEmpty()) Text(stringResource(R.string.agent_no_devices))
                AgentChoice(
                    label = stringResource(R.string.agent_device),
                    value = host?.nickname ?: stringResource(R.string.agent_missing_host),
                    options = hosts.map { it.id to it.nickname },
                    onSelect = { selectedHostId = it },
                )
                AgentChoice(
                    label = stringResource(R.string.agent_kind),
                    value = agentName(kind),
                    options = AgentKind.entries.map { it to agentName(it) },
                    onSelect = { kind = it },
                )
                Text(stringResource(R.string.agent_support_hint), style = MaterialTheme.typography.bodySmall)
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
            TextButton(onClick = { host?.let { onCreate(it, kind, cwd) } }, enabled = host != null && validAgentCwd(cwd)) {
                Text(stringResource(if (supportsStructuredConversation(kind)) R.string.agent_start else R.string.agent_terminal))
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
                }) { Text(stringResource(if (hostKey) R.string.agent_accept else R.string.agent_confirm)) }
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
            Text("$label: $value")
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
