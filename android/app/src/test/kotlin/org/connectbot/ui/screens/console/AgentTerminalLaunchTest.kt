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

package org.connectbot.ui.screens.console

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTerminalLaunchTest {
    @Test
    fun launchRequiresTheSelectedHostAndAnOpenSession() {
        assertTrue(canLaunchAgentCommand("cd -- '/tmp' && exec kimi", 3L, 3L, true))
        assertFalse(canLaunchAgentCommand("kimi", 3L, 4L, true))
        assertFalse(canLaunchAgentCommand("kimi", 3L, 3L, false))
        assertFalse(canLaunchAgentCommand("kimi", null, null, true))
        assertFalse(canLaunchAgentCommand(null, 3L, 3L, true))
        assertFalse(canLaunchAgentCommand(" ", 3L, 3L, true))
    }

    @Test
    fun launchRejectsTerminalControlCharacters() {
        (0..31).plus(127).forEach { code ->
            assertFalse(canLaunchAgentCommand("kimi${code.toChar()}--session id", 3L, 3L, true))
        }
        assertTrue(canLaunchAgentCommand("cd -- '/tmp/中文项目' && exec kimi", 3L, 3L, true))
    }
}
