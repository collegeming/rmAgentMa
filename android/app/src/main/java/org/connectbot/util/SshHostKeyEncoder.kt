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

import net.schmizz.sshj.common.Buffer
import java.math.BigInteger
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey

/**
 * Encodes a host public key into the SSH wire format (RFC 4253 section 6.6).
 *
 * sshj's own `KeyType.fromKey` only recognises "EdDSA"/"Ed25519" algorithm names. The
 * Android platform provider hands back Ed25519 keys whose algorithm name differs, so sshj
 * classifies them as UNKNOWN and throws "Don't know how to encode key" while verifying the
 * server's host key. Encoding here removes that dependency on sshj's type detection.
 *
 * Returns the wire blob and its SSH algorithm name, or null when the key type is not
 * supported.
 */
internal fun encodeSshHostKey(key: PublicKey): SshHostKey? = when (key) {
    is RSAPublicKey -> {
        val blob = Buffer.PlainBuffer()
            .putString(SSH_RSA)
            .putMPInt(key.publicExponent)
            .putMPInt(key.modulus)
            .compactData
        SshHostKey(SSH_RSA, blob)
    }

    is ECPublicKey -> {
        val curve = sshCurveName(key) ?: return null
        val point = uncompressedPoint(key)
        val blob = Buffer.PlainBuffer()
            .putString(curve.first)
            .putString(curve.second)
            .putString(point)
            .compactData
        SshHostKey(curve.first, blob)
    }

    else -> encodeEd25519(key)
}

/** Ed25519 keys arrive as X.509 SubjectPublicKeyInfo; the trailing 32 bytes are the key. */
private fun encodeEd25519(key: PublicKey): SshHostKey? {
    val encoded = key.encoded ?: return null
    if (encoded.size < ED25519_RAW_LENGTH) return null
    if (!looksLikeEd25519SubjectPublicKeyInfo(encoded)) return null
    val raw = encoded.copyOfRange(encoded.size - ED25519_RAW_LENGTH, encoded.size)
    val blob = Buffer.PlainBuffer()
        .putString(SSH_ED25519)
        .putString(raw)
        .compactData
    return SshHostKey(SSH_ED25519, blob)
}

private fun looksLikeEd25519SubjectPublicKeyInfo(encoded: ByteArray): Boolean = encoded.size == ED25519_SPKI_LENGTH && encoded[0] == 0x30.toByte() && encoded[1] == 0x2A.toByte()

private fun uncompressedPoint(key: ECPublicKey): ByteArray {
    val fieldSize = (key.params.curve.field.fieldSize + 7) / 8
    val x = key.w.affineX.toFixedLength(fieldSize)
    val y = key.w.affineY.toFixedLength(fieldSize)
    return ByteArray(1 + fieldSize * 2).also {
        it[0] = 0x04
        x.copyInto(it, 1)
        y.copyInto(it, 1 + fieldSize)
    }
}

private fun BigInteger.toFixedLength(size: Int): ByteArray {
    val raw = toByteArray()
    val trimmed = if (raw.size > 1 && raw[0] == 0.toByte()) raw.copyOfRange(1, raw.size) else raw
    require(trimmed.size <= size) { "EC point coordinate exceeds the curve field size" }
    return ByteArray(size).also { trimmed.copyInto(it, size - trimmed.size) }
}

private fun sshCurveName(key: ECPublicKey): Pair<String, String>? = when (key.params.curve.field.fieldSize) {
    256 -> "ecdsa-sha2-nistp256" to "nistp256"
    384 -> "ecdsa-sha2-nistp384" to "nistp384"
    521 -> "ecdsa-sha2-nistp521" to "nistp521"
    else -> null
}

internal data class SshHostKey(val algorithm: String, val wire: ByteArray) {
    override fun equals(other: Any?): Boolean = other is SshHostKey && algorithm == other.algorithm && wire.contentEquals(other.wire)

    override fun hashCode(): Int = 31 * algorithm.hashCode() + wire.contentHashCode()
}

private const val SSH_RSA = "ssh-rsa"
private const val SSH_ED25519 = "ssh-ed25519"
private const val ED25519_RAW_LENGTH = 32
private const val ED25519_SPKI_LENGTH = 44
