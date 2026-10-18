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

import org.rmagentma.core.AgentKind
import org.rmagentma.core.AgentSession

/** Matched field, declared strongest first so ranking can use the declaration order. */
internal enum class AgentSearchField {
    TITLE,
    PREVIEW,
    CWD,
}

/** Remote rows were re-read in this pass; cached rows only prove what an earlier scan once saw. */
internal enum class AgentSearchSource {
    REMOTE,
    CACHED,
}

internal data class AgentSearchHit(
    val session: AgentSession,
    val field: AgentSearchField,
    val snippet: String,
    val source: AgentSearchSource,
)

/**
 * Coverage of one search pass. [complete] stays false while a page is unloaded or a source failed, so a caller can
 * never render a full-history claim from partial data; it is also the signal that a retry entry point is useful.
 */
internal data class AgentSearchScope(
    val remoteCount: Int,
    val cachedCount: Int,
    val hasUnloadedPages: Boolean,
    val failedSources: List<String>,
) {
    val complete: Boolean get() = !hasUnloadedPages && failedSources.isEmpty()
}

/** Partial results stay usable: [hits] never wait for an unfinished page or a retry. */
internal data class AgentSearchResults(
    val hits: List<AgentSearchHit>,
    val scope: AgentSearchScope,
)

/** Keeps a snippet to one drawer row while leaving context on both sides of the match. */
internal const val AGENT_SEARCH_SNIPPET_LIMIT = 72

/**
 * Counts the queried rows per source. [remoteSources] holds the host/agent pairs re-read from the remote in this pass,
 * so every other session in [sessions] is cache-only.
 */
internal fun agentSearchScope(
    sessions: List<AgentSession>,
    remoteSources: Set<Pair<Long, AgentKind>> = emptySet(),
    hasUnloadedPages: Boolean = false,
    failedSources: List<String> = emptyList(),
): AgentSearchScope {
    // shortcut: source-level granularity, upgrade to per-row freshness if a stale row under a re-read source matters.
    val remoteCount = sessions.count { (it.hostId to it.agent) in remoteSources }
    return AgentSearchScope(
        remoteCount = remoteCount,
        cachedCount = sessions.size - remoteCount,
        hasUnloadedPages = hasUnloadedPages,
        failedSources = failedSources.filter { it.isNotBlank() },
    )
}

/**
 * Ranks every session once by its strongest matching field and reports the range actually covered, so the caller can
 * label hosts, agent, directory, snippet and source without re-deriving match state.
 */
internal fun searchAgentSessions(
    sessions: List<AgentSession>,
    query: String,
    remoteSources: Set<Pair<Long, AgentKind>> = emptySet(),
    hasUnloadedPages: Boolean = false,
    failedSources: List<String> = emptyList(),
    snippetLimit: Int = AGENT_SEARCH_SNIPPET_LIMIT,
): AgentSearchResults {
    val scope = agentSearchScope(sessions, remoteSources, hasUnloadedPages, failedSources)
    val search = query.trim()
    if (search.isEmpty()) return AgentSearchResults(emptyList(), scope)
    val hits = sessions
        .mapNotNull { hit(it, search, remoteSources, snippetLimit) }
        .sortedWith(compareBy<AgentSearchHit> { it.field.ordinal }.thenByDescending { it.session.updatedAt })
    return AgentSearchResults(hits, scope)
}

private fun hit(
    session: AgentSession,
    query: String,
    remoteSources: Set<Pair<Long, AgentKind>>,
    snippetLimit: Int,
): AgentSearchHit? {
    for (field in AgentSearchField.entries) {
        val text = when (field) {
            AgentSearchField.TITLE -> session.title
            AgentSearchField.PREVIEW -> session.preview
            AgentSearchField.CWD -> session.cwd
        }
        val start = text.indexOf(query, ignoreCase = true)
        if (start < 0) continue
        return AgentSearchHit(
            session = session,
            field = field,
            snippet = snippet(text, start, query.length, snippetLimit),
            source = if ((session.hostId to session.agent) in remoteSources) AgentSearchSource.REMOTE else AgentSearchSource.CACHED,
        )
    }
    return null
}

/** Bounded context around the match; ellipses mark clipped text and the match itself stays inside the window. */
private fun snippet(text: String, matchStart: Int, matchLength: Int, limit: Int): String {
    if (text.length <= limit) return text
    val padding = (limit - matchLength).coerceAtLeast(0) / 2
    val from = (matchStart - padding).coerceIn(0, text.length - limit)
    val head = if (from > 0) ELLIPSIS else ""
    val tail = if (from + limit < text.length) ELLIPSIS else ""
    return head + text.substring(from, from + limit) + tail
}

private const val ELLIPSIS = "\u2026"
