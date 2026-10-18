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

import android.content.Context
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.DirectConnection
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.UserAuthException
import org.connectbot.R
import org.connectbot.data.HostRepository
import org.connectbot.data.PubkeyRepository
import org.connectbot.data.entity.Host
import org.connectbot.data.entity.KnownHost
import org.connectbot.di.IoDispatcher
import org.connectbot.util.PubkeyUtils
import org.connectbot.util.SecurePasswordStorage
import org.connectbot.util.encodeSshHostKey
import org.rmagentma.core.ExecChannel
import org.rmagentma.core.HostSession
import java.io.IOException
import java.security.MessageDigest
import java.security.PublicKey
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val ADDRESS_PROBE_TIMEOUT_MS = 5_000

/** One host's link fact: a usable client, or the reconnect bookkeeping that explains why there is none. */
data class AgentLinkStatus(val hostId: Long, val state: AgentLinkState, val attempts: Int)

@Singleton
class AgentSshPool @Inject constructor(
    private val hosts: HostRepository,
    private val keys: PubkeyRepository,
    private val passwords: SecurePasswordStorage,
    private val challenges: AgentChallenges,
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
    private val crypto: AgentCryptoInitializer,
    private val addressStore: AgentHostAddressStore,
    private val discovery: AgentLanSshDiscovery,
) {
    private data class Entry(val chain: List<Host>, val client: SSHClient, val tunnel: DirectConnection?)
    private val reconnect = AgentReconnectPolicy()
    private val reconnectHosts = mutableSetOf<Long>()
    private val automaticKeys = mutableMapOf<Long, Long>()

    private val mutableCount = kotlinx.coroutines.flow.MutableStateFlow(0)
    val connectionCount: kotlinx.coroutines.flow.StateFlow<Int> = mutableCount.asStateFlow()
    private val mutableAddressChanges = MutableSharedFlow<HostAddressChange>(extraBufferCapacity = 8)
    val addressChanges: SharedFlow<HostAddressChange> = mutableAddressChanges.asSharedFlow()
    private val mutex = Mutex()
    private val entries = ConcurrentHashMap<Long, Entry>()
    private val connecting = ConcurrentHashMap.newKeySet<SSHClient>()
    suspend fun allowUserRetry(id: Long) = mutex.withLock {
        chain(id).forEach { reconnect.userRetry(it.id) }
    }

    suspend fun needsUserRetry(id: Long): Boolean = mutex.withLock { reconnect.isBlocked(id) }

    suspend fun hasReconnectWork(): Boolean = mutex.withLock { reconnectHosts.any { !reconnect.isBlocked(it) } }

    /**
     * Live link facts for the hosts the pool can speak about: an authenticated client, a blocked host, or a host
     * whose reconnect schedule is in backoff. A host it reports nothing for is unknown, not connected.
     */
    suspend fun linkStatus(): List<AgentLinkStatus> = mutex.withLock {
        (entries.keys + reconnectHosts + reconnect.blockedHosts()).mapNotNull { id ->
            val entry = entries[id]
            val state = when {
                entry?.client?.isConnected == true && entry.client.isAuthenticated -> AgentLinkState.CONNECTED
                reconnect.isBlocked(id) -> AgentLinkState.NEEDS_USER
                reconnect.hasFailure(id) -> AgentLinkState.RETRYING
                else -> null
            }
            state?.let { AgentLinkStatus(id, it, if (it == AgentLinkState.RETRYING) reconnect.attempts(id) else 0) }
        }
    }

    suspend fun reconnectDisconnected(): List<Long> = withContext(io) {
        mutex.withLock {
            val restored = mutableListOf<Long>()
            for (id in reconnectHosts.toList()) {
                if (reconnect.isBlocked(id)) continue
                val entry = entries[id]
                if (entry?.client?.isConnected == true && entry.client.isAuthenticated) continue
                val now = android.os.SystemClock.elapsedRealtime()
                if (!reconnect.hasFailure(id)) {
                    reconnect.failed(id, now)
                    continue
                }
                if (reconnect.waiting(id, now) > 0) continue
                try {
                    connectChain(chain(id), interactive = false)
                    restored += id
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (e is AgentConnectionNeedsUser || e is IllegalArgumentException || reconnect.isBlocked(id)) {
                        reconnect.block(id)
                        throw AgentConnectionNeedsUser("${entries[id]?.chain?.firstOrNull()?.nickname ?: id}: reconnect requires user action")
                    }
                }
            }
            restored
        }
    }

    suspend fun redialFailedHosts() = withContext(io) {
        val targets = mutex.withLock {
            val now = android.os.SystemClock.elapsedRealtime()
            reconnectHosts.filter { isRedialTarget(it, now) }
        }
        for (id in targets) {
            try {
                hosts.findHostById(id)?.let { redialHost(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // rediscovery is best effort and must not disturb the existing reconnect schedule
            }
        }
    }

    private suspend fun isRedialTarget(id: Long, now: Long): Boolean {
        val host = hosts.findHostById(id) ?: return false
        val entry = entries[id]
        return redialEligible(
            host,
            hasTrust = hosts.getKnownHostsForHost(id).isNotEmpty(),
            hasFailure = reconnect.hasFailure(id),
            blocked = reconnect.isBlocked(id),
            waitingMillis = reconnect.waiting(id, now),
            hasLiveConnection = entry?.client?.isConnected == true && entry.client.isAuthenticated,
        )
    }

    private suspend fun redialHost(host: Host) {
        for (candidate in redialCandidates(host, addressStore.candidates(host.id, host.port), discovery.endpoints())) {
            if (!probeAddress(host, candidate.address, candidate.port)) continue
            // A probe can span seconds and HostRepository updates whole rows, so a snapshot read before the probe
            // would silently overwrite an address the user edited in the meantime; abandon instead of guessing.
            val current = hosts.findHostById(host.id) ?: return
            if (current.hostname != host.hostname || current.port != host.port || !isIpLiteral(current.hostname)) return
            // redialCandidates returns the row's own port only, so the row keeps it unchanged
            hosts.saveHost(current.copy(hostname = candidate.address))
            addressStore.record(host.id, candidate.address, host.port, AgentAddressSource.REDISCOVERED)
            mutableAddressChanges.emit(HostAddressChange(host.id, current.hostname, candidate.address))
            return
        }
    }

    /** Dials a candidate address for its host key only: no authentication, prompt, known-host write or block. */
    internal suspend fun probeAddress(host: Host, address: String, port: Int): Boolean = withContext(io) {
        val known = hosts.getKnownHostsForHost(host.id)
        if (known.isEmpty()) return@withContext false
        val client = try {
            crypto.ensureReady()
            SSHClient(DefaultConfig().apply { keepAliveProvider = KeepAliveProvider.KEEP_ALIVE })
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return@withContext false
        }
        try {
            client.connectTimeout = ADDRESS_PROBE_TIMEOUT_MS
            // connectTimeout covers only the TCP handshake, so the transport needs its own bound: a peer that
            // accepts and then never sends its SSH banner would otherwise stall the redial loop forever.
            client.timeout = ADDRESS_PROBE_TIMEOUT_MS
            client.addHostKeyVerifier(pinnedHostKeyVerifier(known))
            runInterruptible(io) { client.connect(address, port) }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        } finally {
            try {
                client.close()
            } catch (_: IOException) { }
        }
    }

    fun hostSession(id: Long, interactive: Boolean = true): HostSession = object : HostSession {
        override val hostId = id

        override suspend fun exec(command: String): ExecChannel = withContext(io) {
            val client = connect(id, interactive)
            runInterruptible {
                val session = client.startSession()
                try {
                    val remote = session.exec(command)
                    object : ExecChannel {
                        override val stdout = remote.inputStream
                        override val stdin = remote.outputStream
                        override val stderr = remote.errorStream
                        override fun close() = session.close()
                    }
                } catch (e: Exception) {
                    session.close()
                    throw e
                }
            }
        }
    }

    private suspend fun chain(id: Long, visited: Set<Long> = emptySet()): List<Host> {
        require(id !in visited) { "ProxyJump cycle detected" }
        require(visited.size < 16) { "ProxyJump chain is too deep" }
        val host = hosts.findHostById(id) ?: throw IOException("Host $id was deleted")
        require(host.protocol == "ssh") { "Agent connections require SSH" }
        return listOf(host) + (host.jumpHostId?.let { chain(it, visited + id) } ?: emptyList())
    }

    private suspend fun connect(id: Long, interactive: Boolean): SSHClient = mutex.withLock {
        connectChain(chain(id), interactive)
    }

    private fun sameConnection(a: List<Host>, b: List<Host>): Boolean = a.size == b.size && a.zip(b).all { (x, y) ->
        x.id == y.id && x.hostname == y.hostname && x.port == y.port && x.username == y.username &&
            x.jumpHostId == y.jumpHostId && x.useKeys == y.useKeys && x.pubkeyId == y.pubkeyId &&
            x.compression == y.compression && x.ipVersion == y.ipVersion
    }

    private suspend fun connectChain(chain: List<Host>, interactive: Boolean): SSHClient {
        val host = chain.first()
        if (reconnect.isBlocked(host.id)) throw AgentConnectionNeedsUser("Explicit reconnect required for ${host.nickname}")
        entries[host.id]?.let { previous ->
            if (sameConnection(previous.chain, chain) && previous.client.isConnected && previous.client.isAuthenticated) {
                return previous.client
            }
            entries.remove(host.id)?.let(::closeEntry)
        }
        delay(reconnect.waiting(host.id, android.os.SystemClock.elapsedRealtime()))
        crypto.ensureReady()
        val parent = currentCoroutineContext()[Job]
        val client = SSHClient(DefaultConfig().apply { keepAliveProvider = KeepAliveProvider.KEEP_ALIVE })
        var tunnel: DirectConnection? = null
        connecting.add(client)
        try {
            client.connectTimeout = 20_000
            client.timeout = 30_000
            client.connection.keepAlive.keepAliveInterval = 30
            client.transport.setDisconnectListener { _, _ ->
                mutableCount.value = entries.values.count { it.client !== client && it.client.isConnected && it.client.isAuthenticated }
            }
            client.addHostKeyVerifier(object : HostKeyVerifier {
                override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = runBlocking(parent?.takeIf { it.isActive } ?: kotlin.coroutines.EmptyCoroutineContext) {
                    hosts.getHostKeyAlgorithmsForHost(host.id)
                }

                override fun verify(hostname: String, port: Int, key: PublicKey): Boolean = runBlocking(parent?.takeIf { it.isActive } ?: kotlin.coroutines.EmptyCoroutineContext) {
                    val encoded = encodeSshHostKey(key)
                        ?: throw AgentConnectionNeedsUser("Unsupported SSH host key type: ${key.algorithm}")
                    val wire = encoded.wire
                    val known = hosts.getKnownHostsForHost(host.id)
                    val algorithm = encoded.algorithm
                    if (known.isNotEmpty()) {
                        if (trustedAgentHostKey(known, algorithm, wire)) return@runBlocking true
                        reconnect.block(host.id)
                        throw AgentConnectionNeedsUser(context.getString(R.string.agent_host_key_changed, host.nickname))
                    }
                    if (!interactive) {
                        reconnect.block(host.id)
                        throw AgentConnectionNeedsUser("Host key confirmation required")
                    }
                    val fingerprint = "SHA256:" + Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(wire), Base64.NO_WRAP or Base64.NO_PADDING)
                    val answer = ask(host, "host_key", context.getString(R.string.agent_verify_host, host.hostname, host.port, fingerprint))
                    if (answer != "accept") {
                        reconnect.block(host.id)
                        throw AgentConnectionNeedsUser("Host key rejected")
                    }
                    hosts.saveKnownHost(host, host.hostname, host.port, algorithm, wire)
                    true
                }
            })
            if (host.compression) client.useCompression()
            if (chain.size > 1) {
                val jump = connectChain(chain.drop(1), interactive)
                tunnel = runInterruptible(io) { jump.newDirectConnection(host.hostname, host.port) }
                runInterruptible(io) { client.connectVia(tunnel) }
            } else {
                runInterruptible(io) {
                    if (host.ipVersion == "IPV4_AND_IPV6") {
                        client.connect(host.hostname, host.port)
                    } else {
                        val address = java.net.InetAddress.getAllByName(host.hostname).firstOrNull {
                            when (host.ipVersion) {
                                "IPV4_ONLY" -> it is java.net.Inet4Address
                                "IPV6_ONLY" -> it is java.net.Inet6Address
                                else -> false
                            }
                        } ?: throw IOException("No address matches the host IP preference")
                        client.connect(address, host.port)
                    }
                }
            }
            authenticate(client, host, interactive)
            client.socket?.soTimeout = 0
            currentCoroutineContext().ensureActive()
            entries[host.id] = Entry(chain, client, tunnel)
            reconnectHosts.add(host.id)
            try {
                addressStore.record(host.id, host.hostname, host.port, AgentAddressSource.CONNECTED)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // address bookkeeping must never tear down an established connection
            }
            mutableCount.value = entries.values.count { it.client.isConnected && it.client.isAuthenticated }
            reconnect.succeeded(host.id)
            return client
        } catch (e: CancellationException) {
            closeEntry(Entry(chain, client, tunnel))
            throw e
        } catch (e: Exception) {
            closeEntry(Entry(chain, client, tunnel))
            if (e is AgentConnectionNeedsUser || e is UserAuthException || chain.any { reconnect.isBlocked(it.id) }) {
                reconnect.block(host.id)
            } else {
                reconnect.failed(host.id, android.os.SystemClock.elapsedRealtime())
            }
            throw e
        } finally {
            connecting.remove(client)
        }
    }

    private suspend fun authenticate(client: SSHClient, host: Host, interactive: Boolean) {
        val candidates = when {
            !host.useKeys || host.pubkeyId == -2L -> emptyList()
            host.pubkeyId > 0 -> listOf(keys.getById(host.pubkeyId) ?: throw AgentConnectionNeedsUser("SSH key was deleted"))
            else -> keys.getAll().filter { it.startup && !it.encrypted && !it.isBiometric }
        }
        for (key in candidates) {
            if (key.isBiometric) throw AgentConnectionNeedsUser(context.getString(R.string.agent_biometric_terminal))
            if (!interactive && (key.encrypted || key.confirmation || automaticKeys[host.id] != key.id)) continue
            val passphrase = if (key.encrypted) {
                ask(host, "key_passphrase", context.getString(R.string.agent_key_passphrase, key.nickname), true)
            } else {
                null
            }
            val pair = try {
                PubkeyUtils.convertToKeyPair(key, passphrase) ?: throw AgentConnectionNeedsUser("SSH key could not be unlocked")
            } catch (_: PubkeyUtils.BadPasswordException) {
                throw AgentConnectionNeedsUser("SSH key could not be unlocked")
            }
            if (key.confirmation) {
                val confirmed = ask(host, "key_passphrase", context.getString(R.string.agent_confirm_key, key.nickname))
                if (confirmed != "accept") throw AgentConnectionNeedsUser("SSH key use cancelled")
            }
            try {
                runInterruptible(io) { client.authPublickey(host.username, client.loadKeys(pair)) }
                if (interactive && !key.encrypted && !key.confirmation) automaticKeys[host.id] = key.id
                return
            } catch (e: UserAuthException) {
                if (host.pubkeyId > 0) throw AgentConnectionNeedsUser("SSH key authentication was rejected")
            }
        }
        val saved = passwords.getPassword(host.id)
        if (saved != null) {
            try {
                runInterruptible(io) { client.authPassword(host.username, saved.toCharArray()) }
                return
            } catch (_: UserAuthException) {
                if (!interactive) throw AgentConnectionNeedsUser("Saved password was rejected")
            }
        }
        if (!interactive) throw AgentConnectionNeedsUser("Saved credentials are required to reconnect; retry explicitly")
        val password = ask(host, "password", context.getString(R.string.agent_backend_password, host.username, host.hostname), true)
        runInterruptible(io) { client.authPassword(host.username, password.toCharArray()) }
    }

    private suspend fun ask(host: Host, kind: String, message: String, secret: Boolean = false): String = try {
        challenges.ask(host.nickname.ifBlank { host.hostname }, kind, message, secret)
    } catch (e: AgentConnectionNeedsUser) {
        reconnect.block(host.id)
        throw e
    }

    suspend fun retainHosts(ids: Set<Long>) = withContext(io) {
        mutex.withLock {
            entries.values.filter { entry -> entry.chain.any { it.id !in ids } }.forEach { entry ->
                val id = entry.chain.first().id
                entries.remove(id)
                reconnectHosts.remove(id)
                automaticKeys.remove(id)
                closeEntry(entry)
            }
            reconnectHosts.retainAll(ids)
            automaticKeys.keys.retainAll(ids)
        }
    }

    fun interruptConnections() {
        connecting.toList().forEach { client ->
            try {
                client.close()
            } catch (_: IOException) { }
        }
        entries.values.toList().forEach(::closeEntry)
        entries.clear()
    }

    suspend fun disconnectAll() = withContext(io) {
        challenges.cancelAll()
        interruptConnections()
        mutex.withLock {
            entries.values.toList().forEach(::closeEntry)
            entries.clear()
            reconnect.clear()
            reconnectHosts.clear()
            automaticKeys.clear()
            mutableCount.value = 0
        }
    }

    private fun closeEntry(entry: Entry) {
        try {
            entry.client.close()
        } catch (_: IOException) { }
        try {
            entry.tunnel?.close()
        } catch (_: IOException) { }
        mutableCount.value = entries.values.count { it.client.isConnected && it.client.isAuthenticated }
    }
}

/** Accepts a key only when it matches a pinned known-host record; every other outcome fails the handshake. */
internal fun pinnedHostKeyVerifier(known: List<KnownHost>): HostKeyVerifier = object : HostKeyVerifier {
    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val encoded = encodeSshHostKey(key) ?: return false
        return trustedAgentHostKey(known, encoded.algorithm, encoded.wire)
    }
}
