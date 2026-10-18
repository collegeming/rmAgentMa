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

import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class ProbeAttachments(private val directory: File, private val clock: () -> Long = System::currentTimeMillis) {
    private data class Upload(val document: String, val name: String, val mime: String, val size: Long, val hash: String, val file: File, val deadline: Long)
    private val pending = mutableMapOf<String, Upload>()
    init {
        directory.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
    }

    fun handle(action: String, document: String, body: JSONObject): ProbeResult {
        expire()
        return when (action) {
            "attachment.begin" -> {
                requireProbe(pending.size < 4, 429, "UPLOAD_LIMIT")
                val used = directory.listFiles()?.sumOf { it.length() } ?: 0L
                requireProbe(used <= 64L * 1024 * 1024 - MAX_SIZE, 413, "PROBE_STORAGE_LIMIT")
                val name = body.string("name", 160)
                requireProbe(!name.contains('/') && !name.contains('\\') && name.none { it.code < 32 })
                val mime = body.string("mime", 128)
                requireProbe(mime in MIME_TYPES, 415, "UNSUPPORTED_MIME")
                val size = body.opt("size")
                requireProbe(size is Number && size.toLong() in 0..MAX_SIZE && size.toDouble() == size.toLong().toDouble(), 413, "UPLOAD_TOO_LARGE")
                val hash = body.string("sha256", 64)
                requireProbe(hash.matches(Regex("[a-f0-9]{64}")))
                requireProbe(directory.isDirectory || directory.mkdirs(), 500, "UPLOAD_STORAGE_UNAVAILABLE")
                val id = UUID.randomUUID().toString()
                val file = File(directory, "$id.part")
                requireProbe(file.createNewFile(), 500, "UPLOAD_STORAGE_UNAVAILABLE")
                pending[id] = Upload(document, name, mime, (size as Number).toLong(), hash, file, clock() + 300000)
                ProbeResult(201, json("uploadId" to id, "chunkBytes" to CHUNK_SIZE, "expiresInMs" to 300000))
            }

            "attachment.chunk" -> {
                val upload = find(body, document)
                val offset = body.opt("offset")
                requireProbe(offset is Number && offset.toLong() == upload.file.length() && offset.toDouble() == offset.toLong().toDouble(), 409, "UPLOAD_OFFSET_CONFLICT")
                val encoded = body.string("data", 22000)
                requireProbe(encoded.matches(Regex("[A-Za-z0-9+/]*={0,2}")))
                val bytes = try {
                    Base64.decode(encoded, Base64.NO_WRAP)
                } catch (_: IllegalArgumentException) {
                    throw ProbeFailure(400, "INVALID_CHUNK")
                }
                requireProbe(bytes.isNotEmpty() && bytes.size <= CHUNK_SIZE && upload.file.length() + bytes.size <= upload.size, 413, "CHUNK_TOO_LARGE")
                upload.file.appendBytes(bytes)
                ProbeResult(body = json("offset" to upload.file.length()))
            }

            "attachment.finish" -> {
                val upload = find(body, document)
                requireProbe(upload.file.length() == upload.size, 409, "UPLOAD_INCOMPLETE")
                val digest = MessageDigest.getInstance("SHA-256")
                upload.file.inputStream().use { input ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                if (hash != upload.hash) {
                    pending.remove(body.getString("uploadId"))
                    upload.file.delete()
                    throw ProbeFailure(422, "UPLOAD_HASH_MISMATCH")
                }
                val id = body.getString("uploadId")
                val target = File(directory, "$id.blob")
                requireProbe(upload.file.renameTo(target), 500, "UPLOAD_STORAGE_UNAVAILABLE")
                val result = json("attachmentRef" to "probe-upload:$id", "path" to "probe-upload:$id", "name" to upload.name, "mime" to upload.mime, "size" to upload.size, "sha256" to hash, "source" to "mobile-upload")
                File(directory, "$id.json").writeText(result.toString())
                pending.remove(id)
                ProbeResult(201, result)
            }

            "attachment.abort" -> {
                val upload = find(body, document)
                pending.remove(body.getString("uploadId"))
                upload.file.delete()
                ProbeResult(body = json("aborted" to true))
            }

            else -> throw ProbeFailure(404, "UNKNOWN_ACTION")
        }
    }

    fun validateReferences(files: org.json.JSONArray) {
        requireProbe(files.length() <= 4, 413, "UPLOAD_LIMIT")
        for (index in 0 until files.length()) {
            val reference = files.optJSONObject(index) ?: throw ProbeFailure(422, "INVALID_ATTACHMENT")
            requireProbe(reference.optString("kind") == "upload", 422, "ATTACHMENT_SOURCE_NOT_ALLOWED")
            val path = reference.string("path", 80)
            requireProbe(path.matches(Regex("probe-upload:[a-f0-9-]{36}")), 422, "INVALID_ATTACHMENT")
            val id = path.removePrefix("probe-upload:")
            val metadata = File(directory, "$id.json")
            val blob = File(directory, "$id.blob")
            requireProbe(metadata.isFile && blob.isFile, 410, "ATTACHMENT_NOT_FOUND")
            val saved = try {
                JSONObject(metadata.readText())
            } catch (_: Exception) {
                throw ProbeFailure(422, "ATTACHMENT_CORRUPT")
            }
            requireProbe(saved.optString("attachmentRef") == path && saved.optLong("size", -1) == blob.length(), 422, "ATTACHMENT_CORRUPT")
            val digest = MessageDigest.getInstance("SHA-256")
            blob.inputStream().use { stream ->
                val buffer = ByteArray(65536)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            requireProbe(digest.digest().joinToString("") { "%02x".format(it) } == saved.optString("sha256"), 422, "ATTACHMENT_CORRUPT")
        }
    }

    fun invalidate(document: String) {
        pending.filterValues { it.document == document }.keys.toList().forEach { pending.remove(it)?.file?.delete() }
    }

    private fun find(body: JSONObject, document: String): Upload {
        val upload = pending[body.string("uploadId")] ?: throw ProbeFailure(410, "UPLOAD_EXPIRED")
        requireProbe(upload.document == document, 409, "STALE_UPLOAD")
        return upload
    }

    private fun expire() {
        pending.filterValues { it.deadline <= clock() }.keys.toList().forEach { pending.remove(it)?.file?.delete() }
    }

    companion object {
        const val CHUNK_SIZE = 12288
        const val MAX_SIZE = 8L * 1024 * 1024
        val MIME_TYPES = setOf("image/png", "image/jpeg", "image/webp", "text/plain", "application/pdf")
    }
}
