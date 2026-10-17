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

internal class ProbeActions(private val store: ProbeStore, private val attachments: ProbeAttachments) {
    fun handle(action: String, payload: JSONObject, documentId: String): ProbeResult {
        val body = payload.optJSONObject("body") ?: JSONObject()
        if (action.startsWith("attachment.")) return attachments.handle(action, documentId, body)
        if (action in setOf("messages.send", "draft.save")) {
            requireProbe(!body.has("files") || body.optJSONArray("files") != null, 422, "INVALID_ATTACHMENT")
            attachments.validateReferences(body.optJSONArray("files") ?: JSONArray())
        }
        return store.handle(action, payload)
    }
}
