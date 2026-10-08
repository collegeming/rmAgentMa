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

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

internal fun JsonObject?.valueText(key: String): String? = (this?.get(key) as? JsonPrimitive)?.contentOrNull

internal data class AgentPlanEntry(val content: String?, val priority: String?, val status: String?)

internal fun projectPlan(raw: JsonObject?): List<AgentPlanEntry> = (raw?.get("entries") as? JsonArray).orEmpty().mapNotNull {
    val entry = it as? JsonObject ?: return@mapNotNull null
    AgentPlanEntry(entry.valueText("content"), entry.valueText("priority"), entry.valueText("status"))
}

internal data class AgentUsage(val used: String?, val size: String?, val costAmount: String?, val costCurrency: String?)

internal fun projectUsage(raw: JsonObject?): AgentUsage {
    val cost = raw?.get("cost") as? JsonObject
    return AgentUsage(
        raw.valueText("used"),
        raw.valueText("size"),
        cost.valueText("amount") ?: (raw?.get("cost") as? JsonPrimitive)?.contentOrNull,
        cost.valueText("currency"),
    )
}

internal data class AgentSessionInfo(val title: String?, val createdAt: String?, val updatedAt: String?)

internal fun projectSessionInfo(raw: JsonObject?): AgentSessionInfo = AgentSessionInfo(
    raw.valueText("title"),
    raw.valueText("createdAt"),
    raw.valueText("updatedAt"),
)

internal fun hasTruncatedAgentData(raw: JsonElement?): Boolean = when (raw) {
    is JsonObject -> raw.any { (key, value) ->
        (
            (key == "truncated" || key == "_rmagentmaTruncated" || key == "_rmagentma_truncated") &&
                (value as? JsonPrimitive)?.booleanOrNull == true
            ) || hasTruncatedAgentData(value)
    }

    is JsonArray -> raw.any(::hasTruncatedAgentData)

    else -> false
}

internal data class AgentQuestionField(
    val name: String,
    val label: String,
    val type: String,
    val required: Boolean,
    val choices: List<String>,
)

internal data class AgentQuestionForm(val fields: List<AgentQuestionField>, val singleString: Boolean, val schema: JsonObject?)

internal fun projectQuestion(raw: JsonObject?): AgentQuestionForm {
    if (hasTruncatedAgentData(raw)) return AgentQuestionForm(emptyList(), false, null)
    val schema = raw?.get("requestedSchema") as? JsonObject
    val required = (schema?.get("required") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
    val properties = schema?.get("properties") as? JsonObject
    val fields = properties.orEmpty().mapNotNull { (name, element) ->
        val field = element as? JsonObject ?: return@mapNotNull null
        AgentQuestionField(
            name,
            field.valueText("title") ?: name,
            field.valueText("type").orEmpty(),
            name in required,
            (field["enum"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { it.isString }?.content },
        )
    }
    return AgentQuestionForm(fields, properties?.size == 1 && fields.singleOrNull()?.type == "string", schema)
}

internal fun validQuestionAnswer(form: AgentQuestionForm, answer: String): Boolean {
    val schema = form.schema ?: return false
    val properties = schema["properties"] as? JsonObject ?: return false
    if (form.singleString) {
        val field = properties.values.single() as? JsonObject ?: return false
        return answer.isNotBlank() && matchesQuestionValue(JsonPrimitive(answer), field)
    }
    val response = try {
        Json.parseToJsonElement(answer) as? JsonObject
    } catch (_: IllegalArgumentException) {
        null
    } ?: return false
    if (response.values.any { it !is JsonPrimitive && it !is JsonArray }) return false
    val required = (schema["required"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    if (required.any { it !in response }) return false
    return response.all { (name, value) ->
        val field = properties[name] as? JsonObject
        if (field == null) (schema["additionalProperties"] as? JsonPrimitive)?.booleanOrNull != false else matchesQuestionValue(value, field)
    }
}

private fun matchesQuestionValue(value: JsonElement, schema: JsonObject): Boolean {
    val primitive = value as? JsonPrimitive
    val typeMatches = when (schema.valueText("type")) {
        "string" -> primitive?.isString == true

        "boolean" -> primitive != null && !primitive.isString && primitive.booleanOrNull != null

        "number" -> primitive != null && !primitive.isString && primitive.doubleOrNull?.isFinite() == true

        "integer" -> primitive != null && !primitive.isString && primitive.longOrNull != null

        "array" -> value is JsonArray && value.all { item ->
            val itemSchema = schema["items"] as? JsonObject
            if (itemSchema == null) item is JsonPrimitive && item != JsonNull else matchesQuestionValue(item, itemSchema)
        }

        else -> false
    }
    if (!typeMatches || value == JsonNull) return false
    val choices = schema["enum"] as? JsonArray
    return choices == null || value in choices
}
