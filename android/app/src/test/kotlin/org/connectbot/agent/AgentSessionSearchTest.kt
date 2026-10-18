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

class AgentSessionSearchTest {
    @Test
    fun strongestFieldWinsOncePerSession() {
        val both = session(title = "Deploy notes", preview = "deploy preview", cwd = "/deploy/app")
        assertThat(searchAgentSessions(listOf(both), "deploy").hits.single().field).isEqualTo(AgentSearchField.TITLE)

        val previewFirst = both.copy(sessionId = "preview", title = "notes")
        assertThat(searchAgentSessions(listOf(previewFirst), "deploy").hits.single().field).isEqualTo(AgentSearchField.PREVIEW)

        val cwdOnly = both.copy(sessionId = "cwd", title = "notes", preview = "no match here")
        assertThat(searchAgentSessions(listOf(cwdOnly), "deploy").hits.single().field).isEqualTo(AgentSearchField.CWD)
    }

    @Test
    fun titleHitsRankBeforeDirectoryHits() {
        val titleHit = session(sessionId = "title", title = "Deploy log", updatedAt = 1)
        val cwdHit = session(sessionId = "cwd", title = "notes", cwd = "/deploy/app", updatedAt = 99)
        assertThat(searchAgentSessions(listOf(cwdHit, titleHit), "deploy").hits.map { it.session.sessionId })
            .containsExactly("title", "cwd")
    }

    @Test
    fun newerSessionsRankFirstWithinTheSameField() {
        val older = session(sessionId = "old", title = "Deploy log", updatedAt = 10)
        val newer = session(sessionId = "new", title = "Deploy notes", updatedAt = 20)
        assertThat(searchAgentSessions(listOf(older, newer), "deploy").hits.map { it.session.sessionId })
            .containsExactly("new", "old")
    }

    @Test
    fun blankQueryReturnsNoHits() {
        val results = searchAgentSessions(listOf(session(title = "Deploy log")), "   ")
        assertThat(results.hits).isEmpty()
        assertThat(results.scope.cachedCount).isEqualTo(1)
    }

    @Test
    fun matchesChineseSubstrings() {
        val hit = searchAgentSessions(listOf(session(title = "生产环境部署记录")), "部署").hits.single()
        assertThat(hit.field).isEqualTo(AgentSearchField.TITLE)
        assertThat(hit.snippet).isEqualTo("生产环境部署记录")
    }

    @Test
    fun matchesAnyCaseInEveryField() {
        assertThat(searchAgentSessions(listOf(session(title = "Deploy Log")), "deploy").hits.single().field)
            .isEqualTo(AgentSearchField.TITLE)
        assertThat(searchAgentSessions(listOf(session(title = "notes", preview = "DEPLOY failed")), "Deploy").hits.single().field)
            .isEqualTo(AgentSearchField.PREVIEW)
        assertThat(searchAgentSessions(listOf(session(title = "notes", cwd = "/Work/APP")), "app").hits.single().field)
            .isEqualTo(AgentSearchField.CWD)
    }

    @Test
    fun snippetIsBoundedContextAroundTheMatch() {
        val filler = "x".repeat(200)
        val preview = filler + "目标部署" + filler
        val hit = searchAgentSessions(listOf(session(title = "notes", preview = preview)), "目标部署", snippetLimit = 40).hits.single()
        assertThat(hit.field).isEqualTo(AgentSearchField.PREVIEW)
        assertThat(hit.snippet).contains("目标部署")
        assertThat(hit.snippet).startsWith("…")
        assertThat(hit.snippet).endsWith("…")
        assertThat(hit.snippet.length).isLessThanOrEqualTo(42)

        val short = searchAgentSessions(listOf(session(title = "Deploy notes")), "deploy").hits.single()
        assertThat(short.snippet).isEqualTo("Deploy notes")
    }

    @Test
    fun snippetKeepsTheMatchVisibleAtTheEndOfALongField() {
        val hit = searchAgentSessions(listOf(session(title = "y".repeat(200) + " tail-marker")), "tail-marker", snippetLimit = 30).hits.single()
        assertThat(hit.snippet).startsWith("…")
        assertThat(hit.snippet).endsWith("tail-marker")
        assertThat(hit.snippet.length).isLessThanOrEqualTo(32)
    }

    @Test
    fun failedSourceKeepsResultsAndMarksTheRangeIncomplete() {
        val results = searchAgentSessions(
            listOf(session(title = "Deploy log")),
            "deploy",
            hasUnloadedPages = true,
            failedSources = listOf("host1 / kimi: scan failed"),
        )
        assertThat(results.hits).hasSize(1)
        assertThat(results.scope.failedSources).containsExactly("host1 / kimi: scan failed")
        assertThat(results.scope.hasUnloadedPages).isTrue()
        assertThat(results.scope.complete).isFalse()
    }

    @Test
    fun unloadedPageOrBlankFailureMarksTheRangeIncomplete() {
        assertThat(searchAgentSessions(listOf(session(title = "Deploy log")), "deploy", hasUnloadedPages = true).scope.complete)
            .isFalse()
        assertThat(searchAgentSessions(listOf(session(title = "Deploy log")), "deploy", failedSources = listOf("   ")).scope.complete)
            .isTrue()
    }

    @Test
    fun sourceLabelsAndCountsFollowTheHostAgentPair() {
        val remote = session(sessionId = "remote", hostId = 1, agent = AgentKind.KIMI, title = "Deploy one", updatedAt = 20)
        val otherAgent = session(sessionId = "other", hostId = 1, agent = AgentKind.DSH, title = "Deploy two", updatedAt = 10)
        val results = searchAgentSessions(listOf(remote, otherAgent), "deploy", remoteSources = setOf(1L to AgentKind.KIMI))
        assertThat(results.scope.remoteCount).isEqualTo(1)
        assertThat(results.scope.cachedCount).isEqualTo(1)
        assertThat(results.scope.complete).isTrue()
        assertThat(results.hits.map { it.source }).containsExactly(AgentSearchSource.REMOTE, AgentSearchSource.CACHED)

        val cachedOnly = searchAgentSessions(listOf(session(title = "Deploy log")), "deploy")
        assertThat(cachedOnly.hits.single().source).isEqualTo(AgentSearchSource.CACHED)
    }

    private fun session(
        sessionId: String = "s1",
        hostId: Long = 1,
        agent: AgentKind = AgentKind.KIMI,
        cwd: String = "/work",
        title: String = "",
        preview: String = "",
        updatedAt: Long = 0,
    ) = AgentSession(
        hostId = hostId,
        agent = agent,
        sessionId = sessionId,
        cwd = cwd,
        title = title,
        preview = preview,
        updatedAt = updatedAt,
    )
}
