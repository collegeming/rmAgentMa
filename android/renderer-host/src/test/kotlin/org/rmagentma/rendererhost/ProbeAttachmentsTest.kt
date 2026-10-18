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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ProbeAttachmentsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val bytes = "中文附件\n😀".toByteArray()
    private val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun begin(uploads: ProbeAttachments, digest: String = hash): String = (uploads.handle("attachment.begin", "doc", json("name" to "中文附件.txt", "mime" to "text/plain", "size" to bytes.size, "sha256" to digest)).body as JSONObject).getString("uploadId")
    private fun chunk(uploads: ProbeAttachments, id: String, document: String = "doc", offset: Int = 0) = uploads.handle("attachment.chunk", document, json("uploadId" to id, "offset" to offset, "data" to Base64.encodeToString(bytes, Base64.NO_WRAP)))
    private fun failure(code: String, action: () -> Unit) {
        try {
            action()
            fail("Expected $code")
        } catch (failure: ProbeFailure) {
            assertEquals(code, failure.code)
        }
    }

    @Test fun chunksVerifyHashAndNeverExposeFilesystemPaths() {
        val uploads = ProbeAttachments(temporary.root)
        val id = begin(uploads)
        failure("STALE_UPLOAD") { chunk(uploads, id, "old") }
        failure("UPLOAD_OFFSET_CONFLICT") { chunk(uploads, id, offset = 1) }
        chunk(uploads, id)
        val result = uploads.handle("attachment.finish", "doc", json("uploadId" to id)).body as JSONObject
        assertEquals(hash, result.getString("sha256"))
        assertFalse(result.toString().contains(temporary.root.absolutePath))
        assertArrayEquals(bytes, temporary.root.resolve("$id.blob").readBytes())
        uploads.validateReferences(array(json("kind" to "upload", "path" to result.getString("path"))))
        failure("ATTACHMENT_SOURCE_NOT_ALLOWED") { uploads.validateReferences(array(json("kind" to "local", "path" to "/private"))) }
        failure("INVALID_ATTACHMENT") { uploads.validateReferences(array(json("kind" to "upload", "path" to "probe-upload:../private"))) }
        failure("UPLOAD_EXPIRED") { chunk(uploads, id) }
        temporary.root.resolve("$id.blob").writeBytes(ByteArray(bytes.size))
        failure("ATTACHMENT_CORRUPT") { uploads.validateReferences(array(json("kind" to "upload", "path" to result.getString("attachmentRef")))) }
    }

    @Test fun expirationAbortHashFailureAndOversizeAreRejected() {
        var now = 0L
        val uploads = ProbeAttachments(temporary.root) { now }
        val expired = begin(uploads)
        now = 300001
        failure("UPLOAD_EXPIRED") { chunk(uploads, expired) }
        assertFalse(temporary.root.resolve("$expired.part").exists())
        val invalid = begin(uploads, "0".repeat(64))
        chunk(uploads, invalid)
        failure("UPLOAD_HASH_MISMATCH") { uploads.handle("attachment.finish", "doc", json("uploadId" to invalid)) }
        val aborted = begin(uploads)
        uploads.handle("attachment.abort", "doc", json("uploadId" to aborted))
        assertFalse(temporary.root.resolve("$aborted.part").exists())
        val stale = begin(uploads)
        uploads.invalidate("doc")
        failure("UPLOAD_EXPIRED") { chunk(uploads, stale) }
        failure("UPLOAD_TOO_LARGE") { uploads.handle("attachment.begin", "doc", json("name" to "x.txt", "mime" to "text/plain", "size" to ProbeAttachments.MAX_SIZE + 1, "sha256" to hash)) }
    }
}
