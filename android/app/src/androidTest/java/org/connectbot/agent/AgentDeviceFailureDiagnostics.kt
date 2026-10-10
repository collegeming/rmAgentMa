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

import androidx.test.platform.app.InstrumentationRegistry

/**
 * Opt-in raw failure details for the device E2E conversation test.
 *
 * Default runs map every exception message to a keyword category, so an unrecognised
 * message becomes `category=redacted` and only its length survives. Instrumentation
 * started with `agentDeviceE2e=debugRawFailure` additionally renders, per cause, a
 * scrubbed single-line message and the most relevant stack frame. That makes an
 * unexpected production failure diagnosable without relaxing any assertion.
 *
 * Scrubbing strips control characters, UUIDs, and long opaque tokens before they can
 * reach the log, and caps the message length. This is a debug-only switch for the
 * debuggable CI package and must never become the default output.
 */
internal object AgentDeviceFailureDiagnostics {
    const val ARGUMENT = "agentDeviceE2e"
    const val DEBUG_MODE = "debugRawFailure"

    private const val MAX_MESSAGE_CHARS = 512
    private const val TEST_CLASS_PREFIX = "org.connectbot.agent.AgentDevice"
    private val productionPrefixes = listOf("org.connectbot.", "org.rmagentma.")
    private val plumbingPrefixes = listOf(
        "kotlin.", "kotlinx.", "java.", "javax.", "jdk.", "sun.",
        "android.", "androidx.", "com.android.", "dalvik.", "libcore.",
    )
    private val controlCharacters = Regex("[\u0000-\u001F\u007F]+")
    private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val opaqueToken = Regex("[A-Za-z0-9+/_-]{32,}={0,2}")
    private val frameCharacters = Regex("[^A-Za-z0-9_.$:]")

    fun rawFailureEnabled(): Boolean = InstrumentationRegistry.getArguments().getString(ARGUMENT) == DEBUG_MODE

    fun rawCauseDetail(cause: Throwable): String = runCatching {
        "rawMessage=${scrubbed(cause.message.orEmpty())};rawFrame=${frame(cause)}"
    }.getOrElse { "rawMessage=<unavailable>;rawFrame=none" }

    fun scrubbed(message: String): String {
        val singleLine = controlCharacters.replace(message, " ")
        val identified = uuid.replace(singleLine, "<uuid>")
        val masked = opaqueToken.replace(identified) { "<redacted:${it.value.length}>" }
        if (masked.length <= MAX_MESSAGE_CHARS) return masked
        return masked.take(MAX_MESSAGE_CHARS) + "...(+" + (masked.length - MAX_MESSAGE_CHARS) + ")"
    }

    private fun frame(cause: Throwable): String {
        val frames = cause.stackTrace
        val selected = frames.firstOrNull { isProductionFrame(it) } ?: frames.firstOrNull { !isPlumbing(it.className) }
        val rendered = selected?.let { "${it.className}.${it.methodName}:${it.lineNumber}" } ?: return "none"
        return rendered.replace(frameCharacters, "_")
    }

    private fun isProductionFrame(frame: StackTraceElement): Boolean = productionPrefixes.any { frame.className.startsWith(it) } && !frame.className.startsWith(TEST_CLASS_PREFIX)

    private fun isPlumbing(className: String): Boolean = plumbingPrefixes.any { className.startsWith(it) }
}
