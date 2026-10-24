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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.connectbot.R
import org.connectbot.agent.AgentAttentionItem
import org.connectbot.agent.AgentAttentionReason
import org.connectbot.agent.AgentMessageKind
import org.connectbot.agent.AgentTranscriptHit
import org.connectbot.data.entity.Host
import org.rmagentma.core.AgentAvailability
import org.rmagentma.core.AgentSession

/**
 * One inbox row the drawer can act on: the aggregated attention of a conversation with the loaded session it
 * returns to, and the host nickname a reader recognises. A conversation the list no longer holds keeps its row,
 * and that row cannot be opened from here.
 */
internal data class AgentInboxRow(
    val item: AgentAttentionItem,
    val session: AgentSession?,
    val hostLabel: String,
)

/** The inbox as rows the drawer renders: one row per conversation key, exactly as the projection ordered them. */
internal fun agentInboxRows(
    items: List<AgentAttentionItem>,
    sessions: List<AgentSession>,
    hosts: List<Host>,
): List<AgentInboxRow> = items.map { item ->
    AgentInboxRow(
        item = item,
        session = sessions.firstOrNull {
            it.hostId == item.key.hostId && it.agent == item.key.agent && it.sessionId == item.key.sessionId
        },
        hostLabel = hosts.firstOrNull { it.id == item.key.hostId }?.nickname ?: "#${item.key.hostId}",
    )
}

/** The session one transcript hit belongs to, or null when the loaded list no longer holds it. */
internal fun transcriptHitSession(hit: AgentTranscriptHit, sessions: List<AgentSession>): AgentSession? = sessions.firstOrNull {
    it.hostId == hit.hostId && it.agent == hit.hit.agent && it.sessionId == hit.hit.sessionId
}

/**
 * The read-only attention inbox at the top of the drawer: what waits for the user, most urgent first, and a way back
 * to the conversation it belongs to. Nothing here answers an approval or a question - only the conversation itself
 * can do that - so this stays a projection over loaded state and never a second authorization path.
 */
