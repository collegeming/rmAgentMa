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

package org.rmagentma.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * One selectable entry the Agent advertises: a config option value, a session mode, or a legacy model row.
 * The catalog only lists what the Agent may accept; it does not prove the account can use an entry.
 */
data class AgentConfigValue(
    val value: String,
    val name: String,
    val description: String = "",
    val group: String = "",
    val groupName: String = "",
)

/**
 * One session config option. [currentValue] is null when the Agent stated none, and [values] may be
 * empty; neither is filled in from the other. An unrecognized [type] keeps its original JSON in [raw].
 */
data class AgentConfigOption(
    val id: String,
    val name: String,
    val description: String = "",
    val category: String = "",
    val type: String = "",
    val currentValue: JsonPrimitive? = null,
    val values: List<AgentConfigValue> = emptyList(),
    val raw: JsonObject = JsonObject(emptyMap()),
)

/** Complete configuration surface of one ACP session. Every Agent snapshot replaces the whole value. */
data class AgentSessionConfig(
    val options: List<AgentConfigOption> = emptyList(),
    val modes: List<AgentConfigValue> = emptyList(),
    val currentModeId: String? = null,
    val models: List<AgentConfigValue> = emptyList(),
    val currentModelId: String? = null,
)

/** Parses a `configOptions` array; null when the payload carries no such list at all. */
internal fun parseConfigOptions(element: JsonElement?): List<AgentConfigOption>? {
    val array = element as? JsonArray ?: return null
    return array.mapNotNull { item ->
        val option = item as? JsonObject ?: return@mapNotNull null
        val id = option.string("id")
        if (id.isEmpty()) return@mapNotNull null
        AgentConfigOption(
            id = id,
            name = option.string("name"),
            description = option.string("description"),
            category = option.string("category"),
            type = option.string("type"),
            currentValue = option["currentValue"].primitiveOrNull(),
            values = configValues(option["options"]),
            raw = option,
        )
    }
}

/** Flattens a select `options` array; grouped entries keep their group id and label. */
private fun configValues(element: JsonElement?): List<AgentConfigValue> {
    val array = element as? JsonArray ?: return emptyList()
    val values = mutableListOf<AgentConfigValue>()
    for (entry in array) {
        val item = entry as? JsonObject ?: continue
        if (item.containsKey("value")) {
            configValue(item, "value")?.let { values += it }
            continue
        }
        val group = item.string("group")
        val groupName = item.string("name")
        for (nested in (item["options"] as? JsonArray).orEmpty()) {
            val option = nested as? JsonObject ?: continue
            configValue(option, "value", group, groupName)?.let { values += it }
        }
    }
    return values
}

private fun configValueList(element: JsonElement?, idKey: String): List<AgentConfigValue> = (element as? JsonArray).orEmpty().mapNotNull { item ->
    (item as? JsonObject)?.let { configValue(it, idKey) }
}

private fun configValue(entry: JsonObject, idKey: String, group: String = "", groupName: String = ""): AgentConfigValue? {
    val value = entry.string(idKey)
    if (value.isEmpty()) return null
    return AgentConfigValue(value, entry.string("name"), entry.string("description"), group, groupName)
}

/** Applies a `session/new`, `load` or `resume` response; each field the Agent sent is a full snapshot. */
internal fun AgentSessionConfig.applyLifecycle(response: JsonObject): AgentSessionConfig {
    var next = this
    parseConfigOptions(response["configOptions"])?.let { next = next.copy(options = it) }
    (response["modes"] as? JsonObject)?.let { modes ->
        next = next.copy(
            modes = configValueList(modes["availableModes"], "id"),
            currentModeId = modes["currentModeId"].primitiveOrNull()?.contentOrNull,
        )
    }
    (response["models"] as? JsonObject)?.let { models ->
        next = next.copy(
            models = configValueList(models["availableModels"], "modelId"),
            currentModelId = models["currentModelId"].primitiveOrNull()?.contentOrNull,
        )
    }
    return next
}

/**
 * Applies one `session/update` payload. `config_option_update` carries the complete option list, while
 * `current_mode_update` is the protocol's own single-field delta and leaves the mode catalog in place.
 */
internal fun AgentSessionConfig.applyUpdate(update: JsonObject): AgentSessionConfig = when (update.string("sessionUpdate")) {
    "config_option_update" -> parseConfigOptions(update["configOptions"])?.let { copy(options = it) } ?: this
    "current_mode_update" -> update["currentModeId"].primitiveOrNull()?.contentOrNull?.let { copy(currentModeId = it) } ?: this
    else -> this
}

private fun JsonElement?.primitiveOrNull(): JsonPrimitive? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }
