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

package org.connectbot.ui.screens.agents

import kotlinx.serialization.json.JsonPrimitive
import org.connectbot.agent.AgentConfigAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.rmagentma.core.AgentConfigOption
import org.rmagentma.core.AgentConfigValue
import org.rmagentma.core.AgentSessionConfig

class AgentConfigPresentationTest {
    @Test
    fun resolvesModelAndReasoningFromTheAgentsOwnCatalog() {
        val config = AgentSessionConfig(
            options = listOf(
                option("model", "model", "b", values = listOf("a" to "Model A", "b" to "Model B")),
                option("thinking", "thought_level", "high", values = listOf("low" to "Low", "high" to "High")),
            ),
        )
        val model = agentConfigRoleView(config, AgentConfigRole.MODEL)
        val reasoning = agentConfigRoleView(config, AgentConfigRole.REASONING)
        assertEquals("b", model?.currentValue)
        assertEquals("Model B", model?.current)
        assertEquals("high", reasoning?.currentValue)
        assertEquals("High", reasoning?.current)
        assertEquals(AgentConfigRole.OTHER, agentConfigRole(option("temperature", "", "1")))
    }

    @Test
    fun recognizesReasoningByCategoryOrIdAndModeSeparately() {
        assertEquals(AgentConfigRole.REASONING, agentConfigRole(option("thinking", "thought_level", "on")))
        assertEquals(AgentConfigRole.REASONING, agentConfigRole(option("reasoning_effort", "", "high")))
        assertEquals(AgentConfigRole.MODEL, agentConfigRole(option("model", "", "m")))
        assertEquals(AgentConfigRole.MODE, agentConfigRole(option("mode", "", "code")))
        assertEquals(AgentConfigRole.OTHER, agentConfigRole(option("permission", "", "allow")))
    }

    @Test
    fun fallsBackToTheLegacyModelListForDisplayOnly() {
        val config = AgentSessionConfig(
            models = listOf(AgentConfigValue("legacy", "Legacy Model")),
            currentModelId = "legacy",
        )
        val model = agentConfigRoleView(config, AgentConfigRole.MODEL)
        assertEquals("legacy", model?.currentValue)
        assertEquals("Legacy Model", model?.current)
        assertNull(agentConfigRoleView(config, AgentConfigRole.REASONING))
        assertTrue(config.options.isEmpty())
    }

    @Test
    fun keepsARawCurrentValueTheCatalogDoesNotName() {
        val config = AgentSessionConfig(options = listOf(option("model", "model", "removed", values = listOf("a" to "Model A"))))
        assertEquals("removed", agentConfigRoleView(config, AgentConfigRole.MODEL)?.current)
    }

    @Test
    fun reportsAnOptionWithoutACurrentValueAsUnsetInsteadOfGuessing() {
        val config = AgentSessionConfig(options = listOf(option("model", "model", null)))
        val model = agentConfigRoleView(config, AgentConfigRole.MODEL)
        assertEquals(AgentConfigRole.MODEL, model?.role)
        assertNull(model?.currentValue)
        assertNull(model?.current)
    }

    @Test
    fun mapsAccessStatesWithoutInventingASnapshot() {
        val config = AgentSessionConfig(options = listOf(option("model", "model", "a")))
        assertEquals(AgentConfigUiState.Ready(config), AgentConfigAccess.Available(config).toUiState())
        assertEquals(AgentConfigUiState.Ready(config), AgentConfigAccess.Unconfirmed(config).toUiState())
        assertEquals(AgentConfigUiState.Closed, AgentConfigAccess.Unconfirmed(null).toUiState())
        assertEquals(AgentConfigUiState.Unsupported, AgentConfigAccess.Unsupported.toUiState())
        assertEquals(AgentConfigUiState.Closed, AgentConfigAccess.Closed.toUiState())
    }

    @Test
    fun separatesAConfirmedWriteFromOneThatTakesEffectNextTurn() {
        val applied = AgentSessionConfig(options = listOf(option("model", "model", "b")))
        val deferred = AgentSessionConfig(options = listOf(option("model", "model", "a")))
        val missing = AgentSessionConfig()
        assertEquals(AgentConfigWriteOutcome.CONFIRMED, agentConfigWriteOutcome(AgentConfigAccess.Available(applied), "model", "b"))
        assertEquals(AgentConfigWriteOutcome.NEXT_TURN, agentConfigWriteOutcome(AgentConfigAccess.Available(deferred), "model", "b"))
        assertEquals(AgentConfigWriteOutcome.NEXT_TURN, agentConfigWriteOutcome(AgentConfigAccess.Available(missing), "model", "b"))
    }

    @Test
    fun neverTreatsDeliveryAsConfirmationOrSuccess() {
        val config = AgentSessionConfig(options = listOf(option("model", "model", "a")))
        assertEquals(AgentConfigWriteOutcome.UNCONFIRMED, agentConfigWriteOutcome(AgentConfigAccess.Unconfirmed(config), "model", "b"))
        assertEquals(AgentConfigWriteOutcome.UNSUPPORTED, agentConfigWriteOutcome(AgentConfigAccess.Unsupported, "model", "b"))
        assertEquals(AgentConfigWriteOutcome.CLOSED, agentConfigWriteOutcome(AgentConfigAccess.Closed, "model", "b"))
    }

    @Test
    fun offersAValueOnlyForASelectOption() {
        val values = listOf("a" to "Model A")
        assertTrue(isAgentConfigSwitchable(option("model", "model", "a", values)))
        assertFalse(isAgentConfigSwitchable(option("model", "model", "a", values, type = "number")))
        assertFalse(isAgentConfigSwitchable(option("model", "model", "a", values, type = "")))
    }

    @Test
    fun recognizesOnlyTheConfigUpdateEvent() {
        assertTrue(isAgentConfigUpdate("config_option_update"))
        assertFalse(isAgentConfigUpdate("tool_call"))
        assertFalse(isAgentConfigUpdate(""))
    }

    private fun option(
        id: String,
        category: String,
        current: String?,
        values: List<Pair<String, String>> = emptyList(),
        type: String = "select",
    ): AgentConfigOption = AgentConfigOption(
        id = id,
        name = id,
        category = category,
        type = type,
        currentValue = current?.let(::JsonPrimitive),
        values = values.map { (value, name) -> AgentConfigValue(value, name) },
    )
}
