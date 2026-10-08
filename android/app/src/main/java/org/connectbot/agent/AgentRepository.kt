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
import org.connectbot.R
import org.connectbot.data.HostRepository
import org.connectbot.data.entity.Host
import org.connectbot.di.CoroutineDispatchers
import org.connectbot.service.AgentConnectionService
import org.rmagentma.core.AcpDriver
import org.rmagentma.core.AcpSessionHandle
import org.rmagentma.core.AgentEvent
import org.rmagentma.core.AgentKind
import org.rmagentma.core.AgentSession
import org.rmagentma.core.RemoteScanner
import javax.inject.Inject
import javax.inject.Singleton

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

    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val switchMutex = Mutex()
    private val refreshMutex = Mutex()
    private val stateLock = Any()
    private val history = AgentEventHistory()
    private var generation = 0L
    private var handle: AcpSessionHandle? = null
    private var conversationScope: CoroutineScope? = null
    private var promptJob: Job? = null
    private var openingJob: Job? = null
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
                    pool.retainHosts(ids)
                    if (activeSession.value?.hostId?.let { it !in ids } == true) closeConversation()
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

    private suspend fun refreshIndex(hostId: Long?, interactive: Boolean) = refreshMutex.withLock {
        if (interactive) mutableErrors.value = emptyList()
        val allHosts = hostRepository.getSshHosts()
        mutableSessions.value = index.retainHosts(allHosts.map { it.id }.toSet())
        val selected = if (hostId == null) allHosts else allHosts.filter { it.id == hostId }
        for (host in selected) {
            if (interactive) pool.allowUserRetry(host.id)
            for (agent in AgentKind.entries) {
                currentCoroutineContext().ensureActive()
                try {
                    val remote = pool.hostSession(host.id, interactive)
                    val found = if (agent == AgentKind.OMP) {
                        AcpDriver(agent, scope, dispatchers.io).listSessions(remote)
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
        require(session.agent in setOf(AgentKind.KIMI, AgentKind.OPENCODE, AgentKind.OMP)) {
            context.getString(R.string.agent_use_terminal)
        }
        switchMutex.withLock {
            closeCurrent()
            val actualOpening = currentCoroutineContext()[Job]!!
            val token = synchronized(stateLock) {
                openingJob = actualOpening
                mutableActive.value = session
                mutableEvents.value = emptyList()
                history.clear()
                generation
            }
            val child = CoroutineScope(SupervisorJob(scope.coroutineContext[Job]) + dispatchers.io)
            synchronized(stateLock) { conversationScope = child }
            try {
                pool.allowUserRetry(session.hostId)
                currentCoroutineContext().ensureActive()
                val opened = AcpDriver(session.agent, child, dispatchers.io).open(pool.hostSession(session.hostId), sessionId, session.cwd)
                val accepted = synchronized(stateLock) {
                    if (generation == token) {
                        handle = opened
                        mutableActive.value = session.copy(sessionId = opened.sessionId)
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
                child.launch {
                    try {
                        opened.events.collect { event ->
                            synchronized(stateLock) {
                                if (generation == token) mutableEvents.value = history.append(event)
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        synchronized(stateLock) {
                            if (generation == token) report(e)
                        }
                    }
                }
            } catch (e: CancellationException) {
                child.cancel()
                synchronized(stateLock) {
                    if (generation == token) mutableActive.value = null
                }
                throw e
            } catch (e: Exception) {
                child.cancel()
                synchronized(stateLock) {
                    if (generation == token) {
                        mutableActive.value = null
                        report(e)
                    }
                }
            } finally {
                synchronized(stateLock) {
                    if (openingJob === actualOpening) openingJob = null
                }
            }
        }
    }

    suspend fun send(text: String) = operation {
        require(text.isNotBlank())
        val job = currentCoroutineContext()[Job]
        val (current, token) = synchronized(stateLock) {
            val current = handle ?: return@operation
            if (mutableBusy.value) return@operation
            mutableBusy.value = true
            promptJob = job
            mutableEvents.value = history.append(AgentEvent(type = "user", text = text))
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

    suspend fun cancel() {
        val (current, token) = synchronized(stateLock) { handle to generation }
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
        respond: suspend (AcpSessionHandle) -> Unit,
    ) {
        val snapshot = synchronized(stateLock) {
            if (mutableActive.value !== expectedSession || !history.isPending(expectedEvent, id, type)) {
                null
            } else {
                handle?.let { it to generation }
            }
        } ?: return
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
        synchronized(stateLock) {
            openingJob?.cancel()
            conversationScope?.cancel()
        }
        switchMutex.withLock { closeCurrent() }
    }

    private suspend fun closeCurrent() {
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
        synchronized(stateLock) {
            openingJob?.cancel()
            conversationScope?.cancel()
        }
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
        return pending.await()
    }

    private fun report(error: Exception, prefix: String = "") {
        val message = listOf(prefix, error.message ?: error.javaClass.simpleName).filter { it.isNotBlank() }.joinToString(": ")
        mutableErrors.update { (it + message).takeLast(100) }
    }
}
