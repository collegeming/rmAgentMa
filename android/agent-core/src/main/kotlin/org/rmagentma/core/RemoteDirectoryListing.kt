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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.charset.CharacterCodingException

/** Bound one directory listing keeps, so a slow or silent host cannot hold a file panel open indefinitely. */
const val DIRECTORY_LISTING_TIMEOUT_MILLIS: Long = 10_000

/** Most entries one listing returns; a bigger directory is reported as truncated instead of being read whole. */
const val DIRECTORY_LISTING_MAX_ENTRIES: Int = 200

/** Most bytes one listing answer may spend on entries; the rest of a huge directory is left unread. */
const val DIRECTORY_LISTING_MAX_BYTES: Int = 128 * 1024

/** Why one directory could not be listed; the codes are the remote script's own categories. */
enum class DirectoryListingProblem(val code: String) {
    NOT_ABSOLUTE("dir_not_absolute"),
    MISSING("dir_missing"),
    NOT_A_DIRECTORY("dir_not_a_directory"),
    NOT_READABLE("dir_not_readable"),
    TOOL_MISSING("dir_list_tool_missing"),
    TIMEOUT("dir_list_timeout"),
    FAILED("dir_list_failed"),
    ;

    companion object {
        /**
         * The script's own category, and the categories the shared remote transport reports when python3 or
         * base64 is missing on the host. Anything this build has no word for is a failed listing, never a guess.
         */
        fun fromCategory(category: String): DirectoryListingProblem = when (category) {
            "python_missing", "base64_missing" -> TOOL_MISSING
            else -> entries.firstOrNull { it.code == category } ?: FAILED
        }
    }
}

/**
 * What one entry is, in the renderer's own three-way vocabulary. Anything that is neither a directory nor a
 * symlink is drawn as a file, because a panel has no fourth way to show it.
 */
enum class DirectoryEntryKind(val wireName: String) {
    FILE("file"),
    DIRECTORY("dir"),
    SYMLINK("symlink"),
    ;

    companion object {
        fun fromWire(value: String): DirectoryEntryKind = entries.firstOrNull { it.wireName == value } ?: FILE
    }
}

data class RemoteDirectoryEntry(
    val name: String,
    val kind: DirectoryEntryKind,
    /** Size in bytes as the host reported it, or -1 when the host could not stat the entry. */
    val size: Long,
    /** Target of a symlink exactly as stored, or null for every other kind. Never followed here. */
    val symlinkTarget: String? = null,
)

sealed interface DirectoryListing {
    /** Entries the host returned, with [truncated] set when the directory holds more than the listing read. */
    data class Listed(val entries: List<RemoteDirectoryEntry>, val truncated: Boolean) : DirectoryListing

    /** The directory has no listing, and this is why. Never an empty listing: an empty directory lists as empty. */
    data class Unavailable(val problem: DirectoryListingProblem) : DirectoryListing
}

/**
 * Read-only listing of one directory over a host session: one bounded remote script, no writes, no recursion and
 * no symlink followed. An answer from the host is a verdict about that directory; a host that could not be reached
 * keeps its own connection error instead of borrowing a verdict about a directory nobody read.
 */
class RemoteDirectoryListing(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val timeoutMillis: Long = DIRECTORY_LISTING_TIMEOUT_MILLIS,
) {
    init {
        require(timeoutMillis > 0) { "Timeout must be positive" }
    }

    suspend fun list(host: HostSession, directory: String, limit: Int = DIRECTORY_LISTING_MAX_ENTRIES): DirectoryListing {
        require(limit in 1..DIRECTORY_LISTING_MAX_ENTRIES) { "Limit must be between 1 and $DIRECTORY_LISTING_MAX_ENTRIES" }
        if (!directory.startsWith('/') || directory.any { it.code < 32 || it.code == 127 }) {
            return DirectoryListing.Unavailable(DirectoryListingProblem.NOT_ABSOLUTE)
        }
        var problem: DirectoryListingProblem? = null
        var complete = false
        var truncated = false
        val entries = mutableListOf<RemoteDirectoryEntry>()
        try {
            readRemoteJson(
                host,
                LISTING_RESOURCE,
                listOf("--directory", directory, "--limit", limit.toString(), "--max-bytes", DIRECTORY_LISTING_MAX_BYTES.toString()),
                ioDispatcher,
                timeoutMillis,
                // One row per entry plus the one summary row that closes the listing.
                limit + 1,
            ) { row ->
                when (row.string("record")) {
                    "entry" -> {
                        check(problem == null && !complete && entries.size < limit) { "Unexpected directory entry" }
                        val name = row.string("name")
                        check(name.isNotEmpty()) { "Directory entry without a name" }
                        entries += RemoteDirectoryEntry(
                            name = name,
                            kind = DirectoryEntryKind.fromWire(row.string("kind")),
                            size = (row["size"] as? JsonPrimitive)?.longOrNull ?: -1L,
                            symlinkTarget = row.string("symlink_target").ifEmpty { null },
                        )
                    }

                    "listing" -> {
                        check(problem == null && !complete) { "Duplicate directory listing" }
                        // A listing that does not account for every entry it sent is not a listing this host read.
                        check((row["count"] as? JsonPrimitive)?.longOrNull == entries.size.toLong()) { "Directory entry count mismatch" }
                        truncated = (row["truncated"] as? JsonPrimitive)?.booleanOrNull == true
                        complete = true
                    }

                    "error" -> problem = problem ?: DirectoryListingProblem.fromCategory(row.string("error"))

                    else -> error("Invalid directory record")
                }
            }
        } catch (_: TimeoutCancellationException) {
            // The host was still talking when the bound expired, so no directory was ever described.
            problem = problem ?: DirectoryListingProblem.TIMEOUT
        } catch (e: CancellationException) {
            throw e
        } catch (_: InterruptedIOException) {
            // The bound interrupted a read that was still waiting; a cancelled caller still wins over that.
            currentCoroutineContext().ensureActive()
            problem = problem ?: DirectoryListingProblem.TIMEOUT
        } catch (e: IOException) {
            // A frame over the byte bound, or bytes that are not UTF-8, is this host failing to read an answer that
            // did arrive. Every other transport error (a dead session, a credential the user must supply) is the
            // caller's to report as itself, never as a verdict about a directory nobody read.
            if (e is FrameTooLargeException || e is CharacterCodingException) {
                problem = problem ?: DirectoryListingProblem.FAILED
            } else {
                currentCoroutineContext().ensureActive()
                throw e
            }
        } catch (_: Exception) {
            // Malformed or incomplete output, or a resource this build did not package: no listing, never a guess.
            problem = problem ?: DirectoryListingProblem.FAILED
        }
        val failure = problem
        return when {
            failure != null -> DirectoryListing.Unavailable(failure)
            !complete -> DirectoryListing.Unavailable(DirectoryListingProblem.FAILED)
            else -> DirectoryListing.Listed(entries, truncated)
        }
    }

    private companion object {
        const val LISTING_RESOURCE = "remote/list_directory.py"
    }
}
