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

package org.rmagentma.core

import java.io.InputStream
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Never answers on its own, so a test of a bounded remote call that forgot its bound would block here forever.
 * Closing the channel that owns it releases the read, which is what lets such a test finish.
 */
internal class SilentInput : InputStream() {
    private val released = CountDownLatch(1)

    fun release() = released.countDown()

    override fun read(): Int {
        try {
            released.await(30, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            throw InterruptedIOException("Silent stream was interrupted")
        }
        return -1
    }
}
