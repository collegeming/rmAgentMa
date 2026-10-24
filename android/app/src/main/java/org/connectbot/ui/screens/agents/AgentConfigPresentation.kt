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

import kotlinx.serialization.json.contentOrNull
import org.connectbot.agent.AgentConfigAccess
import org.rmagentma.core.AgentConfigOption
import org.rmagentma.core.AgentSessionConfig

/** Event title the ACP driver gives a `config_option_update` notification. */
internal const val AGENT_CONFIG_UPDATE_EVENT = "config_option_update"

internal enum class AgentConfigRole {
    MODEL,
    REASONING,
    MODE,
    OTHER,
}

/** What the native header and panel can currently say about the open conversation's configuration. */
internal sealed interface AgentConfigUiState {
    data object Loading : AgentConfigUiState

    data object Unsupported : AgentConfigUiState

    data object Closed : AgentConfigUiState

    data class Failed(val reason: String?) : AgentConfigUiState

    data class Ready(val config: AgentSessionConfig) : AgentConfigUiState
}

/** One role's current value, resolved only from what the Agent itself reported. */
internal data class AgentConfigRoleView(
    val role: AgentConfigRole,
    val currentValue: String?,
    val current: String?,
)

internal enum class AgentConfigWriteOutcome {
    CONFIRMED,
    NEXT_TURN,
    UNCONFIRMED,
    UNSUPPORTED,
    CLOSED,
    FAILED,
}

internal data class AgentConfigNotice(val outcome: AgentConfigWriteOutcome, val reason: String? = null)

internal fun isAgentConfigUpdate(eventTitle: String): Boolean = eventTitle == AGENT_CONFIG_UPDATE_EVENT

/**
 * Maps a repository answer onto the panel's own state. An unconfirmed write keeps the last snapshot the Agent
 * reported; without one there is nothing truthful left to show, so it counts as closed.
 */
internal fun AgentConfigAccess.toUiState(): AgentConfigUiState = when (this) {
    is AgentConfigAccess.Available -> AgentConfigUiState.Ready(config)
    is AgentConfigAccess.Unconfirmed -> config?.let { AgentConfigUiState.Ready(it) } ?: AgentConfigUiState.Closed
    AgentConfigAccess.Unsupported -> AgentConfigUiState.Unsupported
    AgentConfigAccess.Closed -> AgentConfigUiState.Closed
}

/**
 * Only a `select` option carries values the Agent accepts through the config setter. A type this app does not
 * recognize, including a missing one, is shown read-only instead of being offered as a switch.
 */
internal fun isAgentConfigSwitchable(option: AgentConfigOption): Boolean = option.type == "select"

/** A write is confirmed only when the Agent's new snapshot carries the requested value; otherwise it takes effect later. */
internal fun agentConfigWriteOutcome(
    access: AgentConfigAccess,
    optionId: String,
    value: String,
): AgentConfigWriteOutcome = when (access) {
    is AgentConfigAccess.Available -> if (access.config.currentValue(optionId) == value) {
        AgentConfigWriteOutcome.CONFIRMED
    } else {
        AgentConfigWriteOutcome.NEXT_TURN
    }

    is AgentConfigAccess.Unconfirmed -> AgentConfigWriteOutcome.UNCONFIRMED

    AgentConfigAccess.Unsupported -> AgentConfigWriteOutcome.UNSUPPORTED

    AgentConfigAccess.Closed -> AgentConfigWriteOutcome.CLOSED
}

internal fun agentConfigRole(option: AgentConfigOption): AgentConfigRole {
    val keys = listOf(option.category.lowercase(), option.id.lowercase()).filter { it.isNotBlank() }
    return when {
        keys.any { it == "model" || it.endsWith("_model") } -> AgentConfigRole.MODEL
        keys.any { it in REASONING_KEYS } -> AgentConfigRole.REASONING
        keys.any { it == "mode" || it.endsWith("_mode") } -> AgentConfigRole.MODE
        else -> AgentConfigRole.OTHER
    }
}

private val REASONING_KEYS = setOf("thought_level", "thought-level", "reasoning", "reasoning_effort", "effort", "thinking")

/** The name the Agent gave a current value, or the raw value when its catalogue does not name it. */
internal fun AgentConfigOption.currentText(): String? {
    val value = currentValue?.contentOrNull ?: return null
    return values.firstOrNull { it.value == value }?.name?.takeIf { it.isNotBlank() } ?: value
}

/**
 * The current value of one role. A model the Agent reports only through its legacy model list is still shown,
 * because that list is a real snapshot; it simply has no switchable option in the panel.
 */
internal fun agentConfigRoleView(config: AgentSessionConfig, role: AgentConfigRole): AgentConfigRoleView? {
    val option = config.options.firstOrNull { agentConfigRole(it) == role }
    if (option != null) {
        return AgentConfigRoleView(role, option.currentValue?.contentOrNull, option.currentText())
    }
    if (role != AgentConfigRole.MODEL || config.models.isEmpty()) return null
    val current = config.currentModelId
    val name = current?.let { id -> config.models.firstOrNull { it.value == id }?.name?.takeIf { it.isNotBlank() } ?: id }
    return AgentConfigRoleView(role, current, name)
}

private fun AgentSessionConfig.currentValue(optionId: String): String? = options.firstOrNull { it.id == optionId }?.currentValue?.contentOrNull
