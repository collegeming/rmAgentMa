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

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.rmagentma.core.AgentAvailability
import org.rmagentma.core.AgentKind

class AgentProbeStateTest {
    @Test
    fun mapsEveryDistinctProbeCode() {
        val expected = mapOf(
            "executable_missing" to AgentProbeState.NOT_INSTALLED,
            "zcode_layout_unverified" to AgentProbeState.NOT_INSTALLED,
            "zcode_runtime_files_missing" to AgentProbeState.NOT_INSTALLED,
            "version_unverified" to AgentProbeState.START_FAILED,
            "version_failed" to AgentProbeState.START_FAILED,
            "version_timeout" to AgentProbeState.START_FAILED,
            "initialize_no_response" to AgentProbeState.START_FAILED,
            "initialize_timeout" to AgentProbeState.START_FAILED,
            "initialize_failed" to AgentProbeState.START_FAILED,
            "zcode_no_response" to AgentProbeState.START_FAILED,
            "zcode_ready_timeout" to AgentProbeState.START_FAILED,
            "zcode_startup_failed" to AgentProbeState.START_FAILED,
            "zcode_probe_failed" to AgentProbeState.START_FAILED,
            "output_limit" to AgentProbeState.START_FAILED,
            "initialize_rejected" to AgentProbeState.NO_PROTOCOL_CAPABILITY,
            "unsupported_protocol_version" to AgentProbeState.NO_PROTOCOL_CAPABILITY,
            "invalid_capabilities" to AgentProbeState.NO_PROTOCOL_CAPABILITY,
            "zcode_capabilities_rejected" to AgentProbeState.NO_PROTOCOL_CAPABILITY,
            "probe_transport_failed" to AgentProbeState.CONNECT_FAILED,
            "probe_missing_result" to AgentProbeState.CONNECT_FAILED,
            "python_missing" to AgentProbeState.CONNECT_FAILED,
            "base64_missing" to AgentProbeState.CONNECT_FAILED,
            "remote_exit_failed" to AgentProbeState.CONNECT_FAILED,
        )
        expected.forEach { (code, state) ->
            assertThat(probeStateOf(availability(error = code), refreshing = false)).describedAs(code).isEqualTo(state)
        }
    }

    @Test
    fun unknownCodeFallsBackAndKeepsTheRawCode() {
        val row = availability(error = "future_probe_code")
        assertThat(probeStateOf(row, refreshing = false)).isEqualTo(AgentProbeState.UNKNOWN_FAILURE)
        assertThat(row.error).isEqualTo("future_probe_code")
    }

    @Test
    fun unreportedFailureIsUnknownRatherThanAvailable() {
        assertThat(probeStateOf(availability(), refreshing = false)).isEqualTo(AgentProbeState.UNKNOWN_FAILURE)
    }

    @Test
    fun availableRowWithoutAnyTrueCapabilityHasNoProtocolCapability() {
        val row = availability(available = true, capabilities = capabilities("acp" to false, "list" to false, "load" to false))
        assertThat(probeStateOf(row, refreshing = false)).isEqualTo(AgentProbeState.NO_PROTOCOL_CAPABILITY)
    }

    @Test
    fun availableRowStaysAvailableForVerifiedOrUnreportedCapabilities() {
        assertThat(probeStateOf(availability(available = true), refreshing = false)).isEqualTo(AgentProbeState.AVAILABLE)
        val acp = availability(available = true, capabilities = capabilities("acp" to true, "list" to false))
        assertThat(probeStateOf(acp, refreshing = false)).isEqualTo(AgentProbeState.AVAILABLE)
        val zcode = availability(available = true, capabilities = capabilities("acp" to false, "zcodeProtocol" to true, "startupReady" to true))
        assertThat(probeStateOf(zcode, refreshing = false)).isEqualTo(AgentProbeState.AVAILABLE)
    }

    @Test
    fun refreshingOutranksEveryKnownResult() {
        val rows = listOf(
            availability(available = true, capabilities = capabilities("acp" to true)),
            availability(error = "executable_missing"),
            availability(error = "initialize_rejected"),
            availability(error = "probe_transport_failed"),
            availability(error = "future_probe_code"),
        )
        rows.forEach { row ->
            assertThat(probeStateOf(row, refreshing = true))
                .describedAs(row.error.ifEmpty { "verified row" })
                .isEqualTo(AgentProbeState.REFRESHING)
        }
    }

    private fun availability(
        available: Boolean = false,
        error: String = "",
        capabilities: JsonObject? = null,
    ) = AgentAvailability(
        hostId = 1L,
        kind = AgentKind.DSH,
        executable = "/usr/local/bin/dsh",
        available = available,
        error = error,
        capabilities = capabilities,
    )

    private fun capabilities(vararg entries: Pair<String, Boolean>): JsonObject = JsonObject(entries.associate { (key, value) -> key to JsonPrimitive(value) })
}
