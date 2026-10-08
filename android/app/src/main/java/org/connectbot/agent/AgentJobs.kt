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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class AgentJobs(private val scope: CoroutineScope) {
    private val lock = Any()
    private val jobs = mutableSetOf<Job>()
    private val mutableCount = MutableStateFlow(0)
    val count = mutableCount.asStateFlow()

    fun <T> submit(start: Boolean = true, block: suspend CoroutineScope.() -> T): Deferred<T> = synchronized(lock) {
        val job = scope.async(start = CoroutineStart.LAZY, block = block)
        jobs.add(job)
        mutableCount.value = jobs.size
        job.invokeOnCompletion {
            synchronized(lock) {
                jobs.remove(job)
                mutableCount.value = jobs.size
            }
        }
        if (start) job.start()
        job
    }

    fun cancelAll() = synchronized(lock) {
        jobs.toList().forEach { it.cancel() }
    }
}
