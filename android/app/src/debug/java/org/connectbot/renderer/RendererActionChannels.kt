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

package org.connectbot.renderer

import org.json.JSONObject
import org.rmagentma.rendererhost.ProbeResult
import org.rmagentma.rendererhost.json
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The two channels one renderer request runs on, and what a caller is told while a slow read is still running.
 *
 * The ordered channel is whichever thread calls [dispatch] — the host's own single worker — and every action that
 * writes product state or answers the live ACP session keeps its arrival order there, so a send, a cancel and an
 * approval can never race each other or the Compose path. The concurrent channel takes the read-only routes that
 * can take seconds: probing one host is the slowest thing this host does, and while such a probe ran on the
 * ordered worker every request behind it waited, which is what made a live `challenges.read` die on the renderer's
 * own timeout instead of being answered.
 *
 * A read that leaves the ordered channel is answered later, so [dispatch] returns the placeholder [DEFERRED] for
 * it: the bridge books that request in its own arrival order, the placeholder is never sent to the renderer, and
 * the real answer reaches the caller through the callback it was dispatched with.
 */
internal class RendererActionChannels(
    private val concurrent: ExecutorService,
    private val deadlines: ScheduledExecutorService,
    private val timeoutMillis: Long = READ_TIMEOUT_MILLIS,
    private val slowTimeoutMillis: Long = SLOW_READ_TIMEOUT_MILLIS,
) {
    /** One route's channel: the ordered worker, or the concurrent one reads leave it for. */
    enum class Channel {
        ORDERED,
        CONCURRENT,
    }

    /** Reads still running, by the key of one identical request; only the ordered channel and their own threads touch it. */
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<ProbeResult>>()

    /**
     * Runs [action] on the channel its own entry names. An ordered action runs here and now — this thread is that
     * channel — and its result is the answer to post; a concurrent action is started on the read pool, or joins
     * the identical one already running, and [answer] receives its result, or the code naming why it is late, once
     * that is known. The value returned for a concurrent action is the placeholder alone.
     */
    fun dispatch(action: String, payload: JSONObject, run: () -> ProbeResult, answer: (ProbeResult) -> Unit): ProbeResult =
        if (channelOf(action) == Channel.ORDERED) run() else defer(action, payload, run, answer)

    /**
     * Starts one read, or joins the identical read already in flight: a second tap of "probe again" is answered
     * from the probe that is still running instead of starting another one, and both callers hear it. The bound is
     * armed on this answer alone — the read itself keeps running, so a request that arrives after this answer
     * reuses its result rather than repeating the work.
     */
    private fun defer(action: String, payload: JSONObject, run: () -> ProbeResult, answer: (ProbeResult) -> Unit): ProbeResult {
        val key = coalescingKey(action, payload)
        val work = inFlight[key] ?: start(key, run)
        // One request is answered once, whether its read finished or its bound ran out first.
        val answered = AtomicBoolean(false)
        val answerOnce: (ProbeResult) -> Unit = { result -> if (answered.compareAndSet(false, true)) answer(result) }
        work.whenComplete { result, failure -> answerOnce(failure?.let(::failed) ?: result) }
        val bound = boundFor(action)
        deadlines.schedule({ answerOnce(stillRunning(action, bound)) }, bound, TimeUnit.MILLISECONDS)
        return DEFERRED_RESULT
    }

    /**
     * Starts one read on the concurrent channel and keeps it under [key] until it is done, so an identical
     * request that arrives meanwhile joins it.
     *
     * shortcut: a read is reused until it completes, however long that takes; a probe that never returns from the
     * host is that same call for every later refresh, which each answer names instead of hanging. Upgrade when a
     * repository read can hang without ever completing.
     */
    private fun start(key: String, run: () -> ProbeResult): CompletableFuture<ProbeResult> {
        val work = CompletableFuture<ProbeResult>()
        inFlight[key] = work
        // A read that finished leaves the table at once, so the next request for it starts a fresh one.
        work.whenComplete { _, _ -> inFlight.remove(key, work) }
        concurrent.execute {
            try {
                work.complete(run())
            } catch (error: Throwable) {
                // An escaping failure is an answer too: the caller must never be left waiting for its bound.
                work.completeExceptionally(error)
            }
        }
        return work
    }

    /**
     * Identity of one identical read: the action plus the fields of its payload, each name sorted so two spellings
     * of one request land on one key. Only read-only actions are keyed here, so no credential answer of an ordered
     * action ever becomes part of a key.
     */
    private fun coalescingKey(action: String, payload: JSONObject): String {
        val key = StringBuilder(action)
        for (part in PAYLOAD_PARTS) {
            val fields = payload.optJSONObject(part) ?: continue
            fields.keys().asSequence().sorted().forEach { name ->
                key.append('\u0000').append(part).append('.').append(name).append('=').append(fields.opt(name))
            }
        }
        return key.toString()
    }

    /**
     * What a caller hears when its read outlived its own bound. The read is still running, so this is the reason
     * the answer is late and never a claim about a result: a probe names itself, every other read names the read.
     */
    private fun stillRunning(action: String, boundMillis: Long): ProbeResult {
        val seconds = boundMillis / 1000
        val code = if (action in PROBE_ACTIONS) PROBE_STILL_RUNNING else READ_STILL_RUNNING
        val reason = if (action in PROBE_ACTIONS) {
            "$code：该探测仍在进行，已超过 $seconds 秒仍未完成；稍后重试会复用这次探测，不会重开一次。"
        } else {
            "$code：该读取仍在进行，已超过 $seconds 秒仍未完成；稍后重试会复用它的结果。"
        }
        return ProbeResult(STILL_RUNNING_STATUS, json("success" to false, "code" to code, "error" to reason))
    }

    /**
     * The bound one read's own answer waits for: the renderer waits longer for the actions that really reach a host
     * (`hostActionTimeoutMs`), so a probe or a history page may run for a minute, while a read that should be quick
     * is answered as late before the renderer's own shorter window closes.
     */
    private fun boundFor(action: String): Long = if (action in SLOW_READ_ACTIONS) slowTimeoutMillis else timeoutMillis

    /** A read that failed on this host's own side, named the same way the action layer names an unexpected failure. */
    private fun failed(failure: Throwable): ProbeResult = ProbeResult(
        500,
        json("success" to false, "code" to HANDLE_FAILED, "error" to "无法处理该请求：${failure.message ?: failure.javaClass.simpleName}"),
    )

    companion object {
        /**
         * Status of the placeholder one deferred read answers its caller with. It is never a real answer: the host
         * posts the response that read produced, built for the requestId the bridge booked.
         */
        const val DEFERRED = 102

        /** Body code of that placeholder, for the one caller that could ever read it: a request replayed to the bridge. */
        const val ACTION_DEFERRED = "ACTION_DEFERRED"

        /** Status of an answer that names why it is late: not available now, still running, ask again. */
        const val STILL_RUNNING_STATUS = 503

        /**
         * How long one quick read's own answer waits before it names why it is late. The packaged renderer waits 15 s
         * for these (`hostTransport.ts`, `hostActionTimeoutMs`), so this bound sits below that window: a late read is
         * answered with a code a caller can act on instead of being cut off by the renderer's own limit.
         */
        const val READ_TIMEOUT_MILLIS = 12_000L

        /**
         * How long one slow read's own answer waits. These are the actions the renderer itself waits 120 s for, so a
         * cold probe over a slow link gets its minute instead of being answered as late while it is still working.
         */
        const val SLOW_READ_TIMEOUT_MILLIS = 105_000L

        /**
         * The reads that may take a minute: both probes, one older history page, one content search over a host and
         * the read that opens a conversation not open yet. Everything else should answer far sooner.
         */
        val SLOW_READ_ACTIONS = setOf("detection.refresh", "hosts.refresh", "messages.before", "search.sessions", "conversation.read")

        /** Code of a probe that outlived its bound; a probe is the one read that really takes seconds. */
        const val PROBE_STILL_RUNNING = "PROBE_STILL_RUNNING"

        /** Code of any other read that outlived its bound. */
        const val READ_STILL_RUNNING = "READ_STILL_RUNNING"

        /** Code of a read that failed on this host's own side, as the action layer spells it for its own failures. */
        const val HANDLE_FAILED = "HANDLE_FAILED"

        /**
         * How many reads may run at once. One is not enough by construction — a probe would hold the only thread —
         * and more than a couple would put several remote listing or history reads on one host at the same time.
         */
        const val READ_PARALLELISM = 4

        /** The payload objects one key is built from: a read names its target in `params` and its terms in `query`. */
        private val PAYLOAD_PARTS = listOf("params", "query", "body")

        /** The two probe routes: the slowest reads this host has, and what [PROBE_STILL_RUNNING] names. */
        val PROBE_ACTIONS = setOf("detection.refresh", "hosts.refresh")

        /**
         * The read-only routes that leave the ordered channel, each with why it is here. Every one of them can wait
         * on this product's own state or on a host, and none of them writes anything a later request depends on, so
         * one of them hanging must never hold up a send, a cancel, an approval or another read.
         */
        val CONCURRENT_ACTIONS = setOf(
            // The two probes: one host's Agent target plus its session index, the slowest work this host does and
            // the reason this split exists.
            "detection.refresh",
            "hosts.refresh",
            // One page of history below the loaded window, read from the product's own history store.
            "messages.before",
            // One bounded remote scan per host over the selected transcripts' content.
            "search.sessions",
            // One bounded, read-only remote listing of a project's workspace root.
            "project.read",
            // Reads of what the product already holds. They answer quickly on their own; they leave the ordered
            // channel so that a probe cannot queue them behind itself, which is what the renderer cannot tolerate.
            "conversations.list",
            "conversation.read",
            "messages.list",
            "runtime.snapshot",
            "challenges.read",
            "hosts.list",
        )

        /**
         * Every route that keeps the ordered channel. Anything the table does not name stays here too: an unlisted
         * route must never overtake a write. This set lists the rest so the two together name every route the
         * bridge accepts, and a route added to the bridge has to be placed deliberately.
         */
        val ORDERED_ACTIONS = setOf(
            // The live ACP session: a send, a cancel and the two answers to its own cards must not interleave,
            // and neither may a configuration write or a credential answer to the request that is still pending.
            "messages.send",
            "turn.cancel",
            "approvals.review",
            "interactions.review",
            "config.set",
            "challenges.answer",
            // Writes to the product's own stores: host rows, the draft file, the queue, the two caches and the
            // setting, in the order the user made them.
            "hosts.create",
            "hosts.update",
            "hosts.delete",
            "draft.save",
            "queue.send",
            "queue.cancel",
            "index.clear",
            "history.clear",
            "settings.client.write",
            // One upload's own steps: a chunk after the chunk before it, and the finish after them all.
            "attachment.begin",
            "attachment.chunk",
            "attachment.finish",
            "attachment.abort",
            // State changes that read like reads: a conversation switch, a new conversation and a console hand-over.
            "runtime.ensure",
            "conversations.create",
            "terminal.open",
            "terminal.command.start",
            // Reads that answer from what this host already holds and are cheap; waiting behind a write is the
            // correct order for them, and none of them can hold up the renderer for seconds.
            "auth.read",
            "settings.client.read",
            "projects.list",
            "assistants.list",
            "agents.list",
            "adapters.list",
            "approvals.list",
            "messages.latest",
            "config.read",
            "usage.read",
            "draft.load",
            "queue.list",
            "inbox.list",
            "reference.pack",
            // The subscription itself, and every route this build refuses without touching anything.
            "events.subscribe",
            "events.unsubscribe",
            "runtime.visible",
            "commands.list",
            "artifacts.list",
            "providers.list",
            "mcp.list",
            "skills.list",
            "cron.list",
            "teams.list",
            "settings.read",
        )

        /** The channel one action runs on; a route this table does not name keeps the ordered channel. */
        fun channelOf(action: String): Channel = if (action in CONCURRENT_ACTIONS) Channel.CONCURRENT else Channel.ORDERED

        /** The placeholder one deferred read answers with instead of its result. */
        val DEFERRED_RESULT = ProbeResult(
            DEFERRED,
            json("success" to false, "code" to ACTION_DEFERRED, "error" to "该请求已交给并发通道，结果稍后到达。"),
        )
    }
}
