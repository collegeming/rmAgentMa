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

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import org.connectbot.agent.probeStateOf
import org.connectbot.data.entity.Host
import org.json.JSONArray
import org.json.JSONObject
import org.rmagentma.core.AgentAvailability
import org.rmagentma.core.AgentConfigOption
import org.rmagentma.core.AgentConfigValue
import org.rmagentma.core.AgentEvent
import org.rmagentma.core.AgentKind
import org.rmagentma.core.AgentSession
import org.rmagentma.core.AgentSessionConfig
import org.rmagentma.core.DirectoryListing
import org.rmagentma.core.DirectoryListingProblem
import org.rmagentma.core.remoteTargetOf
import org.rmagentma.rendererhost.array
import org.rmagentma.rendererhost.json

private const val STATUS_ONLINE = "online"
private const val STATUS_MISSING = "missing"
private const val STATUS_UNCHECKED = "unchecked"

/** The explorer's own role and status vocabulary for one project root. */
private const val WORKSPACE_ROLE = "workspace"
private const val WORKSPACE_AVAILABLE = "available"
private const val WORKSPACE_DISCONNECTED = "disconnected"

/** Reason code of a workspace whose host could not be reached at all, so no directory was ever read. */
private const val WORKSPACE_UNREACHABLE = "host_unreachable"

/** Prefix of the opaque id one saved host carries as a project and as a row of the hosts page. */
private const val HOST_PREFIX = "host-"

/**
 * Renders product state into the payload shapes the packaged renderer reads. Everything here is a pure
 * function of already-loaded product models: no disk, no network and no credential ever reaches it, so a
 * payload can only carry the metadata the caller was handed.
 */
internal object RendererProjection {
    /** Stable message identity: the persisted history key of the event row, never a per-response uuid. */
    fun messageId(key: Long): String = "m$key"

    /**
     * Runtime summary of one conversation. The product runs at most one turn on the open conversation, so the
     * state is derived from that fact instead of a copied AionUi state machine.
     */
    fun runtime(snapshot: RendererSnapshot, pending: AgentEvent?): JSONObject {
        val running = snapshot.turnId != null
        val state = when {
            snapshot.cancelling -> "cancelling"
            snapshot.loading -> "starting"
            pending != null -> "waiting_confirmation"
            running -> "running"
            else -> "idle"
        }
        return json(
            "state" to state,
            "can_send_message" to (snapshot.active && !snapshot.cancelling && !running && pending == null),
            "has_task" to (running || pending != null),
            "is_processing" to (running || pending != null || snapshot.cancelling),
            "pending_confirmations" to (if (pending == null) 0 else 1),
            "task_status" to if (running || pending != null) "running" else "finished",
            "turn_id" to snapshot.turnId,
            "supports_midturn_delivery" to false,
        )
    }

    /** One conversation: a derived product session id and the row fields the renderer reads. */
    fun conversation(snapshot: RendererSnapshot, conversationId: String, pending: AgentEvent?, runtime: JSONObject): JSONObject {
        val session = snapshot.session ?: throw IllegalStateException("Conversation projection needs a session")
        val row = snapshot.indexed
        val name = listOf(row?.title, session.title, session.preview, session.sessionId, session.cwd)
            .firstOrNull { !it.isNullOrBlank() }
        return json(
            "id" to conversationId,
            "name" to name,
            "type" to "acp",
            "project_id" to projectId(session.hostId),
            // The index carries the last observed times; a row it never timed keeps the session's own zero.
            "created_at" to (row?.createdAt?.takeIf { it > 0 } ?: session.createdAt),
            "modified_at" to (row?.updatedAt?.takeIf { it > 0 } ?: session.updatedAt),
            "status" to if (snapshot.turnId != null || pending != null) "running" else "finished",
            // The renderer's model and mode pickers read the Agent's own option catalogue from `config.read`; this
            // row names no model, and the cached list stays empty because the host keeps no catalogue of its own.
            "model" to json("provider_id" to "", "use_model" to "", "model" to ""),
            "fork_capability" to json("at_turn" to false),
            "prompt_capability" to json("image" to false, "audio" to false),
            "extra" to json(
                "workspace" to session.cwd,
                "backend" to session.agent.wireName,
                "agent_name" to session.agent.wireName,
                "custom_workspace" to true,
                "cached_config_options" to JSONArray(),
            ),
            "runtime" to runtime,
        )
    }

    fun projectId(hostId: Long): String = HOST_PREFIX + hostId

    /** The host id an opaque `host-<id>` name carries; anything else names no saved host. */
    fun hostId(value: String): Long? {
        val parsed = value.takeIf { it.startsWith(HOST_PREFIX) }?.removePrefix(HOST_PREFIX)?.toLongOrNull()
        return parsed?.takeIf { it > 0 }
    }

    /**
     * One saved host as the hosts page reads it: the row itself plus the probe verdict this host already holds.
     * A row is never dressed up as probed — `detection` says whether any probe answered for it — and the shell
     * context the Agent would run in is named by the target, so a Windows host reads as the target it really is.
     */
    fun hostRow(host: Host, availability: List<AgentAvailability>): JSONObject {
        val target = remoteTargetOf(host.osType, host.agentTarget)
        return json(
            "id" to projectId(host.id),
            "nickname" to host.nickname,
            "hostname" to host.hostname,
            "port" to host.port,
            "username" to host.username,
            "protocol" to host.protocol,
            "os_type" to host.osType,
            "agent_target" to host.agentTarget,
            "target" to target.id,
            "target_supported" to target.supported,
            "detection" to detection(availability.filter { it.hostId == host.id }),
        )
    }

    fun hostRows(hosts: List<Host>, availability: List<AgentAvailability>): JSONArray = array(*hosts.map { hostRow(it, availability) }.toTypedArray())

    /**
     * What the product holds for one host: `checked` is false until some probe answered for it, and each Agent
     * that did answer carries its own state, the absolute executable and version it was found at, and the
     * capabilities it declared — an unrecognized probe code keeps no invented state.
     */
    fun detection(rows: List<AgentAvailability>): JSONObject = json(
        "checked" to rows.isNotEmpty(),
        "agents" to array(*rows.map(::agentRow).toTypedArray()),
    )

    private fun agentRow(row: AgentAvailability): JSONObject = json(
        "id" to row.kind.wireName,
        "state" to probeStateOf(row, refreshing = false).name.lowercase(),
        "executable" to row.executable,
        "version" to row.version,
        "capabilities" to row.capabilities?.asObject(),
        "error" to row.error,
    )

    /**
     * Storage key of one conversation's draft: the identity the product itself resumes the session by, so a
     * draft belongs to that conversation and never to whichever conversation happens to be open next.
     */
    fun draftKey(session: AgentSession): String = "${session.hostId}:${session.agent.wireName}:${session.sessionId}"

    /**
     * A conversation's draft in the renderer's own shape. The file list is empty because this host stores none:
     * it hands out no upload reference, so an attachment in a draft could only be one it never issued.
     */
    fun draft(text: String, conversationId: String): JSONObject = json("text" to text, "files" to JSONArray(), "conversation_id" to conversationId)

    /**
     * A saved host is one project: the product has no server-side project registry. These rows carry no explorer
     * state — only `project.read` names a workspace, and only once the host answered for it — so the list can
     * never be mistaken for a directory that was read and found empty.
     */
    fun project(host: Host, workspace: WorkspaceRoot? = null): JSONObject {
        val entries = JSONArray()
        workspace?.let { root ->
            val entry = json(
                "pe_id" to workspacePeId(host.id),
                "role" to WORKSPACE_ROLE,
                "display_name" to workspaceName(root.directory),
                "display_path" to root.directory,
                "order_index" to 0,
                "runtime_status" to root.status,
            )
            // Only a root that is not available carries the reason, so a flagged row names its own cause.
            root.problem?.let { entry.put("runtime_problem", it) }
            entries.put(entry)
        }
        return json(
            "project_id" to projectId(host.id),
            "name" to host.nickname.ifBlank { host.username.ifBlank { host.hostname } },
            "explorer" to json("workspace_pe_id" to workspace?.let { workspacePeId(host.id) }, "entries" to entries),
        )
    }

    fun projects(hosts: List<Host>): JSONArray = array(*hosts.map { project(it) }.toTypedArray())

    /**
     * Stable identity of one host's workspace root, derived from the host id it belongs to, so a later directory
     * request can be resolved back to that host without a registry of its own.
     */
    fun workspacePeId(hostId: Long): String = "pe-${projectId(hostId)}"

