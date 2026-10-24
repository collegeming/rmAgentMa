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
import kotlinx.coroutines.flow.SharedFlow
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
import org.rmagentma.core.AgentSessionConfig
import org.rmagentma.core.AgentSessionHandle
import org.rmagentma.core.DirectoryEntryKind
import org.rmagentma.core.DirectoryListing
import org.rmagentma.core.DirectoryListingProblem
import org.rmagentma.core.HostSession
import org.rmagentma.core.RemoteAgentProbe
import org.rmagentma.core.RemoteDirectoryListing
import org.rmagentma.core.RemoteScanner
import org.rmagentma.core.RemoteTarget
import org.rmagentma.core.RemoteTranscriptSearch
import org.rmagentma.core.RemoteWorkingDirectoryCheck
import org.rmagentma.core.TRANSCRIPT_SEARCH_MAX_FILES
import org.rmagentma.core.TRANSCRIPT_SEARCH_MAX_QUERY
import org.rmagentma.core.TranscriptSearchProblem
import org.rmagentma.core.TranscriptSearchTarget
import org.rmagentma.core.ZcodeBusyException
import org.rmagentma.core.ZcodeDriver
import org.rmagentma.core.ZcodeLaunchConfig
import org.rmagentma.core.ZcodeSessionHandle
import org.rmagentma.core.remoteTargetOf
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Sessions one remote scan reads per agent, and the same ceiling the index snapshot keeps per host and agent. */
internal const val AGENT_SESSION_PAGE_LIMIT = 200

enum class AgentConversationState {
    Loading,
    Ready,
    Failed,
    Closed,
}

/** What the open conversation can say about its Agent configuration options. */
sealed interface AgentConfigAccess {
    /** The Agent's own snapshot of the open conversation, as it reports it now. */
    data class Available(val config: AgentSessionConfig) : AgentConfigAccess

    /**
     * A write the Agent answered without reporting a new snapshot: it was delivered, nothing confirmed it.
     * [config] is the conversation's configuration as it stands, or null once that conversation stopped being open.
     */
    data class Unconfirmed(val config: AgentSessionConfig?) : AgentConfigAccess

    /** The conversation is open on a backend that has no configuration surface this product can read. */
    data object Unsupported : AgentConfigAccess

    /** No conversation is open, or the one this call was made for stopped being open. */
    data object Closed : AgentConfigAccess
}

/** A configuration write the Agent refused because another update was still in flight; it can be retried. */
class AgentConfigBusyException(val code: Int, message: String) : java.io.IOException(message)

// shortcut: agent-core reports a delivered write that came back without a snapshot as exactly this IOException, so
// the message is the only signal that separates it from a rejection or a broken channel; upgrade to a typed result
// if the driver ever grows one.
private const val UNREPORTED_CONFIG_WRITE = "Agent did not report the updated configuration"

