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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.ECGenParameterSpec

class SshHostKeyEncoderTest {
    @Test
    fun rsaMatchesSshjWireFormat() {
        val key = keyPair("RSA", 2048).public
        val encoded = requireNotNull(encodeSshHostKey(key))

        assertEquals("ssh-rsa", encoded.algorithm)
        // Cross-check against sshj's own encoder, which handles RSA correctly.
        assertArrayEquals(Buffer.PlainBuffer().putPublicKey(key).compactData, encoded.wire)
    }

    @Test
    fun rsaBlobCarriesExponentThenModulus() {
        val rsa = keyPair("RSA", 2048).public as RSAPublicKey
        val encoded = requireNotNull(encodeSshHostKey(rsa))
        val replay = Buffer.PlainBuffer(encoded.wire)

        assertEquals("ssh-rsa", replay.readString())
        assertEquals(rsa.publicExponent, replay.readMPInt())
        assertEquals(rsa.modulus, replay.readMPInt())
    }

    @Test
    fun ecMatchesSshjWireFormatForSupportedCurves() {
        for (curve in listOf("secp256r1", "secp384r1", "secp521r1")) {
            val key = keyPair("EC", spec = ECGenParameterSpec(curve)).public
            val encoded = requireNotNull(encodeSshHostKey(key))

            assertArrayEquals(
                "wire mismatch for $curve",
                Buffer.PlainBuffer().putPublicKey(key).compactData,
                encoded.wire,
            )
        }
    }

    @Test
    fun ecAlgorithmNamesFollowRfc5656() {
        val expected = mapOf(
            "secp256r1" to "ecdsa-sha2-nistp256",
            "secp384r1" to "ecdsa-sha2-nistp384",
            "secp521r1" to "ecdsa-sha2-nistp521",
        )
        for ((curve, algorithm) in expected) {
            val key = keyPair("EC", spec = ECGenParameterSpec(curve)).public as ECPublicKey
            val encoded = requireNotNull(encodeSshHostKey(key))

            assertEquals(algorithm, encoded.algorithm)
            val replay = Buffer.PlainBuffer(encoded.wire)
            assertEquals(algorithm, replay.readString())
            assertEquals(algorithm.substringAfter("ecdsa-sha2-"), replay.readString())
            assertEquals(1 + curvePointLength(curve) * 2, replay.readBytes().size)
        }
    }

    /** Ed25519 is the case sshj cannot encode on Android; the layout must still be correct. */
    @Test
    fun ed25519EncodesTrailingRawKeyAsSshString() {
        val key = keyPair("Ed25519").public
        val encoded = encodeSshHostKey(key)
        if (encoded == null) return // platform without Ed25519 support in this JVM

        assertEquals("ssh-ed25519", encoded.algorithm)
        val replay = Buffer.PlainBuffer(encoded.wire)
        assertEquals("ssh-ed25519", replay.readString())
        val raw = replay.readBytes()
        assertEquals(32, raw.size)
        assertArrayEquals(key.encoded.copyOfRange(key.encoded.size - 32, key.encoded.size), raw)
    }

    @Test
    fun ed25519RejectsUnexpectedEncodings() {
        assertNull(encodeSshHostKey(UnknownKey()))
    }
    private fun curvePointLength(curve: String): Int = when (curve) {
        "secp256r1" -> 32
        "secp384r1" -> 48
        else -> 66
    }

    private fun keyPair(
        algorithm: String,
        size: Int = 0,
        spec: ECGenParameterSpec? = null,
    ): java.security.KeyPair {
        val generator = KeyPairGenerator.getInstance(algorithm)
        when {
            spec != null -> generator.initialize(spec)
            size > 0 -> generator.initialize(size)
        }
        return generator.generateKeyPair()
    }

    private class UnknownKey : PublicKey {
        override fun getAlgorithm(): String = "mystery"

        override fun getFormat(): String = "raw"

        override fun getEncoded(): ByteArray = ByteArray(8)

        private fun readResolve(): Any = this

        companion object {
            private const val serialVersionUID = 1L
        }
    }
}
