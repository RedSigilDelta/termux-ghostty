package com.mrndtvndv.term.herdr

import kotlinx.serialization.SerialName
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Herdr wire format for the methods this client uses. Field names follow the upstream `src/api/schema`
 * modules at FETCH_HEAD. Unknown fields are ignored, and unknown enum values map to Unknown.
 */
internal object HerdrWire {
    private val json = Json { ignoreUnknownKeys = true }

    fun encodeRequest(id: String, method: String, params: JsonObject): String =
        JsonObject(
            mapOf(
                "id" to JsonPrimitive(id),
                "method" to JsonPrimitive(method),
                "params" to params,
            ),
        ).toString()

    fun parseLine(line: String): JsonObject {
        val element = try {
            json.parseToJsonElement(line)
        } catch (error: SerializationException) {
            throw protocolError("Herdr sent malformed JSON: ${error.message}", error)
        }
        return element as? JsonObject ?: throw protocolError("Herdr line is not a JSON object")
    }

    /** Server error body, or null when the line is not an error response. */
    fun errorOf(line: JsonObject): HerdrError? {
        val body = line["error"] as? JsonObject ?: return null
        val code = body.string("code") ?: "unknown"
        val message = body.string("message") ?: "Herdr returned an error"
        val kind = when {
            code == "events_lost" -> HerdrErrorKind.EventsLost
            code == "unknown_method" -> HerdrErrorKind.Unsupported
            code == "invalid_request" && message.contains("unknown variant") -> HerdrErrorKind.Unsupported
            else -> HerdrErrorKind.Rejected
        }
        return HerdrError(kind, message, serverCode = code)
    }

    /** Result object of a success response. Caller checks `type`. */
    fun resultOf(line: JsonObject, requestId: String): JsonObject {
        if (line.string("id") != requestId) throw protocolError("Herdr response id mismatch: expected $requestId")
        return line["result"] as? JsonObject ?: throw protocolError("Herdr response has no result")
    }

    /** Result of one ordinary reply line. Throws the server's error, or a protocol error for a mismatched id. */
    fun replyResult(line: String, requestId: String): JsonObject {
        val response = parseLine(line)
        errorOf(response)?.let { throw HerdrException(it) }
        return resultOf(response, requestId)
    }

    /** Checks the ack of an `events.subscribe` request. Anything else is an error. */
    fun subscriptionAck(line: String, requestId: String) {
        expectType(replyResult(line, requestId), "subscription_started")
    }

    /** The envelope of one subscription stream line. Throws the server's error, or a protocol error otherwise. */
    fun eventOf(line: String): JsonObject {
        val envelope = parseLine(line)
        errorOf(envelope)?.let { throw HerdrException(it) }
        if (envelope["event"] == null || envelope["data"] == null) {
            throw protocolError("Herdr sent an unexpected event line")
        }
        return envelope
    }

    fun expectType(result: JsonObject, type: String): JsonObject {
        val actual = result.string("type") ?: throw protocolError("Herdr result has no type")
        if (actual != type) throw protocolError("Herdr answered '$actual' where '$type' was expected")
        return result
    }

    fun decodePong(result: JsonObject): HerdrServerInfo {
        val pong = decode<PongDto>(expectType(result, "pong"), "pong")
        return HerdrServerInfo(
            version = pong.version,
            protocol = pong.protocol,
            capabilities = pong.capabilities?.toModel(),
        )
    }

    fun decodeSnapshot(result: JsonObject): HerdrSnapshot {
        val snapshot = decode<SessionSnapshotDto>(
            (expectType(result, "session_snapshot")["snapshot"]) as? JsonObject,
            "session snapshot",
        )
        return snapshot.toModel()
    }

    /** The pane a `pane.focus` (or other pane-returning request) acted on, as upstream reports it after the change. */
    fun decodePaneInfo(result: JsonObject): HerdrPane =
        decode<PaneDto>(expectType(result, "pane_info")["pane"] as? JsonObject, "pane info").toModel()

    private inline fun <reified T> decode(element: JsonObject?, what: String): T {
        val source = element ?: throw protocolError("Herdr $what is missing")
        return try {
            json.decodeFromJsonElement<T>(source)
        } catch (error: SerializationException) {
            throw protocolError("Herdr $what does not match the expected shape: ${error.message}", error)
        }
    }

    private fun protocolError(message: String, cause: Throwable? = null) =
        HerdrException(HerdrError(HerdrErrorKind.Protocol, message), cause)
}

@Serializable
private class PongDto(
    val version: String,
    val protocol: Long,
    val capabilities: CapabilitiesDto? = null,
)