@Singleton
class AgentRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val hostRepository: HostRepository,
    private val pool: AgentSshPool,
    private val index: AgentIndexStore,
    private val challenges: AgentChallenges,
    private val linkStates: AgentLinkStateStore,
    private val detections: AgentDetectionStore,
    private val dispatchers: CoroutineDispatchers,
) {
    val hosts: Flow<List<Host>> = hostRepository.observeSshHosts()
    val addressChanges: SharedFlow<HostAddressChange> = pool.addressChanges
    private val mutableSessions = MutableStateFlow<List<AgentSession>>(emptyList())
    val sessions: StateFlow<List<AgentSession>> = mutableSessions.asStateFlow()
    private val mutableEvents = MutableStateFlow<List<AgentEvent>>(emptyList())
    val events: StateFlow<List<AgentEvent>> = mutableEvents.asStateFlow()
    private val mutableBusy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = mutableBusy.asStateFlow()
    private val mutableErrors = MutableStateFlow<List<String>>(emptyList())
    val errors: StateFlow<List<String>> = mutableErrors.asStateFlow()

    /** Host/agent pairs whose current rows came from a successful remote read; every other row is cache-only. */
    private val mutableScannedSources = MutableStateFlow<Set<Pair<Long, AgentKind>>>(emptySet())
    val scannedSources: StateFlow<Set<Pair<Long, AgentKind>>> = mutableScannedSources.asStateFlow()
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
    private val mutableRefreshingHosts = MutableStateFlow<Set<Long>>(emptySet())
    val refreshingHosts: StateFlow<Set<Long>> = mutableRefreshingHosts.asStateFlow()
    private val mutableLinkRecords = MutableStateFlow<List<AgentLinkRecord>>(emptyList())
    val linkRecords: StateFlow<List<AgentLinkRecord>> = mutableLinkRecords.asStateFlow()
    private val mutableLiveLinkStates = MutableStateFlow<Map<Long, AgentLinkState>>(emptyMap())
    val liveLinkStates: StateFlow<Map<Long, AgentLinkState>> = mutableLiveLinkStates.asStateFlow()
    private val probeMutex = Mutex()

    /** The Agent target each host's rows were read on, so a retargeted host keeps no rows from the other context. */
    private val probedHostTargets = mutableMapOf<Long, String>()

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
            try {
                mutableLinkRecords.value = linkStates.all()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // An unreadable snapshot holds no state, so every host stays unknown until the pool reports a fact.
                Timber.w(e, "Agent link state is unreadable")
            }
            hosts.collect { currentHosts ->
                try {
                    val ids = currentHosts.map { it.id }.toSet()
                    refreshMutex.withLock { mutableSessions.value = index.retainHosts(ids) }
                    probeMutex.withLock {
                        forgetRetargetedHosts(currentHosts)
                        mutableAvailability.update { rows -> rows.filter { it.hostId in ids } }
                        seedDetectedAgents(currentHosts)
                    }
                    pool.retainHosts(ids)
                    forgetRemovedHostLinkStates(ids)
                    forgetRemovedHostDetections(ids)
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
        val target = hostTarget(hostId)
        val result = try {
            RemoteAgentProbe(dispatchers.io).probe(pool.hostSession(hostId, interactive), target = target)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AgentKind.entries.map { kind ->
                val previous = cached.firstOrNull { it.kind == kind }
                AgentAvailability(hostId, kind, previous?.executable.orEmpty(), false, describe(e), previous?.version.orEmpty(), previous?.capabilities)
            }
        }
        mutableAvailability.update { previous -> previous.filterNot { it.hostId == hostId } + result }
        probedHostTargets[hostId] = target.id
        recordDetections(hostId, target, result)
        return@withLock result
    }

    /**
     * Drops the rows of a host whose Agent target changed: a path detected in the previous shell context does not
     * hold in the new one, and the host reads as unknown again until it is probed there.
     */
    private fun forgetRetargetedHosts(currentHosts: List<Host>) {
        val targets = currentHosts.associate { it.id to remoteTargetOf(it.osType, it.agentTarget).id }
        val retargeted = probedHostTargets.filter { (hostId, probed) -> targets[hostId]?.let { it != probed } == true }.keys
        if (retargeted.isEmpty()) return
        retargeted.forEach { probedHostTargets.remove(it) }
        mutableAvailability.update { rows -> rows.filterNot { it.hostId in retargeted } }
    }

    /** The shell context a host row describes; a row that vanished keeps the POSIX default it was read with. */
    private suspend fun hostTarget(hostId: Long): RemoteTarget = hostRepository.findHostById(hostId)?.let { remoteTargetOf(it.osType, it.agentTarget) } ?: RemoteTarget.Posix

    /**
     * Writes what the probe just verified into this host's Agent configuration: every row that named an executable
     * is kept for the target it was found on, and the rows of this host stand for no older detection afterwards.
     */
    private suspend fun recordDetections(hostId: Long, target: RemoteTarget, rows: List<AgentAvailability>) {
        try {
            val now = System.currentTimeMillis()
            detections.replace(
                hostId,
                rows.filter { it.executable.isNotBlank() }.map {
                    AgentDetection(hostId, target.id, it.kind, it.executable, it.version, it.capabilities, now)
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A detection that cannot be written still answered this probe; the next probe writes it again.
            Timber.w(e, "Agent detection is not persisted")
        }
    }

    /**
     * Fills in the detections of hosts this process has not probed yet, so a configured connection keeps its
     * detected path across a restart. A row that arrived from a probe is never replaced by a stored one.
     */
    private suspend fun seedDetectedAgents(currentHosts: List<Host>) {
        val stored = try {
            detections.all()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Agent detections are unreadable")
            return
        }
        if (stored.isEmpty()) return
        val targets = currentHosts.associate { it.id to remoteTargetOf(it.osType, it.agentTarget).id }
        val seeded = stored.filter { targets[it.hostId] == it.target }
        if (seeded.isEmpty()) return
        mutableAvailability.update { previous ->
            val known = previous.map { it.hostId to it.kind }.toSet()
            previous + seeded.filterNot { (it.hostId to it.kind) in known }
                .map { AgentAvailability(it.hostId, it.kind, it.executable, true, "", it.version, it.capabilities) }
        }
        seeded.forEach { probedHostTargets.putIfAbsent(it.hostId, it.target) }
    }

    /**
     * The row that opens a conversation: a probe verdict of this process wins, a detection from an earlier run on
     * the same target opens the session without one, and a host with neither is probed now. No path is invented,
     * so a host that holds no installation fails as not installed instead of starting with a guessed path.
     */
    private suspend fun runtimeFor(hostId: Long, kind: AgentKind): AgentAvailability {
        mutableAvailability.value.firstOrNull { it.hostId == hostId && it.kind == kind }?.let { return it }
        return probeHost(hostId, interactive = true, force = false).single { it.kind == kind }
    }

    private suspend fun refreshIndex(hostId: Long?, interactive: Boolean) = refreshMutex.withLock {
        if (interactive) mutableErrors.value = emptyList()
        val allHosts = hostRepository.getSshHosts()
        mutableSessions.value = index.retainHosts(allHosts.map { it.id }.toSet())
        val selected = if (hostId == null) allHosts else allHosts.filter { it.id == hostId }
        for (host in selected) {
            if (interactive) pool.allowUserRetry(host.id)
            // Refreshing is a real state: it holds until this host's probe and scan finished, also when they fail.
            mutableRefreshingHosts.update { it + host.id }
            try {
                val available = probeHost(host.id, interactive, force = true)
                // A target the app cannot enter holds no readable session list either, so the refresh stops at the
                // probe verdict instead of failing once per Agent.
                if (!remoteTargetOf(host.osType, host.agentTarget).supported) continue
                for (agent in AgentKind.entries) {
                    currentCoroutineContext().ensureActive()
                    // Only a completed read promotes this pair to remote-loaded; any path out of the try leaves the
                    // rows on screen as cache-only, so a failed scan can never be reported as fresh remote data.
                    val source = host.id to agent
                    mutableScannedSources.update { it - source }
                    try {
                        val remote = pool.hostSession(host.id, interactive)
                        val found = if (agent == AgentKind.OMP) {
                            val runtime = available.single { it.kind == agent }
                            if (!runtime.available) continue
                            AcpDriver(agent, scope, dispatchers.io, runtime.executable.takeIf { it.isNotBlank() }).listSessions(remote)
                        } else {
                            val scan = RemoteScanner(dispatchers.io).scan(remote, setOf(agent), AGENT_SESSION_PAGE_LIMIT)
                            if (scan.errors.isNotEmpty()) {
                                mutableErrors.update { (it + scan.errors.map { error -> "${host.nickname} / ${agent.wireName}: $error" }).takeLast(100) }
                                continue
                            }
                            scan.sessions
                        }
                        mutableSessions.value = index.replace(host.id, agent, found)
                        mutableScannedSources.update { it + source }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        report(e, "${host.nickname} / ${agent.wireName}")
                        if (pool.needsUserRetry(host.id)) break
                    }
                }
            } finally {
                mutableRefreshingHosts.update { it - host.id }
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
                val runtime = runtimeFor(session.hostId, session.agent)
                trace("probe_end")
                if (!runtime.available) throw java.io.IOException("${session.agent.wireName} unavailable: ${runtime.error.ifBlank { "agent startup probe failed" }}")
                // A verified path is what the launch uses. A blank one means the probe named no installation, and no
                // default stands in for it: the session fails here instead of starting an executable nobody found.
                val executable = runtime.executable.takeIf { it.isNotBlank() }
                    ?: throw java.io.IOException("${session.agent.wireName} executable not found: the probe detected no installation")
                val remote = pool.hostSession(session.hostId)
                // The directory is checked before an Agent process exists for this session, on the same host session
                // the Agent is launched on: a directory the host cannot use fails as itself, leaves the cached probe
                // verdict untouched and opens no half-ready handle, so the user corrects the path and retries.
                trace("cwd_check_start")
                RemoteWorkingDirectoryCheck(dispatchers.io).verify(remote, session.cwd)
                trace("cwd_check_end")
                trace("prepare_start")
                val opened: AgentSessionHandle = when (session.agent) {
                    AgentKind.ZCODE -> ZcodeDriver(
                        child,
                        dispatchers.io,
                        ZcodeLaunchConfig(executable = executable),
                    ).prepare(remote, sessionId, session.cwd, workspaceIdentity = null, replayHistory = true)

                    else -> AcpDriver(session.agent, child, dispatchers.io, executable).prepare(remote, sessionId, session.cwd)
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

    /**
     * One bounded, read-only content search across the transcripts of [sessions]. Every candidate file is named by
     * listing the host first, never by guessing a path, so a session whose transcript this build cannot point at is
     * reported as unread instead of being answered as a history that holds no such text. Each host answers for its
     * own rows: a host that could not be reached keeps its own error, and a search the Agent refused keeps its own
     * code, while the hits that did arrive stay usable.
     */
    suspend fun searchTranscripts(query: String, sessions: List<AgentSession>): AgentTranscriptOutcome = operation {
        val text = query.trim()
        // A query the search would refuse is answered as that refusal before any host is read, so a typo costs no
        // remote round trip; the search itself still validates the same rule on the wire.
        if (text.isEmpty()) {
            return@operation AgentTranscriptOutcome(query = text, problems = listOf(TranscriptSearchProblem.QUERY_EMPTY))
        }
        if (text.length > TRANSCRIPT_SEARCH_MAX_QUERY || text.any { it.code < 32 || it.code == 127 }) {
            return@operation AgentTranscriptOutcome(query = text, problems = listOf(TranscriptSearchProblem.QUERY_INVALID))
        }
        val hits = mutableListOf<AgentTranscriptHit>()
        val problems = mutableListOf<TranscriptSearchProblem>()
        val failures = mutableListOf<AgentTranscriptFailure>()
        val skips = mutableListOf<AgentTranscriptSkip>()
        val roots = mutableListOf<String>()
        var truncated = false
        var searched = 0
        val selection = sessions.distinctBy { Triple(it.hostId, it.agent, it.sessionId) }.groupBy { it.hostId }
        for ((hostId, selected) in selection) {
            val readable = mutableListOf<AgentSession>()
            selected.forEach { session ->
                val reason = agentTranscriptSkipReason(session.agent)
                when {
                    reason != null -> skips += AgentTranscriptSkip(session, reason)
                    readable.size < TRANSCRIPT_SEARCH_MAX_FILES -> readable += session
                    else -> skips += AgentTranscriptSkip(session, AgentTranscriptSkipReason.OVER_LIMIT)
                }
            }
            if (readable.isEmpty()) continue
            try {
                val host = pool.hostSession(hostId)
                val lookup = transcriptRoot(host, readable.first().cwd)
                val root = lookup.root
                if (root == null) {
                    val problem = lookup.problem
                    if (problem != null) {
                        failures += AgentTranscriptFailure(hostId, problem.code)
                    } else {
                        readable.forEach { skips += AgentTranscriptSkip(it, AgentTranscriptSkipReason.ROOT_NOT_FOUND) }
                    }
                    continue
                }
                roots += root
                val directories = sessionDirectories(host, root, readable.map { it.sessionId }.toSet())
                val targets = mutableListOf<TranscriptSearchTarget>()
                for (session in readable) {
                    val directory = directories[session.sessionId]
                    if (directory == null) {
                        skips += AgentTranscriptSkip(session, AgentTranscriptSkipReason.SESSION_DIRECTORY_NOT_FOUND)
                        continue
                    }
                    val file = newestTranscriptFile(transcriptFiles(host, directory))
                    if (file == null) {
                        skips += AgentTranscriptSkip(session, AgentTranscriptSkipReason.FILE_NOT_FOUND)
                        continue
                    }
                    targets += TranscriptSearchTarget(session.agent, session.sessionId, directory, listOf(file))
                }
                if (targets.isEmpty()) continue
                val result = RemoteTranscriptSearch(dispatchers.io).search(host, text, targets)
                hits += result.hits.map { AgentTranscriptHit(hostId, it) }
                truncated = truncated || result.truncated
                result.problem?.let { problems += it }
                // A search that answered with a problem read nothing: those sessions keep no verdict of their own.
                if (result.problem == null) searched += targets.size
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures += AgentTranscriptFailure(hostId, describe(e))
            }
        }
        AgentTranscriptOutcome(
            query = text,
            hits = hits,
            truncated = truncated,
            problems = problems,
            failures = failures,
            skips = skips,
            searchedSessions = searched,
            roots = roots,
        )
    }

    /** Where one host's transcripts live, or the listing problem that kept every candidate from being read. */
    private suspend fun transcriptRoot(host: HostSession, cwd: String): TranscriptRootLookup {
        var problem: DirectoryListingProblem? = null
        for (candidate in transcriptRootCandidates(cwd)) {
            when (val listing = listingOf(host, candidate)) {
                is DirectoryListing.Listed -> return TranscriptRootLookup(candidate, null)
                is DirectoryListing.Unavailable -> when (listing.problem) {
                    DirectoryListingProblem.MISSING, DirectoryListingProblem.NOT_A_DIRECTORY -> Unit
                    else -> problem = problem ?: listing.problem
                }
            }
        }
        return TranscriptRootLookup(null, problem)
    }

    /** Session directories the root holds, keyed by session id; one listing per project directory, bounded. */
    private suspend fun sessionDirectories(host: HostSession, root: String, sessionIds: Set<String>): Map<String, String> {
        val found = mutableMapOf<String, String>()
        val projects = (listingOf(host, root) as? DirectoryListing.Listed)?.entries.orEmpty()
            .filter { it.kind == DirectoryEntryKind.DIRECTORY }
            .take(TRANSCRIPT_ROOT_SCAN_LIMIT)
        for (project in projects) {
            if (found.size == sessionIds.size) break
            val directory = "$root/${project.name}"
            (listingOf(host, directory) as? DirectoryListing.Listed)?.entries.orEmpty()
                .filter { it.kind == DirectoryEntryKind.DIRECTORY && it.name in sessionIds }
                .forEach { found.putIfAbsent(it.name, "$directory/${it.name}") }
        }
        return found
    }

    /** Transcript file names one session directory holds; a directory nobody read contributes no name. */
    private suspend fun transcriptFiles(host: HostSession, directory: String): List<String> =
        (listingOf(host, directory) as? DirectoryListing.Listed)?.entries.orEmpty()
            .filter { it.kind == DirectoryEntryKind.FILE }
            .map { it.name }

    private suspend fun listingOf(host: HostSession, directory: String): DirectoryListing = RemoteDirectoryListing(dispatchers.io).list(host, directory)

    suspend fun send(text: String, expected: AgentSession? = null) {
        sendPrompt(text, expected)
    }

    /**
     * Sends one prompt and reports whether the conversation took it. A prompt refused for having no open handle, a
     * running turn or another conversation, and a prompt whose write failed, all answer false - so a queued message
     * is only ever reported as delivered once the receiving conversation really received it.
     */
    internal suspend fun sendPrompt(text: String, expected: AgentSession? = null): Boolean = operation {
        require(text.isNotBlank())
        val job = currentCoroutineContext()[Job]
        val (current, token) = synchronized(stateLock) {
            val current = handle ?: return@operation false
            if (mutableBusy.value || mutableConversationState.value != AgentConversationState.Ready) return@operation false
            // A caller that named its conversation never writes into whichever conversation replaced it.
            if (expected != null && !matchesActive(expected)) return@operation false
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
        var taken = false
        try {
            current.send(text)
            taken = true
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
        taken
    }

    suspend fun cancel(expected: AgentSession? = null) = withContext(dispatchers.io) {
        if (expected == null) {
            cancelOpen(null)
        } else {
            // The same switch lock an open holds, so a cancellation written for one conversation can never tear
            // down the conversation that replaced it while that replacement was still opening.
            switchMutex.withLock { cancelOpen(expected) }
        }
    }

    private suspend fun cancelOpen(expected: AgentSession?) {
        if (expected != null && !matchesActive(expected)) return
        openingJob?.cancel()
        historyReadJob?.cancel()
        promptJob?.cancel()
        val (current, token) = synchronized(stateLock) {
            if (mutableConversationState.value == AgentConversationState.Loading) {
                openingJob?.cancel()
                conversationScope?.cancel()
                mutableConversationState.value = AgentConversationState.Closed
                mutableActive.value = null
                return
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

    /**
     * The open conversation's Agent configuration as its Agent reports it now, read under the same state lock every
     * other conversation call uses, so the answer can only describe the conversation that is open at this moment.
     */
    suspend fun config(): AgentConfigAccess = withContext(dispatchers.io) {
        synchronized(stateLock) {
            when (val open = handle) {
                null -> AgentConfigAccess.Closed

                is AcpSessionHandle -> AgentConfigAccess.Available(open.config)

                // ZCode reports its own settings model; it is published in the same option shape ACP uses.
                is ZcodeSessionHandle -> AgentConfigAccess.Available(AgentZcodeConfig.project(open.config))

                else -> AgentConfigAccess.Unsupported
            }
        }
    }

    /**
     * Sets one configuration option and reports what the Agent did with it. A value the Agent rejects throws;
     * a change it accepted without reporting a snapshot is [AgentConfigAccess.Unconfirmed] and never a success.
     * A result is only handed back for the conversation it was made for, so a session switch cannot inherit it.
     */
    suspend fun setConfigOption(optionId: String, value: String, expected: AgentSession? = null): AgentConfigAccess = operation {
        require(optionId.isNotBlank())
        val (open, token) = synchronized(stateLock) {
            if (expected != null && !matchesActive(expected)) return@operation AgentConfigAccess.Closed
            val current = handle ?: return@operation AgentConfigAccess.Closed
            current to generation
        }
        when (open) {
            is AcpSessionHandle -> setAcpConfigOption(open, token, optionId, value)
            is ZcodeSessionHandle -> setZcodeConfigOption(open, token, optionId, value)
            else -> AgentConfigAccess.Unsupported
        }
    }

    private suspend fun setAcpConfigOption(open: AcpSessionHandle, token: Long, optionId: String, value: String): AgentConfigAccess = try {
        val updated = open.setConfigOption(optionId, value)
        if (stillOpen(open, token)) AgentConfigAccess.Available(updated) else AgentConfigAccess.Closed
    } catch (e: CancellationException) {
        throw e
    } catch (e: java.io.IOException) {
        if (e.message != UNREPORTED_CONFIG_WRITE) throw e
        // The Agent answered the write, so it was delivered; without a snapshot nothing confirms the value.
        AgentConfigAccess.Unconfirmed(if (stillOpen(open, token)) open.config else null)
    }

    private suspend fun setZcodeConfigOption(open: ZcodeSessionHandle, token: Long, optionId: String, value: String): AgentConfigAccess {
        val change = try {
            AgentZcodeConfig.set(
                open.config,
                optionId,
                value,
                setModel = { providerId, modelId, level -> open.setModel(providerId, modelId, level) },
                setThoughtLevel = { level -> open.setThoughtLevel(level) },
            )
        } catch (e: ZcodeBusyException) {
            throw AgentZcodeConfig.busy(e)
        }
        return if (stillOpen(open, token)) AgentZcodeConfig.receipt(change) else AgentConfigAccess.Closed
    }

    private fun stillOpen(open: AgentSessionHandle, token: Long): Boolean = synchronized(stateLock) { generation == token && handle === open }

    /** True while [expected] is exactly the conversation the repository has open and active. */
    private fun matchesActive(expected: AgentSession): Boolean {
        val current = mutableActive.value ?: return false
        return current.hostId == expected.hostId && current.agent == expected.agent && current.sessionId == expected.sessionId
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
            markHostsDisconnected()
            closeConversation()
            context.stopService(Intent(context, AgentConnectionService::class.java))
        }.await()
    }

    fun onServiceDestroyed() {
        stopOperations()
        scope.launch {
            pool.disconnectAll()
            markHostsDisconnected()
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
                    pool.redialFailedHosts()
                    pool.reconnectDisconnected().forEach { hostId -> refreshIndex(hostId, interactive = false) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    report(e)
                }
                try {
                    persistLinkStates()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    report(e)
                }
            }
        }
    }

    /**
     * Mirrors the pool's live link facts into durable state: an authenticated client is CONNECTED, a blocked host
     * needs the user, and a reconnect schedule in backoff is RETRYING with its attempt count. A row is rewritten
     * only when that fact changes, and a host the pool cannot speak about keeps its last row, which is what makes
     * the next process start report an unknown link instead of a connection that no longer exists.
     */
    internal suspend fun persistLinkStates() {
        val live = pool.linkStatus()
        mutableLiveLinkStates.value = live.associate { it.hostId to it.state }
        var records = mutableLinkRecords.value
        val now = System.currentTimeMillis()
        for (status in live) {
            val previous = records.firstOrNull { it.hostId == status.hostId && it.agent == null }
            if (previous?.state == status.state && previous.attempts == status.attempts) continue
            val record = AgentLinkRecord(status.hostId, null, status.state, status.attempts, now)
            linkStates.record(record)
            records = records.filterNot { it.hostId == record.hostId && it.agent == null } + record
        }
        mutableLinkRecords.value = records
    }

    /** A deleted host must not lend its last link state to whichever host reuses its row id. */
    private suspend fun forgetRemovedHostLinkStates(ids: Set<Long>) {
        val removed = mutableLinkRecords.value.map { it.hostId }.toSet() - ids
        if (removed.isEmpty()) return
        removed.forEach { linkStates.forget(it) }
        mutableLinkRecords.value = mutableLinkRecords.value.filterNot { it.hostId in removed }
    }

    /** The same holds for a detected path: a host that is gone keeps no configuration for a row id reused later. */
    private suspend fun forgetRemovedHostDetections(ids: Set<Long>) {
        val removed = try {
            detections.all().map { it.hostId }.toSet() - ids
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Agent detections are unreadable")
            return
        }
        removed.forEach { detections.forget(it) }
    }

    /** An explicit teardown ends every connection, so no CONNECTED row may survive it. */
    private suspend fun markHostsDisconnected() {
        mutableLiveLinkStates.value = emptyMap()
        val connected = mutableLinkRecords.value.filter { it.agent == null && it.state == AgentLinkState.CONNECTED }
        if (connected.isEmpty()) return
        val now = System.currentTimeMillis()
        val rows = connected.map { AgentLinkRecord(it.hostId, null, AgentLinkState.DISCONNECTED, it.attempts, now) }
        rows.forEach { linkStates.record(it) }
        val byHost = rows.associateBy { it.hostId }
        mutableLinkRecords.value = mutableLinkRecords.value.map { record ->
            (if (record.agent == null) byHost[record.hostId] else null) ?: record
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

/** Where one host's transcripts live, or the listing problem that kept every candidate from being read. */
private data class TranscriptRootLookup(val root: String?, val problem: DirectoryListingProblem?)
