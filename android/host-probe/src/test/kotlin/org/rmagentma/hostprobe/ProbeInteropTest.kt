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

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.rmagentma.rendererhost.ProbeAttachments
import org.rmagentma.rendererhost.ProbeBridge
import org.rmagentma.rendererhost.array
import org.rmagentma.rendererhost.json
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ProbeInteropTest {
    @get:Rule val temporary = TemporaryFolder()
    private val scheduled = mutableListOf<() -> Unit>()
    private var serial = 0
    private fun store() = ProbeStore(temporary.root.resolve("state.json")) { _, task -> scheduled.add(task) }
    private fun bridge(): ProbeBridge = ProbeBridge().apply {
        commit(ProbeBridge.ENTRY)
        receive(
            ProbeBridge.ORIGIN,
            true,
            json(
                "requestId" to "renderer-${++serial}",
                "documentId" to "",
                "action" to "host.handshake",
                "payload" to json("params" to JSONObject(), "query" to JSONObject(), "body" to null),
            ).toString(),
        ) { _, _ -> error("Handshake cannot execute a product action") }
    }
    private fun call(bridge: ProbeBridge, actions: ProbeActions, action: String, body: JSONObject? = null, extra: JSONObject = JSONObject()): JSONObject {
        val params = json("conversationId" to ProbeStore.CONVERSATION)
        extra.keys().forEach { params.put(it, extra.get(it)) }
        val request = json(
            "requestId" to "renderer-${++serial}",
            "documentId" to bridge.documentId,
            "action" to action,
            "payload" to json("params" to params, "query" to JSONObject(), "body" to body),
        )
        return bridge.receive(ProbeBridge.ORIGIN, true, request.toString()) { name, payload -> actions.handle(name, payload, bridge.documentId) }!!
    }
    private fun advance() {
        scheduled.toList().also { scheduled.clear() }.forEach { it() }
    }

    @Test fun attachmentRefReturnedToRendererCanBePersistedInDraftAndSentAfterRecovery() {
        val store = store()
        val uploads = ProbeAttachments(temporary.root.resolve("uploads"))
        val actions = ProbeActions(store, uploads)
        val bridge = bridge()
        val bytes = "中文手机附件\n第二行😀".toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val begin = call(bridge, actions, "attachment.begin", json("name" to "中文.txt", "mime" to "text/plain", "size" to bytes.size, "sha256" to hash)).getJSONObject("body")
        assertFalse(begin.has("data"))
        assertEquals(12288, begin.getInt("chunkBytes"))
        val uploadId = begin.getString("uploadId")
        val ack = call(bridge, actions, "attachment.chunk", json("uploadId" to uploadId, "offset" to 0, "data" to Base64.encodeToString(bytes, Base64.NO_WRAP))).getJSONObject("body")
        assertEquals(bytes.size, ack.getInt("offset"))
        val finished = call(bridge, actions, "attachment.finish", json("uploadId" to uploadId)).getJSONObject("body")
        val reference = finished.getString("attachmentRef")
        assertTrue(reference.startsWith("probe-upload:"))
        val files = array(json("kind" to "upload", "path" to reference))
        assertEquals(200, call(bridge, actions, "draft.save", json("text" to "已确认的中文草稿", "files" to files)).getInt("status"))
        val loaded = call(bridge, actions, "draft.load").getJSONObject("body")
        assertEquals("已确认的中文草稿", loaded.getString("text"))
        assertEquals(reference, loaded.getJSONArray("files").getJSONObject(0).getString("path"))
        val recoveredStore = store()
        val recoveredUploads = ProbeAttachments(temporary.root.resolve("uploads"))
        val recoveredActions = ProbeActions(recoveredStore, recoveredUploads)
        val recoveredBridge = bridge()
        assertNotEquals(bridge.documentId, recoveredBridge.documentId)
        val recovered = call(recoveredBridge, recoveredActions, "draft.load").getJSONObject("body")
        assertEquals(loaded.toString(), recovered.toString())
        val sent = call(recoveredBridge, recoveredActions, "messages.send", json("content" to recovered.getString("text"), "files" to recovered.getJSONArray("files"), "loading_id" to "send-upload"))
        assertTrue(sent.getBoolean("ok"))
        assertTrue(sent.getJSONObject("body").getBoolean("success"))
        val rows = recoveredStore.snapshot().getJSONArray("messages")
        val user = (0 until rows.length()).map { rows.getJSONObject(it) }.first { it.optString("position") == "right" }
        assertEquals(reference, user.getJSONObject("content").getJSONArray("files").getJSONObject(0).getString("path"))
        val invalid = call(recoveredBridge, recoveredActions, "draft.save", json("text" to "不得覆盖", "files" to array(json("kind" to "upload", "path" to "probe-upload:00000000-0000-0000-0000-000000000000"))))
        assertEquals(410, invalid.getInt("status"))
        assertEquals("已确认的中文草稿", call(recoveredBridge, recoveredActions, "draft.load").getJSONObject("body").getString("text"))
        call(recoveredBridge, recoveredActions, "draft.save", json("text" to "仅文本更新"))
        assertEquals(reference, call(recoveredBridge, recoveredActions, "draft.load").getJSONObject("body").getJSONArray("files").getJSONObject(0).getString("path"))
        call(recoveredBridge, recoveredActions, "draft.save", json("text" to "", "files" to JSONArray()))
        assertEquals(0, call(recoveredBridge, recoveredActions, "draft.load").getJSONObject("body").getJSONArray("files").length())
    }

    @Test fun threeMaximumAttachmentsDoNotExhaustBusinessRequests() {
        val store = store()
        val actions = ProbeActions(store, ProbeAttachments(temporary.root.resolve("uploads")))
        val bridge = bridge()
        val chunk = ByteArray(ProbeAttachments.CHUNK_SIZE) { (it % 251).toByte() }
        val size = ProbeAttachments.MAX_SIZE.toInt()
        val digest = MessageDigest.getInstance("SHA-256")
        var offset = 0
        while (offset < size) {
            val count = minOf(chunk.size, size - offset)
            digest.update(chunk, 0, count)
            offset += count
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        val files = JSONArray()
        repeat(3) { index ->
            val started = call(bridge, actions, "attachment.begin", json("name" to "最大附件$index.pdf", "mime" to "application/pdf", "size" to size, "sha256" to hash))
            assertEquals(201, started.getInt("status"))
            val upload = started.getJSONObject("body").getString("uploadId")
            var position = 0
            while (position < size) {
                val count = minOf(chunk.size, size - position)
                val response = call(bridge, actions, "attachment.chunk", json("uploadId" to upload, "offset" to position, "data" to Base64.encodeToString(chunk.copyOf(count), Base64.NO_WRAP)))
                assertEquals(200, response.getInt("status"))
                assertEquals(position + count, response.getJSONObject("body").getInt("offset"))
                position += count
            }
            val finished = call(bridge, actions, "attachment.finish", json("uploadId" to upload))
            assertEquals(201, finished.getInt("status"))
            files.put(json("kind" to "upload", "path" to finished.getJSONObject("body").getString("attachmentRef")))
        }
        assertTrue(serial > 2048)
        assertEquals(200, call(bridge, actions, "draft.save", json("text" to "三个附件后的中文草稿", "files" to files)).getInt("status"))
        val sent = call(bridge, actions, "messages.send", json("content" to "上传后仍可发送", "files" to files, "loading_id" to "large-upload-send"))
        assertEquals(202, sent.getInt("status"))
        val turn = sent.getJSONObject("body").getJSONObject("data").getString("turn_id")
        assertEquals(200, call(bridge, actions, "turn.cancel", json("turn_id" to turn)).getInt("status"))
        assertEquals(200, call(bridge, actions, "approvals.list").getInt("status"))
        assertEquals(202, call(bridge, actions, "messages.send", json("content" to "新回合", "loading_id" to "large-upload-next")).getInt("status"))
    }

    @Test fun chineseEmojiAndWorstCaseEscapedDraftsFitBoundedEnvelope() {
        val store = store()
        val actions = ProbeActions(store, ProbeAttachments(temporary.root.resolve("uploads")))
        val bridge = bridge()
        val texts = listOf("中".repeat(16384), "😀".repeat(8192), "\u0000".repeat(16384), "\"\\\n".repeat(5461))
        texts.forEach { text ->
            val response = call(bridge, actions, "draft.save", json("text" to text, "files" to JSONArray()))
            assertEquals(200, response.getInt("status"))
            assertEquals(text, call(bridge, actions, "draft.load").getJSONObject("body").getString("text"))
        }
        assertEquals(413, call(bridge, actions, "draft.save", json("text" to "中".repeat(16385))).getInt("status"))
        assertEquals(texts.last(), call(bridge, actions, "draft.load").getJSONObject("body").getString("text"))
        val sent = call(bridge, actions, "messages.send", json("content" to "中".repeat(8192), "loading_id" to "max-chinese"))
        assertEquals(202, sent.getInt("status"))
        assertEquals(400, call(bridge, actions, "messages.send", json("content" to "missing-key")).getInt("status"))
    }

    @Test fun rendererHttpReviewCannotAuthorizeAndRuntimeEventsUseSharedNamesAndAcpShapes() {
        val store = store()
        val actions = ProbeActions(store, ProbeAttachments(temporary.root.resolve("uploads")))
        val bridge = bridge()
        val events = mutableListOf<JSONObject>()
        store.listener = { name, data, epoch, seq -> events.add(bridge.event(name, data, epoch, seq)) }
        val subscription = call(bridge, actions, "events.subscribe").getJSONObject("body")
        assertTrue(subscription.has("runtime"))
        assertEquals("runtime.snapshot", events.first().getString("name"))
        assertEquals(events.first().getLong("seq"), events.first().getJSONObject("data").getLong("seq"))
        val sent = call(bridge, actions, "messages.send", json("content" to "审批探针", "loading_id" to "approval-send"))
        assertEquals(202, sent.getInt("status"))
        advance()
        val pending = call(bridge, actions, "approvals.list").getJSONObject("body").getJSONArray("data").getJSONObject(0)
        val tool = pending.getJSONObject("tool_call")
        assertTrue(tool.has("tool_call_id"))
        assertTrue(tool.has("raw_input"))
        assertFalse(tool.has("rawInput"))
        assertFalse(tool.has("sessionUpdate"))
        val diff = tool.getJSONArray("content").getJSONObject(0)
        assertEquals("中文新内容\n", diff.getString("new_text"))
        assertFalse(diff.has("newText"))
        val streamedTool = events.first { it.getString("name") == "message.stream" && it.getJSONObject("data").optString("type") == "acp_tool_call" }.getJSONObject("data").getJSONObject("data")
        assertEquals(ProbeStore.CONVERSATION, streamedTool.getString("session_id"))
        assertEquals("tool_call", streamedTool.getJSONObject("update").getString("sessionUpdate"))
        assertTrue(streamedTool.getJSONObject("update").has("rawInput"))
        val request = pending.getString("request_id")
        val reviewed = call(bridge, actions, "approvals.review", json("epoch" to store.epoch, "data" to "allow-once", "msg_id" to pending.getString("msg_id")), json("requestRef" to request))
        assertEquals("native_review_required", reviewed.getJSONObject("body").getJSONObject("data").getString("state"))
        assertEquals(1, store.snapshot().getJSONArray("pending").length())
        store.decide(request, store.epoch, "reject-once")
        assertEquals(0, store.snapshot().getJSONArray("pending").length())
        val removed = events.last { it.getString("name") == "confirmation.remove" }.getJSONObject("data")
        assertEquals(request, removed.getString("id"))
        assertEquals(409, call(bridge, actions, "approvals.review", json("epoch" to store.epoch, "data" to "allow-once"), json("requestRef" to request)).getInt("status"))
        assertTrue(events.any { it.getString("name") == "runtime.updated" && it.getJSONObject("data").getJSONObject("runtime").getString("state") == "waiting_confirmation" })
        assertTrue(events.any { it.getString("name") == "turn.completed" })
        events.forEachIndexed { index, event -> assertEquals(index + 1L, event.getLong("seq")) }
    }
}