@Serializable
private class CapabilitiesDto(
    @SerialName("live_handoff") val liveHandoff: Boolean = false,
    @SerialName("detached_server_daemon") val detachedServerDaemon: Boolean = false,
    @SerialName("endpoint_protocol_generation") val endpointProtocolGeneration: Long? = null,
    @SerialName("surface_interest") val surfaceInterest: Boolean = false,
    @SerialName("health_check") val healthCheck: Boolean = false,
    @SerialName("ssh_agent_registration") val sshAgentRegistration: Boolean = false,
) {
    fun toModel() = HerdrCapabilities(
        liveHandoff = liveHandoff,
        detachedServerDaemon = detachedServerDaemon,
        endpointProtocolGeneration = endpointProtocolGeneration,
        surfaceInterest = surfaceInterest,
        healthCheck = healthCheck,
        sshAgentRegistration = sshAgentRegistration,
    )
}

// Schema-shaped: one constructor parameter per upstream field, decoded by kotlinx. Splitting it would hide the schema.
@Suppress("LongParameterList")
@Serializable
private class SessionSnapshotDto(
    val version: String,
    val protocol: Long,
    @SerialName("focused_workspace_id") val focusedWorkspaceId: String? = null,
    @SerialName("focused_tab_id") val focusedTabId: String? = null,
    @SerialName("focused_pane_id") val focusedPaneId: String? = null,
    val workspaces: List<WorkspaceDto> = emptyList(),
    val tabs: List<TabDto> = emptyList(),
    val panes: List<PaneDto> = emptyList(),
    val layouts: List<LayoutDto> = emptyList(),
    val agents: List<AgentDto> = emptyList(),
) {
    fun toModel() = HerdrSnapshot(
        version = version,
        protocol = protocol,
        focusedWorkspaceId = focusedWorkspaceId,
        focusedTabId = focusedTabId,
        focusedPaneId = focusedPaneId,
        workspaces = workspaces.map { it.toModel() },
        tabs = tabs.map { it.toModel() },
        panes = panes.map { it.toModel() },
        layouts = layouts.map { it.toModel() },
        agents = agents.map { it.toModel() },
    )
}

// Schema-shaped: one constructor parameter per upstream field, decoded by kotlinx. Splitting it would hide the schema.
@Suppress("LongParameterList")
@Serializable
private class WorkspaceDto(
    @SerialName("workspace_id") val workspaceId: String,
    val number: Int = 0,
    val label: String = "",
    val focused: Boolean = false,
    @SerialName("pane_count") val paneCount: Int = 0,
    @SerialName("tab_count") val tabCount: Int = 0,
    @SerialName("active_tab_id") val activeTabId: String = "",
    @SerialName("agent_status") val agentStatus: String = "unknown",
    val tokens: Map<String, String> = emptyMap(),
    val worktree: WorktreeDto? = null,
) {
    fun toModel() = HerdrWorkspace(
        id = workspaceId,
        number = number,
        label = label,
        focused = focused,
        paneCount = paneCount,
        tabCount = tabCount,
        activeTabId = activeTabId,
        agentStatus = agentStatusOf(agentStatus),
        tokens = tokens,
        worktree = worktree?.toModel(),
    )
}

@Serializable
private class WorktreeDto(
    @SerialName("repo_key") val repoKey: String,
    @SerialName("repo_name") val repoName: String,
    @SerialName("repo_root") val repoRoot: String,
    @SerialName("checkout_path") val checkoutPath: String,
    @SerialName("is_linked_worktree") val isLinkedWorktree: Boolean = false,
) {
    fun toModel() = HerdrWorktree(repoKey, repoName, repoRoot, checkoutPath, isLinkedWorktree)
}

@Serializable
private class TabDto(
    @SerialName("tab_id") val tabId: String,
    @SerialName("workspace_id") val workspaceId: String,
    val number: Int = 0,
    val label: String = "",
    val focused: Boolean = false,
    @SerialName("pane_count") val paneCount: Int = 0,
    @SerialName("agent_status") val agentStatus: String = "unknown",
) {
    fun toModel() = HerdrTab(
        id = tabId,
        workspaceId = workspaceId,
        number = number,
        label = label,
        focused = focused,
        paneCount = paneCount,
        agentStatus = agentStatusOf(agentStatus),
    )
}

