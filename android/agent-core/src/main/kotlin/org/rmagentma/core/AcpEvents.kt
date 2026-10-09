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
import kotlinx.serialization.json.JsonObject

internal fun permissionOptions(params: JsonObject): List<AgentOption> = (params["options"] as? JsonArray).orEmpty().mapNotNull { item ->
    val option = item as? JsonObject ?: return@mapNotNull null
    val id = option.string("optionId")
    if (id.isEmpty()) null else AgentOption(id, option.string("name"))
}

internal fun normalizeUpdate(update: JsonObject): AgentEvent {
    val kind = update.string("sessionUpdate")
    val content = update["content"] as? JsonObject
    val type = when (kind) {
        "agent_message_chunk" -> "content"
        "user_message_chunk" -> "user"
        "agent_thought_chunk" -> "thinking"
        "tool_call" -> "tool"
        "tool_call_update" -> "tool_update"
        "plan" -> "plan"
        "usage_update" -> "usage"
        "session_info_update" -> "session_info"
        else -> "unknown"
    }
    return AgentEvent(
        type = type,
        text = when {
            content == null -> ""
            content.string("type") == "text" -> content.string("text")
            else -> "[${content.string("type").ifEmpty { "non-text content" }}]"
        },
        id = update.string("toolCallId").ifEmpty { update.string("messageId") },
        title = update.string("title").ifEmpty { if (type == "unknown") kind else "" },
        status = update.string("status"),
        raw = update,
    )
}
