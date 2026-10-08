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

package org.connectbot.transport

import android.content.res.Resources
import com.trilead.ssh2.crypto.fingerprint.KeyFingerprint
import com.trilead.ssh2.packets.TypesReader
import com.trilead.ssh2.signature.RSASHA1Verify
import com.trilead.ssh2.signature.RSASHA256Verify
import com.trilead.ssh2.signature.RSASHA512Verify
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.connectbot.R
import org.connectbot.agent.trustedAgentHostKey
import org.connectbot.data.HostRepository
import org.connectbot.data.entity.Host
import org.connectbot.data.entity.KnownHost
import org.connectbot.service.PromptManager
import org.connectbot.service.TerminalBridge
import org.connectbot.service.TerminalManager
import org.connectbot.util.HostKeyTrust
import org.connectbot.util.hostKeyTrust
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.math.BigInteger
import java.security.KeyFactory
import java.security.spec.RSAPublicKeySpec

class HostKeyTrustTest {
    private val host = Host(id = 42, nickname = "fixture", hostname = "192.0.2.2", port = 2222)
    private val wire = rsaWire()

    @Test
    fun addressChange_keepsTrustForStableHostId() {
        val known = listOf(knownHost())
        assertThat(hostKeyTrust(host.id, known, "rsa-sha2-512", wire)).isEqualTo(HostKeyTrust.TRUSTED)
        val harness = verifier(known)

        assertThat(harness.verifier.verifyServerHostKey(host.hostname, host.port, "rsa-sha2-512", wire)).isTrue()

        verifyNoInteractions(harness.prompts)
        verify(harness.repository).getKnownHostsForHostBlocking(host.id)
    }

    @Test
    fun noRecords_requiresFirstUseConfirmation() {
        assertThat(hostKeyTrust(host.id, emptyList(), "ssh-rsa", wire)).isEqualTo(HostKeyTrust.FIRST_USE)
        val harness = verifier(emptyList())
        val sha256 = KeyFingerprint.createSHA256Fingerprint(wire)
        val md5 = KeyFingerprint.createMD5Fingerprint(wire)
        val randomArt = KeyFingerprint.createRandomArt(wire, "RSA", 1024)
        val bubblebabble = KeyFingerprint.createBubblebabbleFingerprint(wire)
        runBlocking {
            `when`(
                harness.prompts.requestHostKeyFingerprintPrompt(
                    host.hostname,
                    "RSA",
                    1024,
                    wire,
                    randomArt,
                    bubblebabble,
                    sha256,
                    md5,
                ),
            ).thenReturn(true)
        }

        assertThat(harness.verifier.verifyServerHostKey(host.hostname, host.port, "rsa-sha2-512", wire)).isTrue()

        runBlocking {
            verify(harness.prompts).requestHostKeyFingerprintPrompt(
                host.hostname,
                "RSA",
                1024,
                wire,
                randomArt,
                bubblebabble,
                sha256,
                md5,
            )
        }
        verify(harness.repository).saveKnownHostBlocking(host, host.hostname, host.port, "rsa-sha2-512", wire)
    }

    @Test
    fun firstUse_rejectionDoesNotSave() {
        val harness = verifier(emptyList())
        runBlocking {
            `when`(
                harness.prompts.requestHostKeyFingerprintPrompt(
                    host.hostname,
                    "RSA",
                    1024,
                    wire,
                    KeyFingerprint.createRandomArt(wire, "RSA", 1024),
                    KeyFingerprint.createBubblebabbleFingerprint(wire),
                    KeyFingerprint.createSHA256Fingerprint(wire),
                    KeyFingerprint.createMD5Fingerprint(wire),
                ),
            ).thenReturn(false)
        }

        assertThat(harness.verifier.verifyServerHostKey(host.hostname, host.port, "rsa-sha2-512", wire)).isFalse()

        assertThat(org.mockito.Mockito.mockingDetails(harness.repository).invocations.map { it.method.name })
            .containsExactly("getKnownHostsForHostBlocking")
    }

    @Test
    fun changedKey_rejectsWithoutPromptOrSaveAndDisplaysWarningFingerprint() {
        val changed = wire.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        assertThat(hostKeyTrust(host.id, listOf(knownHost()), "ssh-rsa", changed)).isEqualTo(HostKeyTrust.CHANGED)
        val harness = verifier(listOf(knownHost()))
        `when`(harness.resources.getString(R.string.host_verification_failure_warning)).thenReturn("changed key warning")
        `when`(harness.resources.getString(R.string.host_fingerprint)).thenReturn("%s %s")

        assertThat(harness.verifier.verifyServerHostKey(host.hostname, host.port, "ssh-rsa", changed)).isFalse()

        verifyNoInteractions(harness.prompts)
        verify(harness.bridge).outputLine("changed key warning")
        verify(harness.bridge).outputLine(
            "RSA \nMD5:${KeyFingerprint.createMD5Fingerprint(changed)}\n${KeyFingerprint.createSHA256Fingerprint(changed)}",
        )
        assertThat(org.mockito.Mockito.mockingDetails(harness.repository).invocations.map { it.method.name })
            .containsExactly("getKnownHostsForHostBlocking")
    }