    /**
     * One project's workspace root: the directory the conversation's Agent really runs in, with the availability
     * the host observed. [problem] names the exact reason behind a status that is not `available`, so a row the
     * panel flags can never be mistaken for a directory that is simply empty.
     */
    data class WorkspaceRoot(val directory: String, val status: String, val problem: String? = null)

    /**
     * The availability the renderer's explorer draws for a workspace root. A directory that is gone or unreadable
     * is reported as itself, and a host that answered nothing at all is `disconnected` — never an empty listing,
     * which would claim the directory was read and found empty.
     */
    fun workspaceRoot(directory: String, listing: DirectoryListing?): WorkspaceRoot = when (listing) {
        is DirectoryListing.Listed -> WorkspaceRoot(directory, WORKSPACE_AVAILABLE)
        is DirectoryListing.Unavailable -> WorkspaceRoot(directory, workspaceStatus(listing.problem), listing.problem.code)
        null -> WorkspaceRoot(directory, WORKSPACE_DISCONNECTED, WORKSPACE_UNREACHABLE)
    }

    private fun workspaceStatus(problem: DirectoryListingProblem): String = when (problem) {
        DirectoryListingProblem.MISSING, DirectoryListingProblem.NOT_A_DIRECTORY -> "missing"
        DirectoryListingProblem.NOT_READABLE -> "permission_denied"
        else -> WORKSPACE_DISCONNECTED
    }

    /** Basename of a workspace path, which is the label its tree row shows; a path without one stays itself. */
    private fun workspaceName(directory: String): String = directory.trimEnd('/').substringAfterLast('/').ifBlank { directory }

    /** The latest message of one type, or null, exactly as `messages/latest` promises. */
    fun latest(messages: List<JSONObject>, type: String?): JSONObject? = if (type.isNullOrEmpty()) {
        messages.lastOrNull()
    } else {
        messages.lastOrNull { it.optString("type") == type }
    }

    /** Cursor page over the loaded window. Cursors are the same stable message ids the items carry. */
    fun page(items: List<JSONObject>, limit: Int, before: String?, after: String?, hasEarlier: Boolean): JSONObject {
        var start = 0
        var end = items.size
        if (!before.isNullOrEmpty()) {
            end = items.indexOfFirst { it.optString("id") == before }
        } else if (!after.isNullOrEmpty()) {
            start = items.indexOfLast { it.optString("id") == after } + 1
        }
        val window = items.subList(start.coerceIn(0, items.size), end.coerceIn(start.coerceIn(0, items.size), items.size))
        val bounded = window.takeLast(limit)
        val page = JSONArray()
        bounded.forEach { page.put(it) }
        return json(
            "items" to page,
            "oldest_cursor" to bounded.firstOrNull()?.optString("id"),
            "newest_cursor" to bounded.lastOrNull()?.optString("id"),
            "has_more_before" to (hasEarlier || window.size > bounded.size),
            "has_more_after" to (end < items.size),
        )
    }

    /** Connectable agent catalogue: only kinds a completed probe found on some saved host. */
    fun assistants(availability: List<AgentAvailability>): JSONArray = array(
        *AgentKind.entries.filter { kind -> availability.any { it.kind == kind && it.available } }
            .map { kind -> assistant(kind, availability) }
            .toTypedArray(),
    )

    private fun assistant(kind: AgentKind, availability: List<AgentAvailability>): JSONObject = json(
        "id" to kind.wireName,
        "source" to "builtin",
        "name" to kind.wireName,
        "name_i18n" to JSONObject(),
        "description_i18n" to JSONObject(),
        "enabled" to true,
        "sort_order" to kind.ordinal,
        "agent_id" to kind.wireName,
        "agent" to json("type" to "acp", "source" to "builtin", "acp_backend" to kind.wireName),
        "enabled_skills" to JSONArray(),
        "custom_skill_names" to JSONArray(),
        "disabled_builtin_skills" to JSONArray(),
        "context_i18n" to JSONObject(),
        "prompts" to JSONArray(),
        "prompts_i18n" to JSONObject(),
        "models" to JSONArray(),
        "agent_status" to (if (agentStatus(kind, availability) == STATUS_ONLINE) STATUS_ONLINE else "offline"),
        "team_selectable" to false,
        "deletable" to false,
    )

