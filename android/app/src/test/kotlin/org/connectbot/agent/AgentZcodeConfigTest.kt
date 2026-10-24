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

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.rmagentma.core.AgentSessionConfig
import org.rmagentma.core.ZcodeBusyException
import org.rmagentma.core.ZcodeConfigChange
import org.rmagentma.core.ZcodeConfigStatus
import org.rmagentma.core.ZcodeModelOption
import org.rmagentma.core.ZcodeModelSelection
import org.rmagentma.core.ZcodeReasoningLevel
import org.rmagentma.core.ZcodeSessionConfig
import org.rmagentma.core.ZcodeThoughtState

/** ZCode's own settings projected into the option surface the Compose panel and the renderer share. */
class AgentZcodeConfigTest {
    @Test
    fun projectsTheAgentsCatalogueIntoAcpShapedOptions() {
        val options = AgentZcodeConfig.project(snapshot()).options
        assertThat(options.map { it.id }).containsExactly(ZCODE_MODEL_OPTION, ZCODE_THOUGHT_OPTION, ZCODE_MODE_OPTION)

        val model = options.first { it.id == ZCODE_MODEL_OPTION }
        assertThat(model.category).isEqualTo("model")
        assertThat(model.type).isEqualTo("select")
        assertThat(model.currentValue?.contentOrNull).isEqualTo("p/m1")
        assertThat(model.values.map { it.value }).containsExactly("p/m1", "p/m2")
        assertThat(model.values.map { it.name }).containsExactly("Model One", "Model Two")

        val thought = options.first { it.id == ZCODE_THOUGHT_OPTION }
        assertThat(thought.category).isEqualTo("thought_level")
        assertThat(thought.type).isEqualTo("select")
        assertThat(thought.currentValue?.contentOrNull).isEqualTo("high")
        assertThat(thought.values.map { it.value }).containsExactly("high", "low")

        val mode = options.first { it.id == ZCODE_MODE_OPTION }
        assertThat(mode.category).isEqualTo("mode")
        assertThat(mode.type).isEqualTo("readonly")
        assertThat(mode.currentValue?.contentOrNull).isEqualTo("build")
        assertThat(mode.values).isEmpty()
    }

    @Test
    fun aCurrentValueTheAgentNeverStatedStaysNull() {
        val model = modelOf(AgentZcodeConfig.project(snapshot(current = null)))
        assertThat(model.currentValue).isNull()
        assertThat(model.values.map { it.value }).containsExactly("p/m1", "p/m2")
    }

    @Test
    fun aCatalogueTheAgentNeverSentLeavesTheModelReadOnly() {
        val model = modelOf(AgentZcodeConfig.project(snapshot(catalog = emptyList(), current = "m9")))
        assertThat(model.type).isEqualTo("readonly")
        assertThat(model.values).isEmpty()
        assertThat(model.currentValue?.contentOrNull).isEqualTo("p/m9")
    }

    @Test
    fun confirmedAndAdjustedHandOverTheSnapshotWhileAnAckStaysUnconfirmed() {
        val before = snapshot(current = "m1")
        val after = snapshot(current = "m2")

        val confirmed = AgentZcodeConfig.receipt(change(ZcodeConfigStatus.CONFIRMED, applied = "p/m2@high", config = after))
        assertThat(confirmed).isInstanceOf(AgentConfigAccess.Available::class.java)
        assertThat(modelOf((confirmed as AgentConfigAccess.Available).config).currentValue?.contentOrNull).isEqualTo("p/m2")

        val adjusted = AgentZcodeConfig.receipt(change(ZcodeConfigStatus.ADJUSTED, applied = "p/m1", config = before))
        assertThat(modelOf((adjusted as AgentConfigAccess.Available).config).currentValue?.contentOrNull).isEqualTo("p/m1")

        val acked = AgentZcodeConfig.receipt(change(ZcodeConfigStatus.DELIVERED_PENDING, applied = null, config = before))
        assertThat(acked).isInstanceOf(AgentConfigAccess.Unconfirmed::class.java)
        assertThat(modelOf((acked as AgentConfigAccess.Unconfirmed).config!!).currentValue?.contentOrNull).isEqualTo("p/m1")
    }