    @Test
    fun newAlgorithmOnKnownHost_isChangedNotFirstUse() {
        val harness = verifier(listOf(knownHost()))

        assertThat(harness.verifier.verifyServerHostKey(host.hostname, host.port, "ssh-ed25519", wire)).isFalse()

        verifyNoInteractions(harness.prompts)
        assertThat(hostKeyTrust(host.id, listOf(knownHost()), "ssh-ed25519", wire)).isEqualTo(HostKeyTrust.CHANGED)
    }

    @Test
    fun differentHostId_cannotInheritTrust() {
        assertThat(hostKeyTrust(43, listOf(knownHost()), "ssh-rsa", wire)).isEqualTo(HostKeyTrust.FIRST_USE)
    }

    @Test
    fun anyMatchingStoredKey_isTrustedEvenWithOtherKeys() {
        val other = knownHost().copy(hostKey = byteArrayOf(1, 2, 3))
        assertThat(hostKeyTrust(host.id, listOf(other, knownHost()), "ssh-rsa", wire))
            .isEqualTo(HostKeyTrust.TRUSTED)
    }

    @Test
    fun rsaSignatureVariants_shareCanonicalSshRsaBlob() {
        val publicKey = RSASHA1Verify.get().decodePublicKey(wire)
        assertThat(TypesReader(wire).readString()).isEqualTo("ssh-rsa")
        assertThat(RSASHA256Verify.get().encodePublicKey(publicKey)).isEqualTo(wire)
        assertThat(RSASHA512Verify.get().encodePublicKey(publicKey)).isEqualTo(wire)
        val algorithms = listOf("ssh-rsa", "rsa-sha2-256", "rsa-sha2-512")
        algorithms.forEach { saved ->
            algorithms.forEach { offered ->
                assertThat(hostKeyTrust(host.id, listOf(knownHost().copy(hostKeyAlgo = saved)), offered, wire))
                    .isEqualTo(HostKeyTrust.TRUSTED)
            }
        }
    }

    @Test
    fun sameBlob_differentKeyFamilyIsNotTrusted() {
        listOf("ssh-ed25519", "ssh-dss", "ecdsa-sha2-nistp256", "unknown").forEach { offered ->
            assertThat(hostKeyTrust(host.id, listOf(knownHost()), offered, wire)).isEqualTo(HostKeyTrust.CHANGED)
        }
        val ec = knownHost().copy(hostKeyAlgo = "ecdsa-sha2-nistp256")
        assertThat(hostKeyTrust(host.id, listOf(ec), "ecdsa-sha2-nistp384", wire)).isEqualTo(HostKeyTrust.CHANGED)
    }

    @Test
    fun terminalAndAgentBackends_useSameFamilyAndBlobTrustRules() {
        listOf("ssh-rsa", "rsa-sha2-256", "rsa-sha2-512", "ssh-ed25519", "ssh-dss", "unknown").forEach { algorithm ->
            listOf(wire, byteArrayOf(9, 8, 7)).forEach { offered ->
                val known = listOf(knownHost())
                assertThat(hostKeyTrust(host.id, known, algorithm, offered) == HostKeyTrust.TRUSTED)
                    .isEqualTo(trustedAgentHostKey(known, algorithm, offered))
            }
        }
    }

    private fun knownHost(): KnownHost = KnownHost(
        hostId = host.id,
        hostname = "192.0.2.1",
        port = 22,
        hostKeyAlgo = "ssh-rsa",
        hostKey = wire.copyOf(),
    )

    private fun rsaWire(): ByteArray {
        val modulus = BigInteger.ONE.shiftLeft(1023).add(BigInteger.valueOf(643))
        val key = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, BigInteger.valueOf(65537)))
        return RSASHA1Verify.get().encodePublicKey(key)
    }

    private fun verifier(known: List<KnownHost>): Harness {
        val repository = mock(HostRepository::class.java)
        val manager = mock(TerminalManager::class.java)
        val bridge = mock(TerminalBridge::class.java)
        val resources = mock(Resources::class.java)
        val prompts = mock(PromptManager::class.java)
        `when`(manager.hostRepository).thenReturn(repository)
        `when`(manager.res).thenReturn(resources)
        `when`(bridge.promptManager).thenReturn(prompts)
        `when`(repository.getKnownHostsForHostBlocking(host.id)).thenReturn(known)
        `when`(resources.getString(eq(R.string.host_authenticity_warning), anyString())).thenReturn("first use")
        val ssh = SSH(host, bridge, manager)
        return Harness(ssh.HostKeyVerifier(), repository, bridge, resources, prompts)
    }

    private data class Harness(
        val verifier: SSH.HostKeyVerifier,
        val repository: HostRepository,
        val bridge: TerminalBridge,
        val resources: Resources,
        val prompts: PromptManager,
    )
}
