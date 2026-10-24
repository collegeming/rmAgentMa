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

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.rmagentma.core.AgentKind
import org.rmagentma.core.AgentSession
import org.rmagentma.core.TranscriptSearchHit
import org.rmagentma.core.TranscriptSearchProblem

class AgentTranscriptSearchTest {
    @Test
    fun looksForTheTranscriptRootBelowEveryAncestorDeepestFirst() {
        assertThat(transcriptRootCandidates("/home/dev/app/deep")).containsExactly(
            "/home/dev/app/deep/.dsh/sessions",
            "/home/dev/app/.dsh/sessions",
            "/home/dev/.dsh/sessions",
            "/home/.dsh/sessions",
        )
    }

    @Test
    fun boundsTheWalkAndRefusesAPathNoRemoteArgumentCanCarry() {
        val deep = "/" + (1..TRANSCRIPT_ROOT_WALK_LIMIT + 4).joinToString("/") { "level$it" }
        assertThat(transcriptRootCandidates(deep)).hasSize(TRANSCRIPT_ROOT_WALK_LIMIT)
        assertThat(transcriptRootCandidates("relative/app")).isEmpty()
        assertThat(transcriptRootCandidates("/home/dev\napp")).isEmpty()
    }

    @Test
    fun keepsTheNewestGenerationOnlySoOneHistoryIsNotCountedTwice() {
        assertThat(newestTranscriptFile(listOf("session.v1.jsonl", "session.v4.jsonl", "session.lock")))
            .isEqualTo("session.v4.jsonl")
        assertThat(newestTranscriptFile(listOf("session.v4.jsonl", "session.v4.jsonl.zstd")))
            .isEqualTo("session.v4.jsonl.zstd")
        assertThat(newestTranscriptFile(listOf("session.lock", "notes.txt", "session.v.jsonl"))).isNull()
        assertThat(newestTranscriptFile(emptyList())).isNull()
    }

    @Test
    fun onlyTheAgentWhoseHistoryIsAFileCanBeSearched() {
        assertThat(agentTranscriptSkipReason(AgentKind.DSH)).isNull()
        listOf(AgentKind.KIMI, AgentKind.OPENCODE, AgentKind.OMP, AgentKind.ZCODE).forEach {
            assertThat(agentTranscriptSkipReason(it)).isEqualTo(AgentTranscriptSkipReason.HISTORY_NOT_A_FILE)
        }
    }

    @Test
    fun anyUnreadPartOfTheRangeKeepsTheAnswerIncomplete() {
        assertThat(outcome().incomplete).isFalse()
        assertThat(outcome(hits = listOf(AgentTranscriptHit(1, hit()))).incomplete).isFalse()
        assertThat(outcome(truncated = true).incomplete).isTrue()
        assertThat(outcome(problems = listOf(TranscriptSearchProblem.TIMEOUT)).incomplete).isTrue()
        assertThat(outcome(skips = listOf(AgentTranscriptSkip(session(), AgentTranscriptSkipReason.FILE_NOT_FOUND))).incomplete).isTrue()
        assertThat(outcome(failures = listOf(AgentTranscriptFailure(1, "connection reset"))).incomplete).isTrue()
    }

    private fun outcome(
        hits: List<AgentTranscriptHit> = emptyList(),
        truncated: Boolean = false,
        problems: List<TranscriptSearchProblem> = emptyList(),
        failures: List<AgentTranscriptFailure> = emptyList(),
        skips: List<AgentTranscriptSkip> = emptyList(),
    ) = AgentTranscriptOutcome(
        query = "keyword",
        hits = hits,
        truncated = truncated,
        problems = problems,
        failures = failures,
        skips = skips,
    )

    private fun hit() = TranscriptSearchHit(
        sessionKey = "dsh:session-1",
        agent = AgentKind.DSH,
        sessionId = "session-1",
        directory = "/home/dev/.dsh/sessions/--home-dev-app--/session-1",
        file = "session.v4.jsonl.zstd",
        line = 12,
        offset = 4,
        snippet = "keyword here",
        clipped = false,
    )

    private fun session() = AgentSession(1, AgentKind.DSH, "session-1", "/home/dev/app", title = "Build")
}
