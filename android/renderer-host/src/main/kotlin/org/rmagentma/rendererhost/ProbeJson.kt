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

package org.rmagentma.rendererhost

import org.json.JSONArray
import org.json.JSONObject

fun json(vararg fields: Pair<String, Any?>): JSONObject = JSONObject().apply {
    fields.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
}

fun array(vararg values: Any?): JSONArray = JSONArray().apply {
    values.forEach { put(it ?: JSONObject.NULL) }
}

object ProbeLimits {
    const val MESSAGE_BYTES = 100 * 1024
    const val DRAFT_UNITS = 16384
    const val DRAFT_BYTES = 48 * 1024
    const val CONTENT_UNITS = 8192
    const val CONTENT_BYTES = 24 * 1024
}

data class ProbeResult(val status: Int = 200, val body: Any? = null)

class ProbeFailure(val status: Int, val code: String) : RuntimeException(code)

fun requireProbe(condition: Boolean, status: Int = 400, code: String = "INVALID_REQUEST") {
    if (!condition) throw ProbeFailure(status, code)
}

fun JSONObject.string(key: String, max: Int = 256): String {
    val value = opt(key)
    requireProbe(value is String && value.isNotEmpty() && value.length <= max)
    return value as String
}
