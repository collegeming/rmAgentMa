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

package org.connectbot.renderer

/**
 * The product's own terminal surface, as a renderer hand-over reaches it. One call starts the SSH console of a
 * saved host with the command the product's own terminal route runs there, so a request the renderer makes really
 * opens a terminal instead of only answering a command text.
 *
 * The command is the text the action layer already built for that host, Agent target and directory; it reaches the
 * console as that route's own pending command, which the user still confirms there before anything is typed into
 * the session. No credential, password or key is part of this call, and no command is run before that confirmation.
 */
internal interface RendererTerminal {
    /** Starts the console of [hostId] with [command] as its pending command, and answers what came of it. */
    suspend fun start(hostId: Long, command: String): Result

    /** What one hand-over produced; only [Started] may ever be reported as an opened terminal. */
    sealed interface Result {
        /** The product's console was started for that host and carries the command as its pending one. */
        object Started : Result

        /** This build has no terminal surface to start at all. */
        object Unsupported : Result

        /** The surface exists but the start itself failed; [reason] is what the system reported. */
        data class Failed(val reason: String) : Result
    }

    companion object {
        /**
         * A host with no terminal surface — a unit test or any build without the product's console — which
         * refuses every hand-over instead of answering a terminal that was never opened.
         */
        val None: RendererTerminal = object : RendererTerminal {
            override suspend fun start(hostId: Long, command: String): Result = Result.Unsupported
        }
    }
}