    @Test
    fun modelWritesUseTheCatalogsProviderAndTheSupportedThoughtLevel() = runTest {
        var call: Triple<String, String, String?>? = null
        AgentZcodeConfig.set(
            snapshot(),
            ZCODE_MODEL_OPTION,
            "p/m2",
            setModel = { providerId, modelId, level ->
                call = Triple(providerId, modelId, level)
                change()
            },
            setThoughtLevel = { error("unexpected thought write") },
        )
        assertThat(call).isEqualTo(Triple("p", "m2", "high"))
    }

    @Test
    fun aModelTheAgentNeverAdvertisedIsRefusedBeforeAnyWrite() = runTest {
        var writes = 0
        val failure = runCatching {
            AgentZcodeConfig.set(
                snapshot(),
                ZCODE_MODEL_OPTION,
                "p/unknown",
                setModel = { _, _, _ ->
                    writes++
                    change()
                },
                setThoughtLevel = {
                    writes++
                    change()
                },
            )
        }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(writes).isZero()
    }

    @Test
    fun thoughtWritesRequireAnEnabledAdvertisedLevel() = runTest {
        var writes = 0
        val disabled = snapshot().copy(thought = ZcodeThoughtState(enabled = false, current = "high", available = LEVELS))
        val failure = runCatching {
            AgentZcodeConfig.set(disabled, ZCODE_THOUGHT_OPTION, "high", { _, _, _ ->
                writes++
                change()
            }, {
                writes++
                change()
            })
        }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(writes).isZero()

        var level: String? = null
        AgentZcodeConfig.set(
            snapshot(),
            ZCODE_THOUGHT_OPTION,
            "low",
            setModel = { _, _, _ ->
                writes++
                change()
            },
            setThoughtLevel = {
                level = it
                change()
            },
        )
        assertThat(level).isEqualTo("low")
    }

    @Test
    fun modeStaysDiagnosticBecauseTheDriverReportsNoCatalogue() = runTest {
        var writes = 0
        val failure = runCatching {
            AgentZcodeConfig.set(snapshot(), ZCODE_MODE_OPTION, "build", { _, _, _ ->
                writes++
                change()
            }, {
                writes++
                change()
            })
        }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(writes).isZero()
    }

    @Test
    fun aBusyRefusalBecomesItsOwnProductError() {
        val busy = AgentZcodeConfig.busy(ZcodeBusyException(-32603, "Permission update is busy; retry mode change"))
        assertThat(busy.code).isEqualTo(-32603)
        assertThat(busy.message).contains("busy")
        assertThat(busy).isNotInstanceOf(ZcodeBusyException::class.java)
    }

    private fun snapshot(
        catalog: List<ZcodeModelOption> = listOf(model("m1", "Model One"), model("m2", "Model Two")),
        current: String? = "m1",
    ): ZcodeSessionConfig = ZcodeSessionConfig(
        catalog = catalog,
        currentModel = current?.let { ZcodeModelSelection("p", it) },
        thought = ZcodeThoughtState(enabled = true, current = "high", available = LEVELS),
        currentMode = "build",
    )

    private fun model(id: String, label: String): ZcodeModelOption = ZcodeModelOption(
        selection = ZcodeModelSelection("p", id),
        label = label,
        reasoningLevels = LEVELS,
        defaultReasoningLevel = "high",
    )

    private fun change(
        status: ZcodeConfigStatus = ZcodeConfigStatus.CONFIRMED,
        requested: String = "p/m2",
        applied: String? = requested,
        config: ZcodeSessionConfig = snapshot(),
    ): ZcodeConfigChange = ZcodeConfigChange(status, requested, applied, revision = 2, busy = false, config = config, raw = JsonObject(emptyMap()))

    private fun modelOf(config: AgentSessionConfig) = config.options.first { it.id == ZCODE_MODEL_OPTION }

    private companion object {
        val LEVELS = listOf(ZcodeReasoningLevel("high", "High"), ZcodeReasoningLevel("low", "Low"))
    }
}
