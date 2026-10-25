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

package org.connectbot.ui

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.assertj.core.api.Assertions.assertThat
import org.connectbot.ui.navigation.TerminalRequest
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The entry a renderer terminal hand-over uses: only a saved host together with the command its console offers
 * for confirmation is a request, and every other launch — including one that names only half of it — opens no
 * console at all.
 */
@RunWith(AndroidJUnit4::class)
class TerminalRequestIntentTest {
    @Test
    fun aHandoverCarriesItsHostAndTheCommandTheConsoleOffersForConfirmation() {
        val request = Intent()
            .putExtra(MainActivity.EXTRA_TERMINAL_HOST_ID, 7L)
            .putExtra(MainActivity.EXTRA_TERMINAL_COMMAND, "cd -- '/workspace/app' && exec kimi --session 'session-a'")

        assertThat(terminalRequestOf(request)).isEqualTo(TerminalRequest(7L, "cd -- '/workspace/app' && exec kimi --session 'session-a'"))
    }

    @Test
    fun everyOtherLaunchCarriesNoRequest() {
        assertThat(terminalRequestOf(null)).isNull()
        assertThat(terminalRequestOf(Intent())).isNull()
        assertThat(terminalRequestOf(Intent().putExtra(MainActivity.EXTRA_TERMINAL_HOST_ID, 7L))).isNull()
        assertThat(terminalRequestOf(Intent().putExtra(MainActivity.EXTRA_TERMINAL_COMMAND, "cd -- '/srv/app'"))).isNull()
        // An id no saved host has, and a command that is only whitespace, are not a hand-over either.
        assertThat(terminalRequestOf(Intent().putExtra(MainActivity.EXTRA_TERMINAL_HOST_ID, 0L).putExtra(MainActivity.EXTRA_TERMINAL_COMMAND, "cd -- '/srv/app'"))).isNull()
        assertThat(terminalRequestOf(Intent().putExtra(MainActivity.EXTRA_TERMINAL_HOST_ID, 7L).putExtra(MainActivity.EXTRA_TERMINAL_COMMAND, "  "))).isNull()
    }
}
