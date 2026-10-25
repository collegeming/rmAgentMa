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

import android.Manifest

/** The nearby-devices grant Wi-Fi based service discovery is gated behind from API 36 on. */
private const val NEARBY_SDK = 36

/** Local-network access, which the platform names and enforces as its own grant from API 37 on. */
private const val LOCAL_NETWORK_SDK = 37

/**
 * Actions that reach a host over the LAN by probing it, reading its state, writing a file it holds or opening
 * its terminal — a console connects to that host itself — and are therefore the first need for the local-network
 * grants. Every other route is answered from the product's own stores, so it is served without any grant —
 * including the upload steps that only buffer a file on this phone, which is why only `attachment.finish` is
 * listed here.
 */
internal val NETWORK_ACTIONS = setOf(
    "detection.refresh",
    "hosts.refresh",
    "conversations.create",
    "project.read",
    "search.sessions",
    "attachment.finish",
    "terminal.open",
    "terminal.command.start",
)

/** The code a network route answers with until the grants it needs are there, so a caller can grant and retry. */
internal const val LOCAL_NETWORK_PERMISSION_REQUIRED = "LOCAL_NETWORK_PERMISSION_REQUIRED"

/**
 * The local-network grants this system may require, in the order they are asked for. A system below both thresholds
 * answers nothing, and the host then asks for nothing on its behalf.
 */
internal fun networkPermissions(sdkInt: Int): List<String> = buildList {
    if (sdkInt >= NEARBY_SDK) add(Manifest.permission.NEARBY_WIFI_DEVICES)
    if (sdkInt >= LOCAL_NETWORK_SDK) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
}

/**
 * True while [action] reaches a host over the LAN and not every permission of [permissions] is granted: that is the
 * answer the host gives instead of running it, with its own stable code and a visible notice, so a refusal is never
 * silent and the action works again once the user granted what it needed.
 */
internal fun localNetworkRefused(action: String, permissions: List<String>, granted: (String) -> Boolean): Boolean =
    action in NETWORK_ACTIONS && permissions.any { !granted(it) }
