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

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ProbeBridgeTest {
    private fun request(action: String, document: String = "", id: String = "renderer-1", params: JSONObject = json("conversationId" to ProbeStore.CONVERSATION)): String = json(
        "requestId" to id,
        "documentId" to document,
        "action" to action,
        "payload" to json("params" to (if (action == "host.handshake") JSONObject() else params), "query" to JSONObject(), "body" to null),
    ).toString()

    @Test fun rejectsOriginsFramesAndUncommittedDocuments() {
        val bridge = ProbeBridge()
        var calls = 0
        val execute = { _: String, _: JSONObject ->
            calls++
            ProbeResult()
        }
        assertNull(bridge.receive(ProbeBridge.ORIGIN, true, request("host.handshake"), execute))
        bridge.commit(ProbeBridge.ENTRY)
        listOf("http://appassets.androidplatform.net", "https://appassets.androidplatform.net:444", "https://appassets.androidplatform.net.evil.test", "https://x@appassets.androidplatform.net", "https://appassets.androidplatform.net/", "file:///index.html", "null").forEach {
            assertNull(it, bridge.receive(it, true, request("host.handshake"), execute))
        }
        assertNull(bridge.receive(ProbeBridge.ORIGIN, false, request("host.handshake"), execute))
        assertEquals(0, calls)
    }

    @Test fun handshakeBindsDocumentAndDeduplicatesActions() {
        val bridge = ProbeBridge()
        bridge.commit(ProbeBridge.ENTRY)
        var calls = 0
        val execute = { _: String, _: JSONObject ->
            calls++
            ProbeResult(body = json("accepted" to true))
        }
        val handshake = bridge.receive(ProbeBridge.ORIGIN, true, request("host.handshake"), execute)!!
        val document = handshake.getJSONObject("body").getString("documentId")
        assertEquals(document, handshake.getString("documentId"))
        assertTrue(document.isNotEmpty())
        val raw = request("messages.send", document, "renderer-2")
        assertEquals(200, bridge.receive(ProbeBridge.ORIGIN, true, raw, execute)!!.getInt("status"))
        assertEquals(200, bridge.receive(ProbeBridge.ORIGIN, true, raw, execute)!!.getInt("status"))
        assertEquals(1, calls)
        assertEquals(409, bridge.receive(ProbeBridge.ORIGIN, true, request("turn.cancel", document, "renderer-2"), execute)!!.getInt("status"))
        assertEquals(404, bridge.receive(ProbeBridge.ORIGIN, true, request("shell.execute", document, "renderer-3"), execute)!!.getInt("status"))
        assertEquals(404, bridge.receive(ProbeBridge.ORIGIN, true, request("messages.send", document, "renderer-4", json("conversationId" to "other")), execute)!!.getInt("status"))
        bridge.invalidate()
        bridge.commit(ProbeBridge.ENTRY)
        bridge.receive(ProbeBridge.ORIGIN, true, request("host.handshake", id = "renderer-1"), execute)
        assertNotEquals(document, bridge.documentId)
        assertEquals(409, bridge.receive(ProbeBridge.ORIGIN, true, raw, execute)!!.getInt("status"))
        assertEquals(1, calls)
    }

    @Test fun evictedAndSkippedRequestIdsNeverExecuteAgain() {
        val bridge = ProbeBridge()
        bridge.commit(ProbeBridge.ENTRY)
        val execute = { _: String, _: JSONObject -> ProbeResult(body = json("accepted" to true)) }
        bridge.receive(ProbeBridge.ORIGIN, true, request("host.handshake"), execute)
        val document = bridge.documentId
        var effects = 0
        val sideEffect = { _: String, _: JSONObject ->
            effects++
            ProbeResult(body = json("effect" to effects))
        }
        val original = request("messages.send", document, "renderer-2")
        bridge.receive(ProbeBridge.ORIGIN, true, original, sideEffect)
        for (sequence in 3..(ProbeBridge.REPLAY_COUNT + 5)) bridge.receive(ProbeBridge.ORIGIN, true, request("messages.list", document, "renderer-$sequence"), execute)
        assertEquals("REQUEST_REPLAY_EXPIRED", bridge.receive(ProbeBridge.ORIGIN, true, original, sideEffect)!!.getJSONObject("body").getString("code"))
        assertEquals(1, effects)
        val next = request("messages.send", document, "renderer-1000")
        assertEquals(200, bridge.receive(ProbeBridge.ORIGIN, true, next, sideEffect)!!.getInt("status"))
        assertEquals(200, bridge.receive(ProbeBridge.ORIGIN, true, next, sideEffect)!!.getInt("status"))
        assertEquals(2, effects)
        assertEquals("REQUEST_ID_CONFLICT", bridge.receive(ProbeBridge.ORIGIN, true, request("turn.cancel", document, "renderer-1000"), sideEffect)!!.getJSONObject("body").getString("code"))
        assertEquals("REQUEST_REPLAY_EXPIRED", bridge.receive(ProbeBridge.ORIGIN, true, request("messages.send", document, "renderer-999"), sideEffect)!!.getJSONObject("body").getString("code"))
        assertEquals(2, effects)
        listOf("call-1001", "renderer-0", "renderer-01", "renderer-9007199254740992").forEach { id ->
            assertEquals("INVALID_REQUEST_ID", bridge.receive(ProbeBridge.ORIGIN, true, request("messages.send", document, id), sideEffect)!!.getJSONObject("body").getString("code"))
        }
        val failing = request("messages.send", document, "renderer-1001")
        val uncertain = { _: String, _: JSONObject ->
            effects++
            throw IllegalStateException("after side effect")
        }
        assertEquals(500, bridge.receive(ProbeBridge.ORIGIN, true, failing, uncertain)!!.getInt("status"))
        assertEquals(500, bridge.receive(ProbeBridge.ORIGIN, true, failing, uncertain)!!.getInt("status"))
        assertEquals(3, effects)
        val huge = request("messages.send", document, "renderer-1002")
        bridge.receive(ProbeBridge.ORIGIN, true, huge) { _, _ ->
            effects++
            ProbeResult(body = "x".repeat(ProbeBridge.REPLAY_BYTES + 1))
        }
        assertEquals("REQUEST_REPLAY_EXPIRED", bridge.receive(ProbeBridge.ORIGIN, true, huge, sideEffect)!!.getJSONObject("body").getString("code"))
        assertEquals(4, effects)
    }

    @Test fun malformedPayloadAndOversizeMessagesDoNotExecute() {
        val bridge = ProbeBridge()
        bridge.commit(ProbeBridge.ENTRY)
        var calls = 0
        val execute = { _: String, _: JSONObject ->
            calls++
            ProbeResult()
        }
        assertEquals(413, bridge.receive(ProbeBridge.ORIGIN, true, "x".repeat(ProbeLimits.MESSAGE_BYTES + 1), execute)!!.getInt("status"))
        assertEquals(400, bridge.receive(ProbeBridge.ORIGIN, true, "{}", execute)!!.getInt("status"))
        assertEquals("JSON_TOO_DEEP", bridge.receive(ProbeBridge.ORIGIN, true, "[".repeat(13) + "0" + "]".repeat(13), execute)!!.getJSONObject("body").getString("code"))
        assertEquals(413, bridge.receive(ProbeBridge.ORIGIN, true, "中".repeat(ProbeLimits.MESSAGE_BYTES / 3 + 1), execute)!!.getInt("status"))
        assertEquals(0, calls)
        assertFalse(ProbeBridge.isEntry("${ProbeBridge.ORIGIN}/assets/renderer/other.html"))
        assertFalse(ProbeBridge.isEntry("${ProbeBridge.ENTRY}?redirect=1"))
        assertTrue(ProbeBridge.isEntry("${ProbeBridge.ENTRY}#/conversation/conv-probe-1"))
    }
}
