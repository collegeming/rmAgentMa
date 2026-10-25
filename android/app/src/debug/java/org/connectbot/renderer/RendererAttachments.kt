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

package org.connectbot.renderer

import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.rmagentma.core.remoteTargetOf
import org.rmagentma.rendererhost.ProbeResult
import org.rmagentma.rendererhost.json
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/**
 * The renderer's attachment uploads, buffered on this phone and landed in the open conversation's working
 * directory on its host.
 *
 * One upload is one local file under this host's own `noBackupFilesDir` directory — never the product database and
 * never the system backup, because an upload is the user's own content and a half-finished one is nothing to keep.
 * The bytes travel in bounded chunks, and every step validates what it received: the name, the declared size, the
 * sha256 and each chunk's offset. Only [finish] writes to the host, over the same SSH session every other remote
 * command uses, and the answer is the absolute path the file ended up at — the renderer uses it as the file path,
 * exactly as it would for any other file it read from a workspace.
 *
 * A name the directory already holds is never overwritten: the write moves to `name-1.ext` instead. A write that
 * fails removes the local buffer and the half-written remote file, and reports `REMOTE_WRITE_FAILED` with the step
 * that failed.
 *
 * A host built without a local upload store ([unavailable]) answers every one of these actions as unsupported
 * rather than pretending an upload happened.
 */
