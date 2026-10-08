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

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

interface PrivateKeyProtector {
    fun isProtected(bytes: ByteArray): Boolean
    fun protect(bytes: ByteArray, keyId: Long, type: String): ByteArray
    fun unprotect(bytes: ByteArray, keyId: Long, type: String): ByteArray
}

@Singleton
class AndroidKeyStorePrivateKeyProtector @Inject constructor() : PrivateKeyProtector {
    private val keyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }
    private val cipher = AesGcmPrivateKeyProtector(::secretKey)

    override fun isProtected(bytes: ByteArray): Boolean = cipher.isProtected(bytes)

    override fun protect(bytes: ByteArray, keyId: Long, type: String): ByteArray = cipher.protect(bytes, keyId, type)

    override fun unprotect(bytes: ByteArray, keyId: Long, type: String): ByteArray = cipher.unprotect(bytes, keyId, type)

    private fun secretKey(create: Boolean): SecretKey = synchronized(KEY_LOCK) {
        val entry = keyStore.getEntry(KEY_ALIAS, null)
        if (entry is KeyStore.SecretKeyEntry) return@synchronized entry.secretKey
        if (entry != null || !create) throw GeneralSecurityException("Private key wrapping key is unavailable")
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        generator.generateKey()
    }

    private companion object {
        val KEY_LOCK = Any()
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "rmagentma_private_key_wrapping_v1"
    }
}

internal class AesGcmPrivateKeyProtector(
    private val secretKey: (create: Boolean) -> SecretKey,
) : PrivateKeyProtector {
    override fun isProtected(bytes: ByteArray): Boolean = bytes.size >= MAGIC.size && MAGIC.indices.all { bytes[it] == MAGIC[it] }

    override fun protect(bytes: ByteArray, keyId: Long, type: String): ByteArray {
        require(keyId > 0) { "A persisted key ID is required" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey(true))
        cipher.updateAAD(aad(keyId, type))
        val ciphertext = cipher.doFinal(bytes)
        val iv = cipher.iv
        if (iv.size != IV_LENGTH) throw GeneralSecurityException("Unexpected GCM nonce length")
        return ByteBuffer.allocate(MAGIC.size + 1 + IV_LENGTH + ciphertext.size)
            .put(MAGIC)
            .put(VERSION)
            .put(iv)
            .put(ciphertext)
            .array()
    }

    override fun unprotect(bytes: ByteArray, keyId: Long, type: String): ByteArray {
        if (!isProtected(bytes) || bytes.size < MAGIC.size + 1 + IV_LENGTH + TAG_LENGTH / 8) {
            throw GeneralSecurityException("Invalid private key envelope")
        }
        if (bytes[MAGIC.size] != VERSION) throw GeneralSecurityException("Unsupported private key envelope version")
        if (keyId <= 0) throw GeneralSecurityException("Invalid private key ID")
        val offset = MAGIC.size + 1
        val iv = bytes.copyOfRange(offset, offset + IV_LENGTH)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(false), GCMParameterSpec(TAG_LENGTH, iv))
        cipher.updateAAD(aad(keyId, type))
        return cipher.doFinal(bytes, offset + IV_LENGTH, bytes.size - offset - IV_LENGTH)
    }

    private fun aad(keyId: Long, type: String): ByteArray {
        val typeBytes = type.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(MAGIC.size + 1 + java.lang.Long.BYTES + java.lang.Integer.BYTES + typeBytes.size)
            .put(MAGIC)
            .put(VERSION)
            .putLong(keyId)
            .putInt(typeBytes.size)
            .put(typeBytes)
            .array()
    }

    private companion object {
        val MAGIC = byteArrayOf(0, 0x52, 0x4d, 0x41, 0x50, 0x52, 0x49, 0x56, 0x4b, 0x45, 0x59, 0x0d, 0x0a, 0x1a, 0x0a, 0)
        const val VERSION: Byte = 1
        const val IV_LENGTH = 12
        const val TAG_LENGTH = 128
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
