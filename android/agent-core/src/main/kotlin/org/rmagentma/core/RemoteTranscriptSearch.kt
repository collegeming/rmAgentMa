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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.charset.CharacterCodingException
import java.util.Base64

/** Bound one search over the wire keeps, so a host that keeps reading cannot hold a search open indefinitely. */
const val TRANSCRIPT_SEARCH_TIMEOUT_MILLIS: Long = 45_000

/**
 * Seconds the remote script gives its own scan, shorter than [TRANSCRIPT_SEARCH_TIMEOUT_MILLIS], so a slow history
 * still answers with what it read and names the clock as the reason instead of dying as a transport error.
 */
const val TRANSCRIPT_SEARCH_SCAN_SECONDS: Int = 30

/** Most hits one search returns; a history holding more is answered as a truncated search instead. */
const val TRANSCRIPT_SEARCH_MAX_MATCHES: Int = 50

/** Most candidate files one search reads; the candidates past this are named as cut off by the limit. */
const val TRANSCRIPT_SEARCH_MAX_FILES: Int = 50

/** Most bytes one search scans across every candidate it reads; a larger history stays mostly unread. */
const val TRANSCRIPT_SEARCH_MAX_BYTES: Int = 2 * 1024 * 1024

/** Most characters one hit's snippet carries, taken around the match rather than around the line. */
const val TRANSCRIPT_SEARCH_MAX_SNIPPET: Int = 160

/** Longest query one search accepts, in characters, since every line it reads is compared against it. */
const val TRANSCRIPT_SEARCH_MAX_QUERY: Int = 256

/** Why a search did not answer with the hits it was asked for; the codes are the remote script's own categories. */
enum class TranscriptSearchProblem(val code: String) {
    /** The caller asked with no query text at all. */
    QUERY_EMPTY("search_query_empty"),

    /** The query holds characters no remote argument can carry, or is longer than one search accepts. */
    QUERY_INVALID("search_query_invalid"),

    /** A scope named a session, directory or file this search will not ask a host about. */
    SCOPE_INVALID("search_scope_invalid"),

    /** The host has no python3 or base64, so no remote search can run there at all. */
    TOOL_MISSING("search_tool_missing"),

    /** The bound expired while the host was still reading; the hits that arrived are real but not the whole answer. */
    TIMEOUT("search_timeout"),

    /** The host answered with something this build cannot read as a search. */
    FAILED("search_failed"),

    /** The host stopped short of its closing summary row, so nothing proves the search finished. */
    INCOMPLETE("search_incomplete"),
    ;

    companion object {
        /**
         * The script's own categories, and the categories the shared remote transport reports when python3 or
         * base64 is missing on the host. Anything this build has no word for is a failed search, never a guess.
         */
        fun fromCategory(category: String): TranscriptSearchProblem = when (category) {
            "python_missing", "base64_missing" -> TOOL_MISSING
            else -> entries.firstOrNull { it.code == category } ?: FAILED
        }
    }
}

/**
 * One session's own transcript files, as the caller already knows them: this search never walks a history itself, so
 * a session whose history is not a file the caller can name (kimi, opencode and zcode keep theirs in sqlite) is not
 * reached by naming its database here.
 */
data class TranscriptSearchTarget(
    val agent: AgentKind,
    val sessionId: String,
    val directory: String,
    val files: List<String>,
)

/** One hit: the message anchor it sits at, and a bounded snippet of the line that matched. */
data class TranscriptSearchHit(
    val sessionKey: String,
    val agent: AgentKind,
    val sessionId: String,
    val directory: String,
    val file: String,
    /** 1-based line of the matching message inside [file]. */
    val line: Int,
    /** Where the match starts in that line, counted in characters rather than bytes. */
    val offset: Int,
    val snippet: String,
    /** True when the line continued beyond the snippet, so the snippet is a window and not the whole line. */
    val clipped: Boolean,
) {
    /** Stable pointer to the matching message, as a caller names it again to read that part of the history. */
    val anchor: String get() = "$file#$line"
}

/**
 * What one search found. [truncated] says this is not the whole history - a limit cut it off, a file was left
 * unread, or the search failed - so a caller that reads nothing else still never claims a complete history.
 */
