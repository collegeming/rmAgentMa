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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.rmagentma.core.AcpDriver
import org.rmagentma.core.AgentKind
import org.rmagentma.core.AgentSession
import org.rmagentma.core.HostSession
import org.rmagentma.core.RemoteScanner

/** Whether the Agent still holds the conversation this app is showing, as far as the Agent itself answers. */
enum class AgentRecoveryState { ATTACHED, GONE, UNKNOWN }

/** The answer of one existence check, with the reason when it could not be made. */
data class AgentRecovery(val state: AgentRecoveryState, val note: String?)

/**
 * One bounded, read-only existence check of a session, through the entry the Agent already offers for its own
 * list: the file scanner reads the Agent's storage, and the one Agent that has no scanner format is asked through
 * its session list. Nothing is created, nothing is resumed and nothing is sent - a second conversation and a
 * replayed prompt are exactly what this check must not do.
 *
 * Only the Agent's own list decides the answer: a session it names is attached, a session a complete list does
 * not name is gone, and a list that could not be read - or that stopped at its bound before naming this session -
 * is unknown with that reason instead of a guess.
 */
internal class AgentRecoveryCheck(private val io: CoroutineDispatcher) {
    suspend fun verify(host: HostSession, session: AgentSession, limit: Int = AGENT_SESSION_PAGE_LIMIT): AgentRecovery {
        val rows = try {
            if (session.agent == AgentKind.OMP) {
                listSessions(host, session.agent, limit)
            } else {
                val scan = RemoteScanner(io).scan(host, setOf(session.agent), limit)
                // A scan that reported a problem did not read the list: saying the session is gone would claim
                // more than the listing answered.
                if (scan.errors.isNotEmpty()) return AgentRecovery(AgentRecoveryState.UNKNOWN, scan.errors.first())
                scan.sessions
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return AgentRecovery(AgentRecoveryState.UNKNOWN, describeRecoveryFailure(e))
        }
        if (rows.any { it.sessionId == session.sessionId }) return AgentRecovery(AgentRecoveryState.ATTACHED, null)
        if (rows.size >= limit) {
            return AgentRecovery(
                AgentRecoveryState.UNKNOWN,
                "the Agent's session list was read up to its bound of $limit sessions and did not name this one",
            )
        }
        return AgentRecovery(AgentRecoveryState.GONE, "the Agent's own list of ${rows.size} sessions does not hold this one")
    }

    /** The one Agent without a file listing this app can read is asked for its own session list instead. */
    private suspend fun listSessions(host: HostSession, agent: AgentKind, limit: Int): List<AgentSession> {
        val scope = CoroutineScope(SupervisorJob() + io)
        return try {
            AcpDriver(agent, scope, io).listSessions(host, limit)
        } finally {
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }
}

/** The reason a check could not be made, bounded and without a stack trace. */
private fun describeRecoveryFailure(error: Throwable): String = generateSequence(error) { it.cause }
    .take(4)
    .map { it.message?.takeIf { message -> message.isNotBlank() } ?: it.javaClass.simpleName }
    .joinToString(" <- ")
    .take(512)
