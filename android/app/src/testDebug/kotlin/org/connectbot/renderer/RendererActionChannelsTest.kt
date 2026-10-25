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

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.assertj.core.api.Assertions.assertThat
import org.json.JSONObject
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.rmagentma.rendererhost.ProbeBridge
import org.rmagentma.rendererhost.ProbeResult
import org.rmagentma.rendererhost.json
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class RendererActionChannelsTest {
    /** The read pool the host really uses: reads run side by side, which is what one slow probe must not break. */
    private val concurrent = Executors.newFixedThreadPool(RendererActionChannels.READ_PARALLELISM)
    private val deadlines = Executors.newSingleThreadScheduledExecutor()

    @After
    fun stop() {
        // A read a test left blocked is interrupted here, so no test leaves the pool holding a thread.
        concurrent.shutdownNow()
        deadlines.shutdownNow()
    }

    @Test
    fun everyWriteKeepsTheOrderedChannelAndEverySlowReadLeavesIt() {
        val ordered = listOf(
            "messages.send", "turn.cancel", "approvals.review", "interactions.review", "challenges.answer",
            "draft.save", "hosts.create", "hosts.update", "hosts.delete", "queue.send", "queue.cancel",
            "attachment.begin", "attachment.chunk", "attachment.finish", "attachment.abort", "config.set",
        )
        val reads = listOf(
            "detection.refresh", "hosts.refresh", "messages.before", "search.sessions", "project.read",
            "conversations.list", "conversation.read", "messages.list", "runtime.snapshot", "challenges.read", "hosts.list",
        )
        ordered.forEach { assertThat(RendererActionChannels.channelOf(it)).describedAs(it).isEqualTo(RendererActionChannels.Channel.ORDERED) }
        reads.forEach { assertThat(RendererActionChannels.channelOf(it)).describedAs(it).isEqualTo(RendererActionChannels.Channel.CONCURRENT) }
        // The two sets name every route the bridge accepts and no route twice, so a route added to the bridge has
        // to be placed deliberately instead of landing on a channel by default.
        assertThat(RendererActionChannels.CONCURRENT_ACTIONS).doesNotContainAnyElementsOf(RendererActionChannels.ORDERED_ACTIONS)
        assertThat(RendererActionChannels.CONCURRENT_ACTIONS + RendererActionChannels.ORDERED_ACTIONS)
            .containsExactlyInAnyOrderElementsOf(ProbeBridge.ACTIONS)
        assertThat(RendererActionChannels.PROBE_ACTIONS).isSubsetOf(RendererActionChannels.CONCURRENT_ACTIONS)
        // One thread would leave a probe holding the only channel there is, which is the failure this split fixes.
        assertThat(RendererActionChannels.READ_PARALLELISM).isGreaterThan(1)
    }

    @Test
    fun anOrderedActionRunsOnTheCallingThreadAndIsAnsweredInline() {
        val thread = AtomicReference<Thread>()
        val answers = LinkedBlockingQueue<ProbeResult>()
        val result = channels().dispatch(
            action = "messages.send",
            payload = payload("conversationId" to "c-1"),
            run = {
                thread.set(Thread.currentThread())
                ProbeResult(202, json("success" to true))
            },
            answer = { answers.add(it) },
        )
        assertThat(result.status).isEqualTo(202)
        assertThat(thread.get()).isSameAs(Thread.currentThread())
        // An ordered action's result is the answer itself; nothing is answered a second time through the callback.
        assertThat(answers.poll()).isNull()
    }

    @Test
    fun aReadIsAnsweredWhileAProbeStillHoldsItsOwnThread() {
        val probeStarted = CountDownLatch(1)
        val probeGate = CountDownLatch(1)
        val probeAnswers = LinkedBlockingQueue<ProbeResult>()
        val readAnswers = LinkedBlockingQueue<ProbeResult>()
        val channels = channels()
        val probe = channels.dispatch("detection.refresh", payload("hostId" to "host-7"), {
            probeStarted.countDown()
            probeGate.await()
            ProbeResult(200, json("success" to true))
        }) { probeAnswers.add(it) }
        assertThat(probe.status).isEqualTo(RendererActionChannels.DEFERRED)
        assertThat(probeStarted.await(5, TimeUnit.SECONDS)).isTrue()

        val read = channels.dispatch("challenges.read", payload(), {
            ProbeResult(200, json("success" to true, "data" to json("challenge" to null)))
        }) { readAnswers.add(it) }
        assertThat(read.status).isEqualTo(RendererActionChannels.DEFERRED)
        // The probe is still blocked on its host, and the live read is answered anyway.
        assertThat(probeGate.count).isEqualTo(1)
        assertThat(readAnswers.poll(5, TimeUnit.SECONDS)?.status).isEqualTo(200)

        probeGate.countDown()
        assertThat(probeAnswers.poll(5, TimeUnit.SECONDS)?.status).isEqualTo(200)
    }

    @Test
    fun aSecondProbeReusesTheOneRunningAndTheNextOneProbesAgain() {
        val runs = AtomicInteger()
        val started = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val work = {
            runs.incrementAndGet()
            started.countDown()
            gate.await()
            ProbeResult(200, json("success" to true, "data" to json("refreshed" to true)))
        }
        val first = LinkedBlockingQueue<ProbeResult>()
        val second = LinkedBlockingQueue<ProbeResult>()
        val channels = channels()
        channels.dispatch("hosts.refresh", payload("hostId" to "host-7"), work) { first.add(it) }
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue()
        // A second tap of the same probe joins the one in flight instead of starting another.
        channels.dispatch("hosts.refresh", payload("hostId" to "host-7"), work) { second.add(it) }
        gate.countDown()
        assertThat(first.poll(5, TimeUnit.SECONDS)?.status).isEqualTo(200)
        assertThat(second.poll(5, TimeUnit.SECONDS)?.status).isEqualTo(200)
        assertThat(runs.get()).isEqualTo(1)

        // Once that probe is done its entry is gone, so the next request probes that host again.
        val third = LinkedBlockingQueue<ProbeResult>()
        channels.dispatch("hosts.refresh", payload("hostId" to "host-7"), work) { third.add(it) }
        assertThat(third.poll(5, TimeUnit.SECONDS)?.status).isEqualTo(200)
        assertThat(runs.get()).isEqualTo(2)

        // Another host is its own probe: it neither joins this one nor is answered from its result.
        val otherStarted = CountDownLatch(1)
        val otherGate = CountDownLatch(1)
        val other = LinkedBlockingQueue<ProbeResult>()
        channels.dispatch("hosts.refresh", payload("hostId" to "host-8"), {
            otherStarted.countDown()
            otherGate.await()
            ProbeResult(200, json("success" to true, "data" to json("refreshed" to true)))
        }) { other.add(it) }
        assertThat(otherStarted.await(5, TimeUnit.SECONDS)).isTrue()
        assertThat(runs.get()).isEqualTo(2)
        otherGate.countDown()
        assertThat(other.poll(5, TimeUnit.SECONDS)?.status).isEqualTo(200)
    }

    @Test
    fun aProbeBeyondItsBoundNamesWhyItIsLateAndKeepsRunning() {
        val channels = channels(timeoutMillis = 150, slowTimeoutMillis = 150)
        val gate = CountDownLatch(1)
        val done = CountDownLatch(1)
        val timedOut = LinkedBlockingQueue<ProbeResult>()
        val probedAgain = AtomicBoolean(false)
        channels.dispatch("detection.refresh", payload("hostId" to "host-7"), {
            gate.await()
            done.countDown()
            ProbeResult(200, json("success" to true))
        }) { timedOut.add(it) }
        val late = timedOut.poll(5, TimeUnit.SECONDS)
        assertThat(late?.status).isEqualTo(RendererActionChannels.STILL_RUNNING_STATUS)
        assertThat(late?.code()).isEqualTo(RendererActionChannels.PROBE_STILL_RUNNING)

        // The probe itself is still running, so a retry reuses it: its own work is never started.
        val retried = LinkedBlockingQueue<ProbeResult>()
        channels.dispatch("detection.refresh", payload("hostId" to "host-7"), {
            probedAgain.set(true)
            ProbeResult(200, json("success" to true))
        }) { retried.add(it) }
        gate.countDown()
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue()
        assertThat(retried.poll(5, TimeUnit.SECONDS)?.status).isEqualTo(200)
        assertThat(probedAgain.get()).isFalse()
        // One request is answered once: the probe finishing does not answer the late request a second time.
        assertThat(timedOut.poll(200, TimeUnit.MILLISECONDS)).isNull()
    }

    @Test
    fun aReadThatIsNotAProbeNamesItsOwnCodeWhenItOutlivesItsBound() {
        val channels = channels(timeoutMillis = 150, slowTimeoutMillis = 150)
        val never = CountDownLatch(1)
        val answers = LinkedBlockingQueue<ProbeResult>()
        channels.dispatch("messages.before", payload("conversationId" to "c-1"), {
            never.await()
            ProbeResult(200, json("success" to true))
        }) { answers.add(it) }
        val late = answers.poll(5, TimeUnit.SECONDS)
        assertThat(late?.status).isEqualTo(RendererActionChannels.STILL_RUNNING_STATUS)
        assertThat(late?.code()).isEqualTo(RendererActionChannels.READ_STILL_RUNNING)
    }

    @Test
    fun theBridgeBooksADeferredReadInOrderAndItsResponseIsBuiltForThatRequest() {
        val bridge = ProbeBridge()
        bridge.commit(ProbeBridge.ENTRY)
        val handshake = bridge.receive(
            ProbeBridge.ORIGIN,
            true,
            message(1, "", "host.handshake", json("params" to JSONObject(), "query" to JSONObject(), "body" to null)),
        ) { _, _ -> error("the bridge answers a handshake itself") }
        val documentId = handshake!!.getJSONObject("body").getString("documentId")

        val answers = LinkedBlockingQueue<ProbeResult>()
        val channels = channels()
        val placeholder = bridge.receive(ProbeBridge.ORIGIN, true, message(2, documentId, "challenges.read", payload())) { action, params ->
            channels.dispatch(action, params, { ProbeResult(200, json("success" to true, "data" to json("challenge" to null))) }) { answers.add(it) }
        }
        // What the bridge gives back for a deferred read is a placeholder, not an answer: the host posts the
        // response that read produced instead, built for the requestId the bridge booked.
        assertThat(placeholder!!.optInt("status")).isEqualTo(RendererActionChannels.DEFERRED)
        val result = answers.poll(5, TimeUnit.SECONDS) ?: error("the deferred read was never answered")
        val response = bridge.response(placeholder.getString("requestId"), result)
        assertThat(response.getString("requestId")).isEqualTo(placeholder.getString("requestId"))
        assertThat(response.getString("documentId")).isEqualTo(documentId)
        assertThat(response.getBoolean("ok")).isTrue()

        // The request behind it is booked in its own arrival order: a read that left the worker never makes the
        // bridge refuse a write that arrived after it.
        val write = bridge.receive(ProbeBridge.ORIGIN, true, message(3, documentId, "messages.send", payload("conversationId" to ProbeBridge.CONVERSATION))) { _, _ ->
            ProbeResult(202, json("success" to true))
        }
        assertThat(write!!.optInt("status")).isEqualTo(202)
        assertThat(write.getString("requestId")).isEqualTo("renderer-3")
    }

    private fun channels(timeoutMillis: Long = 30_000, slowTimeoutMillis: Long = 30_000) =
        RendererActionChannels(concurrent, deadlines, timeoutMillis, slowTimeoutMillis)

    private fun payload(vararg fields: Pair<String, Any?>): JSONObject = json(
        "params" to json(*fields),
        "query" to JSONObject(),
        "body" to JSONObject(),
    )

    private fun message(serial: Int, documentId: String, action: String, payload: JSONObject): String = json(
        "requestId" to "renderer-$serial",
        "documentId" to documentId,
        "action" to action,
        "payload" to payload,
    ).toString()

    private fun ProbeResult.code(): String = (body as JSONObject).optString("code")
}
