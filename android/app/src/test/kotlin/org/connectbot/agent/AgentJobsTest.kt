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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AgentJobsTest {
    @Test
    fun cancellingAWaiterDoesNotCancelTheRepositoryJob() = runTest {
        val jobs = AgentJobs(backgroundScope)
        val finish = CompletableDeferred<Unit>()
        var completed = false
        val operation = jobs.submit {
            finish.await()
            completed = true
        }
        val waiter = async { operation.await() }
        runCurrent()
        waiter.cancel()
        runCurrent()
        assertThat(operation.isActive).isTrue()
        finish.complete(Unit)
        runCurrent()
        assertThat(completed).isTrue()
    }

    @Test
    fun countsQueuedAndChallengingOperationsUntilTheyActuallyFinish() = runTest {
        val jobs = AgentJobs(backgroundScope)
        val challenge = CompletableDeferred<Unit>()
        val operation = jobs.submit(start = false) { challenge.await() }
        assertThat(jobs.count.value).isEqualTo(1)
        operation.start()
        runCurrent()
        assertThat(jobs.count.value).isEqualTo(1)
        challenge.complete(Unit)
        runCurrent()
        assertThat(jobs.count.value).isZero()
    }

    @Test
    fun disconnectCancelsTheRealJobEvenAfterItsWaiterIsGone() = runTest {
        val jobs = AgentJobs(backgroundScope)
        var released = false
        val operation = jobs.submit {
            try {
                kotlinx.coroutines.awaitCancellation()
            } finally {
                released = true
            }
        }
        runCurrent()
        jobs.cancelAll()
        runCurrent()
        assertThat(operation.isCancelled).isTrue()
        assertThat(released).isTrue()
    }
}
