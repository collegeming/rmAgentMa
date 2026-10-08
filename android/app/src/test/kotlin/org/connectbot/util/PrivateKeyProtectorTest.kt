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

package org.connectbot.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.GeneralSecurityException
import javax.crypto.spec.SecretKeySpec

class PrivateKeyProtectorTest {
    private val key = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
    private val protector = AesGcmPrivateKeyProtector { key }
    private val original = "synthetic passphrase-encrypted private key bytes".toByteArray()

    @Test
    fun roundTripPreservesOriginalEncodingWithFreshNonces() {
        val first = protector.protect(original, 17, "RSA")
        val second = protector.protect(original, 17, "RSA")

        assertTrue(protector.isProtected(first))
        assertFalse(protector.isProtected(original))
        assertFalse(first.contentEquals(original))
        assertFalse(first.contentEquals(second))
        assertArrayEquals(original, protector.unprotect(first, 17, "RSA"))
        assertArrayEquals(original, protector.unprotect(second, 17, "RSA"))
    }

    @Test
    fun rejectsSwappedIdOrType() {
        val wrapped = protector.protect(original, 17, "RSA")
        assertThrows(GeneralSecurityException::class.java) { protector.unprotect(wrapped, 18, "RSA") }
        assertThrows(GeneralSecurityException::class.java) { protector.unprotect(wrapped, 17, "ED25519") }
    }

    @Test
    fun rejectsModifiedCiphertextNonceAndHeader() {
        val wrapped = protector.protect(original, 17, "RSA")
        listOf(0, 16, 17, wrapped.lastIndex).forEach { offset ->
            val corrupt = wrapped.copyOf()
            corrupt[offset] = (corrupt[offset].toInt() xor 1).toByte()
            assertThrows(GeneralSecurityException::class.java) { protector.unprotect(corrupt, 17, "RSA") }
        }
    }

    @Test
    fun rejectsRawTruncatedAndUnknownVersionEnvelopes() {
        val wrapped = protector.protect(original, 17, "RSA")
        val futureVersion = wrapped.copyOf().apply { this[16] = 2 }
        assertTrue(protector.isProtected(futureVersion))
        listOf(original, wrapped.copyOf(17), futureVersion).forEach { invalid ->
            assertThrows(GeneralSecurityException::class.java) { protector.unprotect(invalid, 17, "RSA") }
        }
    }

    @Test
    fun decryptionDoesNotRequestNewWrappingKey() {
        var request: Boolean? = null
        val tracked = AesGcmPrivateKeyProtector { create ->
            request = create
            key
        }
        val wrapped = tracked.protect(original, 17, "RSA")
        assertTrue(request == true)
        tracked.unprotect(wrapped, 17, "RSA")
        assertTrue(request == false)
    }

    @Test
    fun wrongOrMissingKeyFailsClosed() {
        val wrapped = protector.protect(original, 17, "RSA")
        val wrongKey = AesGcmPrivateKeyProtector { SecretKeySpec(ByteArray(32) { 9 }, "AES") }
        val missingKey = AesGcmPrivateKeyProtector { throw GeneralSecurityException("Synthetic unavailable key") }
        assertThrows(GeneralSecurityException::class.java) { wrongKey.unprotect(wrapped, 17, "RSA") }
        assertThrows(GeneralSecurityException::class.java) { missingKey.unprotect(wrapped, 17, "RSA") }
    }
}