@Composable
internal fun AgentInboxContent(
    rows: List<AgentInboxRow>,
    onOpen: (AgentSession) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (rows.isEmpty()) return
    Column(modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.agent_inbox), style = MaterialTheme.typography.titleSmall)
        rows.forEach { row ->
            Column(Modifier.fillMaxWidth()) {
                Text(
                    row.session?.title?.takeIf { it.isNotBlank() } ?: row.session?.sessionId ?: row.item.key.sessionId,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(R.string.agent_choice_value, row.hostLabel, agentName(row.item.key.agent)),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                row.item.reasons.forEach { reason ->
                    Text(
                        attentionLine(row.item, reason),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (row.session == null) {
                    Text(stringResource(R.string.agent_transcript_hit_missing), style = MaterialTheme.typography.labelSmall)
                } else {
                    TextButton(onClick = { onOpen(row.session) }) { Text(stringResource(R.string.agent_inbox_open)) }
                }
            }
        }
    }
}

@Composable
private fun attentionLine(item: AgentAttentionItem, reason: AgentAttentionReason): String = when (reason) {
    AgentAttentionReason.APPROVAL -> stringResource(agentAttentionReasonText(reason), item.pendingApprovals)
    AgentAttentionReason.QUESTION -> stringResource(agentAttentionReasonText(reason), item.pendingQuestions)
    AgentAttentionReason.FAILURE -> stringResource(agentAttentionReasonText(reason), item.lastError.orEmpty())
    AgentAttentionReason.BUSY -> stringResource(agentAttentionReasonText(reason))
}

/** One conversation as a notice names it: its host, its Agent and then its own title. */
@Composable
private fun AgentTargetLabel(session: AgentSession, hostLabel: String) {
    Text(
        stringResource(R.string.agent_choice_value, hostLabel, agentName(session.agent)),
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Text(
        session.title.ifBlank { session.sessionId },
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * Hands one message to another conversation, on this host or another. Both hand-off semantics are spelled out next
 * to the choice, because a request is sent automatically when that conversation is opened while a notice is only
 * shown there as a hint.
 */
@Composable
internal fun AgentSendMessageDialog(
    sessions: List<AgentSession>,
    hosts: List<Host>,
    current: AgentSession?,
    availability: List<AgentAvailability>,
    initialText: String,
    onDismiss: () -> Unit,
    onQueue: (AgentSession, AgentMessageKind, String) -> Unit,
) {
    val targets = remember(sessions, current) { sessions.filterNot { sameAgentSession(it, current) } }
    var target by remember(targets) { mutableStateOf(targets.firstOrNull()) }
    var kind by remember { mutableStateOf(AgentMessageKind.REQUEST) }
    var text by remember(initialText) { mutableStateOf(initialText) }
    val hostLabel: (AgentSession) -> String = { session ->
        hosts.firstOrNull { it.id == session.hostId }?.nickname ?: "#${session.hostId}"
    }
    val targetLabel: (AgentSession) -> String = { session ->
        "${hostLabel(session)} / ${session.agent.wireName} · ${session.title.ifBlank { session.sessionId }}"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.agent_message_send)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (targets.isEmpty()) {
                    Text(stringResource(R.string.agent_message_no_targets))
                } else {
                    AgentChoice(
                        label = stringResource(R.string.agent_message_target),
                        value = target?.let(targetLabel).orEmpty(),
                        options = targets.map { it to targetLabel(it) },
                        onSelect = { target = it },
                    )
                }
                Text(stringResource(R.string.agent_message_kind), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AgentMessageKind.entries.forEach { choice ->
                        TextButton(onClick = { kind = choice }) {
                            Text(
                                stringResource(agentMessageKindText(choice)),
                                style = if (choice == kind) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                Text(stringResource(agentMessageKindHint(kind)))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.agent_message_text)) },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 6,
                )
            }
        },
        confirmButton = {
            val selected = target
            TextButton(
                enabled = selected != null && text.isNotBlank() && canOpenAgent(availability, selected.hostId, selected.agent),
                onClick = { selected?.let { onQueue(it, kind, text.trim()) } },
            ) { Text(stringResource(R.string.agent_send)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.agent_cancel)) } },
    )
}

/**
 * What the chat shell reports about queued messages: what was queued, what the queue refused, hints another
 * conversation left here, what a delivery did, and how much still waits - with the retry that hands it over again.
 * Nothing is reported as sent that the receiving conversation did not take.
 */
@Composable
internal fun AgentQueueStatusContent(
    status: AgentQueueStatus,
    sessions: List<AgentSession>,
    hosts: List<Host>,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val queued = status.queued
    val empty = queued == null && !status.refused && status.notices.isEmpty() &&
        status.deliveryFailure == null && status.deliveredRequests == 0 && status.pending == 0
    if (empty) return
    Column(modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (queued != null) {
            Text(
                stringResource(
                    if (queued.kind == AgentMessageKind.REQUEST) R.string.agent_message_queued_request else R.string.agent_message_queued_notify,
                ),
            )
            sessions
                .firstOrNull { it.hostId == queued.hostId && it.agent.wireName == queued.agent && it.sessionId == queued.sessionId }
                ?.let { target ->
                    AgentTargetLabel(target, hosts.firstOrNull { it.id == target.hostId }?.nickname ?: "#${target.hostId}")
                }
        }
        if (status.refused) Text(stringResource(R.string.agent_message_refused), color = MaterialTheme.colorScheme.error)
        status.notices.forEach { notice ->
            Text(stringResource(R.string.agent_message_notice, notice.text), maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
        status.deliveryFailure?.let {
            Text(stringResource(R.string.agent_message_delivery_failed, it), color = MaterialTheme.colorScheme.error)
        }
        if (status.deliveredRequests > 0) Text(stringResource(R.string.agent_message_delivered_request, status.deliveredRequests))
        if (status.pending > 0) {
            Text(stringResource(R.string.agent_message_pending, status.pending))
            TextButton(onClick = onRetry) { Text(stringResource(R.string.agent_message_retry)) }
        }
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.agent_message_dismiss)) }
    }
}