internal class RendererAttachments private constructor(
    private val directory: File?,
    private val source: RendererSource?,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long,
    private val unsupportedReason: String,
) {
    constructor(
        directory: File,
        source: RendererSource,
        io: CoroutineDispatcher = Dispatchers.IO,
        clock: () -> Long = System::currentTimeMillis,
    ) : this(directory, source, io, clock, "")

    private data class Upload(
        val id: String,
        val hostId: Long,
        val name: String,
        val mime: String,
        val size: Long,
        val sha256: String,
        val file: File,
        val deadline: Long,
    )

    private val mutex = Mutex()
    private var pending = emptyMap<String, Upload>()

    suspend fun handle(action: String, params: JSONObject, body: JSONObject): ProbeResult {
        val store = directory ?: return fail(501, ATTACHMENT_UNSUPPORTED, unsupportedReason)
        val reader = source ?: return fail(501, ATTACHMENT_UNSUPPORTED, unsupportedReason)
        return when (action) {
            "attachment.begin" -> begin(store, reader, params, body)
            "attachment.chunk" -> chunk(body)
            "attachment.finish" -> finish(reader, params, body)
            "attachment.abort" -> abort(body)
            else -> fail(404, "UNKNOWN_ACTION", "未知动作：$action。")
        }
    }

    /** Buffers one upload's metadata and its own empty file; nothing is written to any host here. */
    private suspend fun begin(directory: File, source: RendererSource, params: JSONObject, body: JSONObject): ProbeResult = withContext(io) {
        val snapshot = source.snapshot()
        val session = snapshot.session
            ?: return@withContext fail(404, "CONVERSATION_NOT_FOUND", "当前没有可用的会话，附件无处落地。")
        val conversationId = params.optString("conversationId")
        if (conversationId != RENDERER_CONVERSATION && conversationId != RendererConversationId.of(session)) {
            return@withContext fail(404, "CONVERSATION_NOT_FOUND", "附件只能上传到当前打开的会话。")
        }
        val name = body.opt("name")
        if (name !is String || !validName(name)) return@withContext fail(422, "INVALID_REQUEST", "附件名无效。")
        val mime = body.opt("mime")
        if (mime !is String || mime.isBlank() || mime.length > MAX_MIME || mime.any { it.code < 32 || it.code == 127 }) {
            return@withContext fail(422, "INVALID_REQUEST", "附件类型无效。")
        }
        val declared = body.opt("size")
        if (declared !is Number || declared.toDouble() != declared.toLong().toDouble() || declared.toLong() < 0) {
            return@withContext fail(422, "INVALID_REQUEST", "附件大小必须是非负整数。")
        }
        if (declared.toLong() > MAX_BYTES) return@withContext fail(413, "UPLOAD_TOO_LARGE", "单个附件不得超过 8 MiB。")
        val hash = body.opt("sha256")
        if (hash !is String || !SHA256.matches(hash)) return@withContext fail(422, "INVALID_REQUEST", "sha256 必须是 64 位小写十六进制。")
        mutex.withLock {
            expire()
            if (pending.size >= MAX_PENDING) return@withLock fail(429, "UPLOAD_LIMIT", "同时进行的上传过多，请先完成或取消其中一个。")
            val used = directory.listFiles()?.sumOf { it.length() } ?: 0L
            if (used > MAX_STORAGE_BYTES - MAX_BYTES) return@withLock fail(429, "UPLOAD_LIMIT", "本机上传缓冲已满，请先完成或取消已有上传。")
            if (!directory.isDirectory && !directory.mkdirs()) return@withLock fail(500, "UPLOAD_STORAGE_UNAVAILABLE", "本机无法建立上传缓冲目录。")
            val id = UUID.randomUUID().toString()
            val file = File(directory, "$id$PART_SUFFIX")
            if (!file.createNewFile()) return@withLock fail(500, "UPLOAD_STORAGE_UNAVAILABLE", "本机无法建立上传缓冲文件。")
            pending = pending + (
                id to Upload(
                    id = id,
                    hostId = session.hostId,
                    name = name,
                    mime = mime,
                    size = declared.toLong(),
                    sha256 = hash,
                    file = file,
                    deadline = clock() + UPLOAD_TTL_MILLIS,
                )
                )
            created(json("uploadId" to id, "chunkBytes" to CHUNK_BYTES, "expiresInMs" to UPLOAD_TTL_MILLIS))
        }
    }

    /** Appends one chunk at the offset the renderer states, and answers the length after the write. */
    private suspend fun chunk(body: JSONObject): ProbeResult = withContext(io) {
        mutex.withLock {
            expire()
            val upload = pending[body.opt("uploadId") as? String]
                ?: return@withLock fail(410, "UPLOAD_EXPIRED", "该上传已结束或已过期，请重新开始。")
            val offset = body.opt("offset")
            if (offset !is Number || offset.toDouble() != offset.toLong().toDouble() || offset.toLong() < 0) {
                return@withLock fail(422, "INVALID_REQUEST", "offset 必须是非负整数。")
            }
            // The offset has to be exactly where this upload currently ends, or the two sides disagree about what
            // was already written and the file would silently hold something nobody sent.
            if (offset.toLong() != upload.file.length()) {
                return@withLock fail(409, "UPLOAD_OFFSET_CONFLICT", "offset 与已写入长度不一致（当前为 ${upload.file.length()}）。")
            }
            val encoded = body.opt("data")
            if (encoded !is String || encoded.isEmpty() || encoded.length > MAX_ENCODED || !BASE64.matches(encoded)) {
                return@withLock fail(422, "INVALID_REQUEST", "data 必须是 base64 文本。")
            }
            val bytes = try {
                Base64.decode(encoded, Base64.NO_WRAP)
            } catch (_: IllegalArgumentException) {
                return@withLock fail(422, "INVALID_REQUEST", "data 不是有效的 base64。")
            }
            if (bytes.isEmpty() || bytes.size > CHUNK_BYTES || upload.file.length() + bytes.size > upload.size) {
                return@withLock fail(413, "UPLOAD_TOO_LARGE", "该分块为空、超过 $CHUNK_BYTES 字节，或超出声明的附件大小。")
            }
            try {
                upload.file.appendBytes(bytes)
            } catch (_: IOException) {
                return@withLock fail(500, "UPLOAD_STORAGE_UNAVAILABLE", "本机无法写入上传缓冲文件。")
            }
            success(json("offset" to upload.file.length()))
        }
    }

    /**
     * Verifies the buffered file against what was declared and writes it into the open conversation's working
     * directory on its host. The answer's `attachmentRef` is the absolute path the file ended up at.
     */
    private suspend fun finish(source: RendererSource, params: JSONObject, body: JSONObject): ProbeResult {
        val id = body.opt("uploadId") as? String
        val upload = mutex.withLock {
            expire()
            pending[id]
        } ?: return fail(410, "UPLOAD_EXPIRED", "该上传已结束或已过期，请重新开始。")
        if (upload.file.length() != upload.size) {
            return fail(409, "UPLOAD_INCOMPLETE", "附件尚未传完（已收到 ${upload.file.length()} / ${upload.size} 字节）。")
        }
        val digest = withContext(io) { sha256(upload.file) }
            ?: return fail(500, "UPLOAD_STORAGE_UNAVAILABLE", "本机上传缓冲文件无法读取。")
        if (digest != upload.sha256) {
            forget(upload)
            return fail(422, "UPLOAD_HASH_MISMATCH", "附件内容与声明的 sha256 不一致，已丢弃。")
        }
        val snapshot = source.snapshot()
        val session = snapshot.session
        val conversationId = params.optString("conversationId")
        if (session == null || session.hostId != upload.hostId ||
            (conversationId != RENDERER_CONVERSATION && conversationId != RendererConversationId.of(session))
        ) {
            forget(upload)
            return fail(409, "CONVERSATION_SWITCHED", "会话已切换，这次上传不再属于当前会话，已丢弃。")
        }
        if (!snapshot.active) {
            forget(upload)
            return fail(409, "CONVERSATION_NOT_OPEN", "该会话尚未就绪，附件未写入主机。")
        }
        val host = snapshot.host?.takeIf { it.id == session.hostId } ?: snapshot.hosts.firstOrNull { it.id == session.hostId }
        if (host == null) {
            forget(upload)
            return fail(404, "HOST_NOT_FOUND", "该会话的主机已被删除，附件无处落地。")
        }
        val target = remoteTargetOf(host.osType, host.agentTarget)
        if (!target.posixCommands) {
            forget(upload)
            return fail(502, REMOTE_WRITE_FAILED, "该主机的 Agent 目标（${target.id}）尚无附件写入实现，附件未写入主机。")
        }
        val content = withContext(io) {
            try {
                upload.file.readBytes()
            } catch (_: IOException) {
                null
            }
        } ?: run {
            forget(upload)
            return fail(500, "UPLOAD_STORAGE_UNAVAILABLE", "本机上传缓冲文件无法读取。")
        }
        val path = try {
            source.writeWorkspaceFile(upload.hostId, session.cwd, upload.name, content)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            forget(upload)
            return fail(502, REMOTE_WRITE_FAILED, "主机未能写入附件：${error.message ?: error.javaClass.simpleName}")
        }
        forget(upload)
        return created(
            json(
                "attachmentRef" to path,
                "path" to path,
                "name" to upload.name,
                "mime" to upload.mime,
                "size" to upload.size,
                "sha256" to upload.sha256,
                "source" to "mobile-upload",
            ),
        )
    }

    /** Drops one buffered upload and its local file; a host this upload never reached holds nothing to clean. */
    private suspend fun abort(body: JSONObject): ProbeResult = withContext(io) {
        mutex.withLock {
            expire()
            val upload = pending[body.opt("uploadId") as? String]
                ?: return@withLock fail(410, "UPLOAD_EXPIRED", "该上传已结束或已过期。")
            pending = pending - upload.id
            upload.file.delete()
            success(json("aborted" to true))
        }
    }

    /** Removes the buffered file of [upload] and stops tracking it, whatever the write answered. */
    private suspend fun forget(upload: Upload) {
        mutex.withLock {
            pending = pending - upload.id
            withContext(io) { upload.file.delete() }
        }
    }

    /**
     * Drops uploads that ran out of time and buffers left behind by an earlier process. Both are this phone's own
     * leftovers: a host was never told about them, so nothing remote has to be undone here.
     */
    private fun expire() {
        val now = clock()
        pending.values.filter { it.deadline <= now }.forEach {
            pending = pending - it.id
            it.file.delete()
        }
        val leftovers = directory?.listFiles().orEmpty().filter { it.name.endsWith(PART_SUFFIX) && it.lastModified() + UPLOAD_TTL_MILLIS <= now }
        leftovers.forEach { it.delete() }
    }

    private fun sha256(file: File): String? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (_: IOException) {
        null
    }

    /** A name a directory entry can carry: no separator, no control character, and no name for a directory itself. */
    private fun validName(name: String): Boolean = name.isNotBlank() &&
        name.length <= MAX_NAME &&
        name != "." &&
        name != ".." &&
        name.none { it.code < 32 || it.code == 127 || it == '/' || it == '\\' }

    private fun success(data: Any?): ProbeResult = ProbeResult(body = json("success" to true, "data" to data))

    private fun created(data: Any?): ProbeResult = ProbeResult(201, json("success" to true, "data" to data))

    private fun fail(status: Int, code: String, reason: String): ProbeResult =
        ProbeResult(status, json("success" to false, "code" to code, "error" to reason))

    companion object {
        /** One chunk the renderer may send: base64 of this many bytes fits one bridge message. */
        const val CHUNK_BYTES = 12288

        /** The largest attachment one upload carries, as the protocol states it. */
        const val MAX_BYTES = 8L * 1024 * 1024

        /** How long an upload may stay unfinished, and how long its leftover buffer is kept. */
        const val UPLOAD_TTL_MILLIS = 300_000L

        /** The stable code this host answers with when it has no local upload store at all. */
        const val ATTACHMENT_UNSUPPORTED = "ATTACHMENT_UNSUPPORTED"

        /** The stable code a write that did not land on the host is reported with, whatever step failed. */
        const val REMOTE_WRITE_FAILED = "REMOTE_WRITE_FAILED"

        private const val MAX_PENDING = 4
        private const val MAX_STORAGE_BYTES = 64L * 1024 * 1024
        private const val MAX_NAME = 160
        private const val MAX_MIME = 128
        private const val MAX_ENCODED = 16400
        private const val PART_SUFFIX = ".part"
        private val SHA256 = Regex("[a-f0-9]{64}")
        private val BASE64 = Regex("[A-Za-z0-9+/]*={0,2}")

        /** A host with no local upload store: every attachment action is answered as unsupported. */
        fun unavailable(reason: String = "本机未开放附件上传。"): RendererAttachments =
            RendererAttachments(null, null, Dispatchers.IO, System::currentTimeMillis, reason)
    }
}
