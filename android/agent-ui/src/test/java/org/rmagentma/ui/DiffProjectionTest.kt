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

package org.rmagentma.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DiffProjectionTest {
    @Test
    fun distinguishesHeadersAdditionsRemovalsAndContext() {
        val projected = projectDiff("--- a/file\n+++ b/file\n@@ -1 +1 @@\n-old\n+new\n unchanged")
        assertEquals(
            listOf(
                DiffLineKind.HEADER,
                DiffLineKind.HEADER,
                DiffLineKind.HEADER,
                DiffLineKind.REMOVED,
                DiffLineKind.ADDED,
                DiffLineKind.CONTEXT,
            ),
            projected.map { it.kind },
        )
        assertEquals("+new", projected[4].text)
    }

    @Test
    fun gitHeaderDoesNotMisclassifyContentBeginningWithRepeatedSigns() {
        val projected = projectDiff("diff --git a/file b/file\n+++value\n---value")
        assertEquals(
            listOf(DiffLineKind.HEADER, DiffLineKind.ADDED, DiffLineKind.REMOVED),
            projected.map { it.kind },
        )
    }

    @Test
    fun preservesWhitespaceAndNormalizesWindowsLineEndings() {
        val projected = projectDiff("+  indentation\r\n context\r\n")
        assertEquals(listOf("+  indentation", " context", ""), projected.map { it.text })
    }

    @Test
    fun emptyDiffKeepsAnEmptyContextLine() {
        assertEquals(listOf(DiffLine("", DiffLineKind.CONTEXT)), projectDiff(""))
    }

    @Test
    fun markdownProjectionKeepsCodeLiteralAndDoesNotInterpretRemoteHtml() {
        assertEquals(
            listOf(
                MarkdownLine("Title", code = false, heading = true),
                MarkdownLine("# literal", code = true, heading = false),
                MarkdownLine("<script>alert(1)</script>", code = false, heading = false),
            ),
            projectMarkdown("# Title\n```\n# literal\n```\n<script>alert(1)</script>"),
        )
    }
}
