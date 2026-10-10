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
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.connectbot.data.HostRepository
import org.connectbot.data.entity.Host
import org.connectbot.di.CoroutineDispatchers
import org.connectbot.service.AgentConnectionService
import org.rmagentma.core.AcpDriver
import org.rmagentma.core.AcpSessionHandle
import org.rmagentma.core.AgentAvailability
import org.rmagentma.core.AgentEvent
import org.rmagentma.core.AgentKind
import org.rmagentma.core.AgentSession
import org.rmagentma.core.AgentSessionHandle
import org.rmagentma.core.RemoteAgentProbe
import org.rmagentma.core.RemoteScanner
import org.rmagentma.core.ZcodeDriver
import org.rmagentma.core.ZcodeLaunchConfig
import org.rmagentma.core.ZcodeSessionHandle
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

enum class AgentConversationState {
    Loading,
    Ready,
    Failed,
    Closed,
}

@Singleton
class AgentRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val hostRepository: HostRepository,
    private val pool: AgentSshPool,
    private val index: AgentIndexStore,
    private val challenges: AgentChallenges,
    private val dispatchers: CoroutineDispatchers,
) {
    val hosts: Flow<List<Host>> = hostRepository.observeSshHosts()
    private val mutableSessions = MutableStateFlow<List<AgentSession>>(emptyList())
    val sessions: StateFlow<List<AgentSession>> = mutableSessions.asStateFlow()
    private val mutableEvents = MutableStateFlow<List<AgentEvent>>(emptyList())
    val events: StateFlow<List<AgentEvent>> = mutableEvents.asStateFlow()
    private val mutableBusy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = mutableBusy.asStateFlow()
    private val mutableErrors = MutableStateFlow<List<String>>(emptyList())
    val errors: StateFlow<List<String>> = mutableErrors.asStateFlow()
    val challenge: StateFlow<AgentChallenge?> = challenges.challenge
    private val mutableActive = MutableStateFlow<AgentSession?>(null)
    val activeSession: StateFlow<AgentSession?> = mutableActive.asStateFlow()
    private val mutableSelected = MutableStateFlow<AgentSession?>(null)
    val selectedSession: StateFlow<AgentSession?> = mutableSelected.asStateFlow()
    private val mutableConversationState = MutableStateFlow(AgentConversationState.Closed)
    val conversationState: StateFlow<AgentConversationState> = mutableConversationState.asStateFlow()
    private val mutableHasEarlier = MutableStateFlow(false)
    val hasEarlier: StateFlow<Boolean> = mutableHasEarlier.asStateFlow()
    private val mutableHasLatest = MutableStateFlow(false)
    val hasLatest: StateFlow<Boolean> = mutableHasLatest.asStateFlow()
    private val mutableAvailability = MutableStateFlow<List<AgentAvailability>>(emptyList())
    val availability: StateFlow<List<AgentAvailability>> = mutableAvailability.asStateFlow()
    private val probeMutex = Mutex()

    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val switchMutex = Mutex()
    private val refreshMutex = Mutex()
    private val stateLock = Any()
    private val history = AgentEventHistory(directory = java.io.File(context.noBackupFilesDir, "agent-history"), cleanupExisting = true)
    private var generation = 0L
    private var handle: AgentSessionHandle? = null

    @Volatile
    private var conversationScope: CoroutineScope? = null

    @Volatile
    private var promptJob: Job? = null

    @Volatile
    private var openingJob: Job? = null

    @Volatile
    private var historyReadJob: Job? = null
    private val jobs = AgentJobs(scope)
    val operationCount: StateFlow<Int> = jobs.count
    private val foregroundLock = Mutex()
    private val maintenanceLock = Any()
    private var maintenance: Job? = null

    init {
        scope.launch {
            try {
                mutableSessions.value = index.read()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report(e)
            }
            hosts.collect { currentHosts ->
                try {
                    val ids = currentHosts.map { it.id }.toSet()
                    refreshMutex.withLock { mutableSessions.value = index.retainHosts(ids) }
                    probeMutex.withLock { mutableAvailability.update { rows -> rows.filter { it.hostId in ids } } }
                    pool.retainHosts(ids)
                    if (selectedSession.value?.hostId?.let { it !in ids } == true) closeConversation()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    report(e)
                }
            }
        }
    }

    fun respondChallenge(id: String, response: String?) = challenges.respond(id, response)

    suspend fun refresh(hostId: Long? = null) = operation { refreshIndex(hostId, interactive = true) }

    suspend fun clearIndex() {
        jobs.submit {
            refreshMutex.withLock {
                try {
                    mutableSessions.value = index.clear()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    report(e)
                    throw e
                }
            }
        }.await()
    }

    private suspend fun probeHost(hostId: Long, interactive: Boolean, force: Boolean): List<AgentAvailability> = probeMutex.withLock {
        val cached = mutableAvailability.value.filter { it.hostId == hostId }
        if (!force && cached.size == AgentKind.entries.size) return@withLock cached
        val result = try {
            RemoteAgentProbe(dispatchers.io).probe(pool.hostSession(hostId, interactive))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AgentKind.entries.map { kind ->
                val previous = cached.firstOrNull { it.kind == kind }
                AgentAvailability(hostId, kind, previous?.executable.orEmpty(), false, describe(e), previous?.version.orEmpty(), previous?.capabilities)
            }
        }
        mutableAvailability.update { previous -> previous.filterNot { it.hostId == hostId } + result }
        return@withLock result
    }

    private suspend fun refreshIndex(hostId: Long?, interactive: Boolean) = refreshMutex.withLock {
        if (interactive) mutableErrors.value = emptyList()
        val allHosts = hostRepository.getSshHosts()
        mutableSessions.value = index.retainHosts(allHosts.map { it.id }.toSet())
        val selected = if (hostId == null) allHosts else allHosts.filter { it.id == hostId }
        for (host in selected) {
            if (interactive) pool.allowUserRetry(host.id)
            val available = probeHost(host.id, interactive, force = true)
            for (agent in AgentKind.entries) {
                currentCoroutineContext().ensureActive()
                try {
                    val remote = pool.hostSession(host.id, interactive)
                    val found = if (agent == AgentKind.OMP) {
                        val runtime = available.single { it.kind == agent }
                        if (!runtime.available) continue
                        AcpDriver(agent, scope, dispatchers.io, runtime.executable.takeIf { it.isNotBlank() }).listSessions(remote)
                    } else {
                        val scan = RemoteScanner(dispatchers.io).scan(remote, setOf(agent), 200)
                        if (scan.errors.isNotEmpty()) {
                            mutableErrors.update { (it + scan.errors.map { error -> "${host.nickname} / ${agent.wireName}: $error" }).takeLast(100) }
                            continue
                        }
                        scan.sessions
                    }
                    mutableSessions.value = index.replace(host.id, agent, found)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    report(e, "${host.nickname} / ${agent.wireName}")
                    if (pool.needsUserRetry(host.id)) break
                }
            }
        }
    }

    suspend fun open(session: AgentSession) = openConversation(session, session.sessionId)

    suspend fun newSession(hostId: Long, agent: AgentKind, cwd: String) = openConversation(
        AgentSession(hostId = hostId, agent = agent, sessionId = "", cwd = cwd),
        null,
    )

    private suspend fun openConversation(session: AgentSession, sessionId: String?) = operation {
        val started = System.nanoTime()
        fun trace(stage: String) = Timber.tag("AgentRepository").d(
            "hostId=%d agent=%s resume=%s stage=%s elapsedMs=%d",
            session.hostId,
            session.agent.wireName,
            sessionId != null,
            stage,
            (System.nanoTime() - started) / 1_000_000,
        )
        trace("switch_wait")
        switchMutex.withLock {
            trace("close_start")
            closeCurrent()
            trace("close_done")
            val actualOpening = currentCoroutineContext()[Job]!!
            val token = synchronized(stateLock) {
                openingJob = actualOpening
                mutableSelected.value = session
                mutableActive.value = null
                mutableConversationState.value = AgentConversationState.Loading
                mutableEvents.value = emptyList()
                mutableHasEarlier.value = false
                mutableHasLatest.value = false
                generation
            }
            val child = CoroutineScope(SupervisorJob(scope.coroutineContext[Job]) + dispatchers.io)
            synchronized(stateLock) { conversationScope = child }
            try {
                synchronized(stateLock) { history.clear() }
                pool.allowUserRetry(session.hostId)
                currentCoroutineContext().ensureActive()
                trace("probe_start")
                val runtime = probeHost(session.hostId, interactive = true, force = false).single { it.kind == session.agent }
                trace("probe_end")
                if (!runtime.available) throw java.io.IOException("${session.agent.wireName} unavailable: ${runtime.error.ifBlank { "agent startup probe failed" }}")
                val remote = pool.hostSession(session.hostId)
                trace("prepare_start")
                val opened: AgentSessionHandle = when (session.agent) {
                    AgentKind.ZCODE -> ZcodeDriver(
                        child,
                        dispatchers.io,
                        ZcodeLaunchConfig(executable = runtime.executable.takeIf { it.isNotBlank() } ?: "~/.zcode/server/agents/glm/zcode-agent"),
                    ).prepare(remote, sessionId, session.cwd, workspaceIdentity = null, replayHistory = true)

                    else -> AcpDriver(session.agent, child, dispatchers.io, runtime.executable.takeIf { it.isNotBlank() }).prepare(remote, sessionId, session.cwd)
                }
                trace("prepare_done")
                val accepted = synchronized(stateLock) {
                    if (generation == token) {
                        handle = opened
                        true
                    } else {
                        false
                    }
                }
                if (!accepted) {
                    opened.close()
                    child.cancel()
                    return@withLock
                }
                child.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    try {
                        opened.events.collect { event ->
                            val collectingContext = currentCoroutineContext()
                            collectingContext.ensureActive()
                            synchronized(stateLock) {
                                if (generation == token) {
                                    mutableEvents.value = history.append(event) { collectingContext.ensureActive() }
                                    mutableHasEarlier.value = history.hasEarlier
                                }
                            }
                        }
                        failConversation(token, java.io.EOFException("Agent connection closed"))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failConversation(token, e)
                        opened.close()
                    }
                }
                trace("consumer_started")
                trace("load_start")
                when (opened) {
                    is AcpSessionHandle -> opened.load()
                    is ZcodeSessionHandle -> opened.load()
                    else -> error("Unsupported agent session handle")
                }
                synchronized(stateLock) {
                    if (generation == token && mutableConversationState.value == AgentConversationState.Loading) {
                        val loaded = session.copy(sessionId = opened.sessionId)
                        mutableSelected.value = loaded
                        mutableActive.value = loaded
                        mutableConversationState.value = AgentConversationState.Ready
                        trace("open_ready")
                    }
                }
            } catch (e: CancellationException) {
                child.cancel()
                synchronized(stateLock) {
                    if (generation == token) {
                        mutableActive.value = null
                        handle = null
                        if (mutableConversationState.value != AgentConversationState.Failed) {
                            mutableConversationState.value = AgentConversationState.Closed
                        }
                    }
                }
                throw e
            } catch (e: Exception) {
                child.cancel()
                failConversation(token, e)
            } finally {
                synchronized(stateLock) {
                    if (openingJob === actualOpening) openingJob = null
                }
            }
        }
    }

    private fun failConversation(token: Long, error: Exception) = synchronized(stateLock) {
        if (generation == token) {
            mutableActive.value = null
            mutableConversationState.value = AgentConversationState.Failed
            mutableBusy.value = false
            promptJob?.cancel()
            val failed = handle
            handle = null
            conversationScope?.cancel()
            scope.launch { failed?.close() }
            report(error)
        }
    }

    suspend fun loadEarlier() = loadHistoryPage(latest = false)

    suspend fun loadLatest() = loadHistoryPage(latest = true)

    private suspend fun loadHistoryPage(latest: Boolean) = withContext(dispatchers.io) {
        val readingContext = currentCoroutineContext()
        historyReadJob = readingContext[Job]
        try {
            synchronized(stateLock) {
                try {
                    mutableEvents.value = if (latest) {
                        history.loadLatest { readingContext.ensureActive() }
                    } else {
                        history.loadEarlier { readingContext.ensureActive() }
                    }
                    mutableHasEarlier.value = history.hasEarlier
                    mutableHasLatest.value = history.hasLatest
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failConversation(generation, e)
                    throw e
                }
            }
        } finally {
            if (historyReadJob === readingContext[Job]) historyReadJob = null
        }
    }

    suspend fun fullText(event: AgentEvent): AgentEvent = withContext(dispatchers.io) {
        val readingContext = currentCoroutineContext()
        historyReadJob = readingContext[Job]
        try {
            synchronized(stateLock) {
                require(mutableEvents.value.any { it === event }) { "Stale history item" }
                try {
                    history.fullText(event) { readingContext.ensureActive() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failConversation(generation, e)
                    throw e
                }
            }
        } finally {
            if (historyReadJob === readingContext[Job]) historyReadJob = null
        }
    }

    suspend fun send(text: String) = operation {
        require(text.isNotBlank())
        val job = currentCoroutineContext()[Job]
        val (current, token) = synchronized(stateLock) {
            val current = handle ?: return@operation
            if (mutableBusy.value || mutableConversationState.value != AgentConversationState.Ready) return@operation
            try {
                val appended = history.append(AgentEvent(type = "user", text = text, id = "local-${java.util.UUID.randomUUID()}"))
                mutableEvents.value = if (history.hasLatest) history.loadLatest() else appended
                mutableHasEarlier.value = history.hasEarlier
                mutableHasLatest.value = history.hasLatest
            } catch (e: Exception) {
                failConversation(generation, e)
                throw e
            }
            mutableBusy.value = true
            promptJob = job
            current to generation
        }
        try {
            current.send(text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            synchronized(stateLock) { if (generation == token) report(e) }
        } finally {
            synchronized(stateLock) {
                if (generation == token) {
                    mutableBusy.value = false
                    promptJob = null
                }
            }
        }
    }

    suspend fun cancel() = withContext(dispatchers.io) {
        openingJob?.cancel()
        historyReadJob?.cancel()
        promptJob?.cancel()
        val (current, token) = synchronized(stateLock) {
            if (mutableConversationState.value == AgentConversationState.Loading) {
                openingJob?.cancel()
                conversationScope?.cancel()
                mutableConversationState.value = AgentConversationState.Closed
                mutableActive.value = null
                return@withContext
            }
            promptJob?.cancel()
            handle to generation
        }
        jobs.submit {
            try {
                current?.cancel()
                synchronized(stateLock) {
                    if (generation == token) mutableEvents.value = history.resolveAll()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                synchronized(stateLock) { if (generation == token) report(e) }
                throw e
            }
        }.await()
    }

    suspend fun respondPermission(expectedSession: AgentSession, expectedEvent: AgentEvent, id: String, option: String) = respondInteraction(expectedSession, expectedEvent, id, "permission") { current -> current.respondPermission(id, option) }

    suspend fun respondQuestion(expectedSession: AgentSession, expectedEvent: AgentEvent, id: String, answer: String) = respondInteraction(expectedSession, expectedEvent, id, "question") { current -> current.respondQuestion(id, answer) }

    private suspend fun respondInteraction(
        expectedSession: AgentSession,
        expectedEvent: AgentEvent,
        id: String,
        type: String,
        respond: suspend (AgentSessionHandle) -> Unit,
    ) = withContext(dispatchers.io) {
        val snapshot = synchronized(stateLock) {
            if (mutableSelected.value !== expectedSession || mutableConversationState.value !in setOf(AgentConversationState.Loading, AgentConversationState.Ready) || !history.isPending(expectedEvent, id, type)) {
                null
            } else {
                handle?.let { it to generation }
            }
        } ?: return@withContext
        jobs.submit {
            try {
                respond(snapshot.first)
                synchronized(stateLock) {
                    if (generation == snapshot.second) mutableEvents.value = history.resolve(expectedEvent)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                synchronized(stateLock) { if (generation == snapshot.second) report(e) }
                throw e
            }
        }.await()
    }

    suspend fun closeConversation() = withContext(dispatchers.io) {
        openingJob?.cancel()
        historyReadJob?.cancel()
        conversationScope?.cancel()
        switchMutex.withLock { closeCurrent() }
    }

    private suspend fun closeCurrent() {
        historyReadJob?.cancel()
        promptJob?.cancel()
        conversationScope?.cancel()
        val previous = synchronized(stateLock) {
            generation++
            promptJob?.cancel()
            promptJob = null
            conversationScope?.cancel()
            conversationScope = null
            val previous = handle
            handle = null
            mutableBusy.value = false
            mutableActive.value = null
            mutableConversationState.value = AgentConversationState.Closed
            previous
        }
        try {
            previous?.close()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report(e)
        }
    }

    suspend fun disconnectAll() {
        stopOperations()
        scope.async {
            pool.disconnectAll()
            closeConversation()
            context.stopService(Intent(context, AgentConnectionService::class.java))
        }.await()
    }

    fun onServiceDestroyed() {
        stopOperations()
        scope.launch {
            pool.disconnectAll()
            closeConversation()
        }
    }

    private fun stopOperations() {
        synchronized(maintenanceLock) {
            maintenance?.cancel()
            maintenance = null
        }
        challenges.cancelAll()
        jobs.cancelAll()
        openingJob?.cancel()
        historyReadJob?.cancel()
        conversationScope?.cancel()
    }

    private fun startMaintenance() = synchronized(maintenanceLock) {
        if (maintenance?.isActive == true) return@synchronized
        maintenance = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(1000)
                try {
                    pool.reconnectDisconnected().forEach { hostId -> refreshIndex(hostId, interactive = false) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    report(e)
                }
            }
        }
    }

    suspend fun stopServiceIfIdle(stop: () -> Unit) = foregroundLock.withLock {
        if (operationCount.value != 0 || pool.connectionCount.value != 0 || pool.hasReconnectWork()) return@withLock
        stop()
    }

    private suspend fun <T> operation(block: suspend () -> T): T {
        val pending = foregroundLock.withLock {
            val submitted = jobs.submit(start = false) {
                startMaintenance()
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    report(e)
                    throw e
                }
            }
            try {
                ContextCompat.startForegroundService(context, Intent(context, AgentConnectionService::class.java))
                submitted.start()
                submitted
            } catch (e: Exception) {
                submitted.cancel()
                if (e is CancellationException) throw e
                report(e)
                throw e
            }
        }
        return jobs.awaitCancellable(pending)
    }

    private fun report(error: Exception, prefix: String = "") {
        val message = listOf(prefix, describe(error)).filter { it.isNotBlank() }.joinToString(": ")
        Timber.w(error, "Agent operation failed: %s", message)
        mutableErrors.update { (it + message).takeLast(100) }
    }

    private fun describe(error: Throwable): String {
        val chain = generateSequence(error) { it.cause }.take(4).toList()
        return chain.joinToString(" <- ") { throwable ->
            throwable.message?.takeIf { it.isNotBlank() } ?: throwable.javaClass.simpleName
        }
    }
}