data class TranscriptSearchResult(
    val hits: List<TranscriptSearchHit> = emptyList(),
    val truncated: Boolean = false,
    val unreadableFiles: Int = 0,
    val problem: TranscriptSearchProblem? = null,
)

/**
 * Search the body of remote session transcripts for one query: one bounded, read-only remote script over the host
 * session, and a stream of hit rows back. Only the candidate files the caller names are searched, matching is
 * case-insensitive per Unicode character, and a host that could not be reached keeps its own connection error
 * instead of being reported as a history without that text.
 */
class RemoteTranscriptSearch(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val timeoutMillis: Long = TRANSCRIPT_SEARCH_TIMEOUT_MILLIS,
) {
    init {
        require(timeoutMillis > 0) { "Timeout must be positive" }
    }

    suspend fun search(
        host: HostSession,
        query: String,
        scope: List<TranscriptSearchTarget>,
        limit: Int = TRANSCRIPT_SEARCH_MAX_MATCHES,
    ): TranscriptSearchResult {
        require(limit in 1..TRANSCRIPT_SEARCH_MAX_MATCHES) { "Limit must be between 1 and $TRANSCRIPT_SEARCH_MAX_MATCHES" }
        val text = query.trim()
        if (text.isEmpty()) return TranscriptSearchResult(problem = TranscriptSearchProblem.QUERY_EMPTY)
        if (text.length > TRANSCRIPT_SEARCH_MAX_QUERY || text.any { it.code < 32 || it.code == 127 }) {
            return TranscriptSearchResult(problem = TranscriptSearchProblem.QUERY_INVALID)
        }
        val targets = scope.distinct()
        if (targets.isEmpty() || targets.any { it.invalid() }) {
            return TranscriptSearchResult(problem = TranscriptSearchProblem.SCOPE_INVALID)
        }
        val candidates = targets
            .groupBy { Triple(it.agent.wireName, it.sessionId, it.directory) }
            .mapValues { (_, named) -> named.flatMap { it.files }.toSet() }
        val arguments = mutableListOf(
            "--query-b64", encode(text),
            "--max-files", TRANSCRIPT_SEARCH_MAX_FILES.toString(),
            "--max-bytes", TRANSCRIPT_SEARCH_MAX_BYTES.toString(),
            "--max-matches", limit.toString(),
            "--max-snippet", TRANSCRIPT_SEARCH_MAX_SNIPPET.toString(),
            "--timeout", TRANSCRIPT_SEARCH_SCAN_SECONDS.toString(),
        )
        targets.forEach { arguments += listOf("--scope-b64", encode(scopeJson(it))) }
        var problem: TranscriptSearchProblem? = null
        var complete = false
        var truncated = false
        var unreadable = 0
        val hits = mutableListOf<TranscriptSearchHit>()
        try {
            readRemoteJson(host, SEARCH_RESOURCE, arguments, ioDispatcher, timeoutMillis, limit + 2) { row ->
                when (row.string("record")) {
                    "match" -> {
                        check(problem == null && !complete && hits.size < limit) { "Unexpected transcript match" }
                        val agent = AgentKind.fromWire(row.string("agent")) ?: error("Invalid transcript agent")
                        val sessionId = row.string("sessionId")
                        val directory = row.string("directory")
                        val file = row.string("file")
                        // A hit is only a hit when it came from the session and file the caller asked about.
                        check(candidates[Triple(agent.wireName, sessionId, directory)]?.contains(file) == true) {
                            "Transcript search identity mismatch"
                        }
                        val sessionKey = row.string("sessionKey")
                        check(sessionKey == "${agent.wireName}:$sessionId") { "Transcript search key mismatch" }
                        val line = (row["line"] as? JsonPrimitive)?.longOrNull ?: -1L
                        val offset = (row["offset"] as? JsonPrimitive)?.longOrNull ?: -1L
                        check(line in 1..Int.MAX_VALUE.toLong() && offset >= 0) { "Invalid transcript anchor" }
                        val snippet = row.string("snippet")
                        check(
                            snippet.length <= TRANSCRIPT_SEARCH_MAX_SNIPPET &&
                                snippet.none { it.code < 32 || it.code == 127 },
                        ) { "Unsafe transcript snippet" }
                        hits += TranscriptSearchHit(
                            sessionKey = sessionKey,
                            agent = agent,
                            sessionId = sessionId,
                            directory = directory,
                            file = file,
                            line = line.toInt(),
                            offset = offset.toInt(),
                            snippet = snippet,
                            clipped = (row["clipped"] as? JsonPrimitive)?.booleanOrNull == true,
                        )
                    }

                    "search" -> {
                        check(problem == null && !complete) { "Duplicate transcript search summary" }
                        // A summary that does not account for every hit it sent is not a summary of this search.
                        check((row["matches"] as? JsonPrimitive)?.longOrNull == hits.size.toLong()) {
                            "Transcript search count mismatch"
                        }
                        unreadable = ((row["unreadable"] as? JsonPrimitive)?.longOrNull ?: 0L).toInt()
                        check(unreadable >= 0) { "Invalid transcript search summary" }
                        // The script names the clock apart from its limits, and either one leaves history unread.
                        truncated = (row["truncated"] as? JsonPrimitive)?.booleanOrNull == true ||
                            (row["timedOut"] as? JsonPrimitive)?.booleanOrNull == true
                        complete = true
                    }

                    "error" -> problem = problem ?: TranscriptSearchProblem.fromCategory(row.string("error"))

                    else -> error("Invalid transcript search record")
                }
            }
        } catch (e: CancellationException) {
            // The only deadline this search owns is readRemoteJson's own bound, so a cancelled caller stays cancelled.
            if (e !is TimeoutCancellationException) throw e
            problem = problem ?: TranscriptSearchProblem.TIMEOUT
        } catch (_: InterruptedIOException) {
            // A read the bound interrupted ends this search at that bound; a caller that went away keeps its own
            // cancellation, so only the deadline's own exception is answered as a timeout here.
            try {
                currentCoroutineContext().ensureActive()
            } catch (e: CancellationException) {
                if (e !is TimeoutCancellationException) throw e
            }
            problem = problem ?: TranscriptSearchProblem.TIMEOUT
        } catch (e: IOException) {
            // A frame over the byte bound, or bytes that are not UTF-8, is this host failing to answer. Every other
            // transport error (a dead session, a credential the user must supply) is the caller's to report.
            if (e is FrameTooLargeException || e is CharacterCodingException) {
                problem = problem ?: TranscriptSearchProblem.FAILED
            } else {
                currentCoroutineContext().ensureActive()
                throw e
            }
        } catch (_: Exception) {
            // Malformed or incomplete output, or a resource this build did not package: no search, never a guess.
            problem = problem ?: TranscriptSearchProblem.FAILED
        }
        return TranscriptSearchResult(
            hits = hits,
            // Anything but a search that read every candidate it was given to the end leaves history unread.
            truncated = truncated || unreadable > 0 || problem != null,
            unreadableFiles = unreadable,
            problem = problem ?: if (complete) null else TranscriptSearchProblem.INCOMPLETE,
        )
    }

    private fun TranscriptSearchTarget.invalid(): Boolean {
        if (sessionId.isEmpty() || !directory.startsWith('/')) return true
        if (sessionId.any { it.code < 32 || it.code == 127 } || directory.any { it.code < 32 || it.code == 127 }) {
            return true
        }
        // A candidate is one file inside the scope's directory, never a path that could leave it.
        return files.any {
            it.isEmpty() || it == "." || it == ".." || it.any { char -> char.code < 32 || char.code == 127 } ||
                '/' in it || '\\' in it
        }
    }

    private fun scopeJson(target: TranscriptSearchTarget): String = buildJsonObject {
        put("agent", target.agent.wireName)
        put("sessionId", target.sessionId)
        put("directory", target.directory)
        put("files", JsonArray(target.files.map { JsonPrimitive(it) }))
    }.toString()

    private fun encode(value: String): String = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

    private companion object {
        const val SEARCH_RESOURCE = "remote/search_transcripts.py"
    }
}
