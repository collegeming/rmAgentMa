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
import org.rmagentma.core.TranscriptSearchHit
import org.rmagentma.core.TranscriptSearchProblem
import java.util.regex.Pattern

/**
 * Most ancestors of a session's working directory one root lookup reads. The Agent writes its sessions below the
 * account's own directory, so the walk ends at the first ancestor that holds that marker.
 */
internal const val TRANSCRIPT_ROOT_WALK_LIMIT = 8

/** Most project directories one root listing is scanned in, so a busy account stays bounded. */
internal const val TRANSCRIPT_ROOT_SCAN_LIMIT = 40

/** The directory of the file-based Agent, below the account's own directory; its sessions live in `sessions`. */
private const val TRANSCRIPT_HOME_MARKER = ".dsh"

/** One remote hit as the drawer needs it: the hit itself plus the host whose transcript it was read from. */
data class AgentTranscriptHit(val hostId: Long, val hit: TranscriptSearchHit)

/**
 * Why one session was left out of a remote transcript search. The UI words each reason, and none of them means
 * "this history holds no such text": they all say the history was not read at all.
 */
enum class AgentTranscriptSkipReason {
    /** The Agent keeps its history in a database rather than in files this search may name. */
    HISTORY_NOT_A_FILE,

    /** No transcript root was found near the session's working directory. */
    ROOT_NOT_FOUND,

    /** The root holds no session directory named after this session id. */
    SESSION_DIRECTORY_NOT_FOUND,

    /** The session directory holds no readable transcript generation. */
    FILE_NOT_FOUND,

    /** The selection held more sessions than one search reads, so the rest were left out. */
    OVER_LIMIT,
}

/** One session a search did not read, and the reason; it never stands for a session that was read and held no hit. */
data class AgentTranscriptSkip(val session: AgentSession, val reason: AgentTranscriptSkipReason)

/** One host whose transcripts could not be read at all; [reason] is that host's own error, never a verdict. */
data class AgentTranscriptFailure(val hostId: Long, val reason: String)

/**
 * What one remote transcript search covered, not only what it found: [problems], [failures] and [skips] each name a
 * part of the selection that stayed unread, so no caller can render this as a plain "no results".
 */
data class AgentTranscriptOutcome(
    val query: String,
    val hits: List<AgentTranscriptHit> = emptyList(),
    val truncated: Boolean = false,
    val problems: List<TranscriptSearchProblem> = emptyList(),
    val failures: List<AgentTranscriptFailure> = emptyList(),
    val skips: List<AgentTranscriptSkip> = emptyList(),
    val searchedSessions: Int = 0,
    /** Transcript roots that were read; a search that found none read nothing and says so through its skips. */
    val roots: List<String> = emptyList(),
) {
    /** True while this outcome still has to explain why the history is not fully covered. */
    val incomplete: Boolean get() = truncated || problems.isNotEmpty() || failures.isNotEmpty() || skips.isNotEmpty()
}

/** Whether one session's history is a file this build can name at all; null means it can be searched. */
internal fun agentTranscriptSkipReason(agent: AgentKind): AgentTranscriptSkipReason? = when (agent) {
    AgentKind.DSH -> null
    else -> AgentTranscriptSkipReason.HISTORY_NOT_A_FILE
}

/**
 * The transcript roots one working directory is looked for, deepest first: every ancestor's own agent directory.
 * Nothing here guesses a home directory - a candidate the host does not hold is answered as a missing directory,
 * so a wrong candidate costs one bounded listing instead of reporting another account's history.
 */
internal fun transcriptRootCandidates(cwd: String): List<String> {
    if (!cwd.startsWith('/') || cwd.any { it.code < 32 || it.code == 127 }) return emptyList()
    val candidates = mutableListOf<String>()
    var directory = cwd.trimEnd('/').ifEmpty { "/" }
    while (directory != "/" && candidates.size < TRANSCRIPT_ROOT_WALK_LIMIT) {
        candidates += "$directory/$TRANSCRIPT_HOME_MARKER/sessions"
        directory = directory.substringBeforeLast('/').ifEmpty { "/" }
    }
    return candidates
}

/**
 * The transcript generation to search among [names]: the newest one only. Older generations hold the same
 * conversation at an earlier point, so reading them together would count one history twice.
 */
internal fun newestTranscriptFile(names: List<String>): String? = names
    .filter { transcriptGeneration(it) != null }
    .sortedWith(
        compareByDescending<String> { transcriptGeneration(it) }
            .thenByDescending { it.endsWith(".zstd") }
            .thenBy { it },
    )
    .firstOrNull()

private fun transcriptGeneration(name: String): Int? {
    val match = TRANSCRIPT_FILE.matcher(name)
    return if (match.matches()) match.group(1)?.toIntOrNull() else null
}

private val TRANSCRIPT_FILE = Pattern.compile("session\\.v(\\d+)\\.jsonl(?:\\.zstd)?")
