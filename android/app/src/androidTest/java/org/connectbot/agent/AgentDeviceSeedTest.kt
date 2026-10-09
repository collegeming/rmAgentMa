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

package org.connectbot.agent

import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.util.Base64
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.trilead.ssh2.signature.RSASHA1Verify
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.connectbot.data.HostRepository
import org.connectbot.data.PubkeyRepository
import org.connectbot.data.entity.Host
import org.connectbot.data.entity.Pubkey
import org.connectbot.di.CoroutineDispatchers
import org.connectbot.di.DatabaseModule
import org.connectbot.util.AndroidKeyStorePrivateKeyProtector
import org.connectbot.util.PubkeyUtils
import org.connectbot.util.SecurePasswordStorage
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import javax.inject.Inject

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AgentDeviceSeedTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var dispatchers: CoroutineDispatchers

    @Inject
    lateinit var protector: AndroidKeyStorePrivateKeyProtector

    @Inject
    lateinit var passwordStorage: SecurePasswordStorage

    @Before
    fun setUp() {
        assumeTrue(
            "Device seeding requires the explicit seedAgentHost=true instrumentation argument",
            InstrumentationRegistry.getArguments().getString("seedAgentHost") == "true",
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == CI_PACKAGE && context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            "Device seeding is restricted to the debuggable $CI_PACKAGE target"
        }
        hiltRule.inject()
    }

    @Test
    fun seedAgentHost() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val hostname = arguments.getString("seedHost") ?: "127.0.0.1"
        val port = (arguments.getString("seedPort") ?: "2222").toIntOrNull()
        val username = arguments.getString("seedUser") ?: "colle"
        require(hostname.isNotBlank() && hostname.none(Char::isWhitespace)) { "seedHost must be a nonblank hostname" }
        require(port != null && port in 1..65535) { "seedPort must be in 1..65535" }
        require(username.isNotBlank() && username.none(Char::isWhitespace)) { "seedUser must be nonblank" }

        // Shared Hilt tests replace the database and protector with test-only implementations.
        // Use the production factory and concrete Keystore protector for this opt-in persistent seed.
        val database = DatabaseModule.provideConnectBotDatabase(context)
        try {
            val hosts = HostRepository(
                context,
                database,
                database.hostDao(),
                database.portForwardDao(),
                database.knownHostDao(),
                passwordStorage,
            )
            val keys = PubkeyRepository(database.pubkeyDao(), database, protector, dispatchers)
            val result = database.withTransaction {
                val existingHost = hosts.getHosts().singleOrNull { it.nickname == HOST_NICKNAME }
                if (existingHost != null) {
                    check(
                        existingHost.protocol == "ssh" && existingHost.hostname == hostname &&
                            existingHost.port == port && existingHost.username == username && existingHost.useKeys,
                    ) { "Existing $HOST_NICKNAME host differs from the requested seed; nothing was overwritten" }
                    val key = keys.getById(existingHost.pubkeyId)
                        ?: error("Existing $HOST_NICKNAME host has no saved key; nothing was overwritten")
                    SeedResult(existingHost.id, key.id, publicText(key), reused = true)
                } else {
                    val existingKey = keys.getByNickname(KEY_NICKNAME)
                    val key = existingKey ?: createKey(keys)
                    val publicText = publicText(key)
                    val host = hosts.saveHost(
                        Host(
                            nickname = HOST_NICKNAME,
                            hostname = hostname,
                            port = port,
                            username = username,
                            pubkeyId = key.id,
                        ),
                    )
                    SeedResult(host.id, key.id, publicText, reused = existingKey != null)
                }
            }
            val publicFile = File(context.filesDir, PUBLIC_FILENAME)
            publicFile.writeText("${result.publicText}\n", Charsets.UTF_8)
            val output = """
                |AGENT_SEED hostNickname=$HOST_NICKNAME hostId=${result.hostId} keyId=${result.keyId} reusedKey=${result.reused}
                |AGENT_SEED publicKeyFile=${publicFile.absolutePath}
                |${result.publicText}
                |
            """.trimMargin()
            println(output)
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", output) })
        } finally {
            database.close()
        }
    }

    private suspend fun createKey(repository: PubkeyRepository): Pubkey {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }.generateKeyPair()
        val privateBytes = PubkeyUtils.getEncodedPrivate(pair.private, null)
        try {
            val saved = repository.save(
                Pubkey(
                    nickname = KEY_NICKNAME,
                    type = "RSA",
                    privateKey = privateBytes,
                    publicKey = pair.public.encoded,
                    encrypted = false,
                    startup = false,
                    confirmation = false,
                    createdDate = System.currentTimeMillis(),
                    allowBackup = false,
                ),
            )
            return repository.getById(saved.id) ?: error("Saved seed key could not be read back")
        } finally {
            privateBytes.fill(0)
        }
    }

    private fun publicText(key: Pubkey): String {
        try {
            check(key.nickname == KEY_NICKNAME && key.type == "RSA" && !key.encrypted && !key.isBiometric) {
                "Existing seed key is incompatible; nothing was overwritten"
            }
            val publicKey = PubkeyUtils.decodePublic(key.publicKey, key.type)
            val privateKey = PubkeyUtils.decodePrivate(key.privateKey, key.type)
                ?: error("Seed key has no private key")
            val challenge = "AgentDeviceSeedTest key round trip".toByteArray(Charsets.UTF_8)
            val signature = Signature.getInstance("SHA256withRSA").apply {
                initSign(privateKey)
                update(challenge)
            }.sign()
            check(
                Signature.getInstance("SHA256withRSA").apply {
                    initVerify(publicKey)
                    update(challenge)
                }.verify(signature),
            ) { "Saved seed key pair does not match" }
            val encoded = Base64.encodeToString(RSASHA1Verify.get().encodePublicKey(publicKey), Base64.NO_WRAP)
            return "ssh-rsa $encoded $KEY_NICKNAME"
        } finally {
            key.privateKey?.fill(0)
        }
    }

    private data class SeedResult(val hostId: Long, val keyId: Long, val publicText: String, val reused: Boolean)

    private companion object {
        const val CI_PACKAGE = "org.rmagentma.android.debug.ci"
        const val HOST_NICKNAME = "ci-test"
        const val KEY_NICKNAME = "ci-test-rsa"
        const val PUBLIC_FILENAME = "ci-test-rsa.pub"
    }
}
