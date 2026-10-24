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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/** A provider-qualified model selection; [raw] keeps fields this client does not interpret yet. */
data class ZcodeModelSelection(
    val providerId: String,
    val modelId: String,
    val reasoningLevel: String? = null,
    val raw: JsonObject = JsonObject(emptyMap()),
) {
    val label: String get() = "$providerId/$modelId" + (reasoningLevel?.let { "@$it" } ?: "")
}

data class ZcodeReasoningLevel(
    val value: String,
    val label: String,
    val description: String = "",
    val raw: JsonObject = JsonObject(emptyMap()),
)

/** One entry of `settings.model.available`; only the fields the protocol defines are interpreted. */
data class ZcodeModelOption(
    val selection: ZcodeModelSelection,
    val label: String,
    val providerLabel: String = "",
    val description: String = "",
    val contextWindow: Long? = null,
    val maxOutputTokens: Long? = null,
    val reasoningLevels: List<ZcodeReasoningLevel> = emptyList(),
    val defaultReasoningLevel: String? = null,
    val inputFormat: String? = null,
    val outputFormat: String? = null,
    val disabledReason: String? = null,
    val raw: JsonObject = JsonObject(emptyMap()),
)

data class ZcodeThoughtState(
    val enabled: Boolean = false,
    val current: String? = null,
    val defaultLevel: String? = null,
    val available: List<ZcodeReasoningLevel> = emptyList(),
)

/** `FULL` only comes from create/resume; read, subscribe and setter snapshots are `current` projections. */
enum class ZcodeCatalogCompleteness { FULL, CURRENT_ONLY, UNKNOWN }

/**
 * ZCode settings with the model catalog and the current selections stored separately.
 * A current-only update replaces the current values and leaves [catalog] untouched.
 */
data class ZcodeSessionConfig(
    val catalog: List<ZcodeModelOption> = emptyList(),
    val catalogCompleteness: ZcodeCatalogCompleteness = ZcodeCatalogCompleteness.UNKNOWN,
    val currentModel: ZcodeModelSelection? = null,
    val thought: ZcodeThoughtState = ZcodeThoughtState(),
    val currentMode: String? = null,
    val permissionMode: String? = null,
    val revision: Long? = null,
    val busy: Boolean = false,
    val raw: JsonObject = JsonObject(emptyMap()),
)

enum class ZcodeConfigStatus {
    /** The reply carried a snapshot whose current value matches the request exactly. */
    CONFIRMED,

    /** The reply carried a snapshot, but the Agent reports a different (or no) current value. */
    ADJUSTED,

    /** The reply is only an ack/revision; no authoritative settings arrived, so nothing was applied. */
    DELIVERED_PENDING,
}

/**
 * Result of one setter. [status] separates an authoritative snapshot from an ack; [busy] says the
 * Agent was running a turn, which does not declare when the selection affects that in-flight work.
 */
data class ZcodeConfigChange(
    val status: ZcodeConfigStatus,
    val requested: String,
    val applied: String?,
    val revision: Long?,
    val busy: Boolean,
    val config: ZcodeSessionConfig,
    val raw: JsonObject,
)

internal fun parseZcodeModelSelection(element: JsonElement?): ZcodeModelSelection? {
    val selection = element as? JsonObject ?: return null
    val providerId = selection.string("providerId")
    val modelId = selection.string("modelId")
    if (providerId.isEmpty() || modelId.isEmpty()) return null
    val options = selection["options"] as? JsonObject
    return ZcodeModelSelection(
        providerId = providerId,
        modelId = modelId,
        reasoningLevel = options?.string("reasoningLevel")?.takeIf { it.isNotEmpty() },
        raw = selection,
    )
}

private fun parseZcodeLevels(element: JsonElement?): List<ZcodeReasoningLevel> = (element as? JsonArray).orEmpty().mapNotNull { item ->
    val level = item as? JsonObject ?: return@mapNotNull null
    val value = level.string("value")
    if (value.isEmpty()) {
        null
    } else {
        ZcodeReasoningLevel(value, level.string("label"), level.string("description"), level)
    }
}

