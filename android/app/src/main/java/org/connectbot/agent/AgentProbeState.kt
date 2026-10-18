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
import kotlinx.serialization.json.booleanOrNull
import org.connectbot.R
import org.rmagentma.core.AgentAvailability

enum class AgentProbeState {
    REFRESHING,
    AVAILABLE,
    NOT_INSTALLED,
    START_FAILED,
    NO_PROTOCOL_CAPABILITY,
    CONNECT_FAILED,
    UNKNOWN_FAILURE,
}

// Nothing ran because the host holds no supported installation.
private val probeNotInstalledCodes = setOf(
    "executable_missing",
    "zcode_layout_unverified",
    "zcode_runtime_files_missing",
)

// The executable ran but never completed a usable handshake.
private val probeStartFailedCodes = setOf(
    "version_unverified",
    "version_failed",
    "version_timeout",
    "initialize_no_response",
    "initialize_timeout",
    "initialize_failed",
    "zcode_no_response",
    "zcode_ready_timeout",
    "zcode_startup_failed",
    "zcode_probe_failed",
    "output_limit",
)

// The agent answered and then rejected or mismatched the protocol we require.
private val probeNoCapabilityCodes = setOf(
    "initialize_rejected",
    "unsupported_protocol_version",
    "invalid_capabilities",
    "zcode_capabilities_rejected",
)

// The probe run itself never reached the host or never produced rows.
private val probeConnectFailedCodes = setOf(
    "probe_transport_failed",
    "probe_missing_result",
    "python_missing",
    "base64_missing",
    "remote_exit_failed",
)

// Unrecognized codes keep no guessed state; the raw code stays in AgentAvailability.error for diagnosis.
fun probeStateOf(availability: AgentAvailability, refreshing: Boolean): AgentProbeState = when {
    refreshing -> AgentProbeState.REFRESHING
    availability.available && availability.capabilities.declaresNoCapability() -> AgentProbeState.NO_PROTOCOL_CAPABILITY
    availability.available -> AgentProbeState.AVAILABLE
    availability.error in probeNotInstalledCodes -> AgentProbeState.NOT_INSTALLED
    availability.error in probeStartFailedCodes -> AgentProbeState.START_FAILED
    availability.error in probeNoCapabilityCodes -> AgentProbeState.NO_PROTOCOL_CAPABILITY
    availability.error in probeConnectFailedCodes -> AgentProbeState.CONNECT_FAILED
    else -> AgentProbeState.UNKNOWN_FAILURE
}

/** Null means no existing resource describes the state, so callers keep their own text for it. */
fun probeReasonKey(state: AgentProbeState): Int? = when (state) {
    AgentProbeState.AVAILABLE -> R.string.agent_available
    AgentProbeState.REFRESHING -> null
    else -> R.string.agent_unavailable
}

// Declared-and-all-false capabilities disprove protocol readiness; absent capabilities keep the probe verdict.
private fun JsonObject?.declaresNoCapability(): Boolean = this != null &&
    isNotEmpty() &&
    values.none { (it as? JsonPrimitive)?.booleanOrNull == true }
