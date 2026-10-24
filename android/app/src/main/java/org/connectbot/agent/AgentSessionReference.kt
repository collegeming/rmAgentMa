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

import org.rmagentma.core.AgentEvent
import org.rmagentma.core.AgentKind

/** Messages a pack asks for before the budgets narrow it further. */
internal const val AGENT_CONTEXT_MESSAGES = 12

/** Characters one quoted message may keep, its label and its cut marker included. */
internal const val AGENT_CONTEXT_ITEM_LIMIT = 400

/** Characters the whole pack may hold, source label, notices and closing marker included. */
internal const val AGENT_CONTEXT_CHAR_BUDGET = 4_000

/** Below this the source label, the notices and the closing marker cannot all fit. */
internal const val AGENT_CONTEXT_MIN_BUDGET = 512

/** Below this the label and the cut marker leave no room for any of the message itself. */
internal const val AGENT_CONTEXT_MIN_ITEM_LIMIT = 64

/**
 * Source of a quoted context pack: the four fields that key a conversation in the index and the drawer, plus the host
 * nickname a reader recognises.
 */
internal data class AgentSessionReference(
    val hostId: Long,
    val agent: AgentKind,
    val sessionId: String,
    val cwd: String,
    val hostLabel: String = "",
) {
    /** A blank nickname still names the host, so a quote never loses where it came from. */
    val host: String get() = hostLabel.trim().ifBlank { "#$hostId" }
}

/** Whether a pack is worth putting in the input box; [AgentContextPack.text] always names what it left out. */
internal enum class AgentContextPackStatus {
    /** Every message of the requested window is quoted in full. */
    COMPLETE,

    /** Sendable, but the text names the messages or the characters it dropped. */
    TRIMMED,

    /** Nothing to quote: the loaded window held no message text, or no message fit the character budget. */
    EMPTY,
}

internal data class AgentContextPack(
    val status: AgentContextPackStatus,
    /** Ready to paste when [status] is not [AgentContextPackStatus.EMPTY]; never longer than the character budget. */
    val text: String,
    val quotedCount: Int,
    val omittedCount: Int,
    /** Quoted messages whose own text was longer than the item budget. */
    val cutCount: Int,
)

/**
 * Quotes the newest messages of [reference] into one bounded, reviewable text: the source session is named, every
 * message is kept within [itemLimit] characters and the notices state what the window and [charBudget] left out. Only
 * the user reaches an agent with this text - preparing a pack never starts a model call by itself.
 *
 * The newest messages win the budget because they carry the state of the conversation, and the survivors are written
 * oldest first, so the quote still reads in the order it happened. [AgentEvent.historyKey] is the row order the history
 * store assigned; events that share a key keep the order the caller passed them in.
 */
internal fun agentSessionContextPack(
    reference: AgentSessionReference,
    events: List<AgentEvent>,
    maxMessages: Int = AGENT_CONTEXT_MESSAGES,
    itemLimit: Int = AGENT_CONTEXT_ITEM_LIMIT,
    charBudget: Int = AGENT_CONTEXT_CHAR_BUDGET,
): AgentContextPack {
    require(maxMessages > 0) { "The message window must be positive" }
    require(itemLimit >= AGENT_CONTEXT_MIN_ITEM_LIMIT) {
        "An item limit below $AGENT_CONTEXT_MIN_ITEM_LIMIT characters cannot label the message it quotes"
    }
    require(charBudget >= AGENT_CONTEXT_MIN_BUDGET) {
        "A context budget below $AGENT_CONTEXT_MIN_BUDGET characters cannot name the source of the quote"
    }
    val candidates = events.sortedBy { it.historyKey }.mapNotNull { event ->
        val label = QUOTED_TYPES[event.type] ?: return@mapNotNull null
        val text = event.text.trim()
        if (text.isEmpty()) null else label to text
    }
    val requested = candidates.takeLast(maxMessages)
    var quotes = requested.map { (label, text) -> quoteLine(label, text, itemLimit) }
    var text = packText(reference, quotes, candidates.size, requested.size, itemLimit)
    // The oldest messages are the first to go, so a tight budget keeps the part a question about the session needs.
    while (quotes.isNotEmpty() && text.length > charBudget) {
        quotes = quotes.drop(1)
        text = packText(reference, quotes, candidates.size, requested.size, itemLimit)
    }
    val cut = quotes.count { it.cut }
    val status = when {
        quotes.isEmpty() -> AgentContextPackStatus.EMPTY
        cut > 0 || quotes.size < candidates.size -> AgentContextPackStatus.TRIMMED
        else -> AgentContextPackStatus.COMPLETE
    }
    return AgentContextPack(status, text, quotes.size, candidates.size - quotes.size, cut)
}

private data class Quote(val line: String, val cut: Boolean)

/** One quoted message, clipped to [limit]; the marker states how much of it stayed out. */
private fun quoteLine(label: String, text: String, limit: Int): Quote {
    val prefix = "$label: "
    if (prefix.length + text.length <= limit) return Quote(prefix + text, false)
    val marker = "$ELLIPSIS[${text.length} chars, rest omitted]"
    val keep = (limit - prefix.length - marker.length).coerceAtLeast(1)
    return Quote(prefix + text.take(keep) + marker, true)
}

private fun packText(
    reference: AgentSessionReference,
    quotes: List<Quote>,
    candidateCount: Int,
    requestedCount: Int,
    itemLimit: Int,
): String = buildString {
    append("[session context] host=${clip(reference.host)}")
    append(" agent=${clip(reference.agent.wireName)}")
    append(" session=${clip(reference.sessionId)}")
    append(" cwd=${clip(reference.cwd, fromEnd = true)}\n")
    when {
        candidateCount == 0 -> append("No messages to quote: this session has no text yet.\n")

        quotes.isEmpty() -> append("No message of this session fits the character budget.\n")

        else -> {
            append("Quoted ${quotes.size} of $candidateCount messages, oldest first.\n")
            val earlier = candidateCount - requestedCount
            if (earlier > 0) append("Earlier messages omitted: $earlier.\n")
            val overBudget = requestedCount - quotes.size
            if (overBudget > 0) append("Messages over the character budget: $overBudget.\n")
            val cut = quotes.count { it.cut }
            if (cut > 0) append("Messages cut to $itemLimit characters: $cut.\n")
        }
    }
    if (quotes.isNotEmpty()) {
        append('\n')
        quotes.forEach { append(it.line).append('\n') }
    }
    append("\n[end of session context]")
}

/** Keeps a long nickname, session id or path inside the header budget, showing that it was clipped. */
private fun clip(value: String, fromEnd: Boolean = false): String {
    val text = value.trim()
    if (text.length <= FIELD_LIMIT) return text
    return if (fromEnd) {
        ELLIPSIS + text.takeLast(FIELD_LIMIT - 1)
    } else {
        text.take(FIELD_LIMIT - 1) + ELLIPSIS
    }
}

private const val FIELD_LIMIT = 48

private const val ELLIPSIS = "\u2026"

/**
 * Types a pack quotes. Approvals, question forms and turn bookkeeping are not conversation text, and a merged tool row
 * arrives either raw (`tool_update`) or already folded into `tool`.
 */
private val QUOTED_TYPES = mapOf(
    "user" to "user",
    "content" to "assistant",
    "thinking" to "thinking",
    "tool" to "tool",
    "tool_update" to "tool",
    "error" to "error",
)