    /** Managed agent diagnostics rows; a kind counts as installed once any host probe found it. */
    fun agents(availability: List<AgentAvailability>): JSONArray = array(
        *AgentKind.entries.map { kind ->
            val status = agentStatus(kind, availability)
            json(
                "id" to kind.wireName,
                "name" to kind.wireName,
                "backend" to kind.wireName,
                "agent_type" to "acp",
                "agent_source" to "builtin",
                "enabled" to (status != STATUS_MISSING),
                "installed" to (status == STATUS_ONLINE),
                "status" to status,
                "command" to kind.command,
            )
        }.toTypedArray(),
    )

    /** Static capability declaration: this host offers the agent kinds it probed, and installs no adapters. */
    fun adapters(availability: List<AgentAvailability>): JSONArray = array(
        *AgentKind.entries.filter { kind -> availability.any { it.kind == kind && it.available } }
            .map { kind -> json("id" to kind.wireName, "name" to kind.wireName, "backend" to kind.wireName, "source" to "builtin") }
            .toTypedArray(),
    )

    private fun agentStatus(kind: AgentKind, availability: List<AgentAvailability>): String {
        val rows = availability.filter { it.kind == kind }
        return when {
            rows.any { it.available } -> STATUS_ONLINE
            rows.isEmpty() -> STATUS_UNCHECKED
            else -> STATUS_MISSING
        }
    }

    /** Context usage of the newest usage report in the window; null when the agent never reported one. */
    fun usage(event: AgentEvent?): JSONObject? {
        val raw = event?.raw?.asObject() ?: return null
        val report = raw.optJSONObject("usage") ?: raw
        val used = intOrNull(report, "used") ?: return null
        val size = intOrNull(report, "size") ?: return null
        val result = json("used" to used, "size" to size)
        val cost = report.optJSONObject("cost")
        val amount = cost?.let { intOrNull(it, "amount") }
        if (cost != null && amount != null) {
            result.put("cost", json("amount" to amount, "currency" to cost.optString("currency")))
        }
        return result
    }

    /**
     * The open conversation's configuration surface as the renderer's own option list. Every option keeps the
     * Agent's id, category and type, a type this host cannot draw included, so an unknown one stays readable
     * instead of being dropped or renamed here. A catalogue the Agent never sent stays empty and a current value
     * it never stated stays null, so no removed or unused option is shown as chosen.
     */
    fun configOptions(config: AgentSessionConfig): JSONArray {
        val options = config.options.map(::configOption)
        val claimed = options.flatMap { listOf(it.optString("id"), it.optString("category")) }.toSet()
        val roles = mutableListOf<JSONObject>()
        // ACP names modes and models in lists of their own, while the renderer reads all three roles from this
        // option list; an uncovered catalogue is handed over under the protocol's own option id for that role.
        if (config.models.isNotEmpty() && "model" !in claimed) roles += selectOption("model", "model", config.models, config.currentModelId)
        if (config.modes.isNotEmpty() && "mode" !in claimed) roles += selectOption("mode", "mode", config.modes, config.currentModeId)
        return array(*(options + roles).toTypedArray())
    }

    private fun configOption(option: AgentConfigOption): JSONObject = json(
        "id" to option.id,
        "name" to option.name,
        "description" to option.description,
        "category" to option.category,
        "type" to option.type,
        "current_value" to option.currentValue?.contentOrNull,
        "options" to array(*option.values.map(::configValue).toTypedArray()),
    )

    /** One catalogue entry: a role only the Agent's own lists carry has no name or description to report. */
    private fun selectOption(id: String, category: String, values: List<AgentConfigValue>, current: String?): JSONObject = json(
        "id" to id,
        "category" to category,
        "type" to "select",
        "current_value" to current,
        "options" to array(*values.map(::configValue).toTypedArray()),
    )

    private fun configValue(value: AgentConfigValue): JSONObject {
        val entry = json("value" to value.value, "name" to value.name)
        if (value.description.isNotEmpty()) entry.put("description", value.description)
        return entry
    }

    /** The raw update is a kotlinx JSON tree; the renderer's payloads are built with org.json. */
    private fun JsonObject.asObject(): JSONObject = JSONObject(toString())