private fun parseZcodeModelOption(element: JsonElement?): ZcodeModelOption? {
    val option = element as? JsonObject ?: return null
    val selection = parseZcodeModelSelection(option["ref"]) ?: return null
    val reasoning = option["reasoning"] as? JsonObject
    val properties = option["properties"] as? JsonObject
    return ZcodeModelOption(
        selection = selection,
        label = option.string("label"),
        providerLabel = option.string("providerLabel"),
        description = option.string("description"),
        contextWindow = option.long("contextWindow"),
        maxOutputTokens = option.long("maxOutputTokens"),
        reasoningLevels = parseZcodeLevels(reasoning?.get("levels")),
        defaultReasoningLevel = reasoning?.string("defaultLevel")?.takeIf { it.isNotEmpty() },
        inputFormat = properties?.string("inputFormat")?.takeIf { it.isNotEmpty() },
        outputFormat = properties?.string("outputFormat")?.takeIf { it.isNotEmpty() },
        disabledReason = option.string("disabledReason").takeIf { it.isNotEmpty() },
        raw = option,
    )
}

internal fun ZcodeSessionConfig.applySnapshot(snapshot: JsonObject, fullCatalog: Boolean): ZcodeSessionConfig {
    val settings = snapshot["settings"] as? JsonObject ?: return this
    val runtime = snapshot["runtime"] as? JsonObject
    var revision = revision
    var busy = busy
    if (runtime != null) {
        runtime.long("stateRevision")?.let { revision = it }
        busy = runtime.string("activeTurnId").isNotEmpty() || runtime.string("activeTurnKind").isNotEmpty()
    }
    return applySettings(settings, fullCatalog, revision).copy(busy = busy)
}

/**
 * Applies one complete `settings` object. A full source replaces the catalog only when it actually
 * carries `available`; a current-only source never touches the catalog, and marks the catalog as
 * current-only only when no full catalog has been received yet.
 */
internal fun ZcodeSessionConfig.applySettings(
    settings: JsonObject,
    fullCatalog: Boolean,
    revision: Long? = this.revision,
): ZcodeSessionConfig {
    var next = copy(raw = settings, revision = revision)
    val model = settings["model"] as? JsonObject
    if (model != null) {
        if (fullCatalog) {
            val available = model["available"] as? JsonArray
            next = if (available != null) {
                next.copy(
                    catalog = available.mapNotNull(::parseZcodeModelOption),
                    catalogCompleteness = ZcodeCatalogCompleteness.FULL,
                )
            } else {
                next.copy(catalogCompleteness = ZcodeCatalogCompleteness.UNKNOWN)
            }
        } else if (next.catalogCompleteness == ZcodeCatalogCompleteness.UNKNOWN) {
            next = next.copy(catalogCompleteness = ZcodeCatalogCompleteness.CURRENT_ONLY)
        }
        next = next.copy(currentModel = parseZcodeModelSelection(model["current"]))
    }
    (settings["thoughtLevel"] as? JsonObject)?.let { thought ->
        next = next.copy(
            thought = ZcodeThoughtState(
                enabled = (thought["enabled"] as? JsonPrimitive)?.booleanOrNull == true,
                current = thought.string("current").takeIf { it.isNotEmpty() },
                defaultLevel = thought.string("defaultLevel").takeIf { it.isNotEmpty() },
                available = parseZcodeLevels(thought["available"]),
            ),
        )
    }
    (settings["mode"] as? JsonObject)?.let { mode ->
        next = next.copy(currentMode = mode.string("current").takeIf { it.isNotEmpty() })
    }
    (settings["permission"] as? JsonObject)?.let { permission ->
        next = next.copy(permissionMode = permission.string("mode").takeIf { it.isNotEmpty() })
    }
    return next
}

private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
