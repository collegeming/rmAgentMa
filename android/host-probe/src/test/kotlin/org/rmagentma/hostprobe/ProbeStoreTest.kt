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

package org.rmagentma.hostprobe

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ProbeStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val scheduled = mutableListOf<() -> Unit>()
    private fun store() = ProbeStore(temporary.root.resolve("state.json")) { _, task -> scheduled.add(task) }
    private fun payload(body: JSONObject = JSONObject(), params: JSONObject = JSONObject(), query: JSONObject = JSONObject()) = json("params" to params, "query" to query, "body" to body)
    private fun data(result: ProbeResult) = (result.body as JSONObject).get("data")
    private fun failure(code: String, action: () -> Unit) {
        try {
            action()
            fail("Expected $code")
        } catch (failure: ProbeFailure) {
            assertEquals(code, failure.code)
        }
    }
    private fun send(store: ProbeStore, id: String = "中文发送-1") = data(store.handle("messages.send", payload(json("content" to "你好\n中文任务😀", "loading_id" to id)))) as JSONObject
    private fun advance() {
        scheduled.toList().also { scheduled.clear() }.forEach { it() }
    }

    @Test fun chineseStreamUsesSharedDtoAndNativeApprovalIsOnceOnly() {
        val store = store()
        val events = mutableListOf<JSONObject>()
        store.listener = { name, value, epoch, seq -> events.add(json("name" to name, "data" to value, "epoch" to epoch, "seq" to seq)) }
        val sent = send(store)
        assertEquals(sent.getString("msg_id"), send(store).getString("msg_id"))
        advance()
        assertTrue(events.any { it.getString("name") == "message.stream" && it.getJSONObject("data").getString("type") == "text" })
        events.forEachIndexed { index, event -> assertEquals(index + 1L, event.getLong("seq")) }
        val pending = (data(store.handle("approvals.list", payload())) as JSONArray).getJSONObject(0)
        val reference = pending.getString("request_id")
        assertEquals("conv-probe-1", pending.getString("conversation_id"))
        val reviewed = store.handle("approvals.review", payload(json("epoch" to store.epoch, "data" to "allow-once"), json("requestRef" to reference)))
        assertEquals(202, reviewed.status)
        assertEquals(1, (data(store.handle("approvals.list", payload())) as JSONArray).length())
        store.decide(reference, store.epoch, "allow-once")
        failure("NOT_PENDING") { store.decide(reference, store.epoch, "reject-once") }
        assertEquals(0, (data(store.handle("approvals.list", payload())) as JSONArray).length())
        assertEquals("idle", store.snapshot().getJSONObject("runtime").getString("state"))
        val page = data(store.handle("messages.list", payload())) as JSONObject
        val messages = page.getJSONArray("items")
        val ids = (0 until messages.length()).map { messages.getJSONObject(it).getString("id") }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue((0 until messages.length()).filter { messages.getJSONObject(it).getString("type") == "acp_permission" }.all { messages.getJSONObject(it).getJSONObject("content").getBoolean("readonly") })
    }

    @Test fun cancelCannotAffectNextTurnAndRecoveryNeverRevivesApproval() {
        val store = store()
        val sent = send(store)
        advance()
        val old = (data(store.handle("approvals.list", payload())) as JSONArray).getJSONObject(0)
        store.handle("draft.save", payload(json("text" to "已确认中文草稿\n第二行")))
        store.handle("turn.cancel", payload(json("turn_id" to sent.getString("turn_id"))))
        val next = send(store, "next")
        failure("STALE_TURN") { store.handle("turn.cancel", payload(json("turn_id" to sent.getString("turn_id")))) }
        assertNotEquals(sent.getString("turn_id"), next.getString("turn_id"))
        advance()
        val recovered = store()
        assertNotEquals(store.epoch, recovered.epoch)
        assertEquals(0, (data(recovered.handle("approvals.list", payload())) as JSONArray).length())
        assertEquals("interrupted", recovered.snapshot().getJSONObject("runtime").getString("state"))
        assertEquals("已确认中文草稿\n第二行", (recovered.handle("draft.load", payload()).body as JSONObject).getString("text"))
        failure("NOT_PENDING") { recovered.decide(old.getString("request_id"), store.epoch, "allow-once") }
        assertEquals(next.getString("msg_id"), send(recovered, "next").getString("msg_id"))
        assertTrue(scheduled.isEmpty())
    }

    @Test fun cancellationBeforeChunksAndCorruptStateNeverCreateNewApprovals() {
        val store = store()
        val sent = send(store)
        store.handle("turn.cancel", payload(json("turn_id" to sent.getString("turn_id"))))
        advance()
        assertEquals(0, (data(store.handle("approvals.list", payload())) as JSONArray).length())
        temporary.root.resolve("state.json").writeText("not-json")
        try {
            store()
            fail("Corrupt state must not be silently reset")
        } catch (_: org.json.JSONException) { }
    }

    @Test fun invalidModelsHistoryReferencesAndConflictingSendsAreRejected() {
        val store = store()
        failure("MODEL_NOT_ALLOWED") { store.handle("config.set", payload(json("value" to "paid-model"), json("optionId" to "model"))) }
        store.handle("config.set", payload(json("value" to "probe-model-b"), json("optionId" to "model")))
        failure("NOT_PENDING") { store.handle("approvals.review", payload(json("epoch" to store.epoch, "data" to "allow-once"), json("requestRef" to "historical-request"))) }
        send(store)
        failure("SEND_ID_CONFLICT") { store.handle("messages.send", payload(json("content" to "不同任务", "loading_id" to "中文发送-1"))) }
        advance()
        val pending = (data(store.handle("approvals.list", payload())) as JSONArray).getJSONObject(0)
        failure("OPTION_NOT_ALLOWED") { store.decide(pending.getString("request_id"), store.epoch, "always-allow") }
        assertEquals(1, (data(store.handle("approvals.list", payload())) as JSONArray).length())
    }
}
