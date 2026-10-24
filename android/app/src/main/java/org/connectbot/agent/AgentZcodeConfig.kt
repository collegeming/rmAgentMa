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

package org.connectbot.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.rmagentma.core.AgentConfigOption
import org.rmagentma.core.AgentConfigValue
import org.rmagentma.core.AgentSessionConfig
import org.rmagentma.core.ZcodeBusyException
import org.rmagentma.core.ZcodeConfigChange
import org.rmagentma.core.ZcodeConfigStatus
import org.rmagentma.core.ZcodeModelOption
import org.rmagentma.core.ZcodeSessionConfig

/** Option ids the product publishes for the ZCode settings its driver can read and write. */
internal const val ZCODE_MODEL_OPTION = "model"
internal const val ZCODE_THOUGHT_OPTION = "thought_level"
internal const val ZCODE_MODE_OPTION = "mode"

private const val SELECT = "select"

/** A type neither surface has a picker for; it stays readable instead of being offered as a switch. */
private const val READ_ONLY = "readonly"

/**
 * Projects ZCode's settings snapshot into the option shape an ACP session reports, so the Compose panel and
 * the renderer consume one configuration surface. Only values the Agent advertised become choices; a setting
 * without a catalogue stays read-only, and a current value the Agent never stated stays null.
 */
internal object AgentZcodeConfig {
    fun project(config: ZcodeSessionConfig): AgentSessionConfig = AgentSessionConfig(
        options = listOfNotNull(model(config), thought(config), mode(config)),
    )

    /** Routes one published option back to the driver setter that owns it, refusing values it never advertised. */
    suspend fun set(
        config: ZcodeSessionConfig,
        optionId: String,
        value: String,
        setModel: suspend (providerId: String, modelId: String, reasoningLevel: String?) -> ZcodeConfigChange,
        setThoughtLevel: suspend (String) -> ZcodeConfigChange,
    ): ZcodeConfigChange = when (optionId) {
        ZCODE_MODEL_OPTION -> {
            val target = config.catalog.firstOrNull { modelKey(it) == value }
                ?: throw IllegalArgumentException("ZCode model is not in the Agent's catalogue")
            // Keep the thought level only when the target model supports it; the Agent then adjusts the rest.
            val level = config.thought.current?.takeIf { current -> target.reasoningLevels.any { it.value == current } }
                ?: target.selection.reasoningLevel
            setModel(target.selection.providerId, target.selection.modelId, level)
        }

        ZCODE_THOUGHT_OPTION -> {
            val thought = config.thought
            require(thought.enabled) { "ZCode thought levels are disabled" }
            require(thought.available.any { it.value == value }) { "ZCode thought level is not in the Agent's catalogue" }
            setThoughtLevel(value)
        }

        // The driver reports no mode catalogue and keeps plan state outside `mode`, so a mode choice here would
        // be a value this client invented; the option stays a read-only diagnostic.
        ZCODE_MODE_OPTION -> throw IllegalArgumentException("ZCode mode has no catalogue to choose from")

        else -> throw IllegalArgumentException("Unknown ZCode configuration option")
    }

    /**
     * CONFIRMED and ADJUSTED both carried an authoritative snapshot, so the caller compares the new current
     * value with its request; an ack-only DELIVERED_PENDING never claims that any value was applied.
     */
    fun receipt(change: ZcodeConfigChange): AgentConfigAccess = when (change.status) {
        ZcodeConfigStatus.CONFIRMED, ZcodeConfigStatus.ADJUSTED -> AgentConfigAccess.Available(project(change.config))
        ZcodeConfigStatus.DELIVERED_PENDING -> AgentConfigAccess.Unconfirmed(project(change.config))
    }

    /** A refused write because another update is in flight keeps its own type instead of a generic failure. */
    fun busy(error: ZcodeBusyException): AgentConfigBusyException = AgentConfigBusyException(error.code, error.message ?: "ZCode configuration is busy")

    private fun model(config: ZcodeSessionConfig): AgentConfigOption? {
        val values = config.catalog.map { option ->
            AgentConfigValue(modelKey(option), option.label.ifBlank { option.selection.modelId }, option.description)
        }.distinctBy { it.value }
        val current = config.currentModel?.let { modelKey(it.providerId, it.modelId) }
        if (values.isEmpty() && current == null) return null
        return AgentConfigOption(
            id = ZCODE_MODEL_OPTION,
            name = "Model",
            category = ZCODE_MODEL_OPTION,
            type = if (values.isEmpty()) READ_ONLY else SELECT,
            currentValue = current?.let(::JsonPrimitive),
            values = values,
            raw = section(config, "model"),
        )
    }

    private fun thought(config: ZcodeSessionConfig): AgentConfigOption? {
        val thought = config.thought
        if (thought.available.isEmpty() && thought.current == null) return null
        val values = thought.available.map { AgentConfigValue(it.value, it.label.ifBlank { it.value }, it.description) }
        return AgentConfigOption(
            id = ZCODE_THOUGHT_OPTION,
            name = "Thought level",
            category = ZCODE_THOUGHT_OPTION,
            // A level the Agent disabled cannot be chosen; its catalogue is still shown for diagnosis.
            type = if (thought.enabled && values.isNotEmpty()) SELECT else READ_ONLY,
            currentValue = thought.current?.let(::JsonPrimitive),
            values = values,
            raw = section(config, "thoughtLevel"),
        )
    }

    private fun mode(config: ZcodeSessionConfig): AgentConfigOption? {
        val current = config.currentMode ?: return null
        return AgentConfigOption(
            id = ZCODE_MODE_OPTION,
            name = "Mode",
            category = ZCODE_MODE_OPTION,
            type = READ_ONLY,
            currentValue = JsonPrimitive(current),
            raw = section(config, "mode"),
        )
    }

    private fun modelKey(option: ZcodeModelOption): String = modelKey(option.selection.providerId, option.selection.modelId)

    private fun modelKey(providerId: String, modelId: String): String = "$providerId/$modelId"

    private fun section(config: ZcodeSessionConfig, key: String): JsonObject = config.raw[key] as? JsonObject ?: JsonObject(emptyMap())
}