// Schema-shaped: one constructor parameter per upstream field, decoded by kotlinx. Splitting it would hide the schema.
@Suppress("LongParameterList")
@Serializable
private class PaneDto(
    @SerialName("pane_id") val paneId: String,
    @SerialName("terminal_id") val terminalId: String,
    @SerialName("workspace_id") val workspaceId: String,
    @SerialName("tab_id") val tabId: String,
    val focused: Boolean = false,
    val cwd: String? = null,
    @SerialName("foreground_cwd") val foregroundCwd: String? = null,
    @SerialName("restore_error") val restoreError: String? = null,
    val label: String? = null,
    val agent: String? = null,
    val title: String? = null,
    @SerialName("terminal_title") val terminalTitle: String? = null,
    @SerialName("display_agent") val displayAgent: String? = null,
    @SerialName("agent_status") val agentStatus: String = "unknown",
    @SerialName("state_labels") val stateLabels: Map<String, String> = emptyMap(),
    val tokens: Map<String, String> = emptyMap(),
    val scroll: ScrollDto? = null,
    val revision: Long = 0,
) {
    fun toModel() = HerdrPane(
        id = paneId,
        terminalId = terminalId,
        workspaceId = workspaceId,
        tabId = tabId,
        focused = focused,
        cwd = cwd,
        foregroundCwd = foregroundCwd,
        restoreError = restoreError,
        label = label,
        agent = agent,
        title = title,
        terminalTitle = terminalTitle,
        displayAgent = displayAgent,
        agentStatus = agentStatusOf(agentStatus),
        stateLabels = stateLabels,
        tokens = tokens,
        scroll = scroll?.toModel(),
        revision = revision,
    )
}

@Serializable
private class ScrollDto(
    @SerialName("offset_from_bottom") val offsetFromBottom: Long,
    @SerialName("max_offset_from_bottom") val maxOffsetFromBottom: Long,
    @SerialName("viewport_rows") val viewportRows: Long,
) {
    fun toModel() = HerdrScroll(offsetFromBottom, maxOffsetFromBottom, viewportRows)
}

// Schema-shaped: one constructor parameter per upstream field, decoded by kotlinx. Splitting it would hide the schema.
@Suppress("LongParameterList")
@Serializable
private class AgentDto(
    @SerialName("terminal_id") val terminalId: String,
    @SerialName("pane_id") val paneId: String,
    @SerialName("workspace_id") val workspaceId: String,
    @SerialName("tab_id") val tabId: String,
    val focused: Boolean = false,
    val name: String? = null,
    val agent: String? = null,
    val title: String? = null,
    @SerialName("display_agent") val displayAgent: String? = null,
    @SerialName("agent_status") val agentStatus: String = "unknown",
    val cwd: String? = null,
    @SerialName("foreground_cwd") val foregroundCwd: String? = null,
    val revision: Long = 0,
) {
    fun toModel() = HerdrAgent(
        terminalId = terminalId,
        paneId = paneId,
        workspaceId = workspaceId,
        tabId = tabId,
        focused = focused,
        name = name,
        agent = agent,
        title = title,
        displayAgent = displayAgent,
        agentStatus = agentStatusOf(agentStatus),
        cwd = cwd,
        foregroundCwd = foregroundCwd,
        revision = revision,
    )
}

@Serializable
private class LayoutDto(
    @SerialName("workspace_id") val workspaceId: String,
    @SerialName("tab_id") val tabId: String,
    val zoomed: Boolean = false,
    val area: RectDto,
    @SerialName("focused_pane_id") val focusedPaneId: String,
    val panes: List<LayoutPaneDto> = emptyList(),
    val splits: List<LayoutSplitDto> = emptyList(),
) {
    fun toModel() = HerdrLayout(
        workspaceId = workspaceId,
        tabId = tabId,
        zoomed = zoomed,
        focusedPaneId = focusedPaneId,
        area = area.toModel(),
        panes = panes.map { HerdrLayoutPane(it.paneId, it.focused, it.rect.toModel()) },
        splits = splits.map {
            HerdrLayoutSplit(it.id, splitDirectionOf(it.direction), it.ratio, it.rect.toModel())
        },
    )
}

@Serializable
private class LayoutPaneDto(
    @SerialName("pane_id") val paneId: String,
    val focused: Boolean = false,
    val rect: RectDto,
)

@Serializable
private class LayoutSplitDto(
    val id: String,
    val direction: String,
    val ratio: Float,
    val rect: RectDto,
)

@Serializable
private class RectDto(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
) {
    fun toModel() = HerdrRect(x, y, width, height)
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun agentStatusOf(wire: String): HerdrAgentStatus = when (wire) {
    "idle" -> HerdrAgentStatus.Idle
    "working" -> HerdrAgentStatus.Working
    "blocked" -> HerdrAgentStatus.Blocked
    "done" -> HerdrAgentStatus.Done
    else -> HerdrAgentStatus.Unknown
}

private fun splitDirectionOf(wire: String): HerdrSplitDirection = when (wire) {
    "right" -> HerdrSplitDirection.Right
    "down" -> HerdrSplitDirection.Down
    else -> HerdrSplitDirection.Unknown
}
