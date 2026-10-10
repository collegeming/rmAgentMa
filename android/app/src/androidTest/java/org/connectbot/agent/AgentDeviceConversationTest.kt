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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import net.schmizz.sshj.common.SSHException
import net.schmizz.sshj.common.SecurityUtils
import org.connectbot.data.HostRepository
import org.connectbot.data.PubkeyRepository
import org.connectbot.di.DatabaseModule
import org.connectbot.di.DispatcherModule
import org.connectbot.util.AndroidKeyStorePrivateKeyProtector
import org.connectbot.util.ProviderLoader
import org.connectbot.util.ProviderLoaderListener
import org.connectbot.util.SecurePasswordStorage
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.rmagentma.core.AgentEvent
import org.rmagentma.core.AgentKind
import org.rmagentma.core.HostSession
import org.rmagentma.core.ShellCommands
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Security
import java.security.Signature
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AgentDeviceConversationTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    // The foreground service must observe the same real instances as this test.
    @BindValue
    lateinit var repository: AgentRepository

    @BindValue
    lateinit var pool: AgentSshPool

    @Before
    fun setUp() {
        assumeTrue(
            "Real agent conversations require agentDeviceE2e=true",
            InstrumentationRegistry.getArguments().getString("agentDeviceE2e") == "true",
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == CI_PACKAGE && context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            "Agent E2E is restricted to the debuggable CI package"
        }
        require(InstrumentationRegistry.getArguments().getString("expectedFingerprint") == VERIFIED_FINGERPRINT) {
            "The explicitly verified expectedFingerprint argument is required"
        }
    }

    private val phase = AtomicReference("configuration")
    private val currentAgent = AtomicReference("none")
    private val sessionDeadline = java.util.concurrent.atomic.AtomicLong(Long.MAX_VALUE)

    @Test
    fun conversationAndNativeResume() {
        val args = InstrumentationRegistry.getArguments()
        val count = (args.getString("agentKinds") ?: "kimi,dsh,zcode").split(',').distinct().size.coerceIn(1, 5)
        val overallDeadline = android.os.SystemClock.elapsedRealtime() + 30_000 + count * 120_000L
        val watchdog = Executors.newSingleThreadScheduledExecutor { command ->
            Thread(command, "agent-e2e-state-watchdog").apply { isDaemon = true }
        }
        val task = FutureTask { runConversation() }
        val worker = Thread(task, "agent-e2e-runner").apply { isDaemon = true }
        worker.start()
        watchdog.scheduleAtFixedRate({ runCatching { stateEvidence("heartbeat") } }, 10, 10, TimeUnit.SECONDS)
        try {
            while (true) {
                val deadline = minOf(overallDeadline, sessionDeadline.get())
                val remaining = deadline - android.os.SystemClock.elapsedRealtime()
                if (remaining <= 0) {
                    stateEvidence("hard_deadline")
                    val cleanup = Thread({
                        runCatching { if (this::pool.isInitialized) pool.interruptConnections() }
                        runCatching { if (this::repository.isInitialized) runBlocking { repository.disconnectAll() } }
                    }, "agent-e2e-deadline-cleanup").apply { isDaemon = true }
                    cleanup.start()
                    task.cancel(true)
                    cleanup.join(3_000)
                    evidence("deadlineReturned=true cleanupStillRunning=${cleanup.isAlive} workerStillRunning=${worker.isAlive}")
                    throw AssertionError("Agent E2E exceeded its independent deadline; phase=${phase.get()}")
                }
                try {
                    task.get(minOf(remaining, 250), TimeUnit.MILLISECONDS)
                    return
                } catch (_: TimeoutException) {
                    continue
                } catch (failure: ExecutionException) {
                    throw failure.cause ?: failure
                }
            }
        } finally {
            watchdog.shutdownNow()
        }
    }

    private fun runConversation() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val kinds = (args.getString("agentKinds") ?: "kimi,dsh,zcode").split(',').map { name ->
            requireNotNull(AgentKind.fromWire(name)) { "agentKinds contains an unsupported agent" }
        }.distinct()
        require(kinds.isNotEmpty())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dispatchers = DispatcherModule.provideCoroutineDispatchers()
        val database = DatabaseModule.provideConnectBotDatabase(context)
        val passwords = SecurePasswordStorage(context)
        val hosts = HostRepository(context, database, database.hostDao(), database.portForwardDao(), database.knownHostDao(), passwords)
        val keys = PubkeyRepository(database.pubkeyDao(), database, AndroidKeyStorePrivateKeyProtector(), dispatchers)
        val challenges = AgentChallenges()
        val realPool = AgentSshPool(hosts, keys, passwords, challenges, context, dispatchers.io, AgentCryptoInitializer(context, dispatchers))
        val rejected = AtomicReference<String?>(null)
        val permissions = AtomicInteger()
        val questions = AtomicInteger()
        val challengeCollector = launch(dispatchers.io, start = CoroutineStart.UNDISPATCHED) {
            challenges.challenge.collect { challenge ->
                if (challenge != null) {
                    val fingerprints = Regex("SHA256:[A-Za-z0-9+/]{43}(?![A-Za-z0-9+/=])").findAll(challenge.message).map { it.value }.toList()
                    val accepted = challenge.kind == "host_key" && !challenge.secret && challenge.hostName == "ci-test" &&
                        fingerprints == listOf(VERIFIED_FINGERPRINT)
                    if (accepted) {
                        evidence("hostKeyChallenge verified=true accepted=true fingerprint=$VERIFIED_FINGERPRINT")
                        challenges.respond(challenge.id, "accept")
                    } else {
                        rejected.compareAndSet(null, "SSH challenge requires separate authorization")
                        evidence("hostKeyChallenge accepted=false authorizationRequired=true")
                        challenges.respond(challenge.id, null)
                    }
                }
            }
        }
        var interactionCollector: kotlinx.coroutines.Job? = null
        var realRepository: AgentRepository? = null
        try {
            cryptoEvidence("before_connect")
            if (args.getString("agentDeviceLoadProvider") == "true") {
                val initialized = CompletableDeferred<Boolean>()
                ProviderLoader.load(
                    context,
                    object : ProviderLoaderListener {
                        override fun onProviderLoaderSuccess() {
                            initialized.complete(true)
                        }
                        override fun onProviderLoaderError() {
                            initialized.complete(false)
                        }
                    },
                )
                check(withTimeout(15_000) { initialized.await() }) { "Production crypto provider initialization failed" }
                evidence("productionProviderLoader initialized=true explicitOptIn=true")
                cryptoEvidence("after_provider_loader")
            }
            val host = hosts.getHosts().singleOrNull { it.nickname == "ci-test" } ?: error("Seeded ci-test host is required")
            check(
                host.id == 1L && host.protocol == "ssh" && host.hostname == "127.0.0.1" && host.port == 2222 &&
                    host.username == "colle" && host.useKeys && host.pubkeyId > 0 && host.jumpHostId == null,
            ) {
                "Seeded host does not match the isolated E2E endpoint"
            }
            val known = hosts.getKnownHostsForHost(host.id)
            check(known.all { it.hostname == host.hostname && it.port == host.port && fingerprint(it.hostKey) == VERIFIED_FINGERPRINT }) {
                "Existing host trust does not match the verified fingerprint; trust was not changed"
            }
            val savedKey = keys.getById(host.pubkeyId) ?: error("Seeded private key is required")
            try {
                check(savedKey.privateKey != null && !savedKey.encrypted && !savedKey.isBiometric && !savedKey.confirmation) {
                    "Seeded key requires separate authorization"
                }
            } finally {
                savedKey.privateKey?.fill(0)
            }
            val real = AgentRepository(context, hosts, realPool, AgentIndexStore(context, dispatchers.io), challenges, dispatchers)
            realRepository = real
            repository = real
            pool = realPool
            hiltRule.inject()
            interactionCollector = launch(dispatchers.io, start = CoroutineStart.UNDISPATCHED) {
                val answered = mutableSetOf<String>()
                real.events.collect { events ->
                    for (event in events.filter { it.type in setOf("permission", "question") && it.status != "resolved" }) {
                        if (!answered.add(event.id)) continue
                        if (event.type == "permission") permissions.incrementAndGet() else questions.incrementAndGet()
                        rejected.compareAndSet(null, "Unexpected interaction in no-tool conversation")
                        evidence("interaction rejected=true permissionCount=${permissions.get()} questionCount=${questions.get()}")
                        val session = real.selectedSession.value
                        val deny = event.options.firstOrNull { it.id in setOf("reject_once", "deny", "reject") }
                        if (event.type == "permission" && deny != null && session != null) {
                            real.respondPermission(session, event, event.id, deny.id)
                        }
                        real.cancel()
                    }
                }
            }
            for (kind in kinds) {
                currentAgent.set(kind.wireName)
                sessionDeadline.set(android.os.SystemClock.elapsedRealtime() + 120_000)
                val cwd = "/tmp/rmagentma-device-e2e-${UUID.randomUUID()}"
                val remote = realPool.hostSession(host.id)
                withTimeout(120_000) {
                    phase.set("ssh_mkdir")
                    evidence("agent=$currentAgent phase=$phase started=true")
                    shell(remote, "umask 077; mkdir -- ${ShellCommands.quote(cwd)} && printf '%s' RMAGENTMA_DIR_OK", "RMAGENTMA_DIR_OK")
                    cryptoEvidence("after_connect")
                    phase.set("new_session")
                    stateEvidence("phase_started")
                    real.newSession(host.id, kind, cwd)
                    healthy(real, rejected)
                    val session = real.activeSession.value ?: error("Agent did not become active")
                    check(session.sessionId.isNotBlank() && session.cwd == cwd && session.agent == kind)
                    evidence("agent=${kind.wireName} agentReady=true newSidLength=${session.sessionId.length} newSidHash=${hash(session.sessionId)}")
                    phase.set("first_prompt")
                    sendAndVerify(real, FIRST_PROMPT, FIRST_TOKEN, rejected)
                    val first = fullHistory(real)
                    val firstReply = first.filter { it.type == "content" }.joinToString("") { it.text }
                    check(first.any { it.type == "user" && it.text == FIRST_PROMPT } && firstReply.contains(FIRST_TOKEN)) {
                        "First turn lacks complete user and assistant content"
                    }
                    phase.set("close_and_resume")
                    real.closeConversation()
                    check(real.conversationState.value == AgentConversationState.Closed && real.activeSession.value == null)
                    real.open(session)
                    healthy(real, rejected)
                    check(real.activeSession.value?.sessionId == session.sessionId && real.activeSession.value?.cwd == cwd) {
                        "Native session identity was not restored"
                    }
                    val replay = fullHistory(real)
                    val replayReply = replay.filter { it.type == "content" }.joinToString("") { it.text }
                    check(replay.any { it.type == "user" && it.text == FIRST_PROMPT } && replayReply.contains(firstReply)) {
                        "Native replay lacks the complete first user and assistant message"
                    }
                    evidence("agent=${kind.wireName} resumed=true historyUsers=${replay.count { it.type == "user" }} historyAssistant=${replay.count { it.type == "content" }} historyReplyLength=${replayReply.length} historyReplyHash=${hash(replayReply)}")
                    phase.set("second_prompt")
                    sendAndVerify(real, SECOND_PROMPT, SECOND_TOKEN, rejected)
                    healthy(real, rejected)
                    evidence("agent=${kind.wireName} complete=true permissionCount=${permissions.get()} questionCount=${questions.get()} approvalPhase=not_run")
                    real.closeConversation()
                }
                sessionDeadline.set(Long.MAX_VALUE)
            }
        } catch (failure: Throwable) {
            val causeChain = safeCauseChain(failure)
            evidence("agent=$currentAgent phase=$phase complete=false causeChain=$causeChain permissionCount=${permissions.get()} questionCount=${questions.get()} approvalPhase=not_run")
            cryptoEvidence("failure")
            throw AssertionError("Agent device E2E failed: phase=$phase causeChain=$causeChain")
        } finally {
            phase.set("cleanup")
            sessionDeadline.set(android.os.SystemClock.elapsedRealtime() + 12_000)
            evidence("cleanup started=true")
            withContext(NonCancellable) {
                interactionCollector?.cancel()
                challenges.cancelAll()
                challengeCollector.cancel()
                withTimeoutOrNull(10_000) {
                    realPool.disconnectAll()
                    realRepository?.disconnectAll()
                    interactionCollector?.join()
                    challengeCollector.join()
                    database.close()
                    evidence("cleanup complete=true")
                } ?: evidence("cleanup complete=false cooperativeTimeout=true")
            }
        }
    }

    private suspend fun sendAndVerify(real: AgentRepository, prompt: String, token: String, rejected: AtomicReference<String?>) = coroutineScope {
        healthy(real, rejected)
        check(!real.busy.value)
        val originalSession = real.activeSession.value ?: error("No active conversation")
        val originalRows = real.events.value.associate { it.historyKey to it.text }
        val sawBusy = CompletableDeferred<Unit>()
        val sawStream = CompletableDeferred<Unit>()
        val observations = AtomicInteger()
        val busyCollector = launch(start = CoroutineStart.UNDISPATCHED) {
            real.busy.collect { busy -> if (busy) sawBusy.complete(Unit) }
        }
        val streamCollector = launch(start = CoroutineStart.UNDISPATCHED) {
            real.events.collect { events ->
                if (real.busy.value && events.any { it.type == "content" && it.text.isNotEmpty() && originalRows[it.historyKey] != it.text }) {
                    observations.incrementAndGet()
                    sawStream.complete(Unit)
                }
            }
        }
        try {
            val sending = async { real.send(prompt) }
            sawBusy.await()
            sending.await()
            check(!real.busy.value && real.activeSession.value === originalSession) { "Prompt did not complete on its original session" }
            while (!sawStream.isCompleted || !real.events.value.any { it.type == "content" && it.text.contains(token) && originalRows[it.historyKey] != it.text }) {
                healthy(real, rejected)
                delay(25)
            }
            healthy(real, rejected)
            evidence("replyToken=$token observedBusy=true streamObservations=${observations.get()} completed=true")
        } finally {
            busyCollector.cancelAndJoin()
            streamCollector.cancelAndJoin()
        }
    }

    private fun healthy(real: AgentRepository, rejected: AtomicReference<String?>) {
        check(rejected.get() == null) { "A challenge or tool interaction requires separate authorization" }
        check(
            real.conversationState.value == AgentConversationState.Ready && real.errors.value.isEmpty() &&
                real.events.value.none { it.type in setOf("error", "tool", "tool_update", "permission", "question") },
        ) {
            "Agent conversation is not healthy, tool-free and Ready"
        }
    }

    private suspend fun fullHistory(real: AgentRepository): List<AgentEvent> {
        check(!real.hasEarlier.value) { "Short E2E history unexpectedly requires paging" }
        return real.events.value.map { real.fullText(it) }
    }

    private suspend fun shell(remote: HostSession, body: String, expected: String) = coroutineScope {
        remote.exec("/bin/sh -c ${ShellCommands.quote(body)}").use { channel ->
            val watchdog = launch {
                delay(15_000)
                channel.close()
            }
            try {
                channel.stdin.close()
                val errors = async { runInterruptible { channel.stderr.readNBytes(1024) } }
                val output = runInterruptible { channel.stdout.readNBytes(1024) }
                check(output.toString(Charsets.UTF_8) == expected && errors.await().isEmpty()) { "Isolated directory creation failed" }
            } finally {
                watchdog.cancelAndJoin()
            }
        }
    }

    private fun stateEvidence(reason: String) {
        val real = if (this::repository.isInitialized) repository else null
        val connections = if (this::pool.isInitialized) pool.connectionCount.value else -1
        evidence(
            "diagnostic=$reason agent=${currentAgent.get()} phase=${phase.get()} state=${real?.conversationState?.value} " +
                "operations=${real?.operationCount?.value} busy=${real?.busy?.value} connections=$connections " +
                "availabilityCount=${real?.availability?.value?.size} eventCount=${real?.events?.value?.size} errorCount=${real?.errors?.value?.size}",
        )
        Thread.getAllStackTraces().entries.filter { (thread, stack) ->
            thread.name == "main" || thread.name == "agent-e2e-runner" || stack.any { frame ->
                frame.className.startsWith("net.schmizz.") || frame.className.startsWith("org.connectbot.agent.") ||
                    frame.className.startsWith("org.rmagentma.core.")
            }
        }.take(20).forEach { (thread, stack) ->
            val frames = stack.take(16).joinToString("<-") { frame ->
                "${frame.className}.${frame.methodName}:${frame.lineNumber}".replace(Regex("[^A-Za-z0-9_.$:<-]"), "_")
            }
            evidence("diagnostic=$reason threadId=${thread.id} threadState=${thread.state} frames=$frames")
        }
    }

    private fun safeCauseChain(failure: Throwable): String {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        return generateSequence(failure) { it.cause }.takeWhile { seen.add(it) }.take(8).joinToString("<-") { cause ->
            val type = cause.javaClass.name.replace(Regex("[^A-Za-z0-9_.$]"), "_").take(180)
            val message = cause.message.orEmpty()
            val reason = (cause as? SSHException)?.disconnectReason?.name ?: "none"
            val categories = listOf(
                "no such algorithm", "no such provider", "algorithm not available", "not supported", "key exchange",
                "key agreement", "invalid key", "invalid curve", "cannot find", "connection refused", "connection reset",
                "socket closed", "timed out", "unable to negotiate", "host key", "signature", "authentication",
                "permission denied", "MAC error", "decrypt", "encrypt", "EOF",
            ).filter { message.contains(it, ignoreCase = true) }.joinToString("+") { it.replace(' ', '_') }.ifEmpty { "redacted" }
            val algorithms = CRYPTO_NAMES.filter { name ->
                Regex("(?<![A-Za-z0-9])${Regex.escape(name)}(?![A-Za-z0-9])", RegexOption.IGNORE_CASE).containsMatchIn(message)
            }.joinToString(",").ifEmpty { "none" }
            "$type[sshCode=$reason;category=$categories;crypto=$algorithms;messageLength=${message.length}]"
        }
    }

    private fun cryptoEvidence(stage: String) {
        val providers = Security.getProviders().joinToString(",") { providerName(it.name) }
        val selected = if (stage in setOf("failure", "after_connect")) {
            runCatching { SecurityUtils.getSecurityProvider()?.let(::providerName) ?: "platform_default" }
                .getOrElse { safeCauseChain(it) }
        } else {
            "not_queried_before_connect"
        }
        evidence("cryptoStage=$stage providers=$providers sshjProvider=$selected")
        val probes: List<Pair<String, () -> String>> = listOf(
            "KeyAgreement:ECDH" to { KeyAgreement.getInstance("ECDH").provider.name },
            "KeyAgreement:X25519" to { KeyAgreement.getInstance("X25519").provider.name },
            "KeyAgreement:DH" to { KeyAgreement.getInstance("DH").provider.name },
            "KeyPairGenerator:EC" to { KeyPairGenerator.getInstance("EC").provider.name },
            "KeyFactory:EC" to { KeyFactory.getInstance("EC").provider.name },
            "Signature:Ed25519" to { Signature.getInstance("Ed25519").provider.name },
            "Signature:SHA256withRSA" to { Signature.getInstance("SHA256withRSA").provider.name },
            "Cipher:AES/CTR/NoPadding" to { Cipher.getInstance("AES/CTR/NoPadding").provider.name },
            "Mac:HmacSHA256" to { Mac.getInstance("HmacSHA256").provider.name },
        )
        probes.forEach { (algorithm, probe) ->
            val result = runCatching { "provider=${providerName(probe())}" }.getOrElse { "causeChain=${safeCauseChain(it)}" }
            evidence("cryptoStage=$stage algorithm=$algorithm $result")
        }
    }

    private fun providerName(name: String): String = if (
        name in setOf(
            "AndroidOpenSSL", "Conscrypt", "BC", "SC", "AndroidKeyStore", "AndroidKeyStoreBCWorkaround", "AndroidNSSP", "Crypto", "HarmonyJSSE", "Ed25519",
        )
    ) {
        name
    } else {
        "other_${hash(name)}"
    }

    private fun fingerprint(bytes: ByteArray): String = "SHA256:" + Base64.encodeToString(
        MessageDigest.getInstance("SHA-256").digest(bytes),
        Base64.NO_WRAP or Base64.NO_PADDING,
    )

    private fun hash(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }.take(16)

    private fun evidence(message: String) {
        val output = "AGENT_DEVICE_E2E $message\n"
        println(output)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("stream", output) })
    }

    private companion object {
        val CRYPTO_NAMES = listOf(
            "BC", "SC", "Conscrypt", "AndroidOpenSSL", "ECDH", "EC", "X25519", "XDH", "DH", "DiffieHellman", "RSA", "Ed25519",
            "SHA256withRSA", "SHA256withECDSA", "SHA-256", "HmacSHA256", "AES/CTR/NoPadding", "AES/GCM/NoPadding",
            "curve25519-sha256", "curve25519-sha256@libssh.org", "ecdh-sha2-nistp256", "ssh-ed25519", "rsa-sha2-256", "rsa-sha2-512",
        )
        const val CI_PACKAGE = "org.rmagentma.android.debug.ci"
        const val VERIFIED_FINGERPRINT = "SHA256:PHtyTe2zZp+MZUBbF64v3uHF6jumV1GjSDXG58N1BNA"
        const val FIRST_TOKEN = "RMAGENTMA_E2E_OK"
        const val FIRST_PROMPT = "Reply only RMAGENTMA_E2E_OK. Do not run tools or modify files."
        const val SECOND_TOKEN = "RMAGENTMA_E2E_RESUMED_OK"
        const val SECOND_PROMPT = "Reply only RMAGENTMA_E2E_RESUMED_OK. Do not run tools or modify files."
    }
}