    private fun intOrNull(source: JSONObject, key: String): Int? = (source.opt(key) as? Number)?.toInt()

    /**
     * Messages of the loaded window, in window order. A row is skipped only when the renderer has nothing to
     * draw for it, so an update it cannot show is never dressed up as chat history.
     */
    fun messages(events: List<AgentEvent>, conversationId: String, epoch: String, turnId: String?): List<JSONObject> = events.mapNotNull { message(it, conversationId, epoch, turnId) }

    fun message(event: AgentEvent, conversationId: String, epoch: String, turnId: String?): JSONObject? {
        val id = messageId(event.historyKey)
        // The history store keeps no per-row clock, so no frame claims a timestamp it cannot know; the
        // renderer stamps arrival time itself.
        val base = json(
            "id" to id,
            "msg_id" to id,
            "conversation_id" to conversationId,
            "turn_id" to turnId,
        )
        when (event.type) {
            "user" -> base.put("type", "text").put("position", "right").put("status", "finish")
                .put("content", json("content" to event.text, "replace" to true))

            "content" -> base.put("type", "text").put("position", "left").put("status", "finish")
                .put("content", json("content" to event.text, "replace" to true))

            "thinking" -> base.put("type", "thinking").put("position", "left")
                .put("content", json("content" to event.text, "status" to if (turnId == null) "done" else "thinking"))

            "tool", "tool_update" -> base.put("type", "acp_tool_call").put("position", "left")
                .put("content", json("session_id" to conversationId, "update" to toolUpdate(event)))

            "plan" -> base.put("type", "plan").put("position", "left")
                .put("content", json("session_id" to conversationId, "entries" to (event.raw?.asObject()?.opt("entries") ?: JSONArray()), "turn_id" to turnId))

            "permission" -> base.put("type", "acp_permission").put("position", "left")
                .put("content", permission(event, conversationId, epoch, turnId))

            "error" -> base.put("type", "tips").put("position", "center")
                .put("content", json("content" to event.text, "type" to "error"))

            else -> return null
        }
        return base
    }

    private fun toolUpdate(event: AgentEvent): JSONObject {
        val raw = event.raw?.asObject() ?: JSONObject()
        val update = JSONObject(raw.toString())
        val callId = raw.optString("toolCallId").ifEmpty { event.id }
        update.remove("toolCallId")
        update.put("tool_call_id", callId)
        if (update.optString("sessionUpdate").isEmpty()) {
            update.put("sessionUpdate", if (event.type == "tool_update") "tool_call_update" else "tool_call")
        }
        if (update.optString("title").isEmpty()) update.put("title", event.title)
        if (update.optString("status").isEmpty()) update.put("status", event.status)
        return update
    }

    /** Live approval card: identity plus the options the agent really offered, so the answer maps back. */
    fun permission(event: AgentEvent, conversationId: String, epoch: String, turnId: String?): JSONObject {
        val options = JSONArray()
        event.options.forEach { option ->
            options.put(json("option_id" to option.id, "name" to option.label, "kind" to optionKind(option.id)))
        }
        return json(
            "session_id" to conversationId,
            "conversation_id" to conversationId,
            // The opaque reference an answer must quote is this event's own request id.
            "request_id" to event.id,
            "epoch" to epoch,
            "turn_id" to turnId,
            "options" to options,
            "tool_call" to permissionTool(event),
            "title" to event.title,
            "description" to event.text,
            "live" to true,
            "readonly" to false,
            "state" to "waiting",
        )
    }

    private fun permissionTool(event: AgentEvent): JSONObject {
        val tool = event.raw?.asObject()?.optJSONObject("toolCall")
        val callId = tool?.optString("toolCallId").orEmpty().ifEmpty { event.id }
        return json(
            "tool_call_id" to callId,
            "title" to tool?.optString("title").orEmpty().ifEmpty { event.title },
            "kind" to tool?.optString("kind").orEmpty(),
            "status" to tool?.optString("status").orEmpty(),
            "raw_input" to tool?.opt("rawInput"),
            "content" to tool?.optJSONArray("content"),
        )
    }

    private fun optionKind(id: String): String = when (id) {
        "allow_once", "allow-once" -> "allow_once"
        "reject_once", "reject-once" -> "reject_once"
        "allow_always", "allow-always" -> "allow_always"
        else -> id
    }
}
