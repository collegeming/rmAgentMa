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

package org.rmagentma.core

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal const val MAX_FRAME_BYTES = 32 * 1024 * 1024

internal class FrameTooLargeException : IOException("NDJSON frame exceeds byte limit")

internal class NdjsonReader(input: InputStream, private val maxBytes: Int = MAX_FRAME_BYTES) {
    private val input = BufferedInputStream(input)

    fun readLine(): String? {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value == -1 || value == 10) {
                if (value == -1 && bytes.size() == 0) return null
                val data = bytes.toByteArray()
                val size = data.size - if (data.lastOrNull() == 13.toByte()) 1 else 0
                return Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data, 0, size)).toString()
            }
            if (bytes.size() >= maxBytes) throw FrameTooLargeException()
            bytes.write(value)
        }
    }
}

internal fun drainStderr(input: InputStream) {
    val buffer = ByteArray(8192)
    while (input.read(buffer) != -1) {
        // Deliberately discard diagnostics: stderr may contain private session content.
    }
}
